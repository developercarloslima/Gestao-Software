package br.com.nh.cotacao.controller;

import br.com.nh.cotacao.dto.InspectionDtos.*;
import br.com.nh.cotacao.dto.ContractChangeDtos.*;
import br.com.nh.cotacao.entity.InspectionAssetType;
import br.com.nh.cotacao.security.PortalPrincipal;
import br.com.nh.cotacao.service.InspectionResumableUploadService;
import br.com.nh.cotacao.service.InspectionDigitalAcceptanceService;
import br.com.nh.cotacao.service.PortalUserService;
import br.com.nh.cotacao.service.RetratoService;
import br.com.nh.cotacao.service.InspectionContractChangeService;
import jakarta.validation.Valid;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.util.List;

@RestController
public class RetratoController {
    private final RetratoService service;
    private final InspectionResumableUploadService resumableUploadService;
    private final PortalUserService portalUserService;
    private final InspectionDigitalAcceptanceService digitalAcceptanceService;
    private final InspectionContractChangeService contractChangeService;

    public RetratoController(
            RetratoService service,
            InspectionResumableUploadService resumableUploadService,
            PortalUserService portalUserService,
            InspectionDigitalAcceptanceService digitalAcceptanceService,
            InspectionContractChangeService contractChangeService
    ) {
        this.service = service;
        this.resumableUploadService = resumableUploadService;
        this.portalUserService = portalUserService;
        this.digitalAcceptanceService = digitalAcceptanceService;
        this.contractChangeService = contractChangeService;
    }

    @PostMapping("/api/inspections")
    @ResponseStatus(HttpStatus.CREATED)
    public InspectionResponse create(
            @Valid @RequestBody CreateInspectionRequest request, Authentication auth
    ) {
        PortalPrincipal principal = (PortalPrincipal) auth.getPrincipal();
        portalUserService.assertConsultantAccess(principal.username(), principal.role(), request.consultantId());
        return service.create(request);
    }

    @GetMapping("/api/public/inspections/{token}")
    public InspectionResponse publicGet(@PathVariable String token) {
        return service.publicGet(token);
    }

    @GetMapping("/api/public/inspections/{token}/contract-change")
    public PublicContractChangeResponse publicContractChange(@PathVariable String token) {
        return contractChangeService.publicStatus(token);
    }

    @PostMapping("/api/public/inspections/{token}/contract-change/decision")
    public ContractChangeDecisionResponse publicContractChangeDecision(
            @PathVariable String token,
            @Valid @RequestBody ConfirmContractChangeRequest request
    ) {
        return contractChangeService.decide(token, request.accepted(), request.keepOptionals());
    }

    @PostMapping(value = "/api/public/inspections/{token}/upload", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public InspectionUploadResponse upload(
            @PathVariable String token,
            @RequestParam(value = "photos", required = false) List<MultipartFile> photos,
            @RequestParam(value = "labels", required = false) List<String> labels,
            @RequestParam("video") MultipartFile video,
            @RequestParam(value = "videoDurationSeconds", required = false) Double videoDurationSeconds,
            @RequestParam(value = "residenceAddress", required = false) String residenceAddress,
            @RequestParam(value = "preexistingDamage", required = false) Boolean preexistingDamage,
            @RequestParam(value = "preexistingDamageDetails", required = false) String preexistingDamageDetails,
            @RequestParam(value = "signature", required = false) MultipartFile signature,
            @RequestParam(value = "vehicleDocument", required = false) MultipartFile vehicleDocument,
            @RequestParam(value = "identityDocumentFront", required = false) MultipartFile identityDocumentFront,
            @RequestParam(value = "identityDocumentBack", required = false) MultipartFile identityDocumentBack,
            @RequestParam(value = "additionalFiles", required = false) List<MultipartFile> additionalFiles,
            @RequestParam(value = "additionalLabels", required = false) List<String> additionalLabels
    ) {
        return service.upload(
                token, photos, labels, video, videoDurationSeconds, residenceAddress, preexistingDamage, preexistingDamageDetails, signature,
                vehicleDocument, identityDocumentFront, identityDocumentBack, additionalFiles, additionalLabels
        );
    }

    @GetMapping("/api/public/inspections/{token}/upload-chunk-status")
    public ChunkUploadStatusResponse uploadChunkStatus(
            @PathVariable String token,
            @RequestParam InspectionAssetType assetType,
            @RequestParam int sortOrder,
            @RequestParam String uploadId,
            @RequestParam int totalChunks
    ) {
        return resumableUploadService.chunkStatus(token, assetType, sortOrder, uploadId, totalChunks);
    }

    @PostMapping(value = "/api/public/inspections/{token}/upload-chunk", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ChunkUploadResponse uploadChunk(
            @PathVariable String token,
            @RequestParam InspectionAssetType assetType,
            @RequestParam int sortOrder,
            @RequestParam String label,
            @RequestParam String uploadId,
            @RequestParam int chunkIndex,
            @RequestParam int totalChunks,
            @RequestParam long totalSize,
            @RequestParam String contentType,
            @RequestParam(value = "videoDurationSeconds", required = false) Double videoDurationSeconds,
            @RequestParam("chunk") MultipartFile chunk
    ) {
        return resumableUploadService.uploadChunk(
                token,
                assetType,
                sortOrder,
                label,
                uploadId,
                chunkIndex,
                totalChunks,
                totalSize,
                contentType,
                videoDurationSeconds,
                chunk
        );
    }

    @PostMapping(value = "/api/public/inspections/{token}/upload-chunk-raw", consumes = MediaType.APPLICATION_OCTET_STREAM_VALUE)
    public ChunkUploadResponse uploadChunkRaw(
            @PathVariable String token,
            @RequestParam InspectionAssetType assetType,
            @RequestParam int sortOrder,
            @RequestParam String label,
            @RequestParam String uploadId,
            @RequestParam int chunkIndex,
            @RequestParam int totalChunks,
            @RequestParam long totalSize,
            @RequestParam int chunkSize,
            @RequestParam String contentType,
            @RequestParam(value = "videoDurationSeconds", required = false) Double videoDurationSeconds,
            @RequestBody byte[] chunkBytes
    ) {
        return resumableUploadService.uploadChunkRaw(
                token,
                assetType,
                sortOrder,
                label,
                uploadId,
                chunkIndex,
                totalChunks,
                totalSize,
                chunkSize,
                contentType,
                videoDurationSeconds,
                chunkBytes
        );
    }

    @PostMapping("/api/public/inspections/{token}/finalize-upload")
    public InspectionUploadResponse finalizeUpload(
            @PathVariable String token,
            @RequestBody(required = false) FinishInspectionUploadRequest request
    ) {
        return resumableUploadService.finalizeUpload(
                token,
                request == null ? null : request.residenceAddress(),
                request == null ? null : request.preexistingDamage(),
                request == null ? null : request.preexistingDamageDetails()
        );
    }

    @GetMapping("/api/public/inspections/{token}/digital-acceptance")
    public DigitalAcceptanceStatusResponse digitalAcceptanceStatus(@PathVariable String token) {
        return digitalAcceptanceService.status(token);
    }

    @PostMapping("/api/public/inspections/{token}/digital-acceptance/registration-options")
    public WebAuthnRegistrationOptionsResponse webAuthnRegistrationOptions(
            @PathVariable String token,
            @Valid @RequestBody DeviceMetadata device,
            HttpServletRequest httpRequest
    ) {
        return digitalAcceptanceService.beginRegistration(token, device, httpRequest);
    }

    @PostMapping("/api/public/inspections/{token}/digital-acceptance/registration-finish")
    public WebAuthnAssertionOptionsResponse webAuthnRegistrationFinish(
            @PathVariable String token,
            @Valid @RequestBody WebAuthnRegistrationFinishRequest request
    ) {
        return digitalAcceptanceService.finishRegistration(token, request);
    }

    @PostMapping("/api/public/inspections/{token}/digital-acceptance/assertion-options")
    public WebAuthnAssertionOptionsResponse webAuthnAssertionOptions(@PathVariable String token) {
        return digitalAcceptanceService.assertionOptions(token);
    }

    @PostMapping("/api/public/inspections/{token}/digital-acceptance/assertion-finish")
    public DigitalAcceptanceStatusResponse webAuthnAssertionFinish(
            @PathVariable String token,
            @Valid @RequestBody WebAuthnAssertionFinishRequest request
    ) {
        return digitalAcceptanceService.finishAssertion(token, request);
    }

}
