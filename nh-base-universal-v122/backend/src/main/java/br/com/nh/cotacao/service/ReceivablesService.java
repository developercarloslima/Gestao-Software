package br.com.nh.cotacao.service;

import br.com.nh.cotacao.entity.*;
import br.com.nh.cotacao.security.PortalRole;
import br.com.nh.cotacao.repository.CommercialVehicleRepository;
import br.com.nh.cotacao.repository.FinancialChargeRepository;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.math.BigDecimal;
import java.text.Normalizer;
import java.time.LocalDate;
import java.time.YearMonth;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

/**
 * Cobranças da empresa atual. A Base usa banco separado por empresa,
 * e ambos os endpoints são protegidos pelos papéis ADMIN / BUYER.
 */
@Service
public class ReceivablesService {
    private static final List<FinancialChargeStatus> OPEN = List.of(
            FinancialChargeStatus.PENDING, FinancialChargeStatus.OVERDUE);
    private final CommercialVehicleRepository vehicles;
    private final FinancialChargeRepository charges;
    private final FinancialProviderGateway provider;
    private final CommercialFinanceService finance;

    public ReceivablesService(CommercialVehicleRepository vehicles, FinancialChargeRepository charges,
                              FinancialProviderGateway provider, CommercialFinanceService finance) {
        this.vehicles = vehicles;
        this.charges = charges;
        this.provider = provider;
        this.finance = finance;
    }

    public record VehicleRow(UUID id, UUID associateId, String associateName, String whatsapp,
                             String plate, String model, String status, long overdueDays,
                             boolean agreementRequired) {}
    public record VehiclePage(List<VehicleRow> items, long total, int page, int pages,
                              boolean searched, LocalDate today) {}
    public record BillRow(UUID id, LocalDate dueDate, BigDecimal amount, String status,
                          long overdueDays, boolean issued, boolean projected,
                          String bankSlipUrl, String identificationField, String note) {}
    public record VehicleBills(VehicleRow vehicle, List<BillRow> bills, boolean hasOverdue,
                               boolean providerConfigured, String information) {}
    public record SecondCopyRequest(LocalDate newDueDate) {}
    public record SecondCopyResult(UUID chargeId, LocalDate dueDate, String status,
                                   String bankSlipUrl, String identificationField, String message) {}

    @Transactional(readOnly = true)
    public VehiclePage list(String query, int page, int size) {
        if (page < 0 || page > 100_000 || size < 1 || size > 50) {
            throw new IllegalArgumentException("Paginação inválida.");
        }
        String raw = query == null ? "" : query.trim();
        if (raw.length() > 100) throw new IllegalArgumentException("Busca deve conter até 100 caracteres.");
        boolean searched = !raw.isBlank();
        String name = Consultant.normalize(raw);
        String digits = raw.replaceAll("[^0-9]", "");
        String plate = raw.replaceAll("[^a-zA-Z0-9]", "").toLowerCase(Locale.ROOT);
        Page<CommercialVehicle> result = vehicles.receivables(
                CommercialVehicleStatus.DRAFT, searched, FinancialBillingType.BOLETO, OPEN,
                LocalDate.now(), contains(name), contains(digits), contains(plate),
                PageRequest.of(page, size, Sort.by("associate.name").ascending().and(Sort.by("plate").ascending())));
        return new VehiclePage(result.stream().map(this::row).toList(),
                result.getTotalElements(), page, result.getTotalPages(), searched, LocalDate.now());
    }

    @Transactional(readOnly = true)
    public VehicleBills bills(UUID vehicleId) {
        CommercialVehicle v = vehicles.findById(vehicleId).orElseThrow(
                () -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Veículo não encontrado."));
        List<FinancialCharge> all = charges.findAllByVehicle_IdOrderByDueDateDescCreatedAtDesc(vehicleId);
        LocalDate today = LocalDate.now();
        List<FinancialCharge> overdue = all.stream()
                .filter(c -> c.getBillingType() == FinancialBillingType.BOLETO && isOverdue(c, today))
                .sorted(Comparator.comparing(FinancialCharge::getDueDate)).toList();
        if (!overdue.isEmpty()) {
            return new VehicleBills(row(v, all), overdue.stream().map(c -> bill(c, today)).toList(),
                    true, provider.enabled(),
                    "Boletos vencidos. Para segunda via, o financeiro deve confirmar um novo vencimento; o mesmo título será atualizado no Asaas.");
        }
        if (v.getRecurringBillingType() != FinancialBillingType.BOLETO) {
            return new VehicleBills(row(v, all), List.of(), false, provider.enabled(),
                    "Este veículo não possui mensalidade recorrente configurada como boleto.");
        }
        List<FinancialCharge> monthly = all.stream()
                .filter(c -> c.getChargeType() == FinancialChargeType.MONTHLY && c.getBillingType() == FinancialBillingType.BOLETO)
                .toList();
        LocalDate seed = v.getFirstBillingDueDate() == null ? today : v.getFirstBillingDueDate();
        YearMonth month = YearMonth.from(seed);
        if (month.isBefore(YearMonth.from(today))) month = YearMonth.from(today);
        int dueDay = v.getBillingDueDay() == null || v.getBillingDueDay() < 1 || v.getBillingDueDay() > 31
                ? seed.getDayOfMonth() : v.getBillingDueDay();
        List<BillRow> upcoming = new ArrayList<>();
        for (int i = 0; i < 10; i++) {
            YearMonth candidate = month.plusMonths(i);
            LocalDate date = candidate.atDay(Math.min(dueDay, candidate.lengthOfMonth()));
            FinancialCharge issued = monthly.stream()
                    .filter(c -> c.getDueDate() != null && YearMonth.from(c.getDueDate()).equals(candidate))
                    .sorted(Comparator.comparing(FinancialCharge::getCreatedAt).reversed()).findFirst().orElse(null);
            upcoming.add(issued == null
                    ? new BillRow(null, date, v.getMonthlyValue(), "PREVISTO", 0, false, true, null, null,
                            "Parcela prevista: ainda não emitida pelo Asaas. Não é uma segunda via.")
                    : bill(issued, today));
        }
        return new VehicleBills(row(v, all), upcoming, false, provider.enabled(),
                "Mensalidade atual e nove próximas competências. Previsões não possuem boleto até o Asaas emitir a cobrança.");
    }

    /** Emite a cobrança local escolhida pelo ADM/Financeiro, usando a mesma trava do portal. */
    public SecondCopyResult issueBoleto(UUID chargeId) {
        FinancialCharge charge = requireBoleto(chargeId);
        if (charge.getAssociate() == null) throw new IllegalArgumentException("Cobrança sem associado.");
        var issued = finance.issueAssociateBoleto(charge.getAssociate().getId(), chargeId);
        String printable = safeUrl(issued.bankSlipUrl());
        if (printable == null) printable = safeUrl(issued.invoiceUrl());
        return new SecondCopyResult(chargeId, issued.dueDate(), issued.status().name(), printable,
                issued.identificationField(), printable == null
                    ? "A emissão não foi concluída. Solicite conciliação ao Financeiro."
                    : "Boleto emitido. Abrindo em nova aba.");
    }

    @Transactional
    public SecondCopyResult secondCopy(UUID chargeId, SecondCopyRequest request) {
        FinancialCharge charge = requireBoleto(chargeId);
        if (charge.getProviderPaymentId() == null) {
            throw new IllegalArgumentException("Boleto ainda não emitido pela financeira.");
        }
        if (!provider.enabled()) {
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE,
                    "A financeira desta empresa ainda não está configurada.");
        }
        // Consultar a situação real antes de editar título; webhook pode estar atrasado.
        finance.refreshCharge(chargeId, null, PortalRole.ADMIN);
        charge = requireBoleto(chargeId);
        if (!OPEN.contains(charge.getStatus())) {
            throw new IllegalArgumentException("Cobrança paga, cancelada ou estornada não pode ser reemitida.");
        }
        LocalDate today = LocalDate.now();
        if (isOverdue(charge, today)) {
            LocalDate next = request == null ? null : request.newDueDate();
            if (next == null || !next.isAfter(today)) {
                throw new IllegalArgumentException("Para boleto vencido, confirme um novo vencimento futuro.");
            }
            String originalId = charge.getProviderPaymentId();
            FinancialProvider.PaymentData updated = provider.updatePaymentDueDate(originalId, next);
            if (!originalId.equals(updated.id())) {
                throw new IllegalStateException("Asaas não confirmou a atualização do título original.");
            }
            charge.clearPaymentSlipCodes();
            charge.applyProvider(originalId, updated.subscriptionId(), updated.invoiceUrl(),
                    updated.bankSlipUrl(), updated.identificationField(), updated.barCode(),
                    updated.pixPayload(), updated.pixEncodedImage());
            charge.updateFromWebhook(FinancialChargeStatus.PENDING, null, next, null,
                    updated.invoiceUrl(), updated.bankSlipUrl());
            charges.save(charge);
            return copyResult(charge, "Vencimento atualizado na cobrança original. Acordos e reativação de plano exigem confirmação do Financeiro e pagamento.");
        }
        return copyResult(charge, "Segunda via consultada no Asaas; nenhuma nova cobrança foi criada.");
    }

    @Transactional
    public VehicleBills refreshAndList(UUID chargeId) {
        FinancialCharge charge = requireBoleto(chargeId);
        if (charge.getProviderPaymentId() != null && provider.enabled()) {
            finance.refreshCharge(chargeId, null, PortalRole.ADMIN);
        }
        return bills(charge.getVehicle().getId());
    }

    /** Reconcilia somente cobranças já emitidas para esta placa; nunca cria mensalidades. */
    public VehicleSync synchronizeVehicle(UUID vehicleId) {
        CommercialVehicle vehicle = vehicles.findById(vehicleId).orElseThrow(
                () -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Veículo não encontrado."));
        if (!provider.enabled()) {
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE,
                    "A financeira desta empresa ainda não está configurada.");
        }
        List<FinancialCharge> issued = charges.findAllByVehicle_IdOrderByDueDateDescCreatedAtDesc(vehicle.getId())
                .stream()
                .filter(c -> c.getBillingType() == FinancialBillingType.BOLETO
                        && c.getProviderPaymentId() != null
                        && !c.getProviderPaymentId().isBlank()
                        && c.getStatus() != FinancialChargeStatus.CANCELLED)
                .sorted(Comparator.<FinancialCharge>comparingInt(c -> OPEN.contains(c.getStatus()) ? 0 : 1)
                        .thenComparing(FinancialCharge::getDueDate, Comparator.nullsLast(Comparator.naturalOrder())))
                .limit(25).toList();
        int checked = 0;
        int failed = 0;
        for (FinancialCharge charge : issued) {
            try {
                finance.refreshCharge(charge.getId(), null, PortalRole.ADMIN);
                checked++;
            } catch (RuntimeException ex) {
                failed++;
            }
        }
        if (failed > 0 && checked == 0) {
            throw new ResponseStatusException(HttpStatus.BAD_GATEWAY,
                    "Não foi possível sincronizar as cobranças da placa com o Asaas.");
        }
        return new VehicleSync(bills(vehicle.getId()), checked, failed);
    }

    public record VehicleSync(VehicleBills bills, int checked, int failed) {}

    static String businessStatus(CommercialVehicleStatus contract, long maxDays) {
        if (contract == CommercialVehicleStatus.CANCELLED || maxDays > 60) return "CANCELADO";
        if (contract == CommercialVehicleStatus.PENDING_PAYMENT || contract == CommercialVehicleStatus.DRAFT)
            return "AGUARDANDO_ADESAO";
        if (maxDays > 6) return "INADIMPLENTE";
        if (maxDays >= 1) return "ATRASADO";
        return "EM_DIA";
    }

    private VehicleRow row(CommercialVehicle vehicle) {
        return row(vehicle, charges.findAllByVehicle_IdOrderByDueDateDescCreatedAtDesc(vehicle.getId()));
    }

    private VehicleRow row(CommercialVehicle vehicle, List<FinancialCharge> records) {
        LocalDate today = LocalDate.now();
        long maxDays = records.stream().filter(c -> c.getBillingType() == FinancialBillingType.BOLETO)
                .filter(c -> isOverdue(c, today))
                .mapToLong(c -> ChronoUnit.DAYS.between(c.getDueDate(), today)).max().orElse(0);
        return new VehicleRow(vehicle.getId(), vehicle.getAssociate().getId(), vehicle.getAssociate().getName(),
                vehicle.getAssociate().getWhatsapp(), vehicle.getPlate(), vehicle.getModel(),
                businessStatus(vehicle.getStatus(), maxDays), maxDays,
                vehicle.getStatus() == CommercialVehicleStatus.CANCELLED || maxDays > 60);
    }

    private static boolean isOverdue(FinancialCharge charge, LocalDate today) {
        return charge.getDueDate() != null && charge.getDueDate().isBefore(today) && OPEN.contains(charge.getStatus());
    }

    private static BillRow bill(FinancialCharge charge, LocalDate today) {
        long days = isOverdue(charge, today) ? ChronoUnit.DAYS.between(charge.getDueDate(), today) : 0;
        String status = days > 60 ? "VENCIDO_60" : days > 6 ? "INADIMPLENTE" : days > 0 ? "ATRASADO"
                : charge.getStatus() == FinancialChargeStatus.RECEIVED ? "PAGO"
                : charge.getStatus() == FinancialChargeStatus.CONFIRMED ? "CONFIRMADO"
                : charge.getStatus() == FinancialChargeStatus.CANCELLED ? "CANCELADO"
                : charge.getStatus() == FinancialChargeStatus.REFUNDED ? "ESTORNADO" : "PENDENTE";
        String url = safeUrl(charge.getBankSlipUrl());
        if (url == null) url = safeUrl(charge.getInvoiceUrl());
        boolean issued = charge.getProviderPaymentId() != null;
        boolean canCopy = issued && days == 0 && OPEN.contains(charge.getStatus());
        return new BillRow(charge.getId(), charge.getDueDate(),
                charge.getGrossValue() == null ? charge.getValue() : charge.getGrossValue(),
                status, days, issued, false, canCopy ? url : null,
                canCopy ? charge.getIdentificationField() : null,
                !issued ? "Cobrança ainda não emitida." : days > 0
                ? "Boleto vencido: escolha novo vencimento para segunda via." : null);
    }

    private FinancialCharge requireBoleto(UUID id) {
        FinancialCharge charge = charges.findById(id).orElseThrow(
                () -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Cobrança não encontrada."));
        if (charge.getBillingType() != FinancialBillingType.BOLETO) {
            throw new IllegalArgumentException("Esta cobrança não é um boleto.");
        }
        return charge;
    }

    private static SecondCopyResult copyResult(FinancialCharge c, String message) {
        return new SecondCopyResult(c.getId(), c.getDueDate(), c.getStatus().name(),
                safeUrl(c.getBankSlipUrl()) != null ? safeUrl(c.getBankSlipUrl()) : safeUrl(c.getInvoiceUrl()),
                c.getIdentificationField(), message);
    }

    private static String safeUrl(String value) {
        if (value == null) return null;
        try {
            java.net.URI url = java.net.URI.create(value);
            return "https".equalsIgnoreCase(url.getScheme()) && url.getHost() != null && url.getUserInfo() == null
                    ? url.toASCIIString() : null;
        } catch (IllegalArgumentException ignored) {
            return null;
        }
    }

    private static String contains(String text) {
        return "%" + (text == null ? "" : text).replace("!", "!!").replace("%", "!%").replace("_", "!_") + "%";
    }

}
