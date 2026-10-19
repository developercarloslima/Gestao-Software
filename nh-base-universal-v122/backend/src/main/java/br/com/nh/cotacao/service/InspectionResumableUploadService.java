package br.com.nh.cotacao.service;

import br.com.nh.cotacao.dto.InspectionDtos.ChunkUploadResponse;
import br.com.nh.cotacao.dto.InspectionDtos.ChunkUploadStatusResponse;
import br.com.nh.cotacao.dto.InspectionDtos.InspectionResponse;
import br.com.nh.cotacao.dto.InspectionDtos.InspectionUploadResponse;
import br.com.nh.cotacao.entity.InspectionAsset;
import br.com.nh.cotacao.entity.InspectionAssetType;
import br.com.nh.cotacao.entity.InspectionRequest;
import br.com.nh.cotacao.entity.InspectionRequestStatus;
import br.com.nh.cotacao.entity.InspectionRequestType;
import br.com.nh.cotacao.repository.InspectionRequestRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;

@Service
public class InspectionResumableUploadService {
    private static final Logger log = LoggerFactory.getLogger(InspectionResumableUploadService.class);
    private static final long MAX_UPLOAD_BYTES = 15L * 1024 * 1024;
    private static final long MAX_PHOTO_BYTES = MAX_UPLOAD_BYTES;
    private static final long MAX_SIGNATURE_BYTES = MAX_UPLOAD_BYTES;
    private static final long MAX_DOCUMENT_BYTES = MAX_UPLOAD_BYTES;
    private static final long MAX_CHUNK_BYTES = 6L * 1024 * 1024;
    private static final int MAX_CHUNKS = 4096;
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
    private final InspectionAssetStorageService storageService;
    private final RetratoPdfService pdfService;
    private final RetratoService retratoService;

    public InspectionResumableUploadService(
            InspectionRequestRepository repository,
            InspectionAssetStorageService storageService,
            RetratoPdfService pdfService,
            RetratoService retratoService
    ) {
        this.repository = repository;
        this.storageService = storageService;
        this.pdfService = pdfService;
        this.retratoService = retratoService;
    }

    @Transactional(readOnly = true)
    public ChunkUploadStatusResponse chunkStatus(
            String token,
            InspectionAssetType assetType,
            int sortOrder,
            String uploadId,
            int totalChunks
    ) {
        InspectionRequest request = findByToken(token);
        Optional<InspectionAsset> storedAsset = findAsset(request, assetType, sortOrder);
        if (storedAsset.isPresent()
                && storageService.isAvailable(storedAsset.get())
                && assetType != InspectionAssetType.VIDEO) {
            return new ChunkUploadStatusResponse(true, List.of(), retratoService.toResponse(request));
        }
        validateRequestAvailable(request);
        validateUploadIdentity(uploadId, totalChunks);

        InspectionAssetStorageService.ChunkUploadStatus status = storageService.chunkStatus(
                request.getId(), assetType, sortOrder, uploadId, totalChunks
        );
        return new ChunkUploadStatusResponse(
                status.complete(),
                status.receivedChunks(),
                retratoService.toResponse(request)
        );
    }

    @Transactional
    public ChunkUploadResponse uploadChunk(
            String token,
            InspectionAssetType assetType,
            int sortOrder,
            String label,
            String uploadId,
            int chunkIndex,
            int totalChunks,
            long totalSize,
            String contentType,
            Double videoDurationSeconds,
            MultipartFile chunk
    ) {
        // Mantido para clientes antigos. Clientes atuais usam uploadChunkRaw para
        // não depender do parser multipart em iOS/WebKit.
        validateChunk(uploadId, chunkIndex, totalChunks, chunk);
        byte[] chunkBytes = readChunk(chunk);
        return uploadChunkInternal(
                token, assetType, sortOrder, label, uploadId, chunkIndex, totalChunks,
                totalSize, contentType, videoDurationSeconds, chunkBytes
        );
    }

    @Transactional
    public ChunkUploadResponse uploadChunkRaw(
            String token,
            InspectionAssetType assetType,
            int sortOrder,
            String label,
            String uploadId,
            int chunkIndex,
            int totalChunks,
            long totalSize,
            int chunkSize,
            String contentType,
            Double videoDurationSeconds,
            byte[] chunkBytes
    ) {
        validateRawChunk(uploadId, chunkIndex, totalChunks, totalSize, chunkSize, chunkBytes);
        return uploadChunkInternal(
                token, assetType, sortOrder, label, uploadId, chunkIndex, totalChunks,
                totalSize, contentType, videoDurationSeconds, chunkBytes
        );
    }

    private ChunkUploadResponse uploadChunkInternal(
            String token,
            InspectionAssetType assetType,
            int sortOrder,
            String label,
            String uploadId,
            int chunkIndex,
            int totalChunks,
            long totalSize,
            String contentType,
            Double videoDurationSeconds,
            byte[] chunkBytes
    ) {
        // O bloqueio pessimista funciona entre processos/contêineres e impede
        // duas requisições de alterarem ou finalizarem o mesmo slot ao mesmo tempo.
        InspectionRequest current = repository.findByPublicTokenForUpdate(token)
                .orElseThrow(() -> new IllegalArgumentException("Link de vistoria inválido."));
        validateRequestAvailable(current);
        String cleanType = cleanType(contentType);
        String cleanLabel = validateAssetMetadata(current, assetType, sortOrder, label, totalSize, cleanType, videoDurationSeconds);
        String fileName = buildFileName(assetType, sortOrder, cleanLabel, cleanType);
        InspectionAssetStorageService.ChunkStoreResult result = storageService.storeChunk(
                current,
                assetType,
                cleanLabel,
                fileName,
                cleanType,
                totalSize,
                sortOrder,
                uploadId,
                chunkIndex,
                totalChunks,
                chunkBytes
        );
        current.markUploadStarted();
        repository.flush();

        if (result.complete()) {
            if (result.asset() == null || !storageService.isAvailable(result.asset())) {
                throw new IllegalStateException("O arquivo chegou à API, mas não foi confirmado no PostgreSQL.");
            }
            if (assetType == InspectionAssetType.VIDEO) {
                // Valida ainda na conclusão do upload. Se o vídeo for inválido, a exceção
                // reverte a transação da última parte: o slot não fica marcado como vídeo
                // confirmado e o associado pode gravar outro arquivo imediatamente.
                // A validação é repetida no finalize-upload como última barreira para vídeos
                // que tenham sido enviados por versões anteriores da aplicação.
                storageService.trimVideoToMaximumDuration(result.asset(), 90.0d);
                storageService.validateVideoContinuity(result.asset());
                log.info(
                        "Retrato NH: vídeo normalizado e validado no upload inspectionId={} assetId={} declaredDuration={}",
                        current.getId(), result.asset().getId(), videoDurationSeconds
                );
            }
            return completedChunkResponse(current, assetType, sortOrder, totalChunks);
        }

        return new ChunkUploadResponse(
                false,
                result.receivedChunks(),
                totalChunks,
                assetType,
                sortOrder,
                retratoService.toResponse(current)
        );
    }

    @Transactional
    public InspectionUploadResponse finalizeUpload(
            String token, String residenceAddress, Boolean preexistingDamage, String preexistingDamageDetails
    ) {
        InspectionRequest request = repository.findByPublicTokenForUpdate(token)
                .orElseThrow(() -> new IllegalArgumentException("Link de vistoria inválido."));
        if (isCompleted(request)) {
            return completedUploadResponse(request);
        }
        validateRequestAvailable(request);

        boolean newInspection = request.getRequestType() == InspectionRequestType.NEW_INSPECTION;
        int requiredPhotos = request.getVehicleType().requiredPhotoCount();
        if (newInspection) {
            request.registerResidenceAddress(residenceAddress);
            request.registerPreexistingDamage(preexistingDamage, preexistingDamageDetails);
            for (int order = 1; order <= requiredPhotos; order++) {
                requireStoredAsset(request, InspectionAssetType.PHOTO, order, "Ainda falta enviar a foto " + order + " de " + requiredPhotos + ".");
            }
            int signatureOrder = requiredPhotos + 2;
            requireStoredAsset(request, InspectionAssetType.SIGNATURE, signatureOrder, "Ainda falta enviar a assinatura do associado.");
            int vehicleDocumentOrder = requiredPhotos + 3;
            requireStoredAsset(request, InspectionAssetType.VEHICLE_DOCUMENT, vehicleDocumentOrder, "Ainda falta enviar o CRLV do veículo.");
            int identityDocumentFrontOrder = requiredPhotos + 4;
            requireStoredAsset(request, InspectionAssetType.IDENTITY_DOCUMENT, identityDocumentFrontOrder, "Ainda falta enviar a frente do RG ou da CNH do associado.");
            int identityDocumentBackOrder = requiredPhotos + 5;
            requireStoredAsset(request, InspectionAssetType.IDENTITY_DOCUMENT, identityDocumentBackOrder, "Ainda falta enviar o verso do RG ou da CNH do associado.");
        }

        int videoOrder = newInspection ? requiredPhotos + 1 : 1;
        InspectionAsset videoAsset = requireStoredAsset(
                request,
                InspectionAssetType.VIDEO,
                videoOrder,
                "Ainda falta enviar o vídeo da vistoria."
        );

        // O input capture nativo do iOS abre a câmera do sistema fora do controle da
        // página; HTML não oferece API para mandá-la parar exatamente em 90 segundos.
        // Para manter o gravador nativo (que eliminou o congelamento) e ainda garantir
        // o limite contratual, preservamos automaticamente somente os primeiros 90 s.
        storageService.trimVideoToMaximumDuration(videoAsset, 90.0d);
        storageService.validateVideoContinuity(videoAsset);

        repository.flush();
        request.getAssets().stream()
                .filter(asset -> asset.getAssetType() != InspectionAssetType.REPORT)
                .forEach(asset -> {
                    if (!storageService.isAvailable(asset)) {
                        throw new IllegalArgumentException("Um dos arquivos enviados não está disponível no PostgreSQL.");
                    }
                });

        Optional<InspectionAsset> existingReport = findAsset(
                request,
                InspectionAssetType.REPORT,
                newInspection ? requiredPhotos + 6 : 2
        );
        if (existingReport.isEmpty() || !storageService.isAvailable(existingReport.get())) {
            byte[] reportBytes = pdfService.generate(request);
            int reportOrder = newInspection ? requiredPhotos + 6 : 2;
            storageService.storeBytes(
                    request,
                    InspectionAssetType.REPORT,
                    "Relatório da vistoria",
                    "relatorio-retrato-nh.pdf",
                    "application/pdf",
                    reportOrder,
                    reportBytes
            );
        }

        request.complete();
        repository.flush();
        return completedUploadResponse(request);
    }

    private InspectionAsset requireStoredAsset(
            InspectionRequest request,
            InspectionAssetType type,
            int sortOrder,
            String message
    ) {
        Optional<InspectionAsset> asset = findAsset(request, type, sortOrder);
        if (asset.isEmpty() || !storageService.isAvailable(asset.get())) {
            throw new IllegalArgumentException(message);
        }
        return asset.get();
    }

    private InspectionUploadResponse completedUploadResponse(InspectionRequest request) {
        InspectionResponse response = retratoService.toResponse(request);
        return new InspectionUploadResponse(
                response,
                null,
                null,
                false,
                "Arquivos confirmados no PostgreSQL."
        );
    }

    private ChunkUploadResponse completedChunkResponse(
            InspectionRequest request,
            InspectionAssetType assetType,
            int sortOrder,
            int totalChunks
    ) {
        return new ChunkUploadResponse(
                true,
                totalChunks,
                totalChunks,
                assetType,
                sortOrder,
                retratoService.toResponse(request)
        );
    }

    private void validateRequestAvailable(InspectionRequest request) {
        if (request.getStatus() != InspectionRequestStatus.WAITING_FILES
                && request.getStatus() != InspectionRequestStatus.UPLOADING_FILES
                && request.getStatus() != InspectionRequestStatus.CREATED
                && request.getStatus() != InspectionRequestStatus.UNDER_REVIEW) {
            throw new IllegalArgumentException("Esta solicitação não está disponível para novos envios.");
        }
        boolean quotationExpired = request.getQuotation() != null
                && request.getQuotation().getValidUntil() != null
                && java.time.OffsetDateTime.now().isAfter(request.getQuotation().getValidUntil());
        if (!request.hasAnyPreservedFile() && (quotationExpired || request.isExpired())) {
            throw new IllegalArgumentException("Vistoria/cotação vencida, precisa ser refeita.");
        }
    }

    private boolean isCompleted(InspectionRequest request) {
        return request.getStatus() == InspectionRequestStatus.COMPLETED
                || request.getStatus() == InspectionRequestStatus.APPROVED
                || request.getStatus() == InspectionRequestStatus.REJECTED;
    }

    private String validateAssetMetadata(
            InspectionRequest request,
            InspectionAssetType assetType,
            int sortOrder,
            String label,
            long totalSize,
            String contentType,
            Double videoDurationSeconds
    ) {
        if (assetType == null) throw new IllegalArgumentException("Informe o tipo do arquivo.");
        if (totalSize <= 0) throw new IllegalArgumentException("O arquivo enviado está vazio.");

        boolean newInspection = request.getRequestType() == InspectionRequestType.NEW_INSPECTION;
        int requiredPhotos = request.getVehicleType().requiredPhotoCount();
        int videoOrder = newInspection ? requiredPhotos + 1 : 1;
        int signatureOrder = requiredPhotos + 2;
        int vehicleDocumentOrder = requiredPhotos + 3;
        int identityDocumentFrontOrder = requiredPhotos + 4;
        int identityDocumentBackOrder = requiredPhotos + 5;

        switch (assetType) {
            case PHOTO -> {
                if (!newInspection || sortOrder < 1 || sortOrder > requiredPhotos) {
                    throw new IllegalArgumentException("A posição da foto é inválida para esta vistoria.");
                }
                if (totalSize > MAX_PHOTO_BYTES) throw new IllegalArgumentException("Cada foto deve possuir no máximo 15 MB.");
                if (!PHOTO_TYPES.contains(contentType)) throw new IllegalArgumentException("Envie fotos JPG, PNG ou WebP.");
            }
            case VIDEO -> {
                if (sortOrder != videoOrder) throw new IllegalArgumentException("A posição do vídeo é inválida para esta vistoria.");
                if (videoDurationSeconds != null
                        && (!Double.isFinite(videoDurationSeconds) || videoDurationSeconds <= 0)) {
                    throw new IllegalArgumentException("A duração informada para o vídeo é inválida.");
                }
                // Não rejeitamos aqui vídeos nativos acima de 90 s. O navegador não
                // consegue encerrar a câmera nativa do iOS; no finalize-upload o servidor
                // corta o arquivo para 90 s antes de validar a continuidade das imagens.
                if (!VIDEO_TYPES.contains(contentType)) throw new IllegalArgumentException("Envie o vídeo em MP4, MOV, M4V, WebM ou 3GP.");
            }
            case SIGNATURE -> {
                if (!newInspection || sortOrder != signatureOrder) {
                    throw new IllegalArgumentException("A posição da assinatura é inválida para esta vistoria.");
                }
                if (totalSize > MAX_SIGNATURE_BYTES) throw new IllegalArgumentException("A assinatura deve possuir no máximo 15 MB.");
                if (!PHOTO_TYPES.contains(contentType)) {
                    throw new IllegalArgumentException("A assinatura deve ser enviada como imagem PNG, JPG ou WebP.");
                }
            }
            case VEHICLE_DOCUMENT -> {
                if (!newInspection || sortOrder != vehicleDocumentOrder) {
                    throw new IllegalArgumentException("A posição do CRLV é inválida para esta vistoria.");
                }
                validateDocument(totalSize, contentType, "CRLV do veículo");
            }
            case IDENTITY_DOCUMENT -> {
                if (!newInspection || (sortOrder != identityDocumentFrontOrder && sortOrder != identityDocumentBackOrder)) {
                    throw new IllegalArgumentException("A posição do documento pessoal é inválida para esta vistoria.");
                }
                validateDocument(totalSize, contentType, sortOrder == identityDocumentFrontOrder
                        ? "a frente do RG ou da CNH do associado"
                        : "o verso do RG ou da CNH do associado");
            }
            case OTHER_DOCUMENT -> {
                if (sortOrder < 100 || sortOrder > 199) {
                    throw new IllegalArgumentException("A posição do arquivo adicional é inválida.");
                }
                if (VIDEO_TYPES.contains(contentType)) {
                    // Vídeos não possuem limite de tamanho em MB. A duração do vídeo
                    // principal continua limitada a 90 segundos.
                } else if (PHOTO_TYPES.contains(contentType)) {
                    if (totalSize > MAX_PHOTO_BYTES) {
                        throw new IllegalArgumentException("Cada foto adicional deve possuir no máximo 15 MB.");
                    }
                } else {
                    validateDocument(totalSize, contentType, "o arquivo adicional");
                }
            }
            case REPORT -> throw new IllegalArgumentException("O relatório é gerado automaticamente pelo sistema.");
        }

        String cleanLabel = label == null ? "" : label.trim().replaceAll("\\s+", " ");
        if (cleanLabel.isBlank()) {
            cleanLabel = switch (assetType) {
                case PHOTO -> "Foto " + sortOrder;
                case VIDEO -> "Vídeo da vistoria";
                case SIGNATURE -> "Assinatura do associado";
                case VEHICLE_DOCUMENT -> "CRLV do veículo";
                case IDENTITY_DOCUMENT -> "RG ou CNH do associado";
                case OTHER_DOCUMENT -> "Arquivo adicional";
                case REPORT -> "Relatório da vistoria";
            };
        }
        if (cleanLabel.length() > 140) cleanLabel = cleanLabel.substring(0, 140);
        return cleanLabel;
    }

    private void validateDocument(long totalSize, String contentType, String documentName) {
        if (totalSize > MAX_DOCUMENT_BYTES) {
            throw new IllegalArgumentException(documentName + " deve possuir no máximo 15 MB.");
        }
        if (!DOCUMENT_TYPES.contains(contentType)) {
            throw new IllegalArgumentException("Envie " + documentName + " em PDF, DOC, DOCX, ODT, RTF, TXT, JPG, PNG ou WebP.");
        }
    }

    private void validateChunk(String uploadId, int chunkIndex, int totalChunks, MultipartFile chunk) {
        validateUploadIdentity(uploadId, totalChunks);
        if (chunkIndex < 0 || chunkIndex >= totalChunks) {
            throw new IllegalArgumentException("Parte do arquivo inválida.");
        }
        if (chunk == null || chunk.isEmpty()) {
            throw new IllegalArgumentException("Uma das partes do arquivo está vazia.");
        }
        if (chunk.getSize() > MAX_CHUNK_BYTES) {
            throw new IllegalArgumentException("Cada parte do envio deve possuir no máximo 6 MB.");
        }
    }

    private void validateRawChunk(
            String uploadId,
            int chunkIndex,
            int totalChunks,
            long totalSize,
            int declaredChunkSize,
            byte[] chunkBytes
    ) {
        validateUploadIdentity(uploadId, totalChunks);
        if (chunkIndex < 0 || chunkIndex >= totalChunks) {
            throw new IllegalArgumentException("Parte do arquivo inválida.");
        }
        if (declaredChunkSize <= 0 || declaredChunkSize > MAX_CHUNK_BYTES) {
            throw new IllegalArgumentException("O tamanho da parte do arquivo é inválido.");
        }
        if (totalSize <= 0 || declaredChunkSize > totalSize) {
            throw new IllegalArgumentException("O tamanho total do arquivo é inválido.");
        }
        if (chunkBytes == null || chunkBytes.length == 0) {
            throw new IllegalArgumentException("Uma das partes do arquivo está vazia.");
        }
        if (chunkBytes.length != declaredChunkSize) {
            throw new IllegalArgumentException("Uma das partes do arquivo chegou incompleta.");
        }
        if (totalChunks == 1 && chunkBytes.length != totalSize) {
            throw new IllegalArgumentException("O arquivo chegou incompleto ao servidor.");
        }
    }

    private void validateUploadIdentity(String uploadId, int totalChunks) {
        String clean = uploadId == null ? "" : uploadId.trim();
        if (!clean.matches("^[A-Za-z0-9_-]{8,140}$")) {
            throw new IllegalArgumentException("Identificador de envio inválido.");
        }
        if (totalChunks < 1 || totalChunks > MAX_CHUNKS) {
            throw new IllegalArgumentException("Quantidade de partes do arquivo inválida.");
        }
    }

    private byte[] readChunk(MultipartFile chunk) {
        try {
            byte[] bytes = chunk.getBytes();
            if (bytes.length != chunk.getSize()) {
                throw new IllegalArgumentException("Uma das partes do arquivo chegou incompleta.");
            }
            return bytes;
        } catch (IllegalArgumentException exception) {
            throw exception;
        } catch (Exception exception) {
            throw new IllegalStateException("Não foi possível ler uma das partes do arquivo.", exception);
        }
    }

    private InspectionRequest findByToken(String token) {
        return repository.findByPublicToken(token)
                .orElseThrow(() -> new IllegalArgumentException("Link de vistoria inválido."));
    }

    private Optional<InspectionAsset> findAsset(
            InspectionRequest request,
            InspectionAssetType type,
            int sortOrder
    ) {
        return request.getAssets().stream()
                .filter(asset -> asset.getAssetType() == type && asset.getSortOrder() == sortOrder)
                .findFirst();
    }

    private String buildFileName(
            InspectionAssetType assetType,
            int sortOrder,
            String label,
            String contentType
    ) {
        String base = switch (assetType) {
            case PHOTO -> slug(label);
            case VIDEO -> "video-vistoria";
            case SIGNATURE -> "assinatura-associado";
            case VEHICLE_DOCUMENT -> "crlv-veiculo";
            case IDENTITY_DOCUMENT -> "rg-cnh-associado";
            case OTHER_DOCUMENT -> slug(label);
            case REPORT -> "relatorio-retrato-nh";
        };
        return String.format(Locale.ROOT, "%02d-%s%s", sortOrder, base, extension(contentType));
    }

    private String extension(String contentType) {
        return switch (contentType) {
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
            default -> ".bin";
        };
    }

    private String slug(String value) {
        String slug = value.toLowerCase(Locale.ROOT)
                .replaceAll("[^a-z0-9]+", "-")
                .replaceAll("(^-|-$)", "");
        return slug.isBlank() ? "arquivo" : slug;
    }

    private String cleanType(String contentType) {
        return contentType == null
                ? ""
                : contentType.toLowerCase(Locale.ROOT).split(";", 2)[0].trim();
    }
}
