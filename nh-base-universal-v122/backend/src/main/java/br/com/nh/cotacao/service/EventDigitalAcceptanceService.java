package br.com.nh.cotacao.service;

import br.com.nh.cotacao.platform.TenantIdentity;
import br.com.nh.cotacao.dto.ChecklistEventDtos.AttachmentContent;
import br.com.nh.cotacao.dto.ChecklistEventDtos.EventDetail;
import br.com.nh.cotacao.dto.EventAcceptanceDtos.EventAcceptanceInfo;
import br.com.nh.cotacao.dto.InspectionDtos.DeviceMetadata;
import br.com.nh.cotacao.dto.InspectionDtos.WebAuthnAssertionFinishRequest;
import br.com.nh.cotacao.dto.InspectionDtos.WebAuthnAssertionOptionsResponse;
import br.com.nh.cotacao.dto.InspectionDtos.WebAuthnRegistrationFinishRequest;
import br.com.nh.cotacao.dto.InspectionDtos.WebAuthnRegistrationOptionsResponse;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigInteger;
import java.net.URI;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.AlgorithmParameters;
import java.security.KeyFactory;
import java.security.MessageDigest;
import java.security.PublicKey;
import java.security.SecureRandom;
import java.security.Signature;
import java.security.interfaces.ECPublicKey;
import java.security.spec.ECGenParameterSpec;
import java.security.spec.ECParameterSpec;
import java.security.spec.ECPoint;
import java.security.spec.ECPublicKeySpec;
import java.security.spec.RSAPublicKeySpec;
import java.security.spec.X509EncodedKeySpec;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@Service
public class EventDigitalAcceptanceService {
    private static final long CEREMONY_TIMEOUT_MS = 120_000L;
    private static final int CHALLENGE_BYTES = 32;
    private static final String RP_NAME = "" + TenantIdentity.legalName() + "";

    private final JdbcTemplate jdbc;
    private final ChecklistEventService eventService;
    private final ChecklistEventPdfService pdfService;
    private final ObjectMapper jsonMapper;
    private final SecureRandom secureRandom = new SecureRandom();

    public EventDigitalAcceptanceService(JdbcTemplate jdbc, ChecklistEventService eventService,
                                         ChecklistEventPdfService pdfService, ObjectMapper jsonMapper) {
        this.jdbc = jdbc;
        this.eventService = eventService;
        this.pdfService = pdfService;
        this.jsonMapper = jsonMapper;
    }

    @Transactional
    public EventAcceptanceInfo prepare(UUID eventId) {
        AcceptanceRow row = findById(eventId, true);
        assertWorkshopCompleted(row);
        String token = row.publicToken();
        if (token == null || token.isBlank()) {
            token = randomBase64Url(32);
            jdbc.update("update nh_event_records set public_acceptance_token=?,public_acceptance_issued_at=now(),updated_at=now() where id=?", token, eventId);
            row = findById(eventId, true);
        }
        return info(row);
    }

    @Transactional(readOnly = true)
    public EventAcceptanceInfo statusByEvent(UUID eventId) {
        return info(findById(eventId, false));
    }

    @Transactional(readOnly = true)
    public EventAcceptanceInfo publicStatus(String token) {
        return info(findByToken(token, false));
    }

    @Transactional(readOnly = true)
    public byte[] publicDossier(String token) {
        AcceptanceRow row = findByToken(token, false);
        assertWorkshopCompleted(row);
        EventDetail event = eventService.systemDetail(row.id());
        List<AttachmentContent> images = eventService.systemImageAttachments(row.id());
        return pdfService.generate(event, images);
    }

    @Transactional
    public WebAuthnRegistrationOptionsResponse beginRegistration(String token, DeviceMetadata device, HttpServletRequest request) {
        AcceptanceRow row = findByToken(token, true);
        assertPending(row);
        if (device == null) throw new IllegalArgumentException("Não foi possível registrar os metadados do aparelho.");

        OriginInfo origin = resolveOrigin(request);
        EventDetail event = eventService.systemDetail(row.id());
        byte[] dossier = pdfService.generate(event, eventService.systemImageAttachments(row.id()));
        String dossierHash = sha256Hex(dossier);
        String deviceJson = safeDeviceJson(device);
        String ip = resolveClientIp(request);
        String evidenceHash = evidenceHash(row, dossierHash, deviceJson, ip, device);
        String challenge = randomBase64Url(CHALLENGE_BYTES);

        jdbc.update("""
                update nh_event_records
                   set webauthn_registration_challenge=?,webauthn_registration_expires_at=?,webauthn_origin=?,webauthn_rp_id=?,
                       acceptance_evidence_hash=?,acceptance_dossier_sha256=?,acceptance_device_metadata=?,acceptance_ip=?,
                       acceptance_latitude=?,acceptance_longitude=?,acceptance_accuracy_meters=?,
                       webauthn_assertion_challenge=null,webauthn_assertion_expires_at=null,updated_at=now()
                 where id=?
                """, challenge, OffsetDateTime.now().plusMinutes(3), origin.origin(), origin.rpId(), evidenceHash, dossierHash,
                deviceJson, ip, device.latitude(), device.longitude(), device.accuracyMeters(), row.id());

        return new WebAuthnRegistrationOptionsResponse(
                challenge, origin.rpId(), RP_NAME,
                base64Url(sha256Bytes(("event-user:" + row.id()).getBytes(StandardCharsets.UTF_8))),
                "associado-evento-" + row.id(), row.associateName(), CEREMONY_TIMEOUT_MS
        );
    }

    @Transactional
    public WebAuthnAssertionOptionsResponse finishRegistration(String token, WebAuthnRegistrationFinishRequest input) {
        AcceptanceRow row = findByToken(token, true);
        assertPending(row);
        assertPendingRegistration(row);
        if (input == null) throw new IllegalArgumentException("Dados do WebAuthn não informados.");

        byte[] clientData = decodeBase64Url(input.clientDataJSON());
        validateClientData(clientData, "webauthn.create", row.registrationChallenge(), row.origin());
        AttestedCredential credential = parseAttestation(decodeBase64Url(input.attestationObject()), row.rpId());
        String rawCredentialId = normalizeBase64Url(input.rawId());
        if (!rawCredentialId.equals(base64Url(credential.credentialId()))) {
            throw new IllegalArgumentException("A credencial criada pelo aparelho não corresponde ao registro recebido.");
        }
        if (!"public-key".equals(input.type())) throw new IllegalArgumentException("Tipo de credencial WebAuthn inválido.");

        jdbc.update("""
                update nh_event_records
                   set webauthn_credential_id=?,webauthn_public_key=?,webauthn_algorithm=?,webauthn_sign_count=?,
                       webauthn_registration_challenge=null,webauthn_registration_expires_at=null,updated_at=now()
                 where id=?
                """, rawCredentialId, credential.publicKey().getEncoded(), credential.algorithm(), credential.signCount(), row.id());
        return createAssertionOptions(findById(row.id(), true));
    }

    @Transactional
    public WebAuthnAssertionOptionsResponse assertionOptions(String token) {
        AcceptanceRow row = findByToken(token, true);
        assertPending(row);
        if (row.credentialId() == null || row.publicKey() == null) {
            throw new IllegalArgumentException("Primeiro confirme a biometria/PIN para criar a credencial segura deste aceite.");
        }
        return createAssertionOptions(row);
    }

    @Transactional
    public EventAcceptanceInfo finishAssertion(String token, WebAuthnAssertionFinishRequest input) {
        AcceptanceRow row = findByToken(token, true);
        assertPending(row);
        assertPendingAssertion(row);
        if (input == null) throw new IllegalArgumentException("Dados da confirmação WebAuthn não informados.");
        if (!"public-key".equals(input.type())) throw new IllegalArgumentException("Tipo de credencial WebAuthn inválido.");

        String credentialId = normalizeBase64Url(input.rawId());
        if (!credentialId.equals(row.credentialId())) throw new IllegalArgumentException("A credencial usada não pertence a este aceite.");

        // Garante que o associado está aceitando exatamente o mesmo dossiê externo
        // que foi vinculado criptograficamente no início da cerimônia. Dados de compras
        // não fazem parte deste PDF e, portanto, não alteram o hash do aceite.
        String currentDossierHash = sha256Hex(pdfService.generate(
                eventService.systemDetail(row.id()), eventService.systemImageAttachments(row.id())));
        if (row.dossierHash() == null || !MessageDigest.isEqual(
                row.dossierHash().getBytes(StandardCharsets.UTF_8), currentDossierHash.getBytes(StandardCharsets.UTF_8))) {
            jdbc.update("""
                    update nh_event_records
                       set webauthn_registration_challenge=null,webauthn_registration_expires_at=null,
                           webauthn_credential_id=null,webauthn_public_key=null,webauthn_algorithm=null,webauthn_sign_count=0,
                           webauthn_assertion_challenge=null,webauthn_assertion_expires_at=null,
                           acceptance_evidence_hash=null,acceptance_dossier_sha256=null,updated_at=now()
                     where id=?
                    """, row.id());
            throw new IllegalArgumentException("O dossiê foi alterado depois do início do aceite. A confirmação anterior foi reiniciada; revise o documento atualizado e confirme novamente.");
        }

        byte[] clientData = decodeBase64Url(input.clientDataJSON());
        validateClientData(clientData, "webauthn.get", row.assertionChallenge(), row.origin());
        byte[] authenticatorData = decodeBase64Url(input.authenticatorData());
        AuthenticatorData parsed = validateAuthenticatorData(authenticatorData, row.rpId(), true);
        verifyAssertionSignature(decodeStoredPublicKey(row.publicKey(), row.algorithm()), row.algorithm(), authenticatorData, clientData, decodeBase64Url(input.signature()));

        if (parsed.signCount() != 0 && row.signCount() != 0 && parsed.signCount() <= row.signCount()) {
            throw new IllegalArgumentException("O contador da credencial WebAuthn não avançou. Por segurança, o aceite foi recusado.");
        }
        OffsetDateTime acceptedAt = OffsetDateTime.now();
        String proofHash = proofHash(row, row.assertionChallenge(), input.authenticatorData(), input.clientDataJSON(), input.signature(), credentialId, acceptedAt);
        int updated = jdbc.update("""
                update nh_event_records
                   set webauthn_sign_count=?,acceptance_assertion_signature=?,acceptance_authenticator_data=?,acceptance_client_data_json=?,
                       acceptance_proof_hash=?,acceptance_user_verified=?,accepted_at=?,webauthn_assertion_challenge=null,webauthn_assertion_expires_at=null,
                       status='FINALIZED',finalized_at=coalesce(finalized_at,?),last_updated_by='Aceite digital do associado',updated_at=now()
                 where id=? and accepted_at is null
                """, parsed.signCount(), normalizeBase64Url(input.signature()), normalizeBase64Url(input.authenticatorData()),
                normalizeBase64Url(input.clientDataJSON()), proofHash, parsed.userVerified(), acceptedAt, acceptedAt, row.id());
        if (updated != 1) throw new IllegalStateException("Não foi possível registrar o aceite digital deste dossiê.");
        jdbc.update("""
                insert into nh_event_audit_logs(id,event_id,actor_username,actor_name,action,entity_type,entity_id,details,created_at)
                values (?,?,?,?,?,?,?,?,now())
                """, UUID.randomUUID(),row.id(),"ASSOCIATE_WEBAUTHN",row.associateName(),"ASSOCIATE_DIGITAL_ACCEPTANCE","EVENT",row.id(),
                "Aceite WebAuthn confirmado. Dossiê SHA-256="+row.dossierHash()+"; prova="+proofHash);
        return info(findById(row.id(), false));
    }

    private WebAuthnAssertionOptionsResponse createAssertionOptions(AcceptanceRow row) {
        if (row.evidenceHash() == null || row.evidenceHash().isBlank()) throw new IllegalArgumentException("A evidência vinculada ao aceite não foi preparada. Reinicie a confirmação.");
        byte[] random = new byte[CHALLENGE_BYTES]; secureRandom.nextBytes(random);
        String challenge = base64Url(sha256Bytes(concat(random, row.evidenceHash().getBytes(StandardCharsets.UTF_8))));
        jdbc.update("update nh_event_records set webauthn_assertion_challenge=?,webauthn_assertion_expires_at=?,updated_at=now() where id=?",
                challenge, OffsetDateTime.now().plusMinutes(3), row.id());
        return new WebAuthnAssertionOptionsResponse(challenge,row.rpId(),row.credentialId(),CEREMONY_TIMEOUT_MS,row.evidenceHash(),row.dossierHash(),null);
    }

    private EventAcceptanceInfo info(AcceptanceRow row) {
        boolean eligible = row.workshopCompletedAt() != null
                && (row.acceptedAt() != null || AcceptanceLinkPolicy.isValid(row.linkAvailableAt(), OffsetDateTime.now()));
        boolean accepted = row.acceptedAt() != null;
        return new EventAcceptanceInfo(row.id(),row.protocol(),row.associateName(),row.vehiclePlate(),row.vehicleModel(),eligible,accepted,row.acceptedAt(),
                row.publicToken(),row.publicToken()==null?null:"/aceite-evento/?token="+row.publicToken(),row.dossierHash(),row.evidenceHash(),row.proofHash(),row.userVerified());
    }

    private void assertWorkshopCompleted(AcceptanceRow row) {
        if (row.workshopCompletedAt() == null) throw new IllegalArgumentException("O dossiê do associado só é liberado depois da conclusão do checklist da oficina.");
    }
    private void assertPending(AcceptanceRow row) {
        assertWorkshopCompleted(row);
        if (row.acceptedAt() != null) throw new IllegalArgumentException("Este dossiê já possui aceite digital confirmado.");
        AcceptanceLinkPolicy.requireValid(row.linkAvailableAt(), OffsetDateTime.now());
    }
    private void assertPendingRegistration(AcceptanceRow row) {
        if (row.registrationChallenge()==null || row.registrationExpiresAt()==null || row.registrationExpiresAt().isBefore(OffsetDateTime.now()))
            throw new IllegalArgumentException("O desafio de registro WebAuthn expirou. Inicie novamente o aceite.");
    }
    private void assertPendingAssertion(AcceptanceRow row) {
        if (row.assertionChallenge()==null || row.assertionExpiresAt()==null || row.assertionExpiresAt().isBefore(OffsetDateTime.now()))
            throw new IllegalArgumentException("O desafio de confirmação WebAuthn expirou. Inicie novamente o aceite.");
    }

    private AcceptanceRow findById(UUID id, boolean lock) {
        String sql = baseSelect()+" where id=?"+(lock?" for update":"");
        AcceptanceRow row=jdbc.query(sql,rs->rs.next()?map(rs):null,id);
        if(row==null)throw new IllegalArgumentException("Evento não encontrado."); return row;
    }
    private AcceptanceRow findByToken(String token, boolean lock) {
        if(token==null||token.isBlank())throw new IllegalArgumentException("Token do aceite não informado.");
        String sql=baseSelect()+" where public_acceptance_token=?"+(lock?" for update":"");
        AcceptanceRow row=jdbc.query(sql,rs->rs.next()?map(rs):null,token.trim());
        if(row==null)throw new IllegalArgumentException("Link de aceite inválido ou expirado."); return row;
    }
    private String baseSelect(){return """
            select id,protocol,associate_name,associate_number,vehicle_plate,vehicle_model,status,workshop_completed_at,accepted_at,public_acceptance_token,public_acceptance_issued_at,
                   webauthn_registration_challenge,webauthn_registration_expires_at,webauthn_origin,webauthn_rp_id,webauthn_credential_id,webauthn_public_key,
                   webauthn_algorithm,webauthn_sign_count,webauthn_assertion_challenge,webauthn_assertion_expires_at,acceptance_evidence_hash,
                   acceptance_dossier_sha256,acceptance_proof_hash,acceptance_user_verified
              from nh_event_records
            """;}
    private AcceptanceRow map(ResultSet rs)throws SQLException{return new AcceptanceRow(uuid(rs,"id"),rs.getString("protocol"),rs.getString("associate_name"),rs.getString("associate_number"),
            rs.getString("vehicle_plate"),rs.getString("vehicle_model"),rs.getString("status"),offset(rs,"workshop_completed_at"),offset(rs,"accepted_at"),rs.getString("public_acceptance_token"),offset(rs,"public_acceptance_issued_at"),
            rs.getString("webauthn_registration_challenge"),offset(rs,"webauthn_registration_expires_at"),rs.getString("webauthn_origin"),rs.getString("webauthn_rp_id"),
            rs.getString("webauthn_credential_id"),rs.getBytes("webauthn_public_key"),(Integer)rs.getObject("webauthn_algorithm"),rs.getLong("webauthn_sign_count"),
            rs.getString("webauthn_assertion_challenge"),offset(rs,"webauthn_assertion_expires_at"),rs.getString("acceptance_evidence_hash"),rs.getString("acceptance_dossier_sha256"),
            rs.getString("acceptance_proof_hash"),rs.getBoolean("acceptance_user_verified"));}

    private AttestedCredential parseAttestation(byte[] attestationObject,String rpId){try{Object decoded=new CborReader(attestationObject).read();if(!(decoded instanceof Map<?,?> root))throw new IllegalArgumentException("Objeto de atestação WebAuthn inválido.");Object authDataValue=root.get("authData");if(!(authDataValue instanceof byte[] authData))throw new IllegalArgumentException("Resposta WebAuthn sem authenticatorData.");AuthenticatorData parsed=validateAuthenticatorData(authData,rpId,true);if(authData.length<55||(parsed.flags()&0x40)==0)throw new IllegalArgumentException("A resposta WebAuthn não contém a chave pública da credencial.");int credentialLength=((authData[53]&0xff)<<8)|(authData[54]&0xff);int start=55,end=start+credentialLength;if(credentialLength<=0||end>=authData.length)throw new IllegalArgumentException("Identificador da credencial WebAuthn inválido.");byte[] credentialId=Arrays.copyOfRange(authData,start,end);Object coseDecoded=new CborReader(Arrays.copyOfRange(authData,end,authData.length)).read();if(!(coseDecoded instanceof Map<?,?> coseKey))throw new IllegalArgumentException("Chave pública COSE inválida.");CosePublicKey publicKey=parseCosePublicKey(coseKey);return new AttestedCredential(credentialId,publicKey.publicKey(),publicKey.algorithm(),parsed.signCount());}catch(IllegalArgumentException e){throw e;}catch(Exception e){throw new IllegalArgumentException("Não foi possível validar a credencial WebAuthn criada pelo aparelho.",e);}}
    private CosePublicKey parseCosePublicKey(Map<?,?> key)throws Exception{int kty=intField(key,1L),alg=intField(key,3L);if(kty==2&&alg==-7){if(intField(key,-1L)!=1)throw new IllegalArgumentException("Curva WebAuthn não suportada.");byte[] x=binaryField(key,-2L),y=binaryField(key,-3L);AlgorithmParameters parameters=AlgorithmParameters.getInstance("EC");parameters.init(new ECGenParameterSpec("secp256r1"));ECParameterSpec ec=parameters.getParameterSpec(ECParameterSpec.class);ECPublicKeySpec spec=new ECPublicKeySpec(new ECPoint(new BigInteger(1,x),new BigInteger(1,y)),ec);ECPublicKey pk=(ECPublicKey)KeyFactory.getInstance("EC").generatePublic(spec);return new CosePublicKey(pk,alg);}if(kty==3&&alg==-257){PublicKey pk=KeyFactory.getInstance("RSA").generatePublic(new RSAPublicKeySpec(new BigInteger(1,binaryField(key,-1L)),new BigInteger(1,binaryField(key,-2L))));return new CosePublicKey(pk,alg);}throw new IllegalArgumentException("Algoritmo WebAuthn não suportado. Use ES256 ou RS256.");}
    private AuthenticatorData validateAuthenticatorData(byte[] data,String rpId,boolean requireUv){if(data==null||data.length<37)throw new IllegalArgumentException("AuthenticatorData WebAuthn inválido.");if(!MessageDigest.isEqual(sha256Bytes(rpId.getBytes(StandardCharsets.UTF_8)),Arrays.copyOfRange(data,0,32)))throw new IllegalArgumentException("A credencial WebAuthn foi emitida para outro domínio.");int flags=data[32]&0xff;boolean present=(flags&1)!=0,verified=(flags&4)!=0;if(!present)throw new IllegalArgumentException("A presença do usuário não foi confirmada pelo autenticador.");if(requireUv&&!verified)throw new IllegalArgumentException("A biometria, PIN ou bloqueio seguro do aparelho não confirmou o usuário.");long signCount=Integer.toUnsignedLong(ByteBuffer.wrap(data,33,4).getInt());return new AuthenticatorData(flags,signCount,verified);}
    private void validateClientData(byte[] bytes,String type,String challenge,String origin){try{JsonNode n=jsonMapper.readTree(bytes);if(!type.equals(n.path("type").asText()))throw new IllegalArgumentException("Cerimônia WebAuthn inesperada.");if(!normalizeBase64Url(challenge).equals(normalizeBase64Url(n.path("challenge").asText())))throw new IllegalArgumentException("O desafio WebAuthn expirou ou não pertence a este aceite.");if(!origin.equals(n.path("origin").asText()))throw new IllegalArgumentException("A origem da confirmação WebAuthn não corresponde à página do aceite.");}catch(IllegalArgumentException e){throw e;}catch(Exception e){throw new IllegalArgumentException("ClientDataJSON WebAuthn inválido.",e);}}
    private void verifyAssertionSignature(PublicKey key,Integer algorithm,byte[] auth,byte[] client,byte[] signature){try{String alg=switch(algorithm==null?0:algorithm){case -7->"SHA256withECDSA";case -257->"SHA256withRSA";default->throw new IllegalArgumentException("Algoritmo da credencial WebAuthn não suportado.");};Signature verifier=Signature.getInstance(alg);verifier.initVerify(key);verifier.update(auth);verifier.update(sha256Bytes(client));if(!verifier.verify(signature))throw new IllegalArgumentException("A assinatura criptográfica WebAuthn não é válida.");}catch(IllegalArgumentException e){throw e;}catch(Exception e){throw new IllegalArgumentException("Não foi possível verificar a assinatura criptográfica WebAuthn.",e);}}
    private PublicKey decodeStoredPublicKey(byte[] encoded,Integer algorithm){try{return KeyFactory.getInstance(algorithm!=null&&algorithm==-257?"RSA":"EC").generatePublic(new X509EncodedKeySpec(encoded));}catch(Exception e){throw new IllegalArgumentException("A chave pública WebAuthn armazenada não pôde ser validada.",e);}}

    private OriginInfo resolveOrigin(HttpServletRequest request){try{String forwardedProto=firstHeader(request,"X-Forwarded-Proto"),forwardedHost=firstHeader(request,"X-Forwarded-Host");String scheme=forwardedProto!=null?forwardedProto:request.getScheme();String hostPort=forwardedHost!=null?forwardedHost:request.getHeader("Host");if(hostPort==null||hostPort.isBlank())hostPort=request.getServerName()+(isDefaultPort(scheme,request.getServerPort())?"":":"+request.getServerPort());URI uri=URI.create(scheme+"://"+hostPort);String host=uri.getHost();if(host==null||host.isBlank())throw new IllegalArgumentException("Domínio do aceite inválido.");String origin=scheme+"://"+host+(uri.getPort()>0&&!isDefaultPort(scheme,uri.getPort())?":"+uri.getPort():"");return new OriginInfo(origin,host);}catch(Exception e){throw new IllegalArgumentException("Não foi possível identificar o domínio seguro do aceite digital.",e);}}
    private boolean isDefaultPort(String scheme,int port){return port<=0||("https".equalsIgnoreCase(scheme)&&port==443)||("http".equalsIgnoreCase(scheme)&&port==80);}
    private String resolveClientIp(HttpServletRequest request){String forwarded=firstHeader(request,"X-Forwarded-For");if(forwarded!=null)return forwarded.split(",")[0].trim();String real=firstHeader(request,"X-Real-IP");return real!=null?real:request.getRemoteAddr();}
    private String firstHeader(HttpServletRequest request,String name){String v=request.getHeader(name);return v==null||v.isBlank()?null:v.trim();}
    private String safeDeviceJson(DeviceMetadata device){try{return jsonMapper.writeValueAsString(device);}catch(Exception e){throw new IllegalArgumentException("Metadados do aparelho inválidos.",e);}}
    private String evidenceHash(AcceptanceRow row,String dossierHash,String deviceJson,String ip,DeviceMetadata device){String canonical=String.join("\n","NH-EVENT-ACCEPTANCE-EVIDENCE-V1","eventId="+row.id(),"protocol="+row.protocol(),"associateNumber="+normalizeText(row.associateNumber()),"dossierSha256="+dossierHash,"device="+deviceJson,"ip="+normalizeText(ip),"latitude="+String.valueOf(device.latitude()),"longitude="+String.valueOf(device.longitude()),"accuracyMeters="+String.valueOf(device.accuracyMeters()));return sha256Hex(canonical.getBytes(StandardCharsets.UTF_8));}
    private String proofHash(AcceptanceRow row,String challenge,String authenticatorData,String clientDataJson,String signature,String credentialId,OffsetDateTime acceptedAt){String canonical=String.join("\n","NH-EVENT-WEBAUTHN-PROOF-V1","eventId="+row.id(),"evidenceHash="+row.evidenceHash(),"challenge="+normalizeBase64Url(challenge),"credentialId="+credentialId,"authenticatorData="+normalizeBase64Url(authenticatorData),"clientDataJSON="+normalizeBase64Url(clientDataJson),"signature="+normalizeBase64Url(signature),"acceptedAt="+acceptedAt);return sha256Hex(canonical.getBytes(StandardCharsets.UTF_8));}
    private int intField(Map<?,?> node,long field){Object v=node.get(field);if(!(v instanceof Number n))throw new IllegalArgumentException("Chave COSE inválida: "+field);return n.intValue();}
    private byte[] binaryField(Map<?,?> node,long field){Object v=node.get(field);if(!(v instanceof byte[] b))throw new IllegalArgumentException("Chave COSE inválida: "+field);return b;}
    private byte[] decodeBase64Url(String value){try{return Base64.getUrlDecoder().decode(normalizeBase64Url(value));}catch(Exception e){throw new IllegalArgumentException("Campo WebAuthn em Base64URL inválido.");}}
    private String normalizeBase64Url(String value){return value==null?"":value.trim().replace("=","");}
    private String randomBase64Url(int bytes){byte[] b=new byte[bytes];secureRandom.nextBytes(b);return base64Url(b);}
    private String base64Url(byte[] bytes){return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);}
    private byte[] sha256Bytes(byte[] bytes){try{return MessageDigest.getInstance("SHA-256").digest(bytes);}catch(Exception e){throw new IllegalStateException("SHA-256 indisponível.",e);}}
    private String sha256Hex(byte[] bytes){return java.util.HexFormat.of().formatHex(sha256Bytes(bytes));}
    private byte[] concat(byte[] a,byte[] b){byte[] r=Arrays.copyOf(a,a.length+b.length);System.arraycopy(b,0,r,a.length,b.length);return r;}
    private String normalizeText(String value){return value==null?"":value.trim().replaceAll("\\s+"," ");}
    private static UUID uuid(ResultSet rs,String c)throws SQLException{Object v=rs.getObject(c);return v instanceof UUID u?u:UUID.fromString(String.valueOf(v));}
    private static OffsetDateTime offset(ResultSet rs,String c)throws SQLException{Object v=rs.getObject(c);if(v==null)return null;if(v instanceof OffsetDateTime o)return o;if(v instanceof java.sql.Timestamp t)return t.toInstant().atOffset(java.time.ZoneOffset.UTC);return OffsetDateTime.parse(String.valueOf(v));}

    private static final class CborReader{private final byte[] data;private int position;private CborReader(byte[] data){this.data=data==null?new byte[0]:data;}private Object read(){if(position>=data.length)throw new IllegalArgumentException("CBOR inesperadamente vazio.");int initial=readUnsignedByte(),major=initial>>>5,additional=initial&0x1f;if(major==7)return readSimple(additional);long length=readLength(additional);return switch(major){case 0->length;case 1->-1L-length;case 2->readBytes(length);case 3->new String(readBytes(length),StandardCharsets.UTF_8);case 4->readArray(length);case 5->readMap(length);case 6->read();default->throw new IllegalArgumentException("Tipo CBOR não suportado: "+major);};}private Object readSimple(int a){return switch(a){case 20->Boolean.FALSE;case 21->Boolean.TRUE;case 22,23->null;default->throw new IllegalArgumentException("Valor simples CBOR não suportado: "+a);};}private long readLength(int a){if(a<24)return a;return switch(a){case 24->readUnsignedByte();case 25->((long)readUnsignedByte()<<8)|readUnsignedByte();case 26->((long)readUnsignedByte()<<24)|((long)readUnsignedByte()<<16)|((long)readUnsignedByte()<<8)|readUnsignedByte();case 27->{long v=0;for(int i=0;i<8;i++)v=(v<<8)|readUnsignedByte();yield v;}default->throw new IllegalArgumentException("CBOR de tamanho indefinido não é aceito nesta evidência WebAuthn.");};}private byte[] readBytes(long l){if(l<0||l>Integer.MAX_VALUE||position+l>data.length)throw new IllegalArgumentException("Comprimento CBOR inválido.");int size=(int)l;byte[] v=Arrays.copyOfRange(data,position,position+size);position+=size;return v;}private List<Object> readArray(long l){if(l<0||l>10000)throw new IllegalArgumentException("Array CBOR inválido.");List<Object> list=new ArrayList<>((int)l);for(long i=0;i<l;i++)list.add(read());return list;}private Map<Object,Object> readMap(long l){if(l<0||l>10000)throw new IllegalArgumentException("Mapa CBOR inválido.");Map<Object,Object> map=new LinkedHashMap<>();for(long i=0;i<l;i++)map.put(read(),read());return map;}private int readUnsignedByte(){if(position>=data.length)throw new IllegalArgumentException("Fim inesperado do CBOR.");return data[position++]&0xff;}}

    private record OriginInfo(String origin,String rpId){}
    private record AuthenticatorData(int flags,long signCount,boolean userVerified){}
    private record CosePublicKey(PublicKey publicKey,int algorithm){}
    private record AttestedCredential(byte[] credentialId,PublicKey publicKey,int algorithm,long signCount){}
    private record AcceptanceRow(UUID id,String protocol,String associateName,String associateNumber,String vehiclePlate,String vehicleModel,String status,
                                 OffsetDateTime workshopCompletedAt,OffsetDateTime acceptedAt,String publicToken,OffsetDateTime linkAvailableAt,String registrationChallenge,OffsetDateTime registrationExpiresAt,
                                 String origin,String rpId,String credentialId,byte[] publicKey,Integer algorithm,long signCount,String assertionChallenge,OffsetDateTime assertionExpiresAt,
                                 String evidenceHash,String dossierHash,String proofHash,boolean userVerified){}
}
