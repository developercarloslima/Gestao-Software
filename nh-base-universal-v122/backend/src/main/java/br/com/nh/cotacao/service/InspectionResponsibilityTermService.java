package br.com.nh.cotacao.service;

import br.com.nh.cotacao.platform.TenantIdentity;

import br.com.nh.cotacao.dto.InspectionDtos.DeviceMetadata;
import br.com.nh.cotacao.dto.InspectionDtos.WebAuthnAssertionFinishRequest;
import br.com.nh.cotacao.dto.InspectionDtos.WebAuthnAssertionOptionsResponse;
import br.com.nh.cotacao.dto.InspectionDtos.WebAuthnRegistrationFinishRequest;
import br.com.nh.cotacao.dto.InspectionDtos.WebAuthnRegistrationOptionsResponse;
import br.com.nh.cotacao.dto.ResponsibilityTermDtos.ResponsibilityTermInfo;
import br.com.nh.cotacao.entity.InspectionAsset;
import br.com.nh.cotacao.entity.InspectionAssetType;
import br.com.nh.cotacao.entity.InspectionRequest;
import br.com.nh.cotacao.entity.InspectionRequestType;
import br.com.nh.cotacao.repository.InspectionRequestRepository;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.util.UriUtils;

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
public class InspectionResponsibilityTermService {
    private static final long CEREMONY_TIMEOUT_MS = 120_000L;
    private static final int CHALLENGE_BYTES = 32;
    private static final String RP_NAME = TenantIdentity.legalName();

    private final JdbcTemplate jdbc;
    private final ResponsibilityTermPdfService pdfService;
    private final InspectionRequestRepository inspectionRepository;
    private final RetratoPdfService retratoPdfService;
    private final InspectionAssetStorageService storageService;
    private final ResponsibilityTermAcceptanceAttemptService attemptService;
    private final ObjectMapper jsonMapper;
    private final SecureRandom secureRandom = new SecureRandom();
    private final String publicWebUrl;

    public InspectionResponsibilityTermService(
            JdbcTemplate jdbc,
            ResponsibilityTermPdfService pdfService,
            InspectionRequestRepository inspectionRepository,
            RetratoPdfService retratoPdfService,
            InspectionAssetStorageService storageService,
            ResponsibilityTermAcceptanceAttemptService attemptService,
            ObjectMapper jsonMapper,
            @Value("${app.public-web-url:https://aforma-demo.vercel.app}") String publicWebUrl
    ) {
        this.jdbc = jdbc;
        this.pdfService = pdfService;
        this.inspectionRepository = inspectionRepository;
        this.retratoPdfService = retratoPdfService;
        this.storageService = storageService;
        this.attemptService = attemptService;
        this.jsonMapper = jsonMapper;
        this.publicWebUrl = trimSlash(publicWebUrl);
    }

    @Transactional
    public ResponsibilityTermInfo prepare(UUID inspectionId, String createdBy, String requestedDamageDetails) {
        InspectionSnapshot inspection = findInspection(inspectionId);

        TermRow existing = findLatestByInspection(inspectionId, true);
        if (existing != null) {
            if (existing.acceptedAt() != null) return info(existing);
            String requested = clean(requestedDamageDetails);
            if (requested != null && !normalizeText(requested).equals(normalizeText(existing.damageDetails()))) {
                jdbc.update("""
                        update inspection_responsibility_terms
                           set damage_details=?,whatsapp_sent_to=null,sent_at=null,
                               webauthn_registration_challenge=null,webauthn_registration_expires_at=null,
                               webauthn_credential_id=null,webauthn_public_key=null,webauthn_algorithm=null,webauthn_sign_count=0,
                               webauthn_assertion_challenge=null,webauthn_assertion_expires_at=null,
                               acceptance_evidence_hash=null,term_pdf_sha256=null,term_content_sha256=null,updated_at=now()
                         where id=?
                        """, requested, existing.id());
                return info(findById(existing.id(), false));
            }
            return info(existing);
        }

        String damageDetails = clean(requestedDamageDetails);
        if (damageDetails == null) damageDetails = clean(inspection.damageDetails());
        if (damageDetails == null) {
            throw new IllegalArgumentException("Informe as peças/avarias pré-existentes para gerar o Termo de Responsabilidade.");
        }

        UUID id = UUID.randomUUID();
        String token = randomBase64Url(32);
        jdbc.update("""
                insert into inspection_responsibility_terms(
                    id,inspection_id,version,public_token,associate_name,cpf,whatsapp,plate,vehicle_model,model_year,
                    contracted_plan,inspection_created_at,damage_details,created_by,created_at,updated_at
                ) values (?,?,?,?,?,?,?,?,?,?,?,?,?,?,now(),now())
                """,
                id, inspection.id(), 1, token, inspection.associateName(), inspection.cpf(), inspection.whatsapp(),
                inspection.plate(), inspection.vehicleModel(), inspection.modelYear(), inspection.contractedPlan(),
                inspection.createdAt(), damageDetails, clean(createdBy)
        );
        return info(findById(id, false));
    }

    @Transactional(readOnly = true)
    public ResponsibilityTermInfo statusByInspection(UUID inspectionId) {
        TermRow row = findLatestByInspection(inspectionId, false);
        return row == null ? null : info(row);
    }

    @Transactional
    public ResponsibilityTermInfo markSent(UUID inspectionId) {
        TermRow row = requireLatestByInspection(inspectionId, true);
        String phone = normalizeBrazilPhone(row.whatsapp());
        jdbc.update("""
                update inspection_responsibility_terms
                   set whatsapp_sent_to=?,sent_at=coalesce(sent_at,now()),updated_at=now()
                 where id=?
                """, phone, row.id());
        return info(findById(row.id(), false));
    }

    @Transactional(readOnly = true)
    public ResponsibilityTermInfo publicStatus(String token) {
        return info(findByToken(token, false));
    }

    @Transactional(readOnly = true)
    public byte[] publicPdf(String token) {
        TermRow row = findByToken(token, false);
        return pdfService.generate(documentData(row, row.acceptedAt() != null));
    }

    @Transactional
    public WebAuthnRegistrationOptionsResponse beginRegistration(
            String token,
            DeviceMetadata device,
            HttpServletRequest request
    ) {
        TermRow row = findByToken(token, true);
        assertPending(row);
        if (device == null) throw new IllegalArgumentException("Não foi possível registrar os metadados do aparelho.");
        if (device.deviceId() == null || device.deviceId().isBlank()) {
            throw new IllegalArgumentException("Não foi possível identificar o aparelho usado no aceite.");
        }
        if (device.latitude() == null || device.longitude() == null) {
            throw new IllegalArgumentException("Ative a localização do aparelho para registrar o GPS junto ao aceite digital.");
        }

        OriginInfo origin = resolveOrigin(request);
        byte[] termPdf = pdfService.generate(documentData(row, false));
        String termHash = sha256Hex(termPdf);
        String contentHash = contentHash(row);
        String deviceJson = safeDeviceJson(device);
        String ip = resolveClientIp(request);
        String evidenceHash = evidenceHash(row, termHash, deviceJson, ip, device);
        String challenge = randomBase64Url(CHALLENGE_BYTES);

        jdbc.update("""
                update inspection_responsibility_terms
                   set webauthn_registration_challenge=?,webauthn_registration_expires_at=?,webauthn_origin=?,webauthn_rp_id=?,
                       acceptance_evidence_hash=?,term_pdf_sha256=?,term_content_sha256=?,acceptance_device_metadata=?,acceptance_device_id=?,acceptance_ip=?,
                       acceptance_latitude=?,acceptance_longitude=?,acceptance_accuracy_meters=?,acceptance_location_captured_at=?,
                       webauthn_assertion_challenge=null,webauthn_assertion_expires_at=null,updated_at=now()
                 where id=?
                """,
                challenge, OffsetDateTime.now().plusMinutes(3), origin.origin(), origin.rpId(), evidenceHash, termHash, contentHash,
                deviceJson, device.deviceId(), ip, device.latitude(), device.longitude(), device.accuracyMeters(),
                parseCapturedAt(device.capturedAt()), row.id()
        );

        return new WebAuthnRegistrationOptionsResponse(
                challenge,
                origin.rpId(),
                RP_NAME,
                base64Url(sha256Bytes(("responsibility-term-user:" + row.id()).getBytes(StandardCharsets.UTF_8))),
                "associado-termo-" + row.id(),
                row.associateName(),
                CEREMONY_TIMEOUT_MS
        );
    }

    @Transactional
    public WebAuthnAssertionOptionsResponse finishRegistration(
            String token,
            WebAuthnRegistrationFinishRequest input
    ) {
        TermRow row = findByToken(token, true);
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
                update inspection_responsibility_terms
                   set webauthn_credential_id=?,webauthn_public_key=?,webauthn_algorithm=?,webauthn_sign_count=?,
                       webauthn_registration_challenge=null,webauthn_registration_expires_at=null,updated_at=now()
                 where id=?
                """,
                rawCredentialId, credential.publicKey().getEncoded(), credential.algorithm(), credential.signCount(), row.id()
        );
        return createAssertionOptions(findById(row.id(), true));
    }

    @Transactional
    public WebAuthnAssertionOptionsResponse assertionOptions(String token) {
        TermRow row = findByToken(token, true);
        assertPending(row);
        if (row.credentialId() == null || row.publicKey() == null) {
            throw new IllegalArgumentException("Primeiro registre a credencial WebAuthn deste aceite.");
        }
        return createAssertionOptions(row);
    }

    @Transactional
    public ResponsibilityTermInfo finishAssertion(
            String token,
            WebAuthnAssertionFinishRequest input
    ) {
        TermRow row = findByToken(token, true);
        assertPending(row);
        assertPendingAssertion(row);
        if (input == null) throw new IllegalArgumentException("Dados da confirmação WebAuthn não informados.");
        if (!"public-key".equals(input.type())) throw new IllegalArgumentException("Tipo de credencial WebAuthn inválido.");

        ResponsibilityTermAcceptanceAttemptService.Attempt attempt = attemptService.record(row.id(), input);
        try {
            ResponsibilityTermInfo result = completeAssertion(row, input, attempt.receivedAt());
            attemptService.markVerified(attempt.id());
            return result;
        } catch (RuntimeException error) {
            attemptService.markRejected(attempt.id(), error.getMessage());
            throw error;
        }
    }

    private ResponsibilityTermInfo completeAssertion(
            TermRow row,
            WebAuthnAssertionFinishRequest input,
            OffsetDateTime receivedAt
    ) {
        if (row.acceptedAt() != null) throw new IllegalArgumentException("Este termo já possui aceite confirmado.");
        AcceptanceLinkPolicy.requireValid(row.linkAvailableAt(), receivedAt);
        assertPendingAssertionAt(row, receivedAt);

        String currentContentHash = contentHash(row);
        if (row.termContentSha256() == null || !MessageDigest.isEqual(
                row.termContentSha256().getBytes(StandardCharsets.UTF_8), currentContentHash.getBytes(StandardCharsets.UTF_8))) {
            resetPendingWebAuthn(row.id());
            throw new IllegalArgumentException("O Termo de Responsabilidade foi alterado depois do início do aceite. Revise o documento atualizado e confirme novamente.");
        }

        String credentialId = normalizeBase64Url(input.rawId());
        if (!credentialId.equals(row.credentialId())) {
            throw new IllegalArgumentException("A credencial usada não pertence a este aceite.");
        }

        byte[] clientData = decodeBase64Url(input.clientDataJSON());
        validateClientData(clientData, "webauthn.get", row.assertionChallenge(), row.origin());
        byte[] authenticatorData = decodeBase64Url(input.authenticatorData());
        AuthenticatorData parsed = validateAuthenticatorData(authenticatorData, row.rpId(), true);
        verifyAssertionSignature(
                decodeStoredPublicKey(row.publicKey(), row.algorithm()),
                row.algorithm(),
                authenticatorData,
                clientData,
                decodeBase64Url(input.signature())
        );

        if (parsed.signCount() != 0 && row.signCount() != 0 && parsed.signCount() <= row.signCount()) {
            throw new IllegalArgumentException("O contador da credencial WebAuthn não avançou. Por segurança, o aceite foi recusado.");
        }

        OffsetDateTime acceptedAt = OffsetDateTime.now();
        String proofHash = proofHash(
                row,
                row.assertionChallenge(),
                input.authenticatorData(),
                input.clientDataJSON(),
                input.signature(),
                credentialId,
                acceptedAt
        );

        int updated = jdbc.update("""
                update inspection_responsibility_terms
                   set webauthn_sign_count=?,acceptance_assertion_signature=?,acceptance_authenticator_data=?,acceptance_client_data_json=?,
                       acceptance_proof_hash=?,acceptance_user_verified=?,accepted_at=?,
                       webauthn_assertion_challenge=null,webauthn_assertion_expires_at=null,updated_at=now()
                 where id=? and accepted_at is null
                """,
                parsed.signCount(), normalizeBase64Url(input.signature()), normalizeBase64Url(input.authenticatorData()),
                normalizeBase64Url(input.clientDataJSON()), proofHash, parsed.userVerified(), acceptedAt, row.id()
        );
        if (updated != 1) throw new IllegalStateException("Não foi possível registrar o aceite digital deste termo.");
        TermRow accepted = findById(row.id(), false);
        refreshFinalDossierWithAcceptedTerm(accepted.inspectionId());
        return info(accepted);
    }

    private WebAuthnAssertionOptionsResponse createAssertionOptions(TermRow row) {
        if (row.evidenceHash() == null || row.evidenceHash().isBlank()) {
            throw new IllegalArgumentException("A evidência vinculada ao aceite não foi preparada. Reinicie a confirmação.");
        }
        byte[] random = new byte[CHALLENGE_BYTES];
        secureRandom.nextBytes(random);
        String challenge = base64Url(sha256Bytes(concat(random, row.evidenceHash().getBytes(StandardCharsets.UTF_8))));
        jdbc.update("""
                update inspection_responsibility_terms
                   set webauthn_assertion_challenge=?,webauthn_assertion_expires_at=?,updated_at=now()
                 where id=?
                """, challenge, OffsetDateTime.now().plusMinutes(3), row.id());
        return new WebAuthnAssertionOptionsResponse(
                challenge,
                row.rpId(),
                row.credentialId(),
                CEREMONY_TIMEOUT_MS,
                row.evidenceHash(),
                row.termPdfSha256(),
                null
        );
    }

    private ResponsibilityTermInfo info(TermRow row) {
        String path = "/aceite-termo/?token=" + row.publicToken();
        String url = publicWebUrl + path;
        String phone = normalizeBrazilPhone(row.whatsapp());
        boolean expired = row.acceptedAt() == null && !AcceptanceLinkPolicy.isValid(row.linkAvailableAt(), OffsetDateTime.now());
        String whatsappUrl = null;
        if (!expired && row.acceptedAt() == null && phone != null) {
            String firstName = firstName(row.associateName());
            String message = "Olá, " + firstName + "! A " + TenantIdentity.legalName() + " disponibilizou um Termo de Responsabilidade referente às avarias pré-existentes do veículo "
                    + vehicleLabel(row) + ". Revise somente este termo e confirme o aceite digital pelo link: " + url;
            whatsappUrl = "https://wa.me/" + phone + "?text=" + UriUtils.encode(message, StandardCharsets.UTF_8);
        }
        String status = row.acceptedAt() != null ? "SIGNED" : expired ? "EXPIRED" : "PENDING";
        String statusLabel = row.acceptedAt() != null ? "Termo assinado" : expired ? "Prazo de aceite expirado (7 dias)" : "Termo com aceite pendente";
        return new ResponsibilityTermInfo(
                row.id(), row.inspectionId(), row.version(), row.associateName(), row.plate(), row.vehicleModel(), row.damageDetails(),
                !expired, row.acceptedAt() != null, row.acceptedAt(), row.publicToken(), path, url, whatsappUrl,
                row.termPdfSha256(), row.evidenceHash(), row.proofHash(), row.userVerified(), row.whatsappSentTo(), row.sentAt(),
                status, statusLabel, row.acceptanceIp(), row.acceptanceLatitude(), row.acceptanceLongitude(),
                row.acceptanceAccuracyMeters(), row.acceptanceLocationCapturedAt(), row.acceptanceDeviceId(),
                deviceSummary(row.acceptanceDeviceMetadata()), row.acceptanceSource(), row.originalDossierSha256()
        );
    }

    private ResponsibilityTermPdfService.TermDocumentData documentData(TermRow row, boolean includeAcceptance) {
        return new ResponsibilityTermPdfService.TermDocumentData(
                row.associateName(), row.cpf(), row.plate(), row.vehicleModel(), row.modelYear(), row.contractedPlan(),
                row.inspectionCreatedAt(), row.damageDetails(),
                includeAcceptance ? row.whatsappSentTo() : null,
                includeAcceptance ? row.sentAt() : null,
                includeAcceptance ? row.acceptedAt() : null,
                includeAcceptance ? row.termPdfSha256() : null,
                includeAcceptance ? row.evidenceHash() : null,
                includeAcceptance ? row.proofHash() : null,
                includeAcceptance && row.userVerified(),
                includeAcceptance ? row.acceptanceIp() : null,
                includeAcceptance ? row.acceptanceLatitude() : null,
                includeAcceptance ? row.acceptanceLongitude() : null,
                includeAcceptance ? row.acceptanceAccuracyMeters() : null,
                includeAcceptance ? row.acceptanceLocationCapturedAt() : null,
                includeAcceptance ? row.acceptanceDeviceId() : null,
                includeAcceptance ? deviceSummary(row.acceptanceDeviceMetadata()) : null,
                includeAcceptance ? row.acceptanceSource() : null,
                includeAcceptance ? row.originalDossierSha256() : null
        );
    }

    private void refreshFinalDossierWithAcceptedTerm(UUID inspectionId) {
        InspectionRequest inspection = inspectionRepository.findById(inspectionId)
                .orElseThrow(() -> new IllegalArgumentException("Vistoria vinculada ao termo não encontrada."));
        byte[] finalReport = retratoPdfService.generate(inspection);
        int reportOrder = inspection.getAssets().stream()
                .filter(asset -> asset.getAssetType() == InspectionAssetType.REPORT)
                .mapToInt(InspectionAsset::getSortOrder)
                .findFirst()
                .orElseGet(() -> inspection.getRequestType() == InspectionRequestType.NEW_INSPECTION
                        ? inspection.getVehicleType().requiredPhotoCount() + 6
                        : 2);
        storageService.replaceGeneratedReport(
                inspection,
                "Dossiê final com Termo de Responsabilidade aceito",
                "dossie-final-vistoria-" + inspection.getId() + "-termo-aceito.pdf",
                reportOrder,
                finalReport
        );
        inspectionRepository.flush();
    }

    private String deviceSummary(String metadataJson) {
        if (metadataJson == null || metadataJson.isBlank()) return null;
        try {
            JsonNode node = jsonMapper.readTree(metadataJson);
            List<String> parts = new ArrayList<>();
            addDevicePart(parts, node, "platform", "Plataforma");
            addDevicePart(parts, node, "vendor", "Fabricante/Navegador");
            addDevicePart(parts, node, "language", "Idioma");
            addDevicePart(parts, node, "timezone", "Fuso horário");
            JsonNode width = node.get("screenWidth");
            JsonNode height = node.get("screenHeight");
            if (width != null && height != null && width.isNumber() && height.isNumber()) {
                parts.add("Tela " + width.asInt() + "x" + height.asInt());
            }
            JsonNode ua = node.get("userAgent");
            if (ua != null && ua.isTextual() && !ua.asText().isBlank()) {
                String text = ua.asText().trim();
                parts.add("User-Agent " + (text.length() > 220 ? text.substring(0, 220) + "..." : text));
            }
            return parts.isEmpty() ? "Metadados do dispositivo registrados" : String.join(" | ", parts);
        } catch (Exception ignored) {
            return "Metadados do dispositivo registrados";
        }
    }

    private void addDevicePart(List<String> parts, JsonNode node, String field, String label) {
        JsonNode value = node.get(field);
        if (value != null && value.isValueNode()) {
            String text = value.asText();
            if (text != null && !text.isBlank()) parts.add(label + " " + text.trim());
        }
    }

    private void assertPending(TermRow row) {
        if (row.acceptedAt() != null) throw new IllegalArgumentException("Este Termo de Responsabilidade já está assinado e não pode ser enviado ou assinado novamente.");
        AcceptanceLinkPolicy.requireValid(row.linkAvailableAt(), OffsetDateTime.now());
    }

    private void assertPendingRegistration(TermRow row) {
        if (row.registrationChallenge() == null || row.registrationExpiresAt() == null || row.registrationExpiresAt().isBefore(OffsetDateTime.now())) {
            throw new IllegalArgumentException("O desafio de registro WebAuthn expirou. Inicie novamente o aceite.");
        }
    }

    private void assertPendingAssertion(TermRow row) {
        if (row.assertionChallenge() == null || row.assertionExpiresAt() == null || row.assertionExpiresAt().isBefore(OffsetDateTime.now())) {
            throw new IllegalArgumentException("O desafio de confirmação WebAuthn expirou. Inicie novamente o aceite.");
        }
    }

    private void assertPendingAssertionAt(TermRow row, OffsetDateTime receivedAt) {
        if (row.assertionChallenge() == null || row.assertionExpiresAt() == null) {
            throw new IllegalArgumentException("O desafio de confirmação WebAuthn não está disponível. Inicie novamente o aceite.");
        }
        OffsetDateTime effectiveReceivedAt = receivedAt == null ? OffsetDateTime.now() : receivedAt;
        if (effectiveReceivedAt.isAfter(row.assertionExpiresAt())) {
            throw new IllegalArgumentException("O desafio de confirmação WebAuthn já estava expirado quando a resposta foi recebida.");
        }
    }

    private InspectionSnapshot findInspection(UUID id) {
        InspectionSnapshot row = jdbc.query("""
                select ir.id,ir.associate_name,ir.cpf,ir.whatsapp,ir.plate,ir.vehicle_model,ir.model_year,
                       coalesce(ir.contracted_plan,q.selected_plan_name) as contracted_plan,ir.created_at,
                       ir.preexisting_damage,ir.preexisting_damage_details
                  from inspection_requests ir
                  left join quotations q on q.id=ir.quotation_id
                 where ir.id=?
                """, rs -> rs.next() ? new InspectionSnapshot(
                uuid(rs, "id"), rs.getString("associate_name"), rs.getString("cpf"), rs.getString("whatsapp"),
                rs.getString("plate"), rs.getString("vehicle_model"), (Integer) rs.getObject("model_year"),
                rs.getString("contracted_plan"), offset(rs, "created_at"), rs.getBoolean("preexisting_damage"),
                rs.getString("preexisting_damage_details")
        ) : null, id);
        if (row == null) throw new IllegalArgumentException("Vistoria não encontrada.");
        return row;
    }

    private TermRow requireLatestByInspection(UUID inspectionId, boolean lock) {
        TermRow row = findLatestByInspection(inspectionId, lock);
        if (row == null) throw new IllegalArgumentException("O Termo de Responsabilidade ainda não foi preparado para esta vistoria.");
        return row;
    }

    private TermRow findLatestByInspection(UUID inspectionId, boolean lock) {
        String sql = baseSelect() + " where inspection_id=? order by version desc limit 1" + (lock ? " for update" : "");
        return jdbc.query(sql, rs -> rs.next() ? map(rs) : null, inspectionId);
    }

    private TermRow findById(UUID id, boolean lock) {
        TermRow row = jdbc.query(baseSelect() + " where id=?" + (lock ? " for update" : ""), rs -> rs.next() ? map(rs) : null, id);
        if (row == null) throw new IllegalArgumentException("Termo de Responsabilidade não encontrado.");
        return row;
    }

    private TermRow findByToken(String token, boolean lock) {
        if (token == null || token.isBlank()) throw new IllegalArgumentException("Token do termo não informado.");
        TermRow row = jdbc.query(baseSelect() + " where public_token=?" + (lock ? " for update" : ""), rs -> rs.next() ? map(rs) : null, token.trim());
        if (row == null) throw new IllegalArgumentException("Link do Termo de Responsabilidade inválido.");
        return row;
    }

    private String baseSelect() {
        return """
                select id,inspection_id,version,public_token,associate_name,cpf,whatsapp,plate,vehicle_model,model_year,contracted_plan,
                       inspection_created_at,damage_details,created_at,coalesce(link_available_at,created_at) as link_available_at,whatsapp_sent_to,sent_at,accepted_at,
                       webauthn_registration_challenge,webauthn_registration_expires_at,webauthn_origin,webauthn_rp_id,webauthn_credential_id,
                       webauthn_public_key,webauthn_algorithm,webauthn_sign_count,webauthn_assertion_challenge,webauthn_assertion_expires_at,
                       acceptance_evidence_hash,term_pdf_sha256,term_content_sha256,acceptance_proof_hash,acceptance_user_verified,
                       acceptance_device_metadata,acceptance_device_id,acceptance_ip,acceptance_latitude,acceptance_longitude,
                       acceptance_accuracy_meters,acceptance_location_captured_at,acceptance_source,original_dossier_sha256
                  from inspection_responsibility_terms
                """;
    }

    private TermRow map(ResultSet rs) throws SQLException {
        return new TermRow(
                uuid(rs, "id"), uuid(rs, "inspection_id"), rs.getInt("version"), rs.getString("public_token"),
                rs.getString("associate_name"), rs.getString("cpf"), rs.getString("whatsapp"), rs.getString("plate"),
                rs.getString("vehicle_model"), (Integer) rs.getObject("model_year"), rs.getString("contracted_plan"),
                offset(rs, "inspection_created_at"), rs.getString("damage_details"), offset(rs, "link_available_at"), rs.getString("whatsapp_sent_to"),
                offset(rs, "sent_at"), offset(rs, "accepted_at"), rs.getString("webauthn_registration_challenge"),
                offset(rs, "webauthn_registration_expires_at"), rs.getString("webauthn_origin"), rs.getString("webauthn_rp_id"),
                rs.getString("webauthn_credential_id"), rs.getBytes("webauthn_public_key"), (Integer) rs.getObject("webauthn_algorithm"),
                rs.getLong("webauthn_sign_count"), rs.getString("webauthn_assertion_challenge"), offset(rs, "webauthn_assertion_expires_at"),
                rs.getString("acceptance_evidence_hash"), rs.getString("term_pdf_sha256"), rs.getString("term_content_sha256"), rs.getString("acceptance_proof_hash"),
                rs.getBoolean("acceptance_user_verified"), rs.getString("acceptance_device_metadata"), rs.getString("acceptance_device_id"),
                rs.getString("acceptance_ip"), (Double) rs.getObject("acceptance_latitude"), (Double) rs.getObject("acceptance_longitude"),
                (Double) rs.getObject("acceptance_accuracy_meters"), offset(rs, "acceptance_location_captured_at"),
                rs.getString("acceptance_source"), rs.getString("original_dossier_sha256")
        );
    }

    private void resetPendingWebAuthn(UUID id) {
        jdbc.update("""
                update inspection_responsibility_terms
                   set webauthn_registration_challenge=null,webauthn_registration_expires_at=null,
                       webauthn_credential_id=null,webauthn_public_key=null,webauthn_algorithm=null,webauthn_sign_count=0,
                       webauthn_assertion_challenge=null,webauthn_assertion_expires_at=null,
                       acceptance_evidence_hash=null,term_pdf_sha256=null,term_content_sha256=null,updated_at=now()
                 where id=?
                """, id);
    }

    private AttestedCredential parseAttestation(byte[] attestationObject, String rpId) {
        try {
            Object decoded = new CborReader(attestationObject).read();
            if (!(decoded instanceof Map<?, ?> root)) throw new IllegalArgumentException("Objeto de atestação WebAuthn inválido.");
            Object authDataValue = root.get("authData");
            if (!(authDataValue instanceof byte[] authData)) throw new IllegalArgumentException("Resposta WebAuthn sem authenticatorData.");
            AuthenticatorData parsed = validateAuthenticatorData(authData, rpId, true);
            if (authData.length < 55 || (parsed.flags() & 0x40) == 0) throw new IllegalArgumentException("A resposta WebAuthn não contém a chave pública da credencial.");
            int credentialLength = ((authData[53] & 0xff) << 8) | (authData[54] & 0xff);
            int start = 55, end = start + credentialLength;
            if (credentialLength <= 0 || end >= authData.length) throw new IllegalArgumentException("Identificador da credencial WebAuthn inválido.");
            byte[] credentialId = Arrays.copyOfRange(authData, start, end);
            Object coseDecoded = new CborReader(Arrays.copyOfRange(authData, end, authData.length)).read();
            if (!(coseDecoded instanceof Map<?, ?> coseKey)) throw new IllegalArgumentException("Chave pública COSE inválida.");
            CosePublicKey publicKey = parseCosePublicKey(coseKey);
            return new AttestedCredential(credentialId, publicKey.publicKey(), publicKey.algorithm(), parsed.signCount());
        } catch (IllegalArgumentException exception) {
            throw exception;
        } catch (Exception exception) {
            throw new IllegalArgumentException("Não foi possível validar a credencial WebAuthn criada pelo aparelho.", exception);
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
            ECPublicKey publicKey = (ECPublicKey) KeyFactory.getInstance("EC").generatePublic(spec);
            return new CosePublicKey(publicKey, alg);
        }
        if (kty == 3 && alg == -257) {
            PublicKey publicKey = KeyFactory.getInstance("RSA").generatePublic(
                    new RSAPublicKeySpec(new BigInteger(1, binaryField(key, -1L)), new BigInteger(1, binaryField(key, -2L)))
            );
            return new CosePublicKey(publicKey, alg);
        }
        throw new IllegalArgumentException("Algoritmo WebAuthn não suportado. Use ES256 ou RS256.");
    }

    private AuthenticatorData validateAuthenticatorData(byte[] data, String rpId, boolean requireUv) {
        if (data == null || data.length < 37) throw new IllegalArgumentException("AuthenticatorData WebAuthn inválido.");
        if (!MessageDigest.isEqual(sha256Bytes(rpId.getBytes(StandardCharsets.UTF_8)), Arrays.copyOfRange(data, 0, 32))) {
            throw new IllegalArgumentException("A credencial WebAuthn foi emitida para outro domínio.");
        }
        int flags = data[32] & 0xff;
        boolean present = (flags & 1) != 0, verified = (flags & 4) != 0;
        if (!present) throw new IllegalArgumentException("A presença do usuário não foi confirmada pelo autenticador.");
        if (requireUv && !verified) throw new IllegalArgumentException("O autenticador WebAuthn não confirmou a verificação do usuário.");
        long signCount = Integer.toUnsignedLong(ByteBuffer.wrap(data, 33, 4).getInt());
        return new AuthenticatorData(flags, signCount, verified);
    }

    private void validateClientData(byte[] bytes, String type, String challenge, String origin) {
        try {
            JsonNode node = jsonMapper.readTree(bytes);
            if (!type.equals(node.path("type").asText())) throw new IllegalArgumentException("Cerimônia WebAuthn inesperada.");
            if (!normalizeBase64Url(challenge).equals(normalizeBase64Url(node.path("challenge").asText()))) {
                throw new IllegalArgumentException("O desafio WebAuthn expirou ou não pertence a este aceite.");
            }
            if (!origin.equals(node.path("origin").asText())) throw new IllegalArgumentException("A origem da confirmação WebAuthn não corresponde à página do aceite.");
        } catch (IllegalArgumentException exception) {
            throw exception;
        } catch (Exception exception) {
            throw new IllegalArgumentException("ClientDataJSON WebAuthn inválido.", exception);
        }
    }

    private void verifyAssertionSignature(PublicKey key, Integer algorithm, byte[] auth, byte[] client, byte[] signature) {
        try {
            String algorithmName = switch (algorithm == null ? 0 : algorithm) {
                case -7 -> "SHA256withECDSA";
                case -257 -> "SHA256withRSA";
                default -> throw new IllegalArgumentException("Algoritmo da credencial WebAuthn não suportado.");
            };
            Signature verifier = Signature.getInstance(algorithmName);
            verifier.initVerify(key);
            verifier.update(auth);
            verifier.update(sha256Bytes(client));
            if (!verifier.verify(signature)) throw new IllegalArgumentException("A assinatura criptográfica WebAuthn não é válida.");
        } catch (IllegalArgumentException exception) {
            throw exception;
        } catch (Exception exception) {
            throw new IllegalArgumentException("Não foi possível verificar a assinatura criptográfica WebAuthn.", exception);
        }
    }

    private PublicKey decodeStoredPublicKey(byte[] encoded, Integer algorithm) {
        try {
            return KeyFactory.getInstance(algorithm != null && algorithm == -257 ? "RSA" : "EC")
                    .generatePublic(new X509EncodedKeySpec(encoded));
        } catch (Exception exception) {
            throw new IllegalArgumentException("A chave pública WebAuthn armazenada não pôde ser validada.", exception);
        }
    }

    private OriginInfo resolveOrigin(HttpServletRequest request) {
        try {
            String forwardedProto = firstHeader(request, "X-Forwarded-Proto");
            String forwardedHost = firstHeader(request, "X-Forwarded-Host");
            String scheme = forwardedProto != null ? forwardedProto : request.getScheme();
            String hostPort = forwardedHost != null ? forwardedHost : request.getHeader("Host");
            if (hostPort == null || hostPort.isBlank()) {
                hostPort = request.getServerName() + (isDefaultPort(scheme, request.getServerPort()) ? "" : ":" + request.getServerPort());
            }
            URI uri = URI.create(scheme + "://" + hostPort);
            String host = uri.getHost();
            if (host == null || host.isBlank()) throw new IllegalArgumentException("Domínio do aceite inválido.");
            String origin = scheme + "://" + host + (uri.getPort() > 0 && !isDefaultPort(scheme, uri.getPort()) ? ":" + uri.getPort() : "");
            return new OriginInfo(origin, host);
        } catch (Exception exception) {
            throw new IllegalArgumentException("Não foi possível identificar o domínio seguro do aceite digital.", exception);
        }
    }

    private boolean isDefaultPort(String scheme, int port) {
        return port <= 0 || ("https".equalsIgnoreCase(scheme) && port == 443) || ("http".equalsIgnoreCase(scheme) && port == 80);
    }

    private String resolveClientIp(HttpServletRequest request) {
        String forwarded = firstHeader(request, "X-Forwarded-For");
        if (forwarded != null) return forwarded.split(",")[0].trim();
        String real = firstHeader(request, "X-Real-IP");
        return real != null ? real : request.getRemoteAddr();
    }

    private String firstHeader(HttpServletRequest request, String name) {
        String value = request.getHeader(name);
        return value == null || value.isBlank() ? null : value.trim();
    }

    private String safeDeviceJson(DeviceMetadata device) {
        try {
            String json = jsonMapper.writeValueAsString(device);
            return json.length() > 12000 ? json.substring(0, 12000) : json;
        } catch (Exception exception) {
            throw new IllegalArgumentException("Metadados do aparelho inválidos.", exception);
        }
    }

    private String contentHash(TermRow row) {
        String canonical = String.join("\n",
                "NH-RESPONSIBILITY-TERM-CONTENT-V1",
                "termId=" + row.id(),
                "inspectionId=" + row.inspectionId(),
                "version=" + row.version(),
                "associateName=" + normalizeText(row.associateName()),
                "cpf=" + normalizeText(row.cpf()).replaceAll("\\D", ""),
                "plate=" + normalizeText(row.plate()).toUpperCase(java.util.Locale.ROOT),
                "vehicleModel=" + normalizeText(row.vehicleModel()),
                "modelYear=" + String.valueOf(row.modelYear()),
                "contractedPlan=" + normalizeText(row.contractedPlan()),
                "inspectionCreatedAt=" + String.valueOf(row.inspectionCreatedAt()),
                "damageDetails=" + normalizeText(row.damageDetails())
        );
        return sha256Hex(canonical.getBytes(StandardCharsets.UTF_8));
    }

    private String evidenceHash(TermRow row, String termHash, String deviceJson, String ip, DeviceMetadata device) {
        String canonical = String.join("\n",
                "NH-RESPONSIBILITY-TERM-EVIDENCE-V1",
                "termId=" + row.id(),
                "inspectionId=" + row.inspectionId(),
                "version=" + row.version(),
                "associateName=" + normalizeText(row.associateName()),
                "cpf=" + normalizeText(row.cpf()).replaceAll("\\D", ""),
                "damageDetailsSha256=" + sha256Hex(normalizeText(row.damageDetails()).getBytes(StandardCharsets.UTF_8)),
                "termPdfSha256=" + termHash,
                "deviceSha256=" + sha256Hex(deviceJson.getBytes(StandardCharsets.UTF_8)),
                "ip=" + normalizeText(ip),
                "latitude=" + String.valueOf(device.latitude()),
                "longitude=" + String.valueOf(device.longitude()),
                "accuracyMeters=" + String.valueOf(device.accuracyMeters())
        );
        return sha256Hex(canonical.getBytes(StandardCharsets.UTF_8));
    }

    private String proofHash(TermRow row, String challenge, String authenticatorData, String clientDataJson,
                             String signature, String credentialId, OffsetDateTime acceptedAt) {
        String canonical = String.join("\n",
                "NH-RESPONSIBILITY-TERM-WEBAUTHN-PROOF-V1",
                "termId=" + row.id(),
                "inspectionId=" + row.inspectionId(),
                "evidenceHash=" + row.evidenceHash(),
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
        return value == null ? "" : value.trim().replace("=", "");
    }

    private String randomBase64Url(int bytes) {
        byte[] value = new byte[bytes];
        secureRandom.nextBytes(value);
        return base64Url(value);
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
        return java.util.HexFormat.of().formatHex(sha256Bytes(bytes));
    }

    private byte[] concat(byte[] first, byte[] second) {
        byte[] result = Arrays.copyOf(first, first.length + second.length);
        System.arraycopy(second, 0, result, first.length, second.length);
        return result;
    }

    private String normalizeText(String value) {
        return value == null ? "" : value.trim().replaceAll("\\s+", " ");
    }

    private String clean(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }

    private String normalizeBrazilPhone(String value) {
        if (value == null || value.isBlank()) return null;
        String digits = value.replaceAll("\\D", "");
        if (digits.length() == 10 || digits.length() == 11) digits = "55" + digits;
        return digits.matches("^[1-9][0-9]{11,14}$") ? digits : null;
    }

    private String vehicleLabel(TermRow row) {
        String plate = row.plate() == null || row.plate().isBlank() ? "sem placa" : row.plate();
        return row.vehicleModel() == null || row.vehicleModel().isBlank() ? plate : plate + " (" + row.vehicleModel() + ")";
    }

    private String firstName(String name) {
        if (name == null || name.isBlank()) return "associado(a)";
        return name.trim().split("\\s+")[0];
    }

    private String trimSlash(String value) {
        if (value == null || value.isBlank()) return "";
        return value.trim().replaceAll("/+$", "");
    }

    private OffsetDateTime parseCapturedAt(String value) {
        if (value == null || value.isBlank()) return OffsetDateTime.now();
        try {
            return OffsetDateTime.parse(value);
        } catch (Exception ignored) {
            return OffsetDateTime.now();
        }
    }

    private static UUID uuid(ResultSet rs, String column) throws SQLException {
        Object value = rs.getObject(column);
        return value instanceof UUID uuid ? uuid : UUID.fromString(String.valueOf(value));
    }

    private static OffsetDateTime offset(ResultSet rs, String column) throws SQLException {
        Object value = rs.getObject(column);
        if (value == null) return null;
        if (value instanceof OffsetDateTime offset) return offset;
        if (value instanceof java.sql.Timestamp timestamp) return timestamp.toInstant().atOffset(java.time.ZoneOffset.UTC);
        return OffsetDateTime.parse(String.valueOf(value));
    }

    private static final class CborReader {
        private final byte[] data;
        private int position;

        private CborReader(byte[] data) { this.data = data == null ? new byte[0] : data; }

        private Object read() {
            if (position >= data.length) throw new IllegalArgumentException("CBOR inesperadamente vazio.");
            int initial = readUnsignedByte(), major = initial >>> 5, additional = initial & 0x1f;
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

        private Object readSimple(int value) {
            return switch (value) {
                case 20 -> Boolean.FALSE;
                case 21 -> Boolean.TRUE;
                case 22, 23 -> null;
                default -> throw new IllegalArgumentException("Valor simples CBOR não suportado: " + value);
            };
        }

        private long readLength(int value) {
            if (value < 24) return value;
            return switch (value) {
                case 24 -> readUnsignedByte();
                case 25 -> ((long) readUnsignedByte() << 8) | readUnsignedByte();
                case 26 -> ((long) readUnsignedByte() << 24) | ((long) readUnsignedByte() << 16) | ((long) readUnsignedByte() << 8) | readUnsignedByte();
                case 27 -> {
                    long result = 0;
                    for (int i = 0; i < 8; i++) result = (result << 8) | readUnsignedByte();
                    yield result;
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
            List<Object> result = new ArrayList<>((int) length);
            for (long i = 0; i < length; i++) result.add(read());
            return result;
        }

        private Map<Object, Object> readMap(long length) {
            if (length < 0 || length > 10000) throw new IllegalArgumentException("Mapa CBOR inválido.");
            Map<Object, Object> result = new LinkedHashMap<>();
            for (long i = 0; i < length; i++) result.put(read(), read());
            return result;
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
    private record InspectionSnapshot(UUID id, String associateName, String cpf, String whatsapp, String plate,
                                      String vehicleModel, Integer modelYear, String contractedPlan, OffsetDateTime createdAt,
                                      boolean preexistingDamage, String damageDetails) {}
    private record TermRow(UUID id, UUID inspectionId, int version, String publicToken, String associateName, String cpf,
                           String whatsapp, String plate, String vehicleModel, Integer modelYear, String contractedPlan,
                           OffsetDateTime inspectionCreatedAt, String damageDetails, OffsetDateTime linkAvailableAt, String whatsappSentTo, OffsetDateTime sentAt,
                           OffsetDateTime acceptedAt, String registrationChallenge, OffsetDateTime registrationExpiresAt,
                           String origin, String rpId, String credentialId, byte[] publicKey, Integer algorithm, long signCount,
                           String assertionChallenge, OffsetDateTime assertionExpiresAt, String evidenceHash, String termPdfSha256,
                           String termContentSha256,
                           String proofHash, boolean userVerified, String acceptanceDeviceMetadata, String acceptanceDeviceId,
                           String acceptanceIp, Double acceptanceLatitude, Double acceptanceLongitude, Double acceptanceAccuracyMeters,
                           OffsetDateTime acceptanceLocationCapturedAt, String acceptanceSource, String originalDossierSha256) {}
}
