package br.com.nh.cotacao.service;

import br.com.nh.cotacao.platform.TenantIdentity;
import br.com.nh.cotacao.entity.PortalUser;
import br.com.nh.cotacao.security.PortalRole;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.HttpServletRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
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
public class PortalPasskeyService {
    private static final Logger log = LoggerFactory.getLogger(PortalPasskeyService.class);
    private static final int CHALLENGE_BYTES = 32;
    private static final long CEREMONY_TIMEOUT_MS = 120_000L;
    private static final String RP_NAME = "" + TenantIdentity.legalName() + "";

    private final JdbcTemplate jdbc;
    private final ObjectMapper jsonMapper;
    private final SecureRandom secureRandom = new SecureRandom();

    public PortalPasskeyService(JdbcTemplate jdbc, ObjectMapper jsonMapper) {
        this.jdbc = jdbc;
        this.jsonMapper = jsonMapper;
    }

    public boolean required(PortalRole role) {
        // v83.20: somente o ADMIN geral usa usuário + senha + passkey no login.
        // Todos os demais perfis entram apenas com usuário e senha.
        return role == PortalRole.ADMIN;
    }

    @Transactional(readOnly = true)
    public boolean enrolled(String username) {
        UserRow row = find(username, false);
        return credentialCount(row.id()) > 0;
    }

    @Transactional
    public RegistrationOptions beginRegistration(String username, HttpServletRequest request) {
        UserRow row = find(username, true);
        assertEligible(row);
        if (credentialCount(row.id()) > 0) {
            throw new IllegalArgumentException("Esta conta já possui passkey. Novas passkeys só podem ser cadastradas pelo próprio Administrador autenticado na aba Usuários.");
        }
        return prepareRegistration(row, request, List.of());
    }

    @Transactional
    public void finishRegistration(String username, RegistrationFinish input) {
        UserRow row = find(username, true);
        assertEligible(row);
        if (credentialCount(row.id()) > 0) {
            throw new IllegalArgumentException("Esta conta já possui passkey. O cadastro inicial não pode substituir a credencial existente.");
        }
        storeRegistration(row, input, "Passkey principal");
    }

    @Transactional(readOnly = true)
    public List<PasskeyInfo> listOwn(String username) {
        UserRow row = find(username, false);
        assertEligible(row);
        return jdbc.query("""
                select id,credential_id,label,created_at,last_used_at
                  from portal_admin_passkeys
                 where user_id=?
                 order by created_at asc
                """, (rs, index) -> new PasskeyInfo(
                rs.getObject("id", UUID.class), rs.getString("credential_id"), rs.getString("label"),
                offset(rs, "created_at"), offset(rs, "last_used_at")
        ), row.id());
    }

    @Transactional
    public RegistrationOptions beginProfileRegistration(String username, HttpServletRequest request) {
        UserRow row = find(username, true);
        assertEligible(row);
        List<CredentialRow> existing = credentials(row.id());
        return prepareRegistration(row, request, existing.stream().map(CredentialRow::credentialId).toList());
    }

    @Transactional
    public PasskeyInfo finishProfileRegistration(String username, RegistrationFinish input, String label) {
        UserRow row = find(username, true);
        assertEligible(row);
        boolean firstCredential = credentialCount(row.id()) == 0;
        String resolvedLabel = label == null || label.isBlank()
                ? (firstCredential ? "Passkey principal" : "Passkey adicional")
                : label.trim();
        UUID id = storeRegistration(row, input, resolvedLabel);
        return jdbc.queryForObject("""
                select id,credential_id,label,created_at,last_used_at
                  from portal_admin_passkeys where id=?
                """, (rs, index) -> new PasskeyInfo(
                rs.getObject("id", UUID.class), rs.getString("credential_id"), rs.getString("label"),
                offset(rs, "created_at"), offset(rs, "last_used_at")
        ), id);
    }

    @Transactional
    public void revokeOwn(String username, UUID credentialId) {
        UserRow row = find(username, true);
        assertEligible(row);
        int total = credentialCount(row.id());
        if (total <= 1) {
            throw new IllegalArgumentException("A passkey principal não pode ser removida sem outra passkey cadastrada. Cadastre uma nova na aba Usuários primeiro.");
        }
        int changed = jdbc.update("delete from portal_admin_passkeys where id=? and user_id=?", credentialId, row.id());
        if (changed != 1) throw new IllegalArgumentException("Passkey não encontrada neste perfil.");
    }

    @Transactional
    public AssertionOptions beginAssertion(String username, HttpServletRequest request) {
        UserRow row = find(username, true);
        assertEligible(row);
        OriginInfo current = resolveOrigin(request);
        List<CredentialRow> creds = credentials(row.id()).stream()
                .filter(c -> current.rpId().equals(c.rpId()) && current.origin().equals(c.origin()))
                .toList();
        if (creds.isEmpty()) {
            throw new IllegalArgumentException("Esta conta ainda não cadastrou passkey para este domínio.");
        }
        String challenge = randomBase64Url(CHALLENGE_BYTES);
        jdbc.update("""
                update portal_users
                   set passkey_assertion_challenge=?,passkey_assertion_expires_at=?,updated_at=now()
                 where id=?
                """, challenge, OffsetDateTime.now().plusMinutes(3), row.id());
        return new AssertionOptions(challenge, current.rpId(), creds.stream().map(CredentialRow::credentialId).toList(), CEREMONY_TIMEOUT_MS);
    }

    @Transactional
    public void finishAssertion(String username, AssertionFinish input) {
        UserRow row = find(username, true);
        assertEligible(row);
        if (row.assertionChallenge() == null || row.assertionExpiresAt() == null || row.assertionExpiresAt().isBefore(OffsetDateTime.now())) {
            throw new IllegalArgumentException("A confirmação da passkey expirou. Informe usuário e senha novamente.");
        }
        if (input == null || !"public-key".equals(input.type())) throw new IllegalArgumentException("Resposta de passkey inválida.");
        String credentialId = normalizeBase64Url(input.rawId());
        CredentialRow credential = credential(row.id(), credentialId);
        if (credential == null) throw new IllegalArgumentException("A passkey usada não pertence a este usuário.");

        byte[] clientData = decodeBase64Url(input.clientDataJSON());
        validateClientData(clientData, "webauthn.get", row.assertionChallenge(), credential.origin());
        byte[] authenticatorData = decodeBase64Url(input.authenticatorData());
        AuthenticatorData parsed = validateAuthenticatorData(authenticatorData, credential.rpId(), true);
        verifyAssertionSignature(
                decodeStoredPublicKey(credential.publicKey(), credential.algorithm()), credential.algorithm(), authenticatorData, clientData,
                decodeBase64Url(input.signature())
        );
        if (credential.signCount() > 0 && parsed.signCount() > 0 && parsed.signCount() <= credential.signCount()) {
            throw new IllegalArgumentException("A passkey apresentou um contador inválido. O acesso foi bloqueado para evitar reutilização de uma prova anterior.");
        }
        jdbc.update("update portal_admin_passkeys set sign_count=?,last_used_at=now() where id=?", parsed.signCount(), credential.id());
        jdbc.update("""
                update portal_users
                   set passkey_assertion_challenge=null,passkey_assertion_expires_at=null,updated_at=now()
                 where id=?
                """, row.id());
    }

    private RegistrationOptions prepareRegistration(UserRow row, HttpServletRequest request, List<String> excludeCredentialIds) {
        OriginInfo origin = resolveOrigin(request);
        String challenge = randomBase64Url(CHALLENGE_BYTES);
        OffsetDateTime expiresAt = OffsetDateTime.now().plusMinutes(3);
        jdbc.update("""
                update portal_users
                   set passkey_registration_challenge=?,passkey_registration_expires_at=?,passkey_origin=?,passkey_rp_id=?,
                       passkey_assertion_challenge=null,passkey_assertion_expires_at=null,updated_at=now()
                 where id=?
                """, challenge, expiresAt, origin.origin(), origin.rpId(), row.id());
        String displayName = row.displayName() == null || row.displayName().isBlank() ? row.username() : row.displayName();
        return new RegistrationOptions(
                challenge, origin.rpId(), RP_NAME,
                base64Url(sha256Bytes(("portal-user:" + row.id()).getBytes(StandardCharsets.UTF_8))),
                row.username(), displayName, CEREMONY_TIMEOUT_MS, excludeCredentialIds
        );
    }

    private UUID storeRegistration(UserRow row, RegistrationFinish input, String label) {
        if (row.registrationChallenge() == null || row.registrationExpiresAt() == null || row.registrationExpiresAt().isBefore(OffsetDateTime.now())) {
            throw new IllegalArgumentException("O cadastro da passkey expirou. Entre novamente e repita o cadastro.");
        }
        if (row.origin() == null || row.rpId() == null) throw new IllegalArgumentException("O cadastro da passkey não possui origem válida.");
        if (input == null || !"public-key".equals(input.type())) throw new IllegalArgumentException("Resposta de passkey inválida.");
        byte[] clientData = decodeBase64Url(input.clientDataJSON());
        validateClientData(clientData, "webauthn.create", row.registrationChallenge(), row.origin());
        String rawCredentialId = normalizeBase64Url(input.rawId());
        byte[] rawCredentialBytes = decodeBase64Url(input.rawId());
        AttestedCredential credential = resolveRegistrationCredential(input, row.rpId(), rawCredentialBytes);
        if (!rawCredentialId.equals(base64Url(credential.credentialId()))) {
            throw new IllegalArgumentException("A passkey criada não corresponde ao registro recebido.");
        }
        if (credential(row.id(), rawCredentialId) != null) {
            throw new IllegalArgumentException("Esta passkey já está cadastrada. Nenhuma credencial existente foi substituída.");
        }
        UUID id = UUID.randomUUID();
        jdbc.update("""
                insert into portal_admin_passkeys(id,user_id,credential_id,public_key,algorithm,sign_count,origin,rp_id,label,created_at)
                values (?,?,?,?,?,?,?,?,?,now())
                """, id, row.id(), rawCredentialId, credential.publicKey().getEncoded(), credential.algorithm(), credential.signCount(), row.origin(), row.rpId(), label);
        jdbc.update("""
                update portal_users
                   set passkey_enrolled_at=coalesce(passkey_enrolled_at,now()),
                       passkey_registration_challenge=null,passkey_registration_expires_at=null,
                       passkey_assertion_challenge=null,passkey_assertion_expires_at=null,updated_at=now()
                 where id=?
                """, row.id());
        return id;
    }

    private AttestedCredential resolveRegistrationCredential(RegistrationFinish input, String rpId, byte[] rawCredentialId) {
        IllegalArgumentException attestationFailure;
        try {
            return parseAttestation(decodeBase64Url(input.attestationObject()), rpId);
        } catch (IllegalArgumentException exception) {
            attestationFailure = exception;
        }

        if (input.authenticatorData() == null || input.authenticatorData().isBlank()
                || input.publicKey() == null || input.publicKey().isBlank()
                || input.publicKeyAlgorithm() == null) {
            throw attestationFailure;
        }

        try {
            byte[] authenticatorData = decodeBase64Url(input.authenticatorData());
            AuthenticatorData parsed = validateAuthenticatorData(authenticatorData, rpId, true);
            byte[] credentialId = extractCredentialId(authenticatorData);
            if (!MessageDigest.isEqual(rawCredentialId, credentialId)) {
                throw new IllegalArgumentException("A passkey criada não corresponde ao identificador confirmado pelo autenticador.");
            }

            int algorithm = input.publicKeyAlgorithm();
            PublicKey publicKey = decodeRegistrationPublicKey(decodeBase64Url(input.publicKey()), algorithm);
            log.warn("Cadastro de passkey usando compatibilidade WebAuthn SPKI após rejeição do parser CBOR: {}", attestationFailure.getMessage());
            return new AttestedCredential(credentialId, publicKey, algorithm, parsed.signCount());
        } catch (IllegalArgumentException exception) {
            throw new IllegalArgumentException(
                    "A passkey foi criada pelo aparelho, mas a validação final falhou. "
                            + "Motivo principal: " + attestationFailure.getMessage()
                            + " Motivo de compatibilidade: " + exception.getMessage()
            );
        }
    }

    private byte[] extractCredentialId(byte[] authenticatorData) {
        if (authenticatorData == null || authenticatorData.length < 55) {
            throw new IllegalArgumentException("AuthenticatorData da passkey não contém os dados da credencial.");
        }
        int flags = authenticatorData[32] & 0xff;
        if ((flags & 0x40) == 0) {
            throw new IllegalArgumentException("AuthenticatorData da passkey não contém credencial atestada.");
        }
        int credentialLength = ((authenticatorData[53] & 0xff) << 8) | (authenticatorData[54] & 0xff);
        int start = 55;
        int end = start + credentialLength;
        if (credentialLength <= 0 || end > authenticatorData.length) {
            throw new IllegalArgumentException("Identificador da passkey inválido no AuthenticatorData.");
        }
        return Arrays.copyOfRange(authenticatorData, start, end);
    }

    private PublicKey decodeRegistrationPublicKey(byte[] encoded, int algorithm) {
        try {
            String keyAlgorithm = switch (algorithm) {
                case -7 -> "EC";
                case -257 -> "RSA";
                default -> throw new IllegalArgumentException(
                        "Algoritmo da passkey não suportado pelo portal: " + algorithm + "."
                );
            };
            return KeyFactory.getInstance(keyAlgorithm).generatePublic(new X509EncodedKeySpec(encoded));
        } catch (IllegalArgumentException exception) {
            throw exception;
        } catch (Exception exception) {
            throw new IllegalArgumentException("A chave pública da passkey criada pelo aparelho não pôde ser validada.", exception);
        }
    }

    private int credentialCount(UUID userId) {
        Integer count = jdbc.queryForObject("select count(*) from portal_admin_passkeys where user_id=?", Integer.class, userId);
        return count == null ? 0 : count;
    }

    private List<CredentialRow> credentials(UUID userId) {
        return jdbc.query("""
                select id,credential_id,public_key,algorithm,sign_count,origin,rp_id,label,created_at,last_used_at
                  from portal_admin_passkeys where user_id=? order by created_at asc
                """, (rs, index) -> credentialRow(rs), userId);
    }

    private CredentialRow credential(UUID userId, String credentialId) {
        List<CredentialRow> rows = jdbc.query("""
                select id,credential_id,public_key,algorithm,sign_count,origin,rp_id,label,created_at,last_used_at
                  from portal_admin_passkeys where user_id=? and credential_id=?
                """, (rs, index) -> credentialRow(rs), userId, credentialId);
        return rows.isEmpty() ? null : rows.get(0);
    }

    private CredentialRow credentialRow(ResultSet rs) throws SQLException {
        return new CredentialRow(
                rs.getObject("id", UUID.class), rs.getString("credential_id"), rs.getBytes("public_key"),
                (Integer) rs.getObject("algorithm"), rs.getLong("sign_count"), rs.getString("origin"), rs.getString("rp_id"),
                rs.getString("label"), offset(rs, "created_at"), offset(rs, "last_used_at")
        );
    }

    private void assertEligible(UserRow row) {
        if (!row.active()) throw new IllegalArgumentException("Conta de acesso inativa.");
        if (!required(row.role())) throw new IllegalArgumentException("Passkey não disponível para este perfil.");
    }

    private UserRow find(String username, boolean lock) {
        String normalized = PortalUser.normalizeUsername(username);
        String sql = """
                select id,username,display_name,role,active,
                       passkey_credential_id,passkey_public_key,passkey_algorithm,passkey_sign_count,
                       passkey_registration_challenge,passkey_registration_expires_at,
                       passkey_assertion_challenge,passkey_assertion_expires_at,
                       passkey_origin,passkey_rp_id,passkey_enrolled_at
                  from portal_users where normalized_username=?
                """ + (lock ? " for update" : "");
        UserRow row = jdbc.query(sql, rs -> rs.next() ? map(rs) : null, normalized);
        if (row == null) throw new IllegalArgumentException("Conta de acesso não encontrada.");
        return row;
    }

    private UserRow map(ResultSet rs) throws SQLException {
        Object id = rs.getObject("id");
        return new UserRow(
                id instanceof UUID uuid ? uuid : UUID.fromString(String.valueOf(id)),
                rs.getString("username"), rs.getString("display_name"), PortalRole.valueOf(rs.getString("role")), rs.getBoolean("active"),
                rs.getString("passkey_credential_id"), rs.getBytes("passkey_public_key"), (Integer) rs.getObject("passkey_algorithm"), rs.getLong("passkey_sign_count"),
                rs.getString("passkey_registration_challenge"), offset(rs, "passkey_registration_expires_at"),
                rs.getString("passkey_assertion_challenge"), offset(rs, "passkey_assertion_expires_at"),
                rs.getString("passkey_origin"), rs.getString("passkey_rp_id"), offset(rs, "passkey_enrolled_at")
        );
    }

    private AttestedCredential parseAttestation(byte[] attestationObject, String rpId) {
        try {
            Object decoded = new CborReader(attestationObject).read();
            if (!(decoded instanceof Map<?, ?> root)) throw new IllegalArgumentException("Objeto de atestação da passkey inválido.");
            Object authDataValue = root.get("authData");
            if (!(authDataValue instanceof byte[] authData)) throw new IllegalArgumentException("Resposta da passkey sem authenticatorData.");
            AuthenticatorData parsed = validateAuthenticatorData(authData, rpId, true);
            if (authData.length < 55 || (parsed.flags() & 0x40) == 0) throw new IllegalArgumentException("A resposta da passkey não contém a chave pública da credencial.");
            int credentialLength = ((authData[53] & 0xff) << 8) | (authData[54] & 0xff);
            int start = 55, end = start + credentialLength;
            if (credentialLength <= 0 || end >= authData.length) throw new IllegalArgumentException("Identificador da passkey inválido.");
            byte[] credentialId = Arrays.copyOfRange(authData, start, end);
            Object coseDecoded = new CborReader(Arrays.copyOfRange(authData, end, authData.length)).read();
            if (!(coseDecoded instanceof Map<?, ?> coseKey)) throw new IllegalArgumentException("Chave pública da passkey inválida.");
            CosePublicKey publicKey = parseCosePublicKey(coseKey);
            return new AttestedCredential(credentialId, publicKey.publicKey(), publicKey.algorithm(), parsed.signCount());
        } catch (IllegalArgumentException e) {
            throw e;
        } catch (Exception e) {
            throw new IllegalArgumentException("Não foi possível validar a passkey criada pelo aparelho.", e);
        }
    }

    private CosePublicKey parseCosePublicKey(Map<?, ?> key) throws Exception {
        int kty = intField(key, 1L), alg = intField(key, 3L);
        if (kty == 2 && alg == -7) {
            if (intField(key, -1L) != 1) throw new IllegalArgumentException("Curva WebAuthn não suportada.");
            byte[] x = binaryField(key, -2L), y = binaryField(key, -3L);
            AlgorithmParameters parameters = AlgorithmParameters.getInstance("EC");
            parameters.init(new ECGenParameterSpec("secp256r1"));
            ECParameterSpec ec = parameters.getParameterSpec(ECParameterSpec.class);
            ECPublicKeySpec spec = new ECPublicKeySpec(new ECPoint(new BigInteger(1, x), new BigInteger(1, y)), ec);
            ECPublicKey pk = (ECPublicKey) KeyFactory.getInstance("EC").generatePublic(spec);
            return new CosePublicKey(pk, alg);
        }
        if (kty == 3 && alg == -257) {
            PublicKey pk = KeyFactory.getInstance("RSA").generatePublic(new RSAPublicKeySpec(new BigInteger(1, binaryField(key, -1L)), new BigInteger(1, binaryField(key, -2L))));
            return new CosePublicKey(pk, alg);
        }
        throw new IllegalArgumentException("Algoritmo de passkey não suportado. Use ES256 ou RS256.");
    }

    private AuthenticatorData validateAuthenticatorData(byte[] data, String rpId, boolean requireUv) {
        if (data == null || data.length < 37) throw new IllegalArgumentException("AuthenticatorData da passkey inválido.");
        if (!MessageDigest.isEqual(sha256Bytes(rpId.getBytes(StandardCharsets.UTF_8)), Arrays.copyOfRange(data, 0, 32))) {
            throw new IllegalArgumentException("A passkey foi emitida para outro domínio.");
        }
        int flags = data[32] & 0xff;
        boolean present = (flags & 1) != 0, verified = (flags & 4) != 0;
        if (!present) throw new IllegalArgumentException("A presença do usuário não foi confirmada pelo autenticador.");
        if (requireUv && !verified) throw new IllegalArgumentException("A biometria, PIN ou bloqueio seguro do aparelho não confirmou o usuário.");
        long signCount = Integer.toUnsignedLong(ByteBuffer.wrap(data, 33, 4).getInt());
        return new AuthenticatorData(flags, signCount, verified);
    }

    private void validateClientData(byte[] bytes, String type, String challenge, String origin) {
        try {
            JsonNode node = jsonMapper.readTree(bytes);
            if (!type.equals(node.path("type").asText())) throw new IllegalArgumentException("Cerimônia de passkey inesperada.");
            if (!normalizeBase64Url(challenge).equals(normalizeBase64Url(node.path("challenge").asText()))) throw new IllegalArgumentException("O desafio da passkey expirou ou não pertence a este login.");
            String receivedOrigin = node.path("origin").asText();
            if (!origin.equals(receivedOrigin)) {
                log.warn("WebAuthn clientData origin divergente: expected={}, received={}", origin, receivedOrigin);
                throw new IllegalArgumentException("A origem da passkey não corresponde ao portal.");
            }
        } catch (IllegalArgumentException e) {
            throw e;
        } catch (Exception e) {
            throw new IllegalArgumentException("ClientDataJSON da passkey inválido.", e);
        }
    }

    private void verifyAssertionSignature(PublicKey key, Integer algorithm, byte[] auth, byte[] client, byte[] signature) {
        try {
            String alg = switch (algorithm == null ? 0 : algorithm) {
                case -7 -> "SHA256withECDSA";
                case -257 -> "SHA256withRSA";
                default -> throw new IllegalArgumentException("Algoritmo da passkey não suportado.");
            };
            Signature verifier = Signature.getInstance(alg);
            verifier.initVerify(key);
            verifier.update(auth);
            verifier.update(sha256Bytes(client));
            if (!verifier.verify(signature)) throw new IllegalArgumentException("A assinatura criptográfica da passkey não é válida.");
        } catch (IllegalArgumentException e) {
            throw e;
        } catch (Exception e) {
            throw new IllegalArgumentException("Não foi possível verificar a assinatura criptográfica da passkey.", e);
        }
    }

    private PublicKey decodeStoredPublicKey(byte[] encoded, Integer algorithm) {
        try {
            return KeyFactory.getInstance(algorithm != null && algorithm == -257 ? "RSA" : "EC").generatePublic(new X509EncodedKeySpec(encoded));
        } catch (Exception e) {
            throw new IllegalArgumentException("A chave pública da passkey armazenada não pôde ser validada.", e);
        }
    }

    private OriginInfo resolveOrigin(HttpServletRequest request) {
        try {
            // WebAuthn valida a origem EXATA vista pelo navegador (esquema + host + porta).
            // Em produção há Caddy -> Nginx -> backend e, no teste local, Nginx -> backend.
            // Por isso o Host interno pode perder https/porta. O cabeçalho Origin, enviado pelo
            // próprio navegador nas cerimônias POST, é a fonte correta quando pertence ao mesmo host.
            String browserOrigin = firstHeader(request, "Origin");
            if (browserOrigin != null) {
                URI browserUri = URI.create(browserOrigin);
                String browserScheme = browserUri.getScheme();
                String browserHost = browserUri.getHost();
                if (("http".equalsIgnoreCase(browserScheme) || "https".equalsIgnoreCase(browserScheme))
                        && browserHost != null && !browserHost.isBlank()) {
                    String forwardedHost = firstHeader(request, "X-Forwarded-Host");
                    String requestHostPort = forwardedHost != null ? forwardedHost : request.getHeader("Host");
                    String requestHost = hostOnly(requestHostPort);
                    if (requestHost == null || requestHost.equalsIgnoreCase(browserHost)) {
                        return new OriginInfo(normalizedOrigin(browserUri), browserHost);
                    }
                    log.warn("WebAuthn Origin ignorado por host divergente: originHost={}, requestHost={}", browserHost, requestHost);
                }
            }

            String forwardedProto = firstHeader(request, "X-Forwarded-Proto");
            String forwardedHost = firstHeader(request, "X-Forwarded-Host");
            String scheme = forwardedProto != null ? forwardedProto : request.getScheme();
            String hostPort = forwardedHost != null ? forwardedHost : request.getHeader("Host");
            if (hostPort == null || hostPort.isBlank()) {
                hostPort = request.getServerName() + (isDefaultPort(scheme, request.getServerPort()) ? "" : ":" + request.getServerPort());
            }
            URI uri = URI.create(scheme + "://" + hostPort);
            String host = uri.getHost();
            if (host == null || host.isBlank()) throw new IllegalArgumentException("Domínio do portal inválido.");
            return new OriginInfo(normalizedOrigin(uri), host);
        } catch (Exception e) {
            throw new IllegalArgumentException("Não foi possível identificar o domínio seguro para a passkey.", e);
        }
    }

    private String hostOnly(String hostPort) {
        if (hostPort == null || hostPort.isBlank()) return null;
        try {
            URI uri = URI.create("http://" + hostPort.trim());
            return uri.getHost();
        } catch (Exception ignored) {
            return null;
        }
    }

    private String normalizedOrigin(URI uri) {
        String scheme = uri.getScheme().toLowerCase();
        String host = uri.getHost().toLowerCase();
        int port = uri.getPort();
        return scheme + "://" + host + (port > 0 && !isDefaultPort(scheme, port) ? ":" + port : "");
    }

    private boolean isDefaultPort(String scheme, int port) {
        return port <= 0 || ("https".equalsIgnoreCase(scheme) && port == 443) || ("http".equalsIgnoreCase(scheme) && port == 80);
    }

    private String firstHeader(HttpServletRequest request, String name) {
        String value = request.getHeader(name);
        return value == null || value.isBlank() ? null : value.trim().split(",")[0].trim();
    }

    private int intField(Map<?, ?> node, long field) {
        Object value = node.get(field);
        if (!(value instanceof Number number)) throw new IllegalArgumentException("Chave COSE inválida: " + field);
        return number.intValue();
    }

    private byte[] binaryField(Map<?, ?> node, long field) {
        Object value = node.get(field);
        if (!(value instanceof byte[] bytes)) throw new IllegalArgumentException("Chave COSE inválida: " + field);
        return bytes;
    }

    private byte[] decodeBase64Url(String value) {
        try {
            return Base64.getUrlDecoder().decode(normalizeBase64Url(value));
        } catch (Exception e) {
            throw new IllegalArgumentException("Campo da passkey em Base64URL inválido.");
        }
    }

    private String normalizeBase64Url(String value) { return value == null ? "" : value.trim().replace("=", ""); }
    private String randomBase64Url(int bytes) { byte[] buffer = new byte[bytes]; secureRandom.nextBytes(buffer); return base64Url(buffer); }
    private String base64Url(byte[] bytes) { return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes); }
    private byte[] sha256Bytes(byte[] bytes) { try { return MessageDigest.getInstance("SHA-256").digest(bytes); } catch (Exception e) { throw new IllegalStateException("SHA-256 indisponível.", e); } }
    private static OffsetDateTime offset(ResultSet rs, String column) throws SQLException { Object value = rs.getObject(column); if (value == null) return null; if (value instanceof OffsetDateTime date) return date; if (value instanceof java.sql.Timestamp timestamp) return timestamp.toInstant().atOffset(java.time.ZoneOffset.UTC); return OffsetDateTime.parse(String.valueOf(value)); }

    private static final class CborReader {
        private final byte[] data; private int position;
        private CborReader(byte[] data) { this.data = data == null ? new byte[0] : data; }
        private Object read() { if (position >= data.length) throw new IllegalArgumentException("CBOR inesperadamente vazio."); int initial = readUnsignedByte(), major = initial >>> 5, additional = initial & 0x1f; if (major == 7) return readSimple(additional); long length = readLength(additional); return switch (major) { case 0 -> length; case 1 -> -1L - length; case 2 -> readBytes(length); case 3 -> new String(readBytes(length), StandardCharsets.UTF_8); case 4 -> readArray(length); case 5 -> readMap(length); case 6 -> read(); default -> throw new IllegalArgumentException("Tipo CBOR não suportado: " + major); }; }
        private Object readSimple(int additional) { return switch (additional) { case 20 -> Boolean.FALSE; case 21 -> Boolean.TRUE; case 22, 23 -> null; default -> throw new IllegalArgumentException("Valor simples CBOR não suportado: " + additional); }; }
        private long readLength(int additional) { if (additional < 24) return additional; return switch (additional) { case 24 -> readUnsignedByte(); case 25 -> ((long) readUnsignedByte() << 8) | readUnsignedByte(); case 26 -> ((long) readUnsignedByte() << 24) | ((long) readUnsignedByte() << 16) | ((long) readUnsignedByte() << 8) | readUnsignedByte(); case 27 -> { long value = 0; for (int i = 0; i < 8; i++) value = (value << 8) | readUnsignedByte(); yield value; } default -> throw new IllegalArgumentException("CBOR de tamanho indefinido não é aceito."); }; }
        private byte[] readBytes(long length) { if (length < 0 || length > Integer.MAX_VALUE || position + length > data.length) throw new IllegalArgumentException("Comprimento CBOR inválido."); int size = (int) length; byte[] value = Arrays.copyOfRange(data, position, position + size); position += size; return value; }
        private List<Object> readArray(long length) { if (length < 0 || length > 10000) throw new IllegalArgumentException("Array CBOR inválido."); List<Object> list = new ArrayList<>((int) length); for (long i = 0; i < length; i++) list.add(read()); return list; }
        private Map<Object, Object> readMap(long length) { if (length < 0 || length > 10000) throw new IllegalArgumentException("Mapa CBOR inválido."); Map<Object, Object> map = new LinkedHashMap<>(); for (long i = 0; i < length; i++) map.put(read(), read()); return map; }
        private int readUnsignedByte() { if (position >= data.length) throw new IllegalArgumentException("Fim inesperado do CBOR."); return data[position++] & 0xff; }
    }

    public record RegistrationOptions(String challenge, String rpId, String rpName, String userId, String userName, String userDisplayName, long timeoutMs, List<String> excludeCredentialIds) {}
    public record AssertionOptions(String challenge, String rpId, List<String> credentialIds, long timeoutMs) {}
    public record RegistrationFinish(
            String rawId,
            String type,
            String clientDataJSON,
            String attestationObject,
            String authenticatorData,
            String publicKey,
            Integer publicKeyAlgorithm
    ) {
        public RegistrationFinish(String rawId, String type, String clientDataJSON, String attestationObject) {
            this(rawId, type, clientDataJSON, attestationObject, null, null, null);
        }
    }
    public record AssertionFinish(String rawId, String type, String clientDataJSON, String authenticatorData, String signature) {}
    private record OriginInfo(String origin, String rpId) {}
    private record AuthenticatorData(int flags, long signCount, boolean userVerified) {}
    private record CosePublicKey(PublicKey publicKey, int algorithm) {}
    private record AttestedCredential(byte[] credentialId, PublicKey publicKey, int algorithm, long signCount) {}
    public record PasskeyInfo(UUID id, String credentialId, String label, OffsetDateTime createdAt, OffsetDateTime lastUsedAt) {}
    private record CredentialRow(UUID id, String credentialId, byte[] publicKey, Integer algorithm, long signCount, String origin, String rpId, String label, OffsetDateTime createdAt, OffsetDateTime lastUsedAt) {}
    private record UserRow(UUID id, String username, String displayName, PortalRole role, boolean active, String credentialId, byte[] publicKey, Integer algorithm, long signCount, String registrationChallenge, OffsetDateTime registrationExpiresAt, String assertionChallenge, OffsetDateTime assertionExpiresAt, String origin, String rpId, OffsetDateTime enrolledAt) {}
}
