package br.com.nh.cotacao.service;

import br.com.nh.cotacao.entity.InspectionAsset;
import br.com.nh.cotacao.entity.InspectionAssetType;
import br.com.nh.cotacao.entity.InspectionRequest;
import br.com.nh.cotacao.repository.InspectionRequestRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.util.Comparator;
import java.util.UUID;

/**
 * A regeneracao do PDF precisa ser independente da gravacao da assinatura.
 * Uma falha no armazenamento do relatorio nao pode apagar a prova WebAuthn validada.
 */
@Service
public class AcceptedDossierRefreshService {
    private final InspectionRequestRepository repository;
    private final InspectionAssetStorageService storage;
    private final RetratoPdfService pdf;

    public AcceptedDossierRefreshService(InspectionRequestRepository repository,
                                         InspectionAssetStorageService storage,
                                         RetratoPdfService pdf) {
        this.repository = repository;
        this.storage = storage;
        this.pdf = pdf;
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void refresh(UUID id) {
        InspectionRequest request = repository.findById(id)
                .orElseThrow(() -> new IllegalArgumentException("Vistoria do aceite não encontrada."));
        if (request.getAcceptedAt() == null) {
            throw new IllegalStateException("A assinatura ainda não foi gravada no banco.");
        }
        InspectionAsset currentReport = request.getAssets().stream()
                .filter(a -> a.getAssetType() == InspectionAssetType.REPORT)
                .filter(storage::isAvailable)
                .max(Comparator.comparing(InspectionAsset::getStoredAt,
                        Comparator.nullsFirst(Comparator.naturalOrder())))
                .orElseThrow(() -> new IllegalArgumentException("Dossiê aprovado não encontrado."));
        byte[] acceptedDossier = pdf.generate(request);
        storage.replaceGeneratedReport(request,
                "Dossiê final com aceite digital WebAuthn",
                "dossie-final-aceite-digital-" + request.getId() + ".pdf",
                currentReport.getSortOrder(), acceptedDossier);
        repository.flush();
    }
}
