package br.com.nh.cotacao.service;

import br.com.nh.cotacao.dto.QuoteDtos.InspectionUploadResponse;
import br.com.nh.cotacao.entity.InspectionPhoto;
import br.com.nh.cotacao.entity.Quotation;
import br.com.nh.cotacao.entity.QuoteStatus;
import br.com.nh.cotacao.repository.QuotationRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.web.multipart.MultipartFile;

import javax.imageio.ImageIO;
import java.io.ByteArrayInputStream;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;

@Service
public class InspectionService {

    private static final int REQUIRED_PHOTO_COUNT = 9;
    private static final long MAX_PHOTO_BYTES = 15L * 1024 * 1024;
    private static final Set<String> ALLOWED_CONTENT_TYPES = Set.of("image/jpeg", "image/png");

    private final QuoteService quoteService;
    private final QuotationRepository quotationRepository;
    private final GoogleDriveStorageService driveStorage;
    private final QuotePdfService pdfService;

    public InspectionService(
            QuoteService quoteService,
            QuotationRepository quotationRepository,
            GoogleDriveStorageService driveStorage,
            QuotePdfService pdfService
    ) {
        this.quoteService = quoteService;
        this.quotationRepository = quotationRepository;
        this.driveStorage = driveStorage;
        this.pdfService = pdfService;
    }

    @Transactional
    public InspectionUploadResponse upload(UUID quoteId, List<MultipartFile> photos, List<String> labels) {
        Quotation quotation = quoteService.find(quoteId);
        if (quotation.getStatus() != QuoteStatus.ACCEPTED) {
            throw new IllegalArgumentException("A proposta precisa estar aceita antes do envio da vistoria.");
        }

        validateFiles(photos, labels);
        List<PreparedPhoto> preparedPhotos = preparePhotos(photos, labels);
        List<String> oldPhotoIds = quotation.getInspectionPhotos().stream()
                .map(InspectionPhoto::getDriveFileId)
                .toList();
        String oldPdfId = quotation.getDrivePdfFileId();

        GoogleDriveStorageService.DriveFolder folder = driveStorage.ensureQuotationFolder(quotation);
        quotation.registerDriveFolder(folder.id(), folder.url());

        List<UploadedPhoto> uploadedPhotos = new ArrayList<>();
        String newPdfId = null;
        try {
            for (PreparedPhoto prepared : preparedPhotos) {
                GoogleDriveStorageService.DriveFile uploaded = driveStorage.upload(
                        folder.id(),
                        prepared.fileName(),
                        prepared.contentType(),
                        prepared.bytes()
                );
                uploadedPhotos.add(new UploadedPhoto(prepared, uploaded));
            }

            quotation.replaceInspectionPhotos();
            for (UploadedPhoto uploaded : uploadedPhotos) {
                PreparedPhoto prepared = uploaded.prepared();
                quotation.addInspectionPhoto(
                        prepared.label(),
                        prepared.fileName(),
                        prepared.contentType(),
                        prepared.bytes().length,
                        prepared.sortOrder(),
                        uploaded.driveFile().id(),
                        uploaded.driveFile().viewUrl()
                );
            }
            quotationRepository.saveAndFlush(quotation);

            byte[] professionalPdf = pdfService.generate(quotation);
            GoogleDriveStorageService.DriveFile pdf = driveStorage.upload(
                    folder.id(),
                    "Cotação e vistoria - " + quotation.getQuoteNumber() + ".pdf",
                    "application/pdf",
                    professionalPdf
            );
            newPdfId = pdf.id();
            quotation.completeInspection(pdf.id(), pdf.viewUrl());
            quotationRepository.saveAndFlush(quotation);

            registerDriveCleanupAfterTransaction(
                    oldPhotoIds,
                    oldPdfId,
                    uploadedPhotos.stream().map(item -> item.driveFile().id()).toList(),
                    pdf.id()
            );

            var response = quoteService.toResponse(quotation);
            return new InspectionUploadResponse(
                    response,
                    quotation.getDriveFolderUrl(),
                    quotation.getDrivePdfUrl(),
                    quoteService.publicPdfUrl(quotation),
                    response.teamWhatsappUrl(),
                    response.clientWhatsappUrl()
            );
        } catch (RuntimeException exception) {
            uploadedPhotos.forEach(item -> driveStorage.deleteQuietly(item.driveFile().id()));
            driveStorage.deleteQuietly(newPdfId);
            throw exception;
        }
    }

    private void registerDriveCleanupAfterTransaction(
            List<String> oldPhotoIds,
            String oldPdfId,
            List<String> newPhotoIds,
            String newPdfId
    ) {
        if (!TransactionSynchronizationManager.isSynchronizationActive()) {
            oldPhotoIds.forEach(driveStorage::deleteQuietly);
            driveStorage.deleteQuietly(oldPdfId);
            return;
        }

        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCommit() {
                oldPhotoIds.forEach(driveStorage::deleteQuietly);
                driveStorage.deleteQuietly(oldPdfId);
            }

            @Override
            public void afterCompletion(int status) {
                if (status != TransactionSynchronization.STATUS_COMMITTED) {
                    newPhotoIds.forEach(driveStorage::deleteQuietly);
                    driveStorage.deleteQuietly(newPdfId);
                }
            }
        });
    }

    private List<PreparedPhoto> preparePhotos(List<MultipartFile> photos, List<String> labels) {
        List<PreparedPhoto> prepared = new ArrayList<>(photos.size());
        for (int index = 0; index < photos.size(); index++) {
            MultipartFile photo = photos.get(index);
            String label = labels.get(index).trim();
            byte[] bytes = readAndValidateImage(photo);
            String contentType = normalizedContentType(photo.getContentType());
            String extension = contentType.equals("image/png") ? ".png" : ".jpg";
            String fileName = String.format(Locale.ROOT, "%02d - %s%s", index + 1, sanitizeFilePart(label), extension);
            prepared.add(new PreparedPhoto(label, fileName, contentType, bytes, index + 1));
        }
        return prepared;
    }

    private void validateFiles(List<MultipartFile> photos, List<String> labels) {
        if (photos == null || labels == null
                || photos.size() != REQUIRED_PHOTO_COUNT
                || labels.size() != REQUIRED_PHOTO_COUNT) {
            throw new IllegalArgumentException("Envie as 9 fotos obrigatórias da vistoria, incluindo a selfie do associado em frente ao veículo.");
        }
        Set<String> uniqueLabels = new HashSet<>();
        for (int index = 0; index < photos.size(); index++) {
            MultipartFile photo = photos.get(index);
            String label = labels.get(index);
            if (photo == null || photo.isEmpty()) {
                throw new IllegalArgumentException("A foto " + (index + 1) + " não foi enviada.");
            }
            if (photo.getSize() > MAX_PHOTO_BYTES) {
                throw new IllegalArgumentException("A foto " + (index + 1) + " ultrapassa o limite de 15 MB.");
            }
            String contentType = normalizedContentType(photo.getContentType());
            if (!ALLOWED_CONTENT_TYPES.contains(contentType)) {
                throw new IllegalArgumentException("Use somente fotos JPG ou PNG.");
            }
            if (label == null || label.isBlank() || label.length() > 120) {
                throw new IllegalArgumentException("A descrição da foto " + (index + 1) + " é inválida.");
            }
            if (!uniqueLabels.add(label.trim().toLowerCase(Locale.ROOT))) {
                throw new IllegalArgumentException("As descrições das fotos não podem se repetir.");
            }
        }
    }

    private byte[] readAndValidateImage(MultipartFile photo) {
        try {
            byte[] bytes = photo.getBytes();
            if (ImageIO.read(new ByteArrayInputStream(bytes)) == null) {
                throw new IllegalArgumentException("Um dos arquivos enviados não é uma imagem válida.");
            }
            return bytes;
        } catch (IllegalArgumentException exception) {
            throw exception;
        } catch (Exception exception) {
            throw new IllegalArgumentException("Não foi possível ler uma das fotos enviadas.", exception);
        }
    }

    private String normalizedContentType(String contentType) {
        if (contentType == null) {
            return "";
        }
        String normalized = contentType.toLowerCase(Locale.ROOT).trim();
        return normalized.equals("image/jpg") ? "image/jpeg" : normalized;
    }

    private String sanitizeFilePart(String value) {
        String clean = value
                .replaceAll("[\\\\/:*?\"<>|]", "-")
                .replaceAll("\\s+", " ")
                .trim();
        return clean.length() > 90 ? clean.substring(0, 90) : clean;
    }

    private record PreparedPhoto(
            String label,
            String fileName,
            String contentType,
            byte[] bytes,
            int sortOrder
    ) {
    }

    private record UploadedPhoto(
            PreparedPhoto prepared,
            GoogleDriveStorageService.DriveFile driveFile
    ) {
    }
}
