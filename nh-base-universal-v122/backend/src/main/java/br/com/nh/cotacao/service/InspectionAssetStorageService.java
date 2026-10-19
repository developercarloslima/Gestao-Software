package br.com.nh.cotacao.service;

import br.com.nh.cotacao.entity.InspectionAsset;
import br.com.nh.cotacao.entity.InspectionAssetStorageKind;
import br.com.nh.cotacao.entity.InspectionAssetType;
import br.com.nh.cotacao.entity.InspectionRequest;
import br.com.nh.cotacao.repository.InspectionAssetRepository;
import jakarta.persistence.EntityManager;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

@Service
public class InspectionAssetStorageService {
    private static final Logger log = LoggerFactory.getLogger(InspectionAssetStorageService.class);
    private static final int DIRECT_CHUNK_BYTES = 4 * 1024 * 1024;
    private static final long MAX_VIDEO_BYTES = 15L * 1024 * 1024;
    private static final Path VIDEO_DOWNLOAD_CACHE_DIR = Path.of(System.getProperty("java.io.tmpdir"), "nh-video-download-cache");
    private static final Pattern FREEZE_DURATION_PATTERN = Pattern.compile("freeze_duration:\\s*([0-9]+(?:\\.[0-9]+)?)");
    private static final Pattern FREEZE_START_PATTERN = Pattern.compile("freeze_start:\\s*([0-9]+(?:\\.[0-9]+)?)");
    private static final Pattern FREEZE_END_PATTERN = Pattern.compile("freeze_end:\\s*([0-9]+(?:\\.[0-9]+)?)");

    private final InspectionAssetRepository assetRepository;
    private final JdbcTemplate jdbcTemplate;
    private final EntityManager entityManager;

    public InspectionAssetStorageService(
            InspectionAssetRepository assetRepository,
            JdbcTemplate jdbcTemplate,
            EntityManager entityManager
    ) {
        this.assetRepository = assetRepository;
        this.jdbcTemplate = jdbcTemplate;
        this.entityManager = entityManager;
    }

    /**
     * Compatibilidade com chamadas que já possuem um arquivo local temporário.
     * O conteúdo é imediatamente dividido e persistido no PostgreSQL.
     */
    @Transactional
    public InspectionAsset store(
            InspectionRequest request,
            InspectionAssetType type,
            String label,
            String fileName,
            String contentType,
            long fileSize,
            int sortOrder,
            Path source
    ) {
        try {
            long actualSize = Files.size(source);
            if (actualSize != fileSize) {
                throw new IllegalArgumentException("O arquivo recebido está incompleto.");
            }
            try (InputStream input = Files.newInputStream(source)) {
                return storeStreamInternal(
                        request, type, label, fileName, contentType, fileSize, sortOrder, input
                );
            }
        } catch (IllegalArgumentException exception) {
            throw exception;
        } catch (Exception exception) {
            throw new IllegalStateException("Não foi possível armazenar o arquivo da vistoria no banco de dados.", exception);
        }
    }

    /**
     * Armazena um arquivo sem criar cópia persistente no filesystem do servidor.
     */
    @Transactional
    public InspectionAsset storeStream(
            InspectionRequest request,
            InspectionAssetType type,
            String label,
            String fileName,
            String contentType,
            long fileSize,
            int sortOrder,
            InputStream input
    ) {
        if (input == null) throw new IllegalArgumentException("O conteúdo do arquivo não foi informado.");
        try {
            return storeStreamInternal(
                    request, type, label, fileName, contentType, fileSize, sortOrder, input
            );
        } catch (IllegalArgumentException exception) {
            throw exception;
        } catch (Exception exception) {
            throw new IllegalStateException("Não foi possível armazenar o arquivo da vistoria no banco de dados.", exception);
        }
    }

    @Transactional
    public InspectionAsset storeBytes(
            InspectionRequest request,
            InspectionAssetType type,
            String label,
            String fileName,
            String contentType,
            int sortOrder,
            byte[] bytes
    ) {
        if (bytes == null || bytes.length == 0) {
            throw new IllegalArgumentException("O arquivo gerado está vazio.");
        }
        try (InputStream input = new ByteArrayInputStream(bytes)) {
            return storeStreamInternal(
                    request, type, label, fileName, contentType, bytes.length, sortOrder, input
            );
        } catch (IOException exception) {
            throw new IllegalStateException("Não foi possível armazenar o arquivo gerado.", exception);
        }
    }

    /**
     * Substitui exclusivamente o PDF consolidado gerado pelo sistema sem reabrir a vistoria.
     * É usado quando a Supervisão registra a decisão final, pois o relatório criado no
     * envio do associado precisa ser trocado pelo dossiê definitivo com regulamento e
     * registro digital da aprovação/rejeição.
     */
    @Transactional
    public InspectionAsset replaceGeneratedReport(
            InspectionRequest request,
            String label,
            String fileName,
            int sortOrder,
            byte[] bytes
    ) {
        if (request == null) throw new IllegalArgumentException("Informe a vistoria do relatório.");
        if (bytes == null || bytes.length == 0) throw new IllegalArgumentException("O relatório final está vazio.");

        List<InspectionAsset> previousReports = new ArrayList<>(request.getAssets().stream()
                .filter(asset -> asset.getAssetType() == InspectionAssetType.REPORT)
                .toList());

        for (InspectionAsset report : previousReports) {
            deleteBlobSlot(request.getId(), InspectionAssetType.REPORT, report.getSortOrder());
            jdbcTemplate.update("delete from inspection_asset_contents where asset_id = ?", report.getId());
            request.removeAsset(report);
        }
        entityManager.flush();

        return storeBytes(
                request,
                InspectionAssetType.REPORT,
                label,
                fileName,
                "application/pdf",
                sortOrder,
                bytes
        );
    }

    /**
     * Retorna as partes já confirmadas no PostgreSQL. Nenhuma parte é lida do disco.
     */
    @Transactional(readOnly = true)
    public ChunkUploadStatus chunkStatus(
            UUID inspectionId,
            InspectionAssetType assetType,
            int sortOrder,
            String uploadId,
            int totalChunks
    ) {
        List<BlobRow> rows = jdbcTemplate.query(
                """
                select id, upload_id, total_size, total_chunks, status, asset_id
                  from inspection_asset_blobs
                 where inspection_id = ?
                   and asset_type = ?
                   and sort_order = ?
                """,
                (resultSet, rowNum) -> new BlobRow(
                        resultSet.getObject("id", UUID.class),
                        resultSet.getString("upload_id"),
                        resultSet.getLong("total_size"),
                        resultSet.getInt("total_chunks"),
                        resultSet.getString("status"),
                        resultSet.getObject("asset_id", UUID.class)
                ),
                inspectionId,
                assetType.name(),
                sortOrder
        );
        if (rows.isEmpty()) return new ChunkUploadStatus(false, List.of());

        BlobRow blob = rows.getFirst();
        if ("COMPLETE".equals(blob.status()) && blob.assetId() != null && contentExists(blob.assetId())) {
            // Fotos/documentos confirmados não são reenviados. Vídeo é diferente:
            // uma nova gravação possui outro uploadId e precisa poder substituir um
            // arquivo anterior que tenha sido reprovado por congelamento.
            // A normalização de vídeo pode regravar o blob em partes internas de 4 MB,
            // portanto o totalChunks físico pode mudar. O uploadId identifica de forma
            // suficiente a mesma gravação; uma nova gravação recebe outro uploadId.
            boolean sameVideoUpload = blob.uploadId().equals(uploadId);
            if (assetType != InspectionAssetType.VIDEO || sameVideoUpload) {
                return new ChunkUploadStatus(true, List.of());
            }
            return new ChunkUploadStatus(false, List.of());
        }
        if (!blob.uploadId().equals(uploadId) || blob.totalChunks() != totalChunks) {
            return new ChunkUploadStatus(false, List.of());
        }

        List<Integer> chunks = jdbcTemplate.query(
                """
                select chunk_index
                  from inspection_asset_blob_chunks
                 where blob_id = ?
                 order by chunk_index
                """,
                (resultSet, rowNum) -> resultSet.getInt(1),
                blob.id()
        );
        return new ChunkUploadStatus(false, List.copyOf(chunks));
    }

    /**
     * Grava uma parte imediatamente no PostgreSQL e, ao receber a última parte,
     * associa o conteúdo completo a um InspectionAsset na mesma transação.
     */
    @Transactional
    public ChunkStoreResult storeChunk(
            InspectionRequest request,
            InspectionAssetType type,
            String label,
            String fileName,
            String contentType,
            long totalSize,
            int sortOrder,
            String uploadId,
            int chunkIndex,
            int totalChunks,
            byte[] chunkBytes
    ) {
        if (request == null) throw new IllegalArgumentException("Vistoria não encontrada.");
        if (type == null) throw new IllegalArgumentException("Tipo do arquivo não informado.");
        if (totalSize <= 0) throw new IllegalArgumentException("O arquivo recebido está vazio.");
        validateStorageLimit(type, contentType, totalSize);
        if (totalChunks < 1 || totalChunks > 4096) {
            throw new IllegalArgumentException("Quantidade de partes do arquivo inválida.");
        }
        if (chunkIndex < 0 || chunkIndex >= totalChunks) {
            throw new IllegalArgumentException("Índice da parte do arquivo inválido.");
        }
        if (uploadId == null || uploadId.isBlank()) {
            throw new IllegalArgumentException("Identificador do envio não informado.");
        }
        if (chunkBytes == null || chunkBytes.length == 0) {
            throw new IllegalArgumentException("Uma das partes do arquivo está vazia.");
        }

        Optional<InspectionAsset> currentAsset = findSlotAsset(request, type, sortOrder);
        if (currentAsset.isPresent() && isAvailable(currentAsset.get())) {
            boolean sameCompletedUpload = completedBlobMatches(
                    request.getId(), type, sortOrder, uploadId, totalSize, totalChunks
            );
            if (type == InspectionAssetType.VIDEO && !sameCompletedUpload) {
                // Uma nova gravação recebe outro uploadId. Permitimos substituir o
                // vídeo anterior mesmo que o slot já estivesse completo; isso é
                // essencial quando a validação de continuidade pede para regravar.
                deleteBlobSlot(request.getId(), type, sortOrder);
                jdbcTemplate.update("delete from inspection_asset_contents where asset_id = ?", currentAsset.get().getId());
                currentAsset.get().markPurged(OffsetDateTime.now());
                assetRepository.flush();
                entityManager.flush();
                removeUnavailableSlotAsset(request, type, sortOrder);
            } else {
                return new ChunkStoreResult(true, totalChunks, currentAsset.get());
            }
        }

        // Se este slot foi rejeitado/excluído anteriormente, remova o metadado
        // indisponível antes de iniciar a nova sessão de upload. O orphanRemoval
        // elimina o registro antigo e os binários vinculados por cascade, evitando
        // colisões nas restrições únicas do slot durante o reenvio.
        removeUnavailableSlotAsset(request, type, sortOrder);

        BlobRow blob = lockOrCreateBlob(
                request.getId(), type, sortOrder, uploadId, label, fileName,
                contentType, totalSize, totalChunks
        );
        insertOrReplaceChunk(blob.id(), chunkIndex, chunkBytes);
        touchBlob(blob.id());

        BlobProgress progress = blobProgress(blob.id());
        if (!progress.complete(totalChunks, totalSize)) {
            request.markUploadStarted();
            return new ChunkStoreResult(false, progress.receivedChunks(), null);
        }

        InspectionAsset asset = createAssetMetadata(
                request, type, label, fileName, contentType, totalSize, sortOrder
        );
        int updated = jdbcTemplate.update(
                """
                update inspection_asset_blobs
                   set asset_id = ?, status = 'COMPLETE', completed_at = now(), updated_at = now()
                 where id = ? and status = 'UPLOADING'
                """,
                asset.getId(),
                blob.id()
        );
        if (updated != 1 || !contentExists(asset.getId())) {
            throw new IllegalStateException("O arquivo não pôde ser confirmado no PostgreSQL.");
        }
        log.info(
                "Retrato NH: arquivo confirmado no PostgreSQL inspectionId={} assetId={} type={} order={} bytes={} chunks={}",
                request.getId(), asset.getId(), type, sortOrder, totalSize, totalChunks
        );
        return new ChunkStoreResult(true, totalChunks, asset);
    }

    @Transactional(readOnly = true)
    public InspectionAsset requireAvailable(UUID inspectionId, UUID assetId) {
        InspectionAsset asset = assetRepository.findByIdAndInspectionRequest_Id(assetId, inspectionId)
                .orElseThrow(() -> new IllegalArgumentException("Arquivo da vistoria não encontrado."));
        if (!isAvailable(asset)) {
            throw new IllegalArgumentException("Este arquivo não está disponível no armazenamento da vistoria.");
        }
        return asset;
    }

    @Transactional
    public void deleteAsset(UUID inspectionId, UUID assetId) {
        InspectionAsset asset = assetRepository.findByIdAndInspectionRequest_Id(assetId, inspectionId)
                .orElseThrow(() -> new IllegalArgumentException("Arquivo da vistoria não encontrado."));
        InspectionRequest request = asset.getInspectionRequest();
        if (request.getAcceptedAt() != null) {
            throw new IllegalArgumentException(
                    "Esta vistoria já possui aceite digital do associado. Os arquivos não podem mais ser alterados."
            );
        }
        String fileName = asset.getFileName();
        boolean userSubmittedAsset = asset.getAssetType() != InspectionAssetType.REPORT;

        List<InspectionAsset> currentAssets = assetRepository.findAllByInspectionRequest_IdOrderBySortOrderAsc(inspectionId);
        List<InspectionAsset> toDelete = currentAssets.stream()
                .filter(current -> current.getId().equals(assetId)
                        || (userSubmittedAsset && current.getAssetType() == InspectionAssetType.REPORT))
                .toList();

        OffsetDateTime purgedAt = OffsetDateTime.now();
        for (InspectionAsset current : toDelete) {
            // Remove primeiro o conteúdo/binário do slot. O metadado permanece
            // temporariamente como indisponível até o novo upload substituir o slot.
            // Isso evita deixar uma sessão COMPLETE antiga disputando a restrição
            // única (inspection_id, asset_type, sort_order) durante o reenvio.
            deleteBlobSlot(inspectionId, current.getAssetType(), current.getSortOrder());
            jdbcTemplate.update("delete from inspection_asset_contents where asset_id = ?", current.getId());
            current.markPurged(purgedAt);
        }

        if (userSubmittedAsset) {
            request.reopenForMissingFiles();
        }
        assetRepository.flush();
        entityManager.flush();
        log.info(
                "Retrato NH: arquivo excluído individualmente e vistoria reaberta inspectionId={} assetId={} fileName={} removedReport={}",
                inspectionId, assetId, fileName,
                toDelete.stream().anyMatch(current -> current.getAssetType() == InspectionAssetType.REPORT)
        );
    }

    @Transactional(readOnly = true)
    public byte[] readAll(UUID assetId) {
        try (ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            writeTo(assetId, output);
            return output.toByteArray();
        } catch (IOException exception) {
            throw new IllegalStateException("Não foi possível ler o conteúdo do arquivo armazenado.", exception);
        }
    }

    @Transactional(readOnly = true)
    public void writeTo(UUID assetId, OutputStream output) {
        if (output == null) throw new IllegalArgumentException("Destino do download não informado.");

        List<UUID> blobIds = jdbcTemplate.query(
                """
                select id
                  from inspection_asset_blobs
                 where asset_id = ? and status = 'COMPLETE'
                """,
                (resultSet, rowNum) -> resultSet.getObject(1, UUID.class),
                assetId
        );
        if (!blobIds.isEmpty()) {
            streamChunkedBlob(blobIds.getFirst(), output);
            return;
        }

        streamLegacyContent(assetId, output);
    }

    @Transactional(readOnly = true)
    public void writeInspectionZip(UUID inspectionId, OutputStream output) {
        List<InspectionAsset> assets = assetRepository.findAllByInspectionRequest_IdOrderBySortOrderAsc(inspectionId)
                .stream()
                .filter(this::isAvailable)
                .toList();
        if (assets.isEmpty()) {
            throw new IllegalArgumentException("Esta vistoria não possui arquivos disponíveis para download.");
        }
        try (ZipOutputStream zip = new ZipOutputStream(output, java.nio.charset.StandardCharsets.UTF_8)) {
            Set<String> usedNames = new HashSet<>();
            for (InspectionAsset asset : assets) {
                String name = uniqueZipName(downloadFileName(asset), usedNames);
                zip.putNextEntry(new ZipEntry(name));
                writeForDownload(asset, zip);
                zip.closeEntry();
            }
            zip.finish();
        } catch (IllegalArgumentException exception) {
            throw exception;
        } catch (Exception exception) {
            throw new IllegalStateException("Não foi possível gerar o pacote de arquivos da vistoria.", exception);
        }
    }

    @Transactional
    public int purgeExpired() {
        // V83.35: arquivos confirmados de vistoria não expiram mais por idade.
        // Mantemos apenas a higiene de uploads interrompidos que nunca foram concluídos.
        OffsetDateTime abandonedCutoff = OffsetDateTime.now().minusDays(2);
        return jdbcTemplate.update(
                "delete from inspection_asset_blobs where status = 'UPLOADING' and updated_at <= ?",
                abandonedCutoff
        );
    }

    public boolean isAvailable(InspectionAsset asset) {
        // A disponibilidade depende apenas da persistência do conteúdo. Vídeos não
        // possuem mais limite de MB; fotos e documentos mantêm a proteção própria.
        return asset != null
                && asset.getStorageKind() == InspectionAssetStorageKind.DATABASE
                && asset.isAvailable()
                && contentExists(asset.getId());
    }

    public boolean requiresVideoDownloadCompression(InspectionAsset asset) {
        // O vídeo deve ser preservado exatamente como foi gravado. Não aplicamos
        // compactação automática nem teto de tamanho no download.
        return false;
    }

    public String downloadFileName(InspectionAsset asset) {
        if (!requiresVideoDownloadCompression(asset)) return asset.getFileName();
        String name = asset.getFileName() == null || asset.getFileName().isBlank()
                ? "video-vistoria"
                : asset.getFileName().trim();
        int dot = name.lastIndexOf('.');
        String base = dot > 0 ? name.substring(0, dot) : name;
        return base + "-compactado.webm";
    }

    /**
     * Para vídeos legados acima de 15 MB, preserva o original no PostgreSQL e
     * entrega uma cópia WebM (VP8/Opus) compactada somente durante o download.
     */
    @Transactional(readOnly = true)
    public void writeForDownload(InspectionAsset asset, OutputStream output) {
        if (asset == null) throw new IllegalArgumentException("Arquivo da vistoria não informado.");
        if (!requiresVideoDownloadCompression(asset)) {
            writeTo(asset.getId(), output);
            return;
        }
        writeCompressedVideo(asset, output);
    }

    /**
     * Gera a cópia compactada por completo antes de iniciar a resposta HTTP.
     * Como o resultado é limitado a 15 MB, evitamos manter uma resposta
     * assíncrona aberta durante o ffmpeg e eliminamos falsos 403 no download.
     */
    @Transactional(readOnly = true)
    public byte[] compressedVideoDownloadBytes(InspectionAsset asset) {
        if (asset == null || !requiresVideoDownloadCompression(asset)) {
            throw new IllegalArgumentException("Este arquivo não requer compactação de vídeo.");
        }
        ByteArrayOutputStream output = new ByteArrayOutputStream((int) Math.min(MAX_VIDEO_BYTES, 15_000_000L));
        writeCompressedVideo(asset, output);
        return output.toByteArray();
    }

    private void writeCompressedVideo(InspectionAsset asset, OutputStream output) {
        Path source = null;
        Path compressed = null;
        try {
            Files.createDirectories(VIDEO_DOWNLOAD_CACHE_DIR);
            Path cached = cachedVideoPath(asset);
            if (Files.exists(cached)) {
                long cachedSize = Files.size(cached);
                if (cachedSize > 0 && cachedSize <= MAX_VIDEO_BYTES) {
                    try (InputStream input = Files.newInputStream(cached)) {
                        input.transferTo(output);
                    }
                    log.info("Retrato NH: download de vídeo compactado servido do cache assetId={} bytes={}", asset.getId(), cachedSize);
                    return;
                }
                Files.deleteIfExists(cached);
            }

            source = Files.createTempFile("nh-video-original-", videoSourceExtension(asset.getContentType()));
            try (OutputStream fileOut = Files.newOutputStream(source)) {
                writeTo(asset.getId(), fileOut);
            }

            double durationSeconds = probeVideoDuration(source);
            if (!Double.isFinite(durationSeconds) || durationSeconds <= 0) {
                throw new IllegalStateException("Não foi possível identificar a duração do vídeo para compactação.");
            }

            compressed = Files.createTempFile("nh-video-download-", ".webm");
            long[] targets = { 12_500_000L, 11_000_000L };
            boolean success = false;
            for (long targetBytes : targets) {
                Files.deleteIfExists(compressed);
                compressed = Files.createTempFile("nh-video-download-", ".webm");
                transcodeVideo(source, compressed, durationSeconds, targetBytes);
                long size = Files.size(compressed);
                if (size > 0 && size <= MAX_VIDEO_BYTES) {
                    success = true;
                    break;
                }
            }
            if (!success) {
                throw new IllegalStateException("Não foi possível compactar o vídeo para até 15 MB.");
            }

            Files.copy(compressed, cached, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
            try (InputStream input = Files.newInputStream(cached)) {
                input.transferTo(output);
            }
            log.info("Retrato NH: vídeo legado compactado e armazenado em cache assetId={} originalBytes={} downloadBytes={}",
                    asset.getId(), asset.getFileSize(), Files.size(cached));
        } catch (IllegalArgumentException exception) {
            throw exception;
        } catch (Exception exception) {
            throw new IllegalStateException("Não foi possível compactar o vídeo para download. O arquivo original permanece preservado.", exception);
        } finally {
            if (source != null) try { Files.deleteIfExists(source); } catch (Exception ignored) {}
            if (compressed != null) try { Files.deleteIfExists(compressed); } catch (Exception ignored) {}
        }
    }

    private Path cachedVideoPath(InspectionAsset asset) {
        String key = asset.getId() + "-" + asset.getFileSize() + ".webm";
        return VIDEO_DOWNLOAD_CACHE_DIR.resolve(key);
    }

    private double probeVideoDuration(Path source) throws Exception {
        Process process = new ProcessBuilder(
                "ffprobe", "-v", "error",
                "-show_entries", "format=duration",
                "-of", "default=noprint_wrappers=1:nokey=1",
                source.toAbsolutePath().toString()
        ).redirectErrorStream(true).start();
        String result;
        try (InputStream input = process.getInputStream()) {
            result = new String(input.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8).trim();
        }
        int exit = process.waitFor();
        if (exit != 0 || result.isBlank()) {
            throw new IllegalStateException("ffprobe não conseguiu ler a duração do vídeo.");
        }
        return Double.parseDouble(result.split("\\R")[0].trim());
    }

    /**
     * O gravador nativo do iOS é aberto fora da página e o HTML não possui uma API
     * capaz de encerrá-lo exatamente em 90 segundos. Para manter a estabilidade da
     * câmera nativa sem aceitar vídeos maiores no dossiê, normalizamos o arquivo já
     * recebido e preservamos somente os primeiros {@code maxDurationSeconds}.
     *
     * O corte é feito com stream copy, sem recompressão: a qualidade original da
     * câmera é mantida e o servidor não gasta CPU recodificando um vídeo válido.
     */
    @Transactional
    public boolean trimVideoToMaximumDuration(InspectionAsset asset, double maxDurationSeconds) {
        if (asset == null || asset.getAssetType() != InspectionAssetType.VIDEO) {
            throw new IllegalArgumentException("Vídeo da vistoria não encontrado.");
        }
        if (!Double.isFinite(maxDurationSeconds) || maxDurationSeconds <= 0) {
            throw new IllegalArgumentException("Limite de duração do vídeo inválido.");
        }
        if (!isAvailable(asset)) {
            throw new IllegalArgumentException("O vídeo da vistoria não está disponível no PostgreSQL.");
        }

        Path source = null;
        Path trimmed = null;
        try {
            String extension = videoSourceExtension(asset.getContentType());
            source = Files.createTempFile("nh-video-source-", extension);
            trimmed = Files.createTempFile("nh-video-trimmed-", extension);

            try (OutputStream output = Files.newOutputStream(source)) {
                writeTo(asset.getId(), output);
            }

            double originalDuration = probeVideoDuration(source);
            long originalSize = asset.getFileSize();
            if (!Double.isFinite(originalDuration) || originalDuration <= 0) {
                throw new IllegalArgumentException("Não foi possível identificar a duração do vídeo enviado.");
            }
            if (originalDuration <= maxDurationSeconds + 0.5d) {
                return false;
            }

            trimVideoWithoutReencoding(source, trimmed, maxDurationSeconds, extension);
            long trimmedSize = Files.size(trimmed);
            if (trimmedSize <= 0) {
                throw new IllegalStateException("O corte do vídeo gerou um arquivo vazio.");
            }

            double trimmedDuration = probeVideoDuration(trimmed);
            if (!Double.isFinite(trimmedDuration) || trimmedDuration <= 0
                    || trimmedDuration > maxDurationSeconds + 0.5d) {
                throw new IllegalStateException("O vídeo não pôde ser limitado corretamente a 1 minuto e 30 segundos.");
            }

            replaceChunkedAssetContent(asset, trimmed, trimmedSize);
            log.info(
                    "Retrato NH: vídeo normalizado sem recompressão assetId={} originalDuration={} finalDuration={} originalBytes={} finalBytes={}",
                    asset.getId(), originalDuration, trimmedDuration, originalSize, trimmedSize
            );
            return true;
        } catch (IllegalArgumentException exception) {
            throw exception;
        } catch (Exception exception) {
            log.warn("Retrato NH: falha ao limitar vídeo assetId={}", asset.getId(), exception);
            throw new IllegalArgumentException(
                    "O vídeo foi gravado, mas não foi possível limitar o arquivo a 1 minuto e 30 segundos. Tente gravar novamente mais próximo desse tempo."
            );
        } finally {
            if (source != null) try { Files.deleteIfExists(source); } catch (Exception ignored) {}
            if (trimmed != null) try { Files.deleteIfExists(trimmed); } catch (Exception ignored) {}
        }
    }

    private void trimVideoWithoutReencoding(Path source, Path destination, double maxDurationSeconds, String extension) throws Exception {
        List<String> command = new ArrayList<>(List.of(
                "ffmpeg", "-y", "-nostdin", "-hide_banner", "-loglevel", "error",
                "-i", source.toAbsolutePath().toString(),
                "-map", "0:v:0", "-map", "0:a?",
                "-t", String.format(Locale.ROOT, "%.3f", maxDurationSeconds),
                "-c", "copy"
        ));
        if (".mp4".equals(extension) || ".mov".equals(extension) || ".m4v".equals(extension)) {
            command.add("-movflags");
            command.add("+faststart");
        }
        command.add(destination.toAbsolutePath().toString());

        Process process = new ProcessBuilder(command).redirectErrorStream(true).start();
        if (!process.waitFor(45, TimeUnit.SECONDS)) {
            process.destroyForcibly();
            throw new IllegalStateException("ffmpeg excedeu o tempo para limitar o vídeo.");
        }
        String output;
        try (InputStream input = process.getInputStream()) {
            output = new String(input.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
        }
        if (process.exitValue() != 0 || !Files.exists(destination) || Files.size(destination) <= 0) {
            throw new IllegalStateException("ffmpeg não conseguiu limitar o vídeo: " + output.trim());
        }
    }

    private void replaceChunkedAssetContent(InspectionAsset asset, Path replacement, long replacementSize) throws IOException {
        List<UUID> blobIds = jdbcTemplate.query(
                "select id from inspection_asset_blobs where asset_id = ? and status = 'COMPLETE' for update",
                (resultSet, rowNum) -> resultSet.getObject(1, UUID.class),
                asset.getId()
        );
        if (blobIds.isEmpty()) {
            throw new IllegalStateException("Conteúdo binário do vídeo não encontrado para substituição.");
        }

        UUID blobId = blobIds.getFirst();
        int totalChunks = Math.toIntExact((replacementSize + DIRECT_CHUNK_BYTES - 1L) / DIRECT_CHUNK_BYTES);
        if (totalChunks < 1 || totalChunks > 4096) {
            throw new IllegalArgumentException("O vídeo resultante possui partes demais para armazenamento.");
        }

        jdbcTemplate.update("delete from inspection_asset_blob_chunks where blob_id = ?", blobId);
        jdbcTemplate.update(
                "update inspection_asset_blobs set total_size = ?, total_chunks = ?, updated_at = now() where id = ?",
                replacementSize, totalChunks, blobId
        );

        try (InputStream input = Files.newInputStream(replacement)) {
            long remaining = replacementSize;
            int chunkIndex = 0;
            while (remaining > 0) {
                int requested = (int) Math.min(DIRECT_CHUNK_BYTES, remaining);
                byte[] bytes = input.readNBytes(requested);
                if (bytes.length != requested) {
                    throw new IllegalArgumentException("O vídeo limitado ficou incompleto durante a persistência.");
                }
                insertOrReplaceChunk(blobId, chunkIndex, bytes);
                remaining -= bytes.length;
                chunkIndex++;
            }
            if (input.read() != -1) {
                throw new IllegalArgumentException("O tamanho do vídeo limitado não corresponde ao arquivo gerado.");
            }
        }

        BlobProgress progress = blobProgress(blobId);
        if (!progress.complete(totalChunks, replacementSize)) {
            throw new IllegalStateException("O vídeo limitado não foi integralmente persistido no PostgreSQL.");
        }

        asset.replaceStoredFileMetadata(asset.getFileName(), asset.getContentType(), replacementSize);
        assetRepository.flush();
        entityManager.flush();
        if (!contentExists(asset.getId())) {
            throw new IllegalStateException("O vídeo limitado não foi confirmado no PostgreSQL.");
        }
    }

    /**
     * Última barreira contra o defeito observado no WebKit/iPhone: em alguns casos
     * o áudio continua até o fim do contêiner, mas a trilha de vídeo deixa de gerar
     * quadros muitos segundos antes. O frontend tenta recuperar a câmera em tempo
     * real; esta validação no servidor impede que um arquivo com cauda congelada seja
     * aceito como vistoria concluída caso o navegador ainda falhe.
     */
    @Transactional(readOnly = true)
    public void validateVideoContinuity(InspectionAsset asset) {
        if (asset == null || asset.getAssetType() != InspectionAssetType.VIDEO) {
            throw new IllegalArgumentException("Vídeo da vistoria não encontrado.");
        }
        if (!isAvailable(asset)) {
            throw new IllegalArgumentException("O vídeo da vistoria não está disponível no PostgreSQL.");
        }

        Path source = null;
        try {
            source = Files.createTempFile("nh-video-integrity-", videoSourceExtension(asset.getContentType()));
            try (OutputStream output = Files.newOutputStream(source)) {
                writeTo(asset.getId(), output);
            }

            double containerDuration = probeVideoDuration(source);
            double lastVideoTimestamp = probeLastVideoFrameTimestamp(source);
            if (!Double.isFinite(containerDuration) || containerDuration <= 0
                    || !Double.isFinite(lastVideoTimestamp) || lastVideoTimestamp < 0) {
                throw new IllegalArgumentException(
                        "O vídeo recebido não possui uma trilha de imagem válida. Grave novamente a vistoria."
                );
            }

            if (containerDuration > 90.5d) {
                throw new IllegalArgumentException(
                        "O vídeo ultrapassou 1 minuto e 30 segundos. Grave novamente dentro do limite."
                );
            }

            // Um quadro final normalmente fica poucos décimos antes da duração total.
            // Margem de 4,5 s evita falso positivo em contêineres móveis, mas detecta
            // o caso em que a trilha de vídeo termina e o áudio continua.
            double missingTailSeconds = Math.max(0d, containerDuration - lastVideoTimestamp);
            if (containerDuration >= 8d && missingTailSeconds > 4.5d) {
                log.warn(
                        "Retrato NH: vídeo rejeitado por congelamento assetId={} duration={} lastVideoFrame={} frozenTail={}",
                        asset.getId(), containerDuration, lastVideoTimestamp, missingTailSeconds
                );
                throw new IllegalArgumentException(
                        "O vídeo parou de receber imagens antes do fim da gravação (a câmera congelou enquanto o áudio continuou). Grave o vídeo novamente."
                );
            }

            double longestVisualFreeze = probeLongestVisualFreeze(source, containerDuration);
            // Uma pessoa pode manter o celular praticamente parado por alguns segundos
            // ao mostrar chassi/odômetro. O limiar de 12 s evita rejeitar esse comportamento
            // legítimo, mas continua barrando o congelamento prolongado observado no iPhone.
            double visualFreezeRejectSeconds = 12d;
            if (containerDuration >= 15d && longestVisualFreeze >= visualFreezeRejectSeconds) {
                log.warn(
                        "Retrato NH: vídeo rejeitado por quadro congelado assetId={} duration={} longestFreeze={} threshold={}",
                        asset.getId(), containerDuration, longestVisualFreeze, visualFreezeRejectSeconds
                );
                throw new IllegalArgumentException(
                        "O vídeo contém um trecho prolongado com a imagem congelada. Grave novamente a vistoria."
                );
            }

            log.info(
                    "Retrato NH: continuidade do vídeo validada assetId={} duration={} lastVideoFrame={} tailGap={} longestFreeze={}",
                    asset.getId(), containerDuration, lastVideoTimestamp, missingTailSeconds, longestVisualFreeze
            );
        } catch (IllegalArgumentException exception) {
            throw exception;
        } catch (Exception exception) {
            log.warn("Retrato NH: falha ao validar continuidade do vídeo assetId={}", asset.getId(), exception);
            throw new IllegalArgumentException(
                    "Não foi possível validar a integridade das imagens do vídeo. Grave novamente antes de concluir a vistoria."
            );
        } finally {
            if (source != null) try { Files.deleteIfExists(source); } catch (Exception ignored) {}
        }
    }

    private double probeLastVideoFrameTimestamp(Path source) throws Exception {
        Process process = new ProcessBuilder(
                "ffprobe", "-v", "error",
                "-select_streams", "v:0",
                "-show_entries", "frame=best_effort_timestamp_time",
                "-of", "default=noprint_wrappers=1:nokey=1",
                source.toAbsolutePath().toString()
        ).redirectErrorStream(true).start();

        String result;
        try (InputStream input = process.getInputStream()) {
            result = new String(input.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8).trim();
        }

        if (!process.waitFor(25, TimeUnit.SECONDS)) {
            process.destroyForcibly();
            throw new IllegalStateException("ffprobe excedeu o tempo de validação do vídeo.");
        }
        if (process.exitValue() != 0 || result.isBlank()) {
            throw new IllegalStateException("ffprobe não encontrou quadros de vídeo válidos.");
        }

        String[] lines = result.split("\\R");
        for (int index = lines.length - 1; index >= 0; index--) {
            String value = lines[index].trim();
            if (value.isBlank() || "N/A".equalsIgnoreCase(value)) continue;
            try {
                return Double.parseDouble(value);
            } catch (NumberFormatException ignored) {
                // Procura a última linha numérica válida.
            }
        }
        throw new IllegalStateException("ffprobe não retornou timestamp de quadro de vídeo.");
    }

    private double probeLongestVisualFreeze(Path source, double containerDuration) throws Exception {
        // A validação anterior só verificava se existiam timestamps até o fim. Quando
        // o encoder repete o mesmo quadro congelado com timestamps novos, isso passa
        // despercebido. O freezedetect compara o conteúdo visual dos quadros.
        Process process = new ProcessBuilder(
                "ffmpeg", "-nostdin", "-hide_banner", "-nostats", "-v", "info",
                "-i", source.toAbsolutePath().toString(),
                "-an",
                "-vf", "scale=160:-2,fps=2,freezedetect=n=0.0001:d=4",
                "-f", "null", "-"
        ).redirectErrorStream(true).start();

        if (!process.waitFor(45, TimeUnit.SECONDS)) {
            process.destroyForcibly();
            throw new IllegalStateException("ffmpeg excedeu o tempo de análise de congelamento do vídeo.");
        }

        String output;
        try (InputStream input = process.getInputStream()) {
            output = new String(input.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
        }
        if (process.exitValue() != 0) {
            throw new IllegalStateException("ffmpeg não conseguiu analisar os quadros do vídeo.");
        }

        double longest = 0d;
        double openFreezeStart = -1d;
        for (String line : output.split("\\R")) {
            Matcher startMatcher = FREEZE_START_PATTERN.matcher(line);
            if (startMatcher.find()) {
                try {
                    openFreezeStart = Double.parseDouble(startMatcher.group(1));
                } catch (NumberFormatException ignored) {
                    openFreezeStart = -1d;
                }
            }

            Matcher durationMatcher = FREEZE_DURATION_PATTERN.matcher(line);
            if (durationMatcher.find()) {
                try {
                    longest = Math.max(longest, Double.parseDouble(durationMatcher.group(1)));
                } catch (NumberFormatException ignored) {
                    // Continua procurando outras ocorrências.
                }
            }

            Matcher endMatcher = FREEZE_END_PATTERN.matcher(line);
            if (endMatcher.find()) {
                openFreezeStart = -1d;
            }
        }

        // Se o último congelamento alcança o EOF, algumas versões do ffmpeg não
        // emitem freeze_duration/freeze_end. Não podemos ignorar essa cauda só
        // porque houve outro congelamento menor anteriormente no mesmo arquivo.
        if (openFreezeStart >= 0d && containerDuration > openFreezeStart) {
            longest = Math.max(longest, containerDuration - openFreezeStart);
        }
        return longest;
    }

    private void transcodeVideo(Path source, Path destination, double durationSeconds, long targetBytes) throws Exception {
        // Reserva margem para container/metadata e áudio. O objetivo é ficar
        // confortavelmente abaixo de 15 MB em vez de encostar no limite.
        long targetTotalBps = Math.max(120_000L, (long) Math.floor((targetBytes * 8.0) / durationSeconds));
        long audioBps = Math.min(32_000L, Math.max(20_000L, targetTotalBps / 8));
        long videoBps = Math.max(80_000L, targetTotalBps - audioBps - 20_000L);

        Process process = new ProcessBuilder(
                "ffmpeg", "-y", "-hide_banner", "-loglevel", "error",
                "-i", source.toAbsolutePath().toString(),
                "-vf", "scale=min(640\\,iw):-2",
                "-c:v", "libvpx",
                "-deadline", "realtime",
                "-cpu-used", "5",
                "-threads", "2",
                "-b:v", Long.toString(videoBps),
                "-maxrate", Long.toString(videoBps),
                "-bufsize", Long.toString(Math.max(160_000L, videoBps * 2)),
                "-pix_fmt", "yuv420p",
                "-c:a", "libopus",
                "-b:a", Long.toString(audioBps),
                "-ac", "1",
                "-ar", "24000",
                destination.toAbsolutePath().toString()
        ).redirectErrorStream(true).start();

        String errorText;
        try (InputStream input = process.getInputStream()) {
            errorText = new String(input.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
        }
        int exit = process.waitFor();
        if (exit != 0 || !Files.exists(destination) || Files.size(destination) <= 0) {
            throw new IllegalStateException("ffmpeg falhou ao compactar o vídeo: " + errorText.trim());
        }
    }

    private String videoSourceExtension(String contentType) {
        String type = contentType == null ? "" : contentType.toLowerCase(Locale.ROOT);
        return switch (type) {
            case "video/quicktime" -> ".mov";
            case "video/webm" -> ".webm";
            case "video/3gpp" -> ".3gp";
            case "video/x-m4v" -> ".m4v";
            default -> ".mp4";
        };
    }

    private void validateStorageLimit(InspectionAssetType type, String contentType, long fileSize) {
        String normalizedType = normalizeContentType(contentType);
        boolean videoContent = normalizedType != null && normalizedType.startsWith("video/");
        if (type == InspectionAssetType.VIDEO || videoContent) {
            return;
        }
        if (fileSize > MAX_VIDEO_BYTES) {
            throw new IllegalArgumentException("Cada foto ou documento enviado deve possuir no máximo 15 MB.");
        }
    }

    @Transactional(readOnly = true)
    public boolean contentExists(UUID assetId) {
        Boolean exists = jdbcTemplate.queryForObject(
                """
                select (
                    exists (
                        select 1
                          from inspection_asset_blobs blob
                         where blob.asset_id = ?
                           and blob.status = 'COMPLETE'
                           and blob.total_chunks = (
                               select count(*) from inspection_asset_blob_chunks chunk where chunk.blob_id = blob.id
                           )
                           and blob.total_size = (
                               select coalesce(sum(chunk.chunk_size), 0) from inspection_asset_blob_chunks chunk where chunk.blob_id = blob.id
                           )
                    )
                    or exists (
                        select 1 from inspection_asset_contents legacy where legacy.asset_id = ?
                    )
                )
                """,
                Boolean.class,
                assetId,
                assetId
        );
        return Boolean.TRUE.equals(exists);
    }

    private InspectionAsset storeStreamInternal(
            InspectionRequest request,
            InspectionAssetType type,
            String label,
            String fileName,
            String contentType,
            long fileSize,
            int sortOrder,
            InputStream input
    ) throws IOException {
        if (fileSize <= 0) throw new IllegalArgumentException("O arquivo recebido está vazio.");
        validateStorageLimit(type, contentType, fileSize);
        Optional<InspectionAsset> existing = findSlotAsset(request, type, sortOrder);
        if (existing.isPresent() && isAvailable(existing.get())) {
            return existing.get();
        }
        removeUnavailableSlotAsset(request, type, sortOrder);
        // Um envio retomável abandonado pode ocupar o mesmo slot mesmo sem haver
        // InspectionAsset. A persistência direta deve substituir essa sessão antiga.
        deleteBlobSlot(request.getId(), type, sortOrder);

        int totalChunks = Math.toIntExact((fileSize + DIRECT_CHUNK_BYTES - 1L) / DIRECT_CHUNK_BYTES);
        UUID blobId = UUID.randomUUID();
        insertBlob(
                blobId, request.getId(), type, sortOrder,
                "direct-" + UUID.randomUUID(), label, fileName,
                normalizeContentType(contentType), fileSize, totalChunks
        );

        long remaining = fileSize;
        int chunkIndex = 0;
        while (remaining > 0) {
            int requested = (int) Math.min(DIRECT_CHUNK_BYTES, remaining);
            byte[] bytes = input.readNBytes(requested);
            if (bytes.length != requested) {
                throw new IllegalArgumentException("O arquivo recebido está incompleto.");
            }
            insertOrReplaceChunk(blobId, chunkIndex, bytes);
            remaining -= bytes.length;
            chunkIndex++;
        }
        if (input.read() != -1) {
            throw new IllegalArgumentException("O tamanho informado não corresponde ao arquivo recebido.");
        }

        BlobProgress progress = blobProgress(blobId);
        if (!progress.complete(totalChunks, fileSize)) {
            throw new IllegalStateException("O conteúdo do arquivo não foi integralmente persistido no PostgreSQL.");
        }

        InspectionAsset asset = createAssetMetadata(
                request, type, label, fileName, normalizeContentType(contentType), fileSize, sortOrder
        );
        int updated = jdbcTemplate.update(
                """
                update inspection_asset_blobs
                   set asset_id = ?, status = 'COMPLETE', completed_at = now(), updated_at = now()
                 where id = ? and status = 'UPLOADING'
                """,
                asset.getId(),
                blobId
        );
        if (updated != 1 || !contentExists(asset.getId())) {
            throw new IllegalStateException("O arquivo não pôde ser confirmado no PostgreSQL.");
        }
        log.info(
                "Retrato NH: arquivo direto confirmado no PostgreSQL inspectionId={} assetId={} type={} order={} bytes={} chunks={}",
                request.getId(), asset.getId(), type, sortOrder, fileSize, totalChunks
        );
        return asset;
    }

    private InspectionAsset createAssetMetadata(
            InspectionRequest request,
            InspectionAssetType type,
            String label,
            String fileName,
            String contentType,
            long fileSize,
            int sortOrder
    ) {
        OffsetDateTime storedAt = OffsetDateTime.now();
        // V83.35: arquivos confirmados permanecem junto à vistoria e não possuem
        // mais expiração automática por idade.
        OffsetDateTime expiresAt = null;
        InspectionAsset asset = InspectionAsset.createDatabase(
                request,
                type,
                cleanLabel(label),
                cleanFileName(fileName),
                normalizeContentType(contentType),
                fileSize,
                sortOrder,
                storedAt,
                expiresAt
        );
        request.addAsset(asset);
        entityManager.persist(asset);
        entityManager.flush();
        return asset;
    }

    private BlobRow lockOrCreateBlob(
            UUID inspectionId,
            InspectionAssetType type,
            int sortOrder,
            String uploadId,
            String label,
            String fileName,
            String contentType,
            long totalSize,
            int totalChunks
    ) {
        List<BlobRow> rows = jdbcTemplate.query(
                """
                select id, upload_id, total_size, total_chunks, status, asset_id
                  from inspection_asset_blobs
                 where inspection_id = ?
                   and asset_type = ?
                   and sort_order = ?
                 for update
                """,
                (resultSet, rowNum) -> new BlobRow(
                        resultSet.getObject("id", UUID.class),
                        resultSet.getString("upload_id"),
                        resultSet.getLong("total_size"),
                        resultSet.getInt("total_chunks"),
                        resultSet.getString("status"),
                        resultSet.getObject("asset_id", UUID.class)
                ),
                inspectionId,
                type.name(),
                sortOrder
        );

        if (!rows.isEmpty()) {
            BlobRow current = rows.getFirst();
            boolean sameUpload = current.uploadId().equals(uploadId)
                    && current.totalSize() == totalSize
                    && current.totalChunks() == totalChunks
                    && "UPLOADING".equals(current.status());
            if (sameUpload) return current;
            jdbcTemplate.update("delete from inspection_asset_blobs where id = ?", current.id());
        }

        UUID id = UUID.randomUUID();
        insertBlob(
                id, inspectionId, type, sortOrder, uploadId, label, fileName,
                normalizeContentType(contentType), totalSize, totalChunks
        );
        return new BlobRow(id, uploadId, totalSize, totalChunks, "UPLOADING", null);
    }

    private boolean completedBlobMatches(
            UUID inspectionId,
            InspectionAssetType type,
            int sortOrder,
            String uploadId,
            long totalSize,
            int totalChunks
    ) {
        Integer matches = jdbcTemplate.queryForObject(
                """
                select count(*)
                  from inspection_asset_blobs
                 where inspection_id = ?
                   and asset_type = ?
                   and sort_order = ?
                   and upload_id = ?
                   and total_size = ?
                   and total_chunks = ?
                   and status = 'COMPLETE'
                """,
                Integer.class,
                inspectionId, type.name(), sortOrder, uploadId, totalSize, totalChunks
        );
        return matches != null && matches > 0;
    }

    private void deleteBlobSlot(UUID inspectionId, InspectionAssetType type, int sortOrder) {
        jdbcTemplate.update(
                "delete from inspection_asset_blobs where inspection_id = ? and asset_type = ? and sort_order = ?",
                inspectionId,
                type.name(),
                sortOrder
        );
    }

    private void insertBlob(
            UUID id,
            UUID inspectionId,
            InspectionAssetType type,
            int sortOrder,
            String uploadId,
            String label,
            String fileName,
            String contentType,
            long totalSize,
            int totalChunks
    ) {
        jdbcTemplate.update(
                """
                insert into inspection_asset_blobs(
                    id, inspection_id, asset_type, sort_order, upload_id,
                    label, file_name, content_type, total_size, total_chunks,
                    status, created_at, updated_at
                ) values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, 'UPLOADING', now(), now())
                """,
                id,
                inspectionId,
                type.name(),
                sortOrder,
                uploadId,
                cleanLabel(label),
                cleanFileName(fileName),
                contentType,
                totalSize,
                totalChunks
        );
    }

    private void insertOrReplaceChunk(UUID blobId, int chunkIndex, byte[] bytes) {
        jdbcTemplate.update(
                """
                insert into inspection_asset_blob_chunks(
                    blob_id, chunk_index, chunk_size, chunk_sha256, chunk_data, created_at
                ) values (?, ?, ?, ?, ?, now())
                on conflict (blob_id, chunk_index) do update
                    set chunk_size = excluded.chunk_size,
                        chunk_sha256 = excluded.chunk_sha256,
                        chunk_data = excluded.chunk_data,
                        created_at = now()
                """,
                blobId,
                chunkIndex,
                bytes.length,
                sha256(bytes),
                bytes
        );
    }

    private void touchBlob(UUID blobId) {
        jdbcTemplate.update("update inspection_asset_blobs set updated_at = now() where id = ?", blobId);
    }

    private BlobProgress blobProgress(UUID blobId) {
        return jdbcTemplate.queryForObject(
                """
                select count(*)::int as received_chunks,
                       coalesce(sum(chunk_size), 0)::bigint as received_bytes,
                       coalesce(min(chunk_index), -1)::int as min_index,
                       coalesce(max(chunk_index), -1)::int as max_index
                  from inspection_asset_blob_chunks
                 where blob_id = ?
                """,
                (resultSet, rowNum) -> new BlobProgress(
                        resultSet.getInt("received_chunks"),
                        resultSet.getLong("received_bytes"),
                        resultSet.getInt("min_index"),
                        resultSet.getInt("max_index")
                ),
                blobId
        );
    }

    private Optional<InspectionAsset> findSlotAsset(
            InspectionRequest request,
            InspectionAssetType type,
            int sortOrder
    ) {
        if (request == null || request.getAssets() == null) return Optional.empty();
        return request.getAssets().stream()
                .filter(asset -> asset.getAssetType() == type && asset.getSortOrder() == sortOrder)
                .findFirst();
    }

    private void removeUnavailableSlotAsset(
            InspectionRequest request,
            InspectionAssetType type,
            int sortOrder
    ) {
        List<InspectionAsset> stale = new ArrayList<>(request.getAssets().stream()
                .filter(asset -> asset.getAssetType() == type && asset.getSortOrder() == sortOrder)
                .filter(asset -> !isAvailable(asset))
                .toList());
        if (stale.isEmpty()) return;
        for (InspectionAsset asset : stale) {
            request.removeAsset(asset);
        }
        entityManager.flush();
    }

    private void streamChunkedBlob(UUID blobId, OutputStream output) {
        final int[] count = {0};
        jdbcTemplate.query(
                """
                select chunk_data
                  from inspection_asset_blob_chunks
                 where blob_id = ?
                 order by chunk_index
                """,
                statement -> statement.setObject(1, blobId),
                resultSet -> {
                    while (resultSet.next()) {
                        count[0]++;
                        try (InputStream input = resultSet.getBinaryStream(1)) {
                            input.transferTo(output);
                        } catch (IOException exception) {
                            throw new IllegalStateException("Não foi possível transferir o conteúdo do arquivo.", exception);
                        }
                    }
                    return null;
                }
        );
        if (count[0] == 0) throw new IllegalArgumentException("Conteúdo do arquivo não encontrado.");
    }

    private void streamLegacyContent(UUID assetId, OutputStream output) {
        jdbcTemplate.query(
                "select file_data from inspection_asset_contents where asset_id = ?",
                statement -> statement.setObject(1, assetId),
                resultSet -> {
                    if (!resultSet.next()) {
                        throw new IllegalArgumentException("Conteúdo do arquivo não encontrado.");
                    }
                    try (InputStream input = resultSet.getBinaryStream(1)) {
                        input.transferTo(output);
                    } catch (IOException exception) {
                        throw new IllegalStateException("Não foi possível transferir o conteúdo do arquivo.", exception);
                    }
                    return null;
                }
        );
    }

    private String uniqueZipName(String requested, Set<String> usedNames) {
        String clean = requested == null || requested.isBlank()
                ? "arquivo"
                : requested.replaceAll("[\\/:*?\"<>|]", "-");
        if (usedNames.add(clean)) return clean;
        int dot = clean.lastIndexOf('.');
        String base = dot > 0 ? clean.substring(0, dot) : clean;
        String extension = dot > 0 ? clean.substring(dot) : "";
        int index = 2;
        while (!usedNames.add(base + "-" + index + extension)) index++;
        return base + "-" + index + extension;
    }

    private String cleanLabel(String value) {
        String clean = value == null ? "Arquivo" : value.trim().replaceAll("\\s+", " ");
        if (clean.isBlank()) clean = "Arquivo";
        return clean.length() > 140 ? clean.substring(0, 140) : clean;
    }

    private String cleanFileName(String value) {
        String clean = value == null ? "arquivo.bin" : value.trim().replaceAll("[\\/:*?\"<>|]", "-");
        if (clean.isBlank()) clean = "arquivo.bin";
        return clean.length() > 220 ? clean.substring(0, 220) : clean;
    }

    private String normalizeContentType(String value) {
        String clean = value == null ? "application/octet-stream" : value.toLowerCase(Locale.ROOT).split(";", 2)[0].trim();
        return clean.isBlank() ? "application/octet-stream" : clean;
    }

    private String sha256(byte[] bytes) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (Exception exception) {
            throw new IllegalStateException("Não foi possível validar a parte do arquivo.", exception);
        }
    }

    public record ChunkUploadStatus(boolean complete, List<Integer> receivedChunks) {}

    public record ChunkStoreResult(boolean complete, int receivedChunks, InspectionAsset asset) {}

    private record BlobRow(
            UUID id,
            String uploadId,
            long totalSize,
            int totalChunks,
            String status,
            UUID assetId
    ) {}

    private record BlobProgress(
            int receivedChunks,
            long receivedBytes,
            int minIndex,
            int maxIndex
    ) {
        boolean complete(int expectedChunks, long expectedBytes) {
            return receivedChunks == expectedChunks
                    && receivedBytes == expectedBytes
                    && minIndex == 0
                    && maxIndex == expectedChunks - 1;
        }
    }
}
