package br.com.nh.cotacao.service;

import br.com.nh.cotacao.platform.TenantIdentity;
import br.com.nh.cotacao.dto.InspectionDtos.*;
import br.com.nh.cotacao.entity.*;
import br.com.nh.cotacao.repository.InspectionRequestRepository;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.*;

@Service
public class RetratoService {
    private static final String DEFAULT_PUBLIC_WEB_URL = "https://aforma-demo.vercel.app";
    private static final long MAX_UPLOAD_BYTES = 15L * 1024 * 1024;
    private static final long MAX_PHOTO_BYTES = MAX_UPLOAD_BYTES;
    private static final long MAX_SIGNATURE_BYTES = MAX_UPLOAD_BYTES;
    private static final long MAX_DOCUMENT_BYTES = MAX_UPLOAD_BYTES;
    private static final Set<String> PHOTO_TYPES = Set.of("image/jpeg", "image/png", "image/webp");
    private static final Set<String> VIDEO_TYPES = Set.of("video/mp4", "video/quicktime", "video/webm", "video/3gpp", "video/x-m4v");
    private static final Set<String> DOCUMENT_TYPES = Set.of(
            "application/pdf",
            "application/msword",
            "application/vnd.openxmlformats-officedocument.wordprocessingml.document",
            "application/vnd.oasis.opendocument.text",
            "application/rtf",
            "text/rtf",
            "text/plain",
            "image/jpeg",
            "image/png",
            "image/webp"
    );

    private final InspectionRequestRepository repository;
    private final ConsultantService consultantService;
    private final InspectionAssetStorageService storageService;
    private final RetratoPdfService pdfService;
    private final String publicWebUrl;
    private final CommunicationSettingsService communicationSettings;
    private final InspectionDigitalAcceptanceService digitalAcceptanceService;

    public RetratoService(
            InspectionRequestRepository repository,
            ConsultantService consultantService,
            InspectionAssetStorageService storageService,
            RetratoPdfService pdfService,
            CommunicationSettingsService communicationSettings,
            InspectionDigitalAcceptanceService digitalAcceptanceService,
            @Value("${app.public-web-url:https://aforma-demo.vercel.app}") String publicWebUrl
    ) {
        this.repository = repository;
        this.consultantService = consultantService;
        this.storageService = storageService;
        this.pdfService = pdfService;
        this.publicWebUrl = normalizePublicWebUrl(publicWebUrl);
        this.communicationSettings = communicationSettings;
        this.digitalAcceptanceService = digitalAcceptanceService;
    }

    @Transactional
    public InspectionResponse create(CreateInspectionRequest input) {
        if (input.requestType() == InspectionRequestType.NEW_INSPECTION) {
            throw new IllegalArgumentException(
                    "Nova vistoria só pode ser iniciada a partir de uma cotação aceita. Abra a cotação aceita e clique em Iniciar vistoria."
            );
        }
        String cpf = input.cpf().replaceAll("\\D", "");
        if (!validCpf(cpf)) throw new IllegalArgumentException("Informe um CPF válido.");
        Consultant consultant = consultantService.findActive(input.consultantId());
        boolean zeroKm = false;
        String plate = validateAndNormalizeManualPlate(input.plate(), input.requestType(), zeroKm);
        InspectionRequest request = InspectionRequest.create(
                randomToken(),
                input.requestType(),
                input.associateName(),
                cpf,
                input.whatsapp(),
                plate,
                input.vehicleType() == null ? InspectionVehicleType.FOUR_WHEELS_OR_MORE : input.vehicleType(),
                consultant,
                input.contractedPlan()
        );
        return toResponse(repository.save(request));
    }

    @Transactional
    public InspectionResponse ensureForSelfServiceQuote(Quotation quotation) {
        if (quotation == null || quotation.getOrigin() != QuoteOrigin.SELF_SERVICE) {
            throw new IllegalArgumentException("A vistoria automática exige uma cotação feita pelo cliente.");
        }
        return ensureForQuotation(quotation);
    }

    @Transactional
    public InspectionResponse ensureForQuotation(Quotation quotation) {
        if (quotation == null) {
            throw new IllegalArgumentException("Cotação não encontrada.");
        }
        var existing = repository.findByQuotation_Id(quotation.getId());
        if (existing.isPresent()) {
            assertNotExpiredWithoutPreservedFiles(existing.get());
            return toResponse(existing.get());
        }
        if (quotation.getStatus() != QuoteStatus.ACCEPTED) {
            throw new IllegalArgumentException("A cotação precisa estar aceita para iniciar a nova vistoria.");
        }
        if (java.time.OffsetDateTime.now().isAfter(quotation.getValidUntil())) {
            throw new IllegalArgumentException("Vistoria/cotação vencida, precisa ser refeita.");
        }
        return toResponse(repository.save(InspectionRequest.createForQuotation(randomToken(), quotation)));
    }

    @Transactional(readOnly = true)
    public Optional<InspectionResponse> findForQuotation(UUID quotationId) {
        return repository.findByQuotation_Id(quotationId).map(this::toResponse);
    }

    @Transactional(readOnly = true)
    public boolean hasPreservedFileForQuotation(UUID quotationId) {
        return repository.findByQuotation_Id(quotationId)
                .map(InspectionRequest::hasAnyPreservedFile)
                .orElse(false);
    }

    @Transactional(readOnly = true)
    public InspectionResponse publicGet(String token) {
        InspectionRequest request = findByToken(token);
        assertNotExpiredWithoutPreservedFiles(request);
        return toResponse(request);
    }

    @Transactional(readOnly = true)
    public List<InspectionResponse> adminList() {
        return repository.findTop300ByOrderByCreatedAtDesc().stream().map(this::toResponse).toList();
    }

    @Transactional
    public InspectionUploadResponse upload(
            String token,
            List<MultipartFile> photos,
            List<String> labels,
            MultipartFile video,
            Double videoDurationSeconds,
            String residenceAddress,
            Boolean preexistingDamage,
            String preexistingDamageDetails,
            MultipartFile signature,
            MultipartFile vehicleDocument,
            MultipartFile identityDocumentFront,
            MultipartFile identityDocumentBack,
            List<MultipartFile> additionalFiles,
            List<String> additionalLabels
    ) {
        InspectionRequest request = findByToken(token);
        if (request.getStatus() != InspectionRequestStatus.WAITING_FILES
                && request.getStatus() != InspectionRequestStatus.UPLOADING_FILES
                && request.getStatus() != InspectionRequestStatus.CREATED
                && request.getStatus() != InspectionRequestStatus.UNDER_REVIEW) {
            throw new IllegalArgumentException("Esta solicitação não está disponível para novos envios.");
        }
        assertNotExpiredWithoutPreservedFiles(request);
        if (video == null || video.isEmpty()) {
            throw new IllegalArgumentException("O vídeo da vistoria é obrigatório.");
        }
        validateVideo(video);
        validateVideoDuration(videoDurationSeconds);

        boolean newInspection = request.getRequestType() == InspectionRequestType.NEW_INSPECTION;
        if (newInspection) {
            request.registerResidenceAddress(residenceAddress);
            request.registerPreexistingDamage(preexistingDamage, preexistingDamageDetails);
            if (signature == null || signature.isEmpty()) {
                throw new IllegalArgumentException("A assinatura do associado é obrigatória para concluir o cadastro.");
            }
            validateSignature(signature);
            if (vehicleDocument == null || vehicleDocument.isEmpty()) {
                throw new IllegalArgumentException("O CRLV do veículo é obrigatório.");
            }
            if (identityDocumentFront == null || identityDocumentFront.isEmpty()) {
                throw new IllegalArgumentException("A frente do RG ou da CNH do associado é obrigatória.");
            }
            if (identityDocumentBack == null || identityDocumentBack.isEmpty()) {
                throw new IllegalArgumentException("O verso do RG ou da CNH do associado é obrigatório.");
            }
            validateDocument(vehicleDocument, "CRLV do veículo");
            validateDocument(identityDocumentFront, "frente do RG ou da CNH do associado");
            validateDocument(identityDocumentBack, "verso do RG ou da CNH do associado");
        }

        List<MultipartFile> safePhotos = photos == null ? List.of() : photos.stream()
                .filter(file -> file != null && !file.isEmpty())
                .toList();
        int requiredPhotoCount = request.getVehicleType().requiredPhotoCount();
        if (newInspection && safePhotos.size() != requiredPhotoCount) {
            throw new IllegalArgumentException(
                    "A nova vistoria para " + request.getVehicleType().displayName().toLowerCase(Locale.ROOT)
                            + " exige exatamente " + requiredPhotoCount + " fotos obrigatórias e o vídeo."
            );
        }
        safePhotos.forEach(this::validatePhoto);
        List<MultipartFile> safeAdditionalFiles = additionalFiles == null ? List.of() : additionalFiles.stream()
                .filter(file -> file != null && !file.isEmpty())
                .toList();
        if (safeAdditionalFiles.size() > 20) {
            throw new IllegalArgumentException("Envie no máximo 20 arquivos adicionais por vistoria.");
        }
        safeAdditionalFiles.forEach(this::validateAdditionalFile);

        int order = 1;
        for (MultipartFile photo : safePhotos) {
            String label = labelAt(labels, order - 1, "Foto " + order);
            String fileName = String.format(Locale.ROOT, "%02d-%s%s", order, slug(label), extension(photo.getContentType(), ".jpg"));
            storeMultipart(request, InspectionAssetType.PHOTO, label, fileName, photo, order);
            order++;
        }

        int videoOrder = newInspection ? requiredPhotoCount + 1 : 1;
        storeMultipart(
                request,
                InspectionAssetType.VIDEO,
                newInspection ? "Vídeo da vistoria" : "Vídeo para atualização de boleto",
                String.format(Locale.ROOT, "%02d-video-vistoria%s", videoOrder, extension(video.getContentType(), ".mp4")),
                video,
                videoOrder
        );

        if (newInspection) {
            int signatureOrder = requiredPhotoCount + 2;
            storeMultipart(request, InspectionAssetType.SIGNATURE, "Assinatura do associado",
                    String.format(Locale.ROOT, "%02d-assinatura-associado%s", signatureOrder, extension(signature.getContentType(), ".png")),
                    signature, signatureOrder);

            int vehicleDocumentOrder = requiredPhotoCount + 3;
            storeMultipart(request, InspectionAssetType.VEHICLE_DOCUMENT, "CRLV do veículo",
                    String.format(Locale.ROOT, "%02d-crlv-veiculo%s", vehicleDocumentOrder, extension(vehicleDocument.getContentType(), ".pdf")),
                    vehicleDocument, vehicleDocumentOrder);

            int identityDocumentFrontOrder = requiredPhotoCount + 4;
            storeMultipart(request, InspectionAssetType.IDENTITY_DOCUMENT, "RG ou CNH — frente",
                    String.format(Locale.ROOT, "%02d-rg-cnh-frente%s", identityDocumentFrontOrder, extension(identityDocumentFront.getContentType(), ".pdf")),
                    identityDocumentFront, identityDocumentFrontOrder);

            int identityDocumentBackOrder = requiredPhotoCount + 5;
            storeMultipart(request, InspectionAssetType.IDENTITY_DOCUMENT, "RG ou CNH — verso",
                    String.format(Locale.ROOT, "%02d-rg-cnh-verso%s", identityDocumentBackOrder, extension(identityDocumentBack.getContentType(), ".pdf")),
                    identityDocumentBack, identityDocumentBackOrder);
        }

        for (int index = 0; index < safeAdditionalFiles.size(); index++) {
            MultipartFile additionalFile = safeAdditionalFiles.get(index);
            int additionalOrder = 100 + index;
            String additionalLabel = labelAt(additionalLabels, index, "Arquivo adicional " + (index + 1));
            String fileName = String.format(
                    Locale.ROOT,
                    "%03d-%s%s",
                    additionalOrder,
                    slug(additionalLabel),
                    extension(additionalFile.getContentType(), ".bin")
            );
            storeMultipart(
                    request,
                    InspectionAssetType.OTHER_DOCUMENT,
                    additionalLabel,
                    fileName,
                    additionalFile,
                    additionalOrder
            );
        }

        repository.flush();
        byte[] reportBytes = pdfService.generate(request);
        int reportOrder = newInspection ? requiredPhotoCount + 6 : 2;
        storageService.storeBytes(request, InspectionAssetType.REPORT, "Relatório da vistoria",
                "relatorio-retrato-nh.pdf", "application/pdf", reportOrder, reportBytes);
        request.complete();
        repository.flush();

        return new InspectionUploadResponse(toResponse(request), null, null, false, "Arquivos armazenados no painel de análise.");
    }

    private void storeMultipart(
            InspectionRequest request,
            InspectionAssetType type,
            String label,
            String fileName,
            MultipartFile file,
            int sortOrder
    ) {
        try (var input = file.getInputStream()) {
            storageService.storeStream(
                    request,
                    type,
                    label,
                    fileName,
                    cleanType(file.getContentType()),
                    file.getSize(),
                    sortOrder,
                    input
            );
        } catch (RuntimeException exception) {
            throw exception;
        } catch (Exception exception) {
            throw new IllegalStateException("Não foi possível armazenar um dos arquivos da vistoria no PostgreSQL.", exception);
        }
    }

    private void assertNotExpiredWithoutPreservedFiles(InspectionRequest request) {
        if (request == null || request.hasAnyPreservedFile()) return;
        boolean quotationExpired = request.getQuotation() != null
                && request.getQuotation().getValidUntil() != null
                && java.time.OffsetDateTime.now().isAfter(request.getQuotation().getValidUntil());
        if (quotationExpired || request.isExpired()) {
            throw new IllegalArgumentException("Vistoria/cotação vencida, precisa ser refeita.");
        }
    }

    private InspectionRequest findByToken(String token) {
        return repository.findByPublicToken(token)
                .orElseThrow(() -> new IllegalArgumentException("Link de vistoria inválido."));
    }

    public InspectionResponse toResponse(InspectionRequest request) {
        String publicUrl = publicWebUrl + "/retrato/?token=" + request.getPublicToken();
        String whatsappUrl = null;
        if (request.getWhatsapp() != null && !request.getWhatsapp().isBlank()) {
            boolean hasPreservedFile = request.getAssets().stream()
                    .filter(asset -> asset.getAssetType() != InspectionAssetType.REPORT)
                    .anyMatch(storageService::isAvailable);
            String message;
            if (hasPreservedFile && request.getCompletedAt() == null) {
                message = "Olá, " + request.getAssociateName() + "! Precisamos refazer apenas alguns arquivos da vistoria do veículo "
                        + vehiclePlateLabel(request) + ". Os arquivos já aceitos foram mantidos e não precisam ser enviados novamente. "
                        + "O link mostrará somente o que está pendente/rejeitado:\n" + publicUrl;
            } else {
                message = "Olá, " + request.getAssociateName() + "! Acesse o link abaixo para realizar "
                        + (request.getRequestType() == InspectionRequestType.NEW_INSPECTION
                        ? "a vistoria digital completa e enviar as fotos, o vídeo e os documentos"
                        : "o vídeo e, se necessário, os arquivos para atualização de boleto")
                        + " do veículo " + vehiclePlateLabel(request) + ":\n" + publicUrl;
            }
            whatsappUrl = "https://wa.me/55" + normalizeBrazilPhone(request.getWhatsapp())
                    + "?text=" + URLEncoder.encode(message, StandardCharsets.UTF_8);
        }
        String associateCompletionWhatsappUrl = buildAssociateCompletionWhatsappUrl(request);
        String teamWhatsappUrl = null;
        String teamWhatsappNumber = communicationSettings.teamWhatsapp();
        if (teamWhatsappNumber != null && !teamWhatsappNumber.isBlank()) {
            String requestTypeLabel = request.getRequestType() == InspectionRequestType.NEW_INSPECTION
                    ? "Nova vistoria" : "Atualização de boleto";
            String teamMessage;

            if (request.getCompletedAt() == null) {
                teamMessage = "Nova solicitação do Retrato NH\n\nAssociado: " + request.getAssociateName()
                        + "\nPlaca: " + vehiclePlateLabel(request)
                        + "\nWhatsApp do associado: " + visiblePhone(request.getWhatsapp())
                        + "\nConsultor: " + request.getConsultantName()
                        + "\nTipo: " + requestTypeLabel
                        + (request.getContractedPlan() == null ? "" : "\nPlano já contratado: " + request.getContractedPlan())
                        + "\n\nLink para o associado: " + publicUrl;
            } else {
                teamMessage = "Retrato NH concluído\n\nAssociado: " + request.getAssociateName()
                        + "\nPlaca: " + vehiclePlateLabel(request)
                        + "\nConsultor: " + request.getConsultantName()
                        + "\nTipo: " + requestTypeLabel
                        + (request.getContractedPlan() == null ? "" : "\nPlano já contratado: " + request.getContractedPlan())
                        + "\n\nOs arquivos confirmados permanecem disponíveis no painel de análise junto à vistoria.";
            }

            teamWhatsappUrl = "https://wa.me/" + teamWhatsappNumber + "?text="
                    + URLEncoder.encode(teamMessage, StandardCharsets.UTF_8);
        }
        List<InspectionAssetResponse> assets = request.getAssets().stream()
                .map(asset -> new InspectionAssetResponse(
                        asset.getId(), asset.getAssetType(), asset.getLabel(), asset.getFileName(),
                        asset.getContentType(), asset.getFileSize(), null, asset.getSortOrder(),
                        storageService.isAvailable(asset), asset.getStoredAt(), asset.getExpiresAt(), asset.getPurgedAt()
                )).toList();
        Quotation linkedQuotation = request.getQuotation();
        return new InspectionResponse(
                request.getId(), request.getPublicToken(), request.getRequestType(), request.getVehicleType(), request.getAssociateName(),
                maskCpf(request.getCpf()), request.getWhatsapp(), request.getPlate(), request.getVehicleModel(), request.getModelYear(),
                request.getResidenceAddress(), request.hasPreexistingDamage(), request.getPreexistingDamageDetails(),
                request.getContractedPlan(),
                linkedQuotation == null ? 0 : linkedQuotation.getDiscountPercent(),
                linkedQuotation == null ? RearWindowBranding.NOT_APPLICABLE : linkedQuotation.getRearWindowBranding(),
                request.getConsultant() == null ? null : request.getConsultant().getId(),
                request.getConsultantName(), request.getStatus(), request.getCreatedAt(), request.getExpiresAt(),
                request.getCompletedAt(), publicUrl, whatsappUrl, teamWhatsappUrl, associateCompletionWhatsappUrl,
                null, null, digitalAcceptanceService.toStatus(request), assets
        );
    }

    private String buildAssociateCompletionWhatsappUrl(InspectionRequest request) {
        if (request.getWhatsapp() == null || request.getWhatsapp().isBlank()) return null;
        String phone = request.getWhatsapp().replaceAll("\\D", "");
        if (phone.length() == 10 || phone.length() == 11) phone = "55" + phone;
        if (!phone.matches("^[1-9][0-9]{11,14}$")) return null;
        String message = "Olá, " + request.getAssociateName().trim().split("\\s+")[0]
                + "! Sua vistoria foi realizada com sucesso. Aguarde a análise da equipe " + TenantIdentity.legalName() + ".";
        return "https://wa.me/" + phone + "?text=" + URLEncoder.encode(message, StandardCharsets.UTF_8);
    }

    private String validateAndNormalizeManualPlate(
            String plate,
            InspectionRequestType requestType,
            boolean zeroKm
    ) {
        if (requestType == InspectionRequestType.NEW_INSPECTION && zeroKm) {
            return null;
        }

        String normalized = plate == null ? "" : plate.replaceAll("[^A-Za-z0-9]", "").toUpperCase(Locale.ROOT);
        if (!normalized.matches("^[A-Z0-9]{7,10}$")) {
            throw new IllegalArgumentException(requestType == InspectionRequestType.NEW_INSPECTION
                    ? "Informe a placa do veículo ou marque Sim em veículo 0 km."
                    : "Informe a placa do veículo para atualização de boleto.");
        }
        return normalized;
    }

    private void validatePhoto(MultipartFile file) {
        if (file.getSize() > MAX_PHOTO_BYTES) throw new IllegalArgumentException("Cada foto deve possuir no máximo 15 MB.");
        if (!PHOTO_TYPES.contains(cleanType(file.getContentType()))) throw new IllegalArgumentException("Envie fotos JPG, PNG ou WebP.");
    }

    private void validateVideo(MultipartFile file) {
        if (!VIDEO_TYPES.contains(cleanType(file.getContentType()))) throw new IllegalArgumentException("Envie o vídeo em MP4, MOV, M4V, WebM ou 3GP.");
    }

    private void validateVideoDuration(Double durationSeconds) {
        if (durationSeconds == null || !Double.isFinite(durationSeconds) || durationSeconds <= 0 || durationSeconds > 90.5) {
            throw new IllegalArgumentException("O vídeo deve possuir no máximo 1 minuto e 30 segundos.");
        }
    }

    private void validateSignature(MultipartFile file) {
        if (file.getSize() > MAX_SIGNATURE_BYTES) {
            throw new IllegalArgumentException("A assinatura deve possuir no máximo 15 MB.");
        }
        if (!PHOTO_TYPES.contains(cleanType(file.getContentType()))) {
            throw new IllegalArgumentException("A assinatura deve ser enviada como imagem PNG, JPG ou WebP.");
        }
    }

    private void validateDocument(MultipartFile file, String label) {
        if (file.getSize() > MAX_DOCUMENT_BYTES) {
            throw new IllegalArgumentException(label + " deve possuir no máximo 15 MB.");
        }
        if (!DOCUMENT_TYPES.contains(cleanType(file.getContentType()))) {
            throw new IllegalArgumentException("Envie " + label + " em PDF, DOC, DOCX, ODT, RTF, TXT, JPG, PNG ou WebP.");
        }
    }

    private void validateAdditionalFile(MultipartFile file) {
        String type = cleanType(file.getContentType());
        if (VIDEO_TYPES.contains(type)) {
            validateVideo(file);
            return;
        }
        if (PHOTO_TYPES.contains(type)) {
            validatePhoto(file);
            return;
        }
        validateDocument(file, "o arquivo adicional");
    }

    private byte[] bytes(MultipartFile file) {
        try { return file.getBytes(); }
        catch (Exception exception) { throw new IllegalArgumentException("Não foi possível ler um dos arquivos enviados."); }
    }

    private String randomToken() {
        String raw = UUID.randomUUID().toString().replace("-", "")
                + UUID.randomUUID().toString().replace("-", "").substring(0, 16);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(raw.getBytes(StandardCharsets.UTF_8));
    }

    private String labelAt(List<String> labels, int index, String fallback) {
        if (labels == null || index >= labels.size() || labels.get(index) == null || labels.get(index).isBlank()) return fallback;
        return labels.get(index).trim();
    }

    private String extension(String type, String fallback) {
        return switch (cleanType(type)) {
            case "image/jpeg" -> ".jpg";
            case "image/png" -> ".png";
            case "image/webp" -> ".webp";
            case "video/quicktime" -> ".mov";
            case "video/webm" -> ".webm";
            case "video/3gpp" -> ".3gp";
            case "video/mp4" -> ".mp4";
            case "application/pdf" -> ".pdf";
            case "application/msword" -> ".doc";
            case "application/vnd.openxmlformats-officedocument.wordprocessingml.document" -> ".docx";
            case "application/vnd.oasis.opendocument.text" -> ".odt";
            case "application/rtf", "text/rtf" -> ".rtf";
            case "text/plain" -> ".txt";
            default -> fallback;
        };
    }

    private String cleanType(String type) { return type == null ? "" : type.toLowerCase(Locale.ROOT).split(";")[0]; }
    private String slug(String value) { return value.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]+", "-").replaceAll("(^-|-$)", ""); }
    private String stripTrailingSlash(String value) { return value == null ? "" : value.replaceAll("/+$", ""); }
    private String normalizePublicWebUrl(String value) {
        String normalized = stripTrailingSlash(value);
        if (normalized.isBlank() || normalized.matches("(?i)^https?://(localhost|127\\.0\\.0\\.1)(:\\d+)?$")) {
            return DEFAULT_PUBLIC_WEB_URL;
        }
        return normalized;
    }
    private String vehiclePlateLabel(InspectionRequest request) {
        return request.getPlate() == null || request.getPlate().isBlank() ? "Veículo 0 km — sem placa" : request.getPlate();
    }
    private String maskCpf(String cpf) { return cpf.length() == 11 ? "***." + cpf.substring(3, 6) + "." + cpf.substring(6, 9) + "-**" : "***.***.***-**"; }
    private String visiblePhone(String value) {
        if (value == null || value.isBlank()) return "Não informado";
        return value.replaceAll("\\D", "");
    }
    private String normalizeBrazilPhone(String value) {
        String digits = value.replaceAll("\\D", "");
        if (digits.startsWith("55") && digits.length() >= 12) return digits.substring(2);
        return digits;
    }

    private boolean validCpf(String cpf) {
        if (cpf == null || !cpf.matches("\\d{11}") || cpf.chars().distinct().count() == 1) return false;
        try {
            int sum = 0;
            for (int i = 0; i < 9; i++) sum += Character.digit(cpf.charAt(i), 10) * (10 - i);
            int first = 11 - (sum % 11); if (first >= 10) first = 0;
            sum = 0;
            for (int i = 0; i < 10; i++) sum += Character.digit(cpf.charAt(i), 10) * (11 - i);
            int second = 11 - (sum % 11); if (second >= 10) second = 0;
            return first == Character.digit(cpf.charAt(9), 10) && second == Character.digit(cpf.charAt(10), 10);
        } catch (Exception ignored) { return false; }
    }
}
