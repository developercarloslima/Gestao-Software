package br.com.nh.cotacao.service;

import br.com.nh.cotacao.platform.TenantIdentity;
import br.com.nh.cotacao.dto.InspectionDtos.DeviceMetadata;
import br.com.nh.cotacao.dto.InspectionDtos.DigitalAcceptanceStatusResponse;
import br.com.nh.cotacao.dto.InspectionDtos.WebAuthnAssertionFinishRequest;
import br.com.nh.cotacao.dto.InspectionDtos.WebAuthnAssertionOptionsResponse;
import br.com.nh.cotacao.dto.InspectionDtos.WebAuthnRegistrationFinishRequest;
import br.com.nh.cotacao.dto.InspectionDtos.WebAuthnRegistrationOptionsResponse;
import br.com.nh.cotacao.entity.InspectionAsset;
import br.com.nh.cotacao.entity.InspectionAssetType;
import br.com.nh.cotacao.entity.InspectionRequest;
import br.com.nh.cotacao.entity.InspectionRequestStatus;
import br.com.nh.cotacao.repository.InspectionRequestRepository;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
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
import java.time.OffsetDateTime;
import java.util.Arrays;
import java.util.Base64;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.LinkedHashMap;
import java.util.ArrayList;
import java.util.Locale;
import java.util.UUID;

@Service
public class InspectionDigitalAcceptanceService {
    private static final Logger log = LoggerFactory.getLogger(InspectionDigitalAcceptanceService.class);
    private static final long CEREMONY_TIMEOUT_MS = 120_000L;
    private static final int CHALLENGE_BYTES = 32;
    private static final String RP_NAME = "" + TenantIdentity.legalName() + "";

    private final InspectionRequestRepository repository;
    private final InspectionAssetStorageService storageService;
    private final AcceptedDossierRefreshService acceptedDossierRefreshService;
    private final InspectionReportDownloadService reportDownloadService;
    private final ObjectMapper jsonMapper;
    private final SecureRandom secureRandom = new SecureRandom();

    public InspectionDigitalAcceptanceService(
            InspectionRequestRepository repository,
            InspectionAssetStorageService storageService,
            AcceptedDossierRefreshService acceptedDossierRefreshService,
            InspectionReportDownloadService reportDownloadService,
            ObjectMapper jsonMapper
    ) {
        this.repository = repository;
        this.storageService = storageService;
        this.acceptedDossierRefreshService = acceptedDossierRefreshService;
        this.reportDownloadService = reportDownloadService;
        this.jsonMapper = jsonMapper;
    }

    @Transactional(readOnly = true)
    public DigitalAcceptanceStatusResponse status(String token) {
        return toStatus(find(token));
    }

    public DigitalAcceptanceStatusResponse toStatus(InspectionRequest request) {
        boolean approved = request.getStatus() == InspectionRequestStatus.APPROVED;
        boolean accepted = request.getAcceptedAt() != null;
        boolean readyForAcceptance = approved
                && request.getRegistrationCompletedAt() != null
                && !request.hasPendingContractChange()
                && (accepted || AcceptanceLinkPolicy.isValid(request.getAcceptanceLinkAvailableAt(), OffsetDateTime.now()));
        return new DigitalAcceptanceStatusResponse(
                approved,
                readyForAcceptance && !accepted,
                accepted,
                request.getAcceptedAt(),
                request.getAcceptanceEvidenceHash(),
                request.getAcceptanceProofHash(),
                request.getAcceptanceDossierSha256(),
                request.getAcceptanceSelfieSha256(),
                request.isAcceptanceUserVerified()
        );
    }

    @Transactional
    public WebAuthnRegistrationOptionsResponse beginRegistration(
            String token,
            DeviceMetadata device,
            HttpServletRequest httpRequest
    ) {
        InspectionRequest request = findForUpdate(token);
        assertApprovedAndPending(request);
        if (device == null) throw new IllegalArgumentException("Não foi possível registrar os metadados do aparelho.");
        if (device.deviceId() == null || device.deviceId().isBlank()) {
            throw new IllegalArgumentException("Não foi possível identificar o aparelho usado no aceite.");
        }
        if (device.latitude() == null || device.longitude() == null) {
            throw new IllegalArgumentException("Ative a localização do aparelho para registrar o GPS junto ao aceite digital.");
        }

        OriginInfo originInfo = resolveOrigin(httpRequest);
        InspectionAsset selfie = findSelfie(request);
        // Antes de calcular o hash do aceite, força a verificação/regeneração do
        // dossiê contra os dados atuais da vistoria e da cotação. Assim o cliente
        // nunca assina uma versão anterior às correções feitas por ADM/Supervisão.
        reportDownloadService.generate(request.getId());
        InspectionAsset dossier = findFinalDossier(request);
        byte[] selfieBytes = storageService.readAll(selfie.getId());
        byte[] dossierBytes = storageService.readAll(dossier.getId());
        String selfieHash = sha256Hex(selfieBytes);
        String dossierHash = sha256Hex(dossierBytes);
        String deviceJson = safeDeviceJson(device);
        String ip = resolveClientIp(httpRequest);
        String evidenceHash = evidenceHash(request, selfieHash, dossierHash, deviceJson, ip, device);
        String challenge = randomBase64Url(CHALLENGE_BYTES);

        request.beginWebAuthnRegistration(
                challenge,
                OffsetDateTime.now().plusMinutes(3),
                originInfo.origin(),
                originInfo.rpId(),
                evidenceHash,
                selfieHash,
                dossierHash,
                deviceJson,
                device.deviceId(),
                ip,
                device.latitude(),
                device.longitude(),
                device.accuracyMeters(),
                parseDeviceCapturedAt(device.capturedAt())
        );
        repository.flush();

        return new WebAuthnRegistrationOptionsResponse(
                challenge,
                originInfo.rpId(),
                RP_NAME,
                base64Url(sha256Bytes(("inspection-user:" + request.getId()).getBytes(StandardCharsets.UTF_8))),
                "associado-" + request.getId(),
                request.getAssociateName(),
                CEREMONY_TIMEOUT_MS
        );
    }

    @Transactional
    public WebAuthnAssertionOptionsResponse finishRegistration(
            String token,
            WebAuthnRegistrationFinishRequest input
    ) {
        InspectionRequest request = findForUpdate(token);
        assertApprovedAndPending(request);
        assertPendingRegistration(request);
        if (input == null) throw new IllegalArgumentException("Dados do WebAuthn não informados.");

        byte[] clientDataBytes = decodeBase64Url(input.clientDataJSON());
        validateClientData(
                clientDataBytes,
                "webauthn.create",
                request.getWebauthnRegistrationChallenge(),
                request.getWebauthnOrigin()
        );

        byte[] attestationObject = decodeBase64Url(input.attestationObject());
        AttestedCredential credential = parseAttestation(attestationObject, request.getWebauthnRpId());
        String rawCredentialId = normalizeBase64Url(input.rawId());
        if (!rawCredentialId.equals(base64Url(credential.credentialId()))) {
            throw new IllegalArgumentException("A credencial criada pelo aparelho não corresponde ao registro recebido.");
        }
        if (!"public-key".equals(input.type())) {
            throw new IllegalArgumentException("Tipo de credencial WebAuthn inválido.");
        }

        request.registerWebAuthnCredential(
                rawCredentialId,
                credential.publicKey().getEncoded(),
                credential.algorithm(),
                credential.signCount()
        );
        repository.flush();
        return createAssertionOptions(request);
    }

    @Transactional
    public WebAuthnAssertionOptionsResponse assertionOptions(String token) {
        InspectionRequest request = findForUpdate(token);
        assertApprovedAndPending(request);
        if (request.getWebauthnCredentialId() == null || request.getWebauthnPublicKey() == null) {
            throw new IllegalArgumentException("Primeiro confirme a biometria/PIN para criar a credencial segura deste aceite.");
        }
        return createAssertionOptions(request);
    }

    @Transactional
    public DigitalAcceptanceStatusResponse finishAssertion(
            String token,
            WebAuthnAssertionFinishRequest input
    ) {
        InspectionRequest request = findForUpdate(token);
        assertApprovedAndPending(request);
        assertPendingAssertion(request);
        if (input == null) throw new IllegalArgumentException("Dados da confirmação WebAuthn não informados.");
        if (!"public-key".equals(input.type())) throw new IllegalArgumentException("Tipo de credencial WebAuthn inválido.");

        String credentialId = normalizeBase64Url(input.rawId());
        if (!credentialId.equals(request.getWebauthnCredentialId())) {
            throw new IllegalArgumentException("A credencial usada não pertence a este aceite.");
        }

        byte[] clientData = decodeBase64Url(input.clientDataJSON());
        validateClientData(
                clientData,
                "webauthn.get",
                request.getWebauthnAssertionChallenge(),
                request.getWebauthnOrigin()
        );

        byte[] authenticatorData = decodeBase64Url(input.authenticatorData());
        AuthenticatorData parsed = validateAuthenticatorData(authenticatorData, request.getWebauthnRpId(), true);
        byte[] signatureBytes = decodeBase64Url(input.signature());
        PublicKey publicKey = decodeStoredPublicKey(request.getWebauthnPublicKey(), request.getWebauthnAlgorithm());
        verifyAssertionSignature(publicKey, request.getWebauthnAlgorithm(), authenticatorData, clientData, signatureBytes);

        long previousCount = request.getWebauthnSignCount();
        if (parsed.signCount() != 0 && previousCount != 0 && parsed.signCount() <= previousCount) {
            throw new IllegalArgumentException("O contador da credencial WebAuthn não avançou. Por segurança, o aceite foi recusado.");
        }

        OffsetDateTime acceptedAt = OffsetDateTime.now();
        String proofHash = proofHash(
                request,
                request.getWebauthnAssertionChallenge(),
                input.authenticatorData(),
                input.clientDataJSON(),
                input.signature(),
                credentialId,
                acceptedAt
        );

        request.completeDigitalAcceptance(
                parsed.signCount(),
                normalizeBase64Url(input.signature()),
                normalizeBase64Url(input.authenticatorData()),
                normalizeBase64Url(input.clientDataJSON()),
                proofHash,
                parsed.userVerified(),
                acceptedAt
        );
        repository.flush();

        // A prova e persistida antes de tentar regenerar o PDF. Se a geracao do
        // relatorio falhar (inclusive por restricao de integridade), a assinatura
        // criptografica JA CONFIRMADA nao pode ser revertida por essa operacao.
        UUID inspectionId = request.getId();
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCommit() {
                try {
                    acceptedDossierRefreshService.refresh(inspectionId);
                } catch (Exception error) {
                    // Os detalhes da falha ficam exclusivamente no log interno,
                    // nunca na resposta publica com dados pessoais/SQL.
                    log.error("NH WebAuthn: aceite registrado, mas falha ao atualizar PDF do dossie da vistoria {}. "
                            + "A prova digital permanece persistida para reparo do PDF.", inspectionId, error);
                }
            }
        });
        return toStatus(request);
    }

    private WebAuthnAssertionOptionsResponse createAssertionOptions(InspectionRequest request) {
        String evidenceHash = request.getAcceptanceEvidenceHash();
        if (evidenceHash == null || evidenceHash.isBlank()) {
            throw new IllegalArgumentException("A evidência vinculada ao aceite não foi preparada. Reinicie a confirmação.");
        }
        byte[] random = new byte[CHALLENGE_BYTES];
        secureRandom.nextBytes(random);
        byte[] bound = sha256Bytes(concat(random, evidenceHash.getBytes(StandardCharsets.UTF_8)));
        String challenge = base64Url(bound);
        request.beginWebAuthnAssertion(challenge, OffsetDateTime.now().plusMinutes(3));
        repository.flush();
        return new WebAuthnAssertionOptionsResponse(
                challenge,
                request.getWebauthnRpId(),
                request.getWebauthnCredentialId(),
                CEREMONY_TIMEOUT_MS,
                evidenceHash,
                request.getAcceptanceDossierSha256(),
                request.getAcceptanceSelfieSha256()
        );
    }

    private AttestedCredential parseAttestation(byte[] attestationObject, String rpId) {
        try {
            Object decoded = new CborReader(attestationObject).read();
            if (!(decoded instanceof Map<?, ?> root)) {
                throw new IllegalArgumentException("Objeto de atestação WebAuthn inválido.");
            }
            Object authDataValue = root.get("authData");
            if (!(authDataValue instanceof byte[] authData)) {
                throw new IllegalArgumentException("Resposta WebAuthn sem authenticatorData.");
            }
            AuthenticatorData parsed = validateAuthenticatorData(authData, rpId, true);
            if (authData.length < 55 || (parsed.flags() & 0x40) == 0) {
                throw new IllegalArgumentException("A resposta WebAuthn não contém a chave pública da credencial.");
            }
            int credentialLength = ((authData[53] & 0xff) << 8) | (authData[54] & 0xff);
            int credentialStart = 55;
            int credentialEnd = credentialStart + credentialLength;
            if (credentialLength <= 0 || credentialEnd >= authData.length) {
                throw new IllegalArgumentException("Identificador da credencial WebAuthn inválido.");
            }
            byte[] credentialId = Arrays.copyOfRange(authData, credentialStart, credentialEnd);
            byte[] coseBytes = Arrays.copyOfRange(authData, credentialEnd, authData.length);
            Object coseDecoded = new CborReader(coseBytes).read();
            if (!(coseDecoded instanceof Map<?, ?> coseKey)) {
                throw new IllegalArgumentException("Chave pública COSE inválida.");
            }
            CosePublicKey publicKey = parseCosePublicKey(coseKey);
            return new AttestedCredential(credentialId, publicKey.publicKey(), publicKey.algorithm(), parsed.signCount());
        } catch (IllegalArgumentException exception) {
            throw exception;
        } catch (Exception exception) {
            throw new IllegalArgumentException("Não foi possível validar a credencial WebAuthn criada pelo aparelho.", exception);
        }
    }

    private CosePublicKey parseCosePublicKey(Map<?, ?> key) throws Exception {
        int kty = intField(key, 1L);
        int alg = intField(key, 3L);
        if (kty == 2 && alg == -7) {
            int curve = intField(key, -1L);
            if (curve != 1) throw new IllegalArgumentException("Curva WebAuthn não suportada.");
            byte[] x = binaryField(key, -2L);
            byte[] y = binaryField(key, -3L);
            AlgorithmParameters parameters = AlgorithmParameters.getInstance("EC");
            parameters.init(new ECGenParameterSpec("secp256r1"));
            ECParameterSpec ecParameters = parameters.getParameterSpec(ECParameterSpec.class);
            ECPublicKeySpec spec = new ECPublicKeySpec(
                    new ECPoint(new BigInteger(1, x), new BigInteger(1, y)),
                    ecParameters
            );
            ECPublicKey publicKey = (ECPublicKey) KeyFactory.getInstance("EC").generatePublic(spec);
            return new CosePublicKey(publicKey, alg);
        }
        if (kty == 3 && alg == -257) {
            byte[] n = binaryField(key, -1L);
            byte[] e = binaryField(key, -2L);
            PublicKey publicKey = KeyFactory.getInstance("RSA").generatePublic(
                    new RSAPublicKeySpec(new BigInteger(1, n), new BigInteger(1, e))
            );
            return new CosePublicKey(publicKey, alg);
        }
        throw new IllegalArgumentException("Algoritmo WebAuthn não suportado. Use um autenticador compatível com ES256 ou RS256.");
    }

    private AuthenticatorData validateAuthenticatorData(byte[] data, String rpId, boolean requireUv) {
        if (data == null || data.length < 37) throw new IllegalArgumentException("AuthenticatorData WebAuthn inválido.");
        byte[] expectedRpHash = sha256Bytes(rpId.getBytes(StandardCharsets.UTF_8));
        byte[] receivedRpHash = Arrays.copyOfRange(data, 0, 32);
        if (!MessageDigest.isEqual(expectedRpHash, receivedRpHash)) {
            throw new IllegalArgumentException("A credencial WebAuthn foi emitida para outro domínio.");
        }
        int flags = data[32] & 0xff;
        boolean userPresent = (flags & 0x01) != 0;
        boolean userVerified = (flags & 0x04) != 0;
        if (!userPresent) throw new IllegalArgumentException("A presença do usuário não foi confirmada pelo autenticador.");
        if (requireUv && !userVerified) {
            throw new IllegalArgumentException("A biometria, PIN ou bloqueio seguro do aparelho não confirmou o usuário.");
        }
        long signCount = Integer.toUnsignedLong(ByteBuffer.wrap(data, 33, 4).getInt());
        return new AuthenticatorData(flags, signCount, userVerified);
    }

    private void validateClientData(byte[] clientDataBytes, String expectedType, String expectedChallenge, String expectedOrigin) {
        try {
            JsonNode clientData = jsonMapper.readTree(clientDataBytes);
            String type = clientData.path("type").asText();
            String challenge = normalizeBase64Url(clientData.path("challenge").asText());
            String origin = clientData.path("origin").asText();
            if (!expectedType.equals(type)) throw new IllegalArgumentException("Cerimônia WebAuthn inesperada.");
            if (!normalizeBase64Url(expectedChallenge).equals(challenge)) {
                throw new IllegalArgumentException("O desafio WebAuthn expirou ou não pertence a este aceite.");
            }
            if (!expectedOrigin.equals(origin)) {
                throw new IllegalArgumentException("A origem da confirmação WebAuthn não corresponde à página do aceite.");
            }
        } catch (IllegalArgumentException exception) {
            throw exception;
        } catch (Exception exception) {
            throw new IllegalArgumentException("ClientDataJSON WebAuthn inválido.", exception);
        }
    }

    private void verifyAssertionSignature(
            PublicKey publicKey,
            Integer algorithm,
            byte[] authenticatorData,
            byte[] clientData,
            byte[] signatureBytes
    ) {
        try {
            String signatureAlgorithm = switch (algorithm == null ? 0 : algorithm) {
                case -7 -> "SHA256withECDSA";
                case -257 -> "SHA256withRSA";
                default -> throw new IllegalArgumentException("Algoritmo da credencial WebAuthn não suportado.");
            };
            byte[] clientDataHash = sha256Bytes(clientData);
            Signature verifier = Signature.getInstance(signatureAlgorithm);
            verifier.initVerify(publicKey);
            verifier.update(authenticatorData);
            verifier.update(clientDataHash);
            if (!verifier.verify(signatureBytes)) {
                throw new IllegalArgumentException("A assinatura criptográfica WebAuthn não é válida.");
            }
        } catch (IllegalArgumentException exception) {
            throw exception;
        } catch (Exception exception) {
            throw new IllegalArgumentException("Não foi possível verificar a assinatura criptográfica WebAuthn.", exception);
        }
    }

    private PublicKey decodeStoredPublicKey(byte[] encoded, Integer algorithm) {
        try {
            String factory = algorithm != null && algorithm == -257 ? "RSA" : "EC";
            return KeyFactory.getInstance(factory).generatePublic(new X509EncodedKeySpec(encoded));
        } catch (Exception exception) {
            throw new IllegalArgumentException("A chave pública WebAuthn armazenada não pôde ser validada.", exception);
        }
    }

    private InspectionAsset findSelfie(InspectionRequest request) {
        return request.getAssets().stream()
                .filter(asset -> asset.getAssetType() == InspectionAssetType.PHOTO)
                .filter(storageService::isAvailable)
                .filter(asset -> asset.getLabel() != null && asset.getLabel().toLowerCase(Locale.ROOT).contains("selfie"))
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException("A selfie obrigatória não está disponível para vincular ao aceite digital."));
    }

    private InspectionAsset findFinalDossier(InspectionRequest request) {
        return request.getAssets().stream()
                .filter(asset -> asset.getAssetType() == InspectionAssetType.REPORT)
                .filter(storageService::isAvailable)
                .max((a, b) -> {
                    if (a.getStoredAt() == null && b.getStoredAt() == null) return 0;
                    if (a.getStoredAt() == null) return -1;
                    if (b.getStoredAt() == null) return 1;
                    return a.getStoredAt().compareTo(b.getStoredAt());
                })
                .orElseThrow(() -> new IllegalArgumentException("O dossiê final aprovado ainda não está disponível para o aceite digital."));
    }

    private void assertApprovedAndPending(InspectionRequest request) {
        if (request.getStatus() != InspectionRequestStatus.APPROVED) {
            throw new IllegalArgumentException("O aceite digital só é liberado depois da aprovação da Supervisão.");
        }
        if (request.getRegistrationCompletedAt() == null) {
            throw new IllegalArgumentException("Marque Cadastro realizado antes de enviar o link para aceite.");
        }
        if (request.hasPendingContractChange()) {
            throw new IllegalArgumentException("Existe uma revisão comercial aguardando confirmação. Conclua essa etapa antes do aceite digital final.");
        }
        if (request.getAcceptedAt() != null) {
            throw new IllegalArgumentException("Este dossiê já possui aceite digital confirmado.");
        }
        AcceptanceLinkPolicy.requireValid(request.getAcceptanceLinkAvailableAt(), OffsetDateTime.now());
    }

    private void assertPendingRegistration(InspectionRequest request) {
        if (request.getWebauthnRegistrationChallenge() == null
                || request.getWebauthnRegistrationExpiresAt() == null
                || request.getWebauthnRegistrationExpiresAt().isBefore(OffsetDateTime.now())) {
            throw new IllegalArgumentException("A etapa de criação da credencial expirou. Inicie o aceite novamente.");
        }
    }

    private void assertPendingAssertion(InspectionRequest request) {
        if (request.getWebauthnAssertionChallenge() == null
                || request.getWebauthnAssertionExpiresAt() == null
                || request.getWebauthnAssertionExpiresAt().isBefore(OffsetDateTime.now())) {
            throw new IllegalArgumentException("A confirmação criptográfica expirou. Inicie novamente.");
        }
    }

    private InspectionRequest find(String token) {
        return repository.findByPublicToken(token)
                .orElseThrow(() -> new IllegalArgumentException("Solicitação do Retrato NH não encontrada."));
    }

    private InspectionRequest findForUpdate(String token) {
        InspectionRequest request = repository.findByPublicTokenForUpdate(token)
                .orElseThrow(() -> new IllegalArgumentException("Solicitação do Retrato NH não encontrada."));
        request.getAssets().size();
        return request;
    }

    private OriginInfo resolveOrigin(HttpServletRequest request) {
        String origin = request.getHeader("Origin");
        if (origin == null || origin.isBlank()) {
            String proto = firstHeader(request, "X-Forwarded-Proto");
            if (proto == null) proto = request.getScheme();
            String host = firstHeader(request, "X-Forwarded-Host");
            if (host == null) host = request.getHeader("Host");
            origin = proto + "://" + host;
        }
        try {
            URI uri = URI.create(origin.trim());
            if (uri.getHost() == null || !("https".equalsIgnoreCase(uri.getScheme()) || "http".equalsIgnoreCase(uri.getScheme()))) {
                throw new IllegalArgumentException("Origem inválida para WebAuthn.");
            }
            if ("http".equalsIgnoreCase(uri.getScheme()) && !"localhost".equalsIgnoreCase(uri.getHost()) && !"127.0.0.1".equals(uri.getHost())) {
                throw new IllegalArgumentException("WebAuthn exige HTTPS fora do ambiente local.");
            }
            int port = uri.getPort();
            String normalizedOrigin = uri.getScheme().toLowerCase(Locale.ROOT) + "://" + uri.getHost().toLowerCase(Locale.ROOT)
                    + (port > 0 && !isDefaultPort(uri.getScheme(), port) ? ":" + port : "");
            return new OriginInfo(normalizedOrigin, uri.getHost().toLowerCase(Locale.ROOT));
        } catch (IllegalArgumentException exception) {
            throw exception;
        } catch (Exception exception) {
            throw new IllegalArgumentException("Não foi possível identificar o domínio seguro para o WebAuthn.", exception);
        }
    }

    private boolean isDefaultPort(String scheme, int port) {
        return ("https".equalsIgnoreCase(scheme) && port == 443) || ("http".equalsIgnoreCase(scheme) && port == 80);
    }

    private String resolveClientIp(HttpServletRequest request) {
        String forwarded = firstHeader(request, "X-Forwarded-For");
        String ip = forwarded == null ? request.getRemoteAddr() : forwarded.split(",", 2)[0].trim();
        if (ip == null || ip.isBlank()) return "não informado";
        return ip.length() > 80 ? ip.substring(0, 80) : ip;
    }

    private String firstHeader(HttpServletRequest request, String name) {
        String value = request.getHeader(name);
        if (value == null || value.isBlank()) return null;
        return value.split(",", 2)[0].trim();
    }


    private OffsetDateTime parseDeviceCapturedAt(String value) {
        if (value == null || value.isBlank()) return OffsetDateTime.now();
        try {
            return OffsetDateTime.parse(value);
        } catch (Exception ignored) {
            return OffsetDateTime.now();
        }
    }

    private String safeDeviceJson(DeviceMetadata device) {
        try {
            String json = jsonMapper.writeValueAsString(device);
            return json.length() > 12000 ? json.substring(0, 12000) : json;
        } catch (Exception exception) {
            throw new IllegalArgumentException("Não foi possível registrar os metadados do aparelho.", exception);
        }
    }

    private String evidenceHash(
            InspectionRequest request,
            String selfieHash,
            String dossierHash,
            String deviceJson,
            String ip,
            DeviceMetadata device
    ) {
        String canonical = String.join("\n",
                "NH-WEBAUTHN-EVIDENCE-V1",
                "inspectionId=" + request.getId(),
                "associateName=" + normalizeText(request.getAssociateName()),
                "cpf=" + request.getCpf().replaceAll("\\D", ""),
                "selfieSha256=" + selfieHash,
                "dossierSha256=" + dossierHash,
                "preexistingDamage=" + request.hasPreexistingDamage(),
                "preexistingDamageDetailsSha256=" + sha256Hex(
                        normalizeText(request.getPreexistingDamageDetails()).getBytes(StandardCharsets.UTF_8)
                ),
                "decision=" + request.getStatus().name(),
                "reviewedAt=" + String.valueOf(request.getReviewedAt()),
                "reviewedBy=" + normalizeText(request.getReviewedByName()),
                "whatsappSentTo=" + normalizeText(request.getAcceptanceWhatsappSentTo()),
                "deviceSha256=" + sha256Hex(deviceJson.getBytes(StandardCharsets.UTF_8)),
                "ip=" + normalizeText(ip),
                "latitude=" + String.valueOf(device.latitude()),
                "longitude=" + String.valueOf(device.longitude()),
                "accuracyMeters=" + String.valueOf(device.accuracyMeters())
        );
        return sha256Hex(canonical.getBytes(StandardCharsets.UTF_8));
    }

    private String proofHash(
            InspectionRequest request,
            String challenge,
            String authenticatorData,
            String clientDataJson,
            String signature,
            String credentialId,
            OffsetDateTime acceptedAt
    ) {
        String canonical = String.join("\n",
                "NH-WEBAUTHN-PROOF-V1",
                "inspectionId=" + request.getId(),
                "evidenceHash=" + request.getAcceptanceEvidenceHash(),
                "challenge=" + normalizeBase64Url(challenge),
                "credentialId=" + credentialId,
                "authenticatorData=" + normalizeBase64Url(authenticatorData),
                "clientDataJSON=" + normalizeBase64Url(clientDataJson),
                "signature=" + normalizeBase64Url(signature),
                "acceptedAt=" + acceptedAt
        );
        return sha256Hex(canonical.getBytes(StandardCharsets.UTF_8));
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
        } catch (Exception exception) {
            throw new IllegalArgumentException("Campo WebAuthn em Base64URL inválido.");
        }
    }

    private String normalizeBase64Url(String value) {
        if (value == null) return "";
        return value.trim().replace("=", "");
    }

    private String randomBase64Url(int bytes) {
        byte[] challenge = new byte[bytes];
        secureRandom.nextBytes(challenge);
        return base64Url(challenge);
    }

    private String base64Url(byte[] bytes) {
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    private byte[] sha256Bytes(byte[] bytes) {
        try {
            return MessageDigest.getInstance("SHA-256").digest(bytes);
        } catch (Exception exception) {
            throw new IllegalStateException("SHA-256 indisponível.", exception);
        }
    }

    private String sha256Hex(byte[] bytes) {
        return HexFormat.of().formatHex(sha256Bytes(bytes));
    }

    private byte[] concat(byte[] first, byte[] second) {
        byte[] result = Arrays.copyOf(first, first.length + second.length);
        System.arraycopy(second, 0, result, first.length, second.length);
        return result;
    }

    private String normalizeText(String value) {
        return value == null ? "" : value.trim().replaceAll("\\s+", " ");
    }

    private static final class CborReader {
        private final byte[] data;
        private int position;

        private CborReader(byte[] data) {
            this.data = data == null ? new byte[0] : data;
        }

        private Object read() {
            if (position >= data.length) throw new IllegalArgumentException("CBOR inesperadamente vazio.");
            int initial = readUnsignedByte();
            int major = initial >>> 5;
            int additional = initial & 0x1f;
            if (major == 7) return readSimple(additional);
            long length = readLength(additional);
            return switch (major) {
                case 0 -> length;
                case 1 -> -1L - length;
                case 2 -> readBytes(length);
                case 3 -> new String(readBytes(length), StandardCharsets.UTF_8);
                case 4 -> readArray(length);
                case 5 -> readMap(length);
                case 6 -> read();
                default -> throw new IllegalArgumentException("Tipo CBOR não suportado: " + major);
            };
        }

        private Object readSimple(int additional) {
            return switch (additional) {
                case 20 -> Boolean.FALSE;
                case 21 -> Boolean.TRUE;
                case 22, 23 -> null;
                default -> throw new IllegalArgumentException("Valor simples CBOR não suportado: " + additional);
            };
        }

        private long readLength(int additional) {
            if (additional < 24) return additional;
            return switch (additional) {
                case 24 -> readUnsignedByte();
                case 25 -> ((long) readUnsignedByte() << 8) | readUnsignedByte();
                case 26 -> ((long) readUnsignedByte() << 24)
                        | ((long) readUnsignedByte() << 16)
                        | ((long) readUnsignedByte() << 8)
                        | readUnsignedByte();
                case 27 -> {
                    long value = 0;
                    for (int i = 0; i < 8; i++) value = (value << 8) | readUnsignedByte();
                    yield value;
                }
                default -> throw new IllegalArgumentException("CBOR de tamanho indefinido não é aceito nesta evidência WebAuthn.");
            };
        }

        private byte[] readBytes(long length) {
            if (length < 0 || length > Integer.MAX_VALUE || position + length > data.length) {
                throw new IllegalArgumentException("Comprimento CBOR inválido.");
            }
            int size = (int) length;
            byte[] value = Arrays.copyOfRange(data, position, position + size);
            position += size;
            return value;
        }

        private List<Object> readArray(long length) {
            if (length < 0 || length > 10000) throw new IllegalArgumentException("Array CBOR inválido.");
            List<Object> list = new ArrayList<>((int) length);
            for (long i = 0; i < length; i++) list.add(read());
            return list;
        }

        private Map<Object, Object> readMap(long length) {
            if (length < 0 || length > 10000) throw new IllegalArgumentException("Mapa CBOR inválido.");
            Map<Object, Object> map = new LinkedHashMap<>();
            for (long i = 0; i < length; i++) map.put(read(), read());
            return map;
        }

        private int readUnsignedByte() {
            if (position >= data.length) throw new IllegalArgumentException("Fim inesperado do CBOR.");
            return data[position++] & 0xff;
        }
    }

    private record OriginInfo(String origin, String rpId) {}
    private record AuthenticatorData(int flags, long signCount, boolean userVerified) {}
    private record CosePublicKey(PublicKey publicKey, int algorithm) {}
    private record AttestedCredential(byte[] credentialId, PublicKey publicKey, int algorithm, long signCount) {}
}
