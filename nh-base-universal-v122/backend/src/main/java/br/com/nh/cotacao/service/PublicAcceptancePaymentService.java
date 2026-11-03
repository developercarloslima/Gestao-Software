package br.com.nh.cotacao.service;

import br.com.nh.cotacao.entity.*;
import br.com.nh.cotacao.repository.CommercialVehicleRepository;
import br.com.nh.cotacao.repository.FinancialChargeRepository;
import br.com.nh.cotacao.repository.InspectionRequestRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDate;

/** Uma cobrança existente por veículo: jamais criar outra cobrança numa consulta pública. */
@Service
public class PublicAcceptancePaymentService {
    private final InspectionRequestRepository inspections;
    private final CommercialVehicleRepository vehicles;
    private final FinancialChargeRepository charges;
    private final FinancialProviderGateway provider;

    public PublicAcceptancePaymentService(InspectionRequestRepository inspections,
            CommercialVehicleRepository vehicles, FinancialChargeRepository charges,
            FinancialProviderGateway provider) {
        this.inspections = inspections;
        this.vehicles = vehicles;
        this.charges = charges;
        this.provider = provider;
    }

    public record PaymentView(boolean available, boolean providerConfigured, String status,
            BigDecimal amount, LocalDate dueDate, String invoiceUrl, String bankSlipUrl,
            String identificationField, String barCode, String pixPayload, String pixEncodedImage,
            boolean futureInvoicesAvailable, String message) {}

    @Transactional(readOnly = true)
    public PaymentView findAdhesion(String token) {
        if (token == null || token.isBlank()) throw new IllegalArgumentException("Link inválido.");
        InspectionRequest inspection = inspections.findByPublicToken(token)
                .orElseThrow(() -> new IllegalArgumentException("Link de vistoria não encontrado."));
        if (inspection.getAcceptedAt() == null || inspection.getStatus() != InspectionRequestStatus.APPROVED) {
            throw new IllegalArgumentException("O aceite digital precisa ser confirmado antes de consultar o pagamento.");
        }
        boolean configured = provider.enabled();
        if (inspection.getQuotation() == null) {
            return unavailable(configured, "Este cadastro não possui cotação vinculada. Solicite a cobrança ao Financeiro.");
        }
        CommercialVehicle vehicle = vehicles.findByQuotation_Id(inspection.getQuotation().getId()).orElse(null);
        if (vehicle == null || vehicle.getAssociate() == null
                || !java.util.Objects.equals(Associate.normalizeDocument(inspection.getCpf()), vehicle.getAssociate().getDocument())) {
            return unavailable(configured, "A cobrança deste veículo ainda não está vinculada ao associado. Consulte o Financeiro.");
        }
        FinancialCharge charge = charges.findFirstByVehicle_IdAndChargeTypeOrderByCreatedAtAsc(
                vehicle.getId(), FinancialChargeType.ADHESION).orElse(null);
        if (charge == null) {
            return unavailable(configured, configured
                    ? "A adesão ainda não foi emitida. Solicite a geração ao Financeiro."
                    : "A financeira ainda não foi configurada nesta empresa.");
        }
        boolean futureInvoices = configured && charges.findAllByVehicle_IdOrderByDueDateDescCreatedAtDesc(vehicle.getId())
                .stream().anyMatch(other -> other.getChargeType() == FinancialChargeType.MONTHLY
                        && other.getProviderPaymentId() != null
                        && other.getDueDate() != null && other.getDueDate().isAfter(LocalDate.now()));
        boolean received = charge.getStatus() == FinancialChargeStatus.RECEIVED;
        boolean payable = (charge.getStatus() == FinancialChargeStatus.PENDING
                    || charge.getStatus() == FinancialChargeStatus.CONFIRMED)
                && (charge.getDueDate() == null || !charge.getDueDate().isBefore(LocalDate.now()))
                && configured && charge.getProviderPaymentId() != null;
        String message = received ? "A adesão já consta como paga."
                : charge.getStatus() == FinancialChargeStatus.OVERDUE
                        || charge.getDueDate() != null && charge.getDueDate().isBefore(LocalDate.now())
                            ? "Esta cobrança venceu. Solicite uma via atualizada ao Financeiro."
                : !configured ? "A integração financeira desta empresa ainda não está disponível."
                : !payable ? "Cobrança aguardando emissão pelo Asaas. Consulte o Financeiro."
                : "Pague por Pix ou boleto usando os dados abaixo.";
        return new PaymentView(payable, configured, charge.getStatus().name(),
                charge.getGrossValue(), charge.getDueDate(),
                payable ? httpsOnly(charge.getInvoiceUrl()) : null,
                payable ? httpsOnly(charge.getBankSlipUrl()) : null,
                payable ? charge.getIdentificationField() : null,
                payable ? charge.getBarCode() : null,
                payable ? charge.getPixPayload() : null,
                payable ? charge.getPixEncodedImage() : null,
                futureInvoices, message);
    }

    private PaymentView unavailable(boolean configured, String message) {
        return new PaymentView(false, configured, "NOT_ISSUED", null, null,
                null, null, null, null, null, null, false, message);
    }

    private String httpsOnly(String link) {
        return link != null && link.startsWith("https://") ? link : null;
    }
}
