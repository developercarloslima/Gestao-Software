package br.com.nh.cotacao.service;

import br.com.nh.cotacao.dto.CommercialFinanceDtos.*;
import br.com.nh.cotacao.entity.*;
import br.com.nh.cotacao.repository.*;
import br.com.nh.cotacao.security.PortalRole;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.*;
import java.util.*;

@Service
public class CommercialFinanceService {
    public static final int REQUIRED_CLOSINGS = 10;

    private final AssociateRepository associates;
    private final CommercialVehicleRepository vehicles;
    private final FinancialChargeRepository charges;
    private final ConsultantRepository consultants;
    private final ConsultantWithdrawalRepository withdrawals;
    private final ConsultantWalletMovementRepository wallet;
    private final AsaasWebhookEventRepository webhookEvents;
    private final AsaasClient asaas;
    private final ConsultantCommissionGuideService commissionGuide;
    private final ObjectMapper mapper;

    @Value("${app.asaas.payment-fee:2.00}")
    private BigDecimal asaasPaymentFee;
    @Value("${app.asaas.withdrawal-fee:2.00}")
    private BigDecimal asaasWithdrawalFee;

    public CommercialFinanceService(
            AssociateRepository associates,
            CommercialVehicleRepository vehicles,
            FinancialChargeRepository charges,
            ConsultantRepository consultants,
            ConsultantWithdrawalRepository withdrawals,
            ConsultantWalletMovementRepository wallet,
            AsaasWebhookEventRepository webhookEvents,
            AsaasClient asaas,
            ConsultantCommissionGuideService commissionGuide,
            ObjectMapper mapper
    ) {
        this.associates = associates;
        this.vehicles = vehicles;
        this.charges = charges;
        this.consultants = consultants;
        this.withdrawals = withdrawals;
        this.wallet = wallet;
        this.webhookEvents = webhookEvents;
        this.asaas = asaas;
        this.commissionGuide = commissionGuide;
        this.mapper = mapper;
    }

    @Transactional
    public AssociateSummary createAssociate(CreateAssociateRequest request) {
        String document = Associate.normalizeDocument(request.document());
        if (document != null && associates.findByDocument(document).isPresent()) {
            throw new IllegalArgumentException("Já existe um associado cadastrado com este CPF/CNPJ.");
        }
        Associate saved = associates.save(Associate.create(request.name(), document, request.whatsapp(), request.email()));
        return toAssociateSummary(saved);
    }

    @Transactional(readOnly = true)
    public List<AssociateSummary> searchAssociates(String query) {
        String raw = query == null ? "" : query.trim();
        String normalized = Consultant.normalize(raw);
        String digits = raw.replaceAll("\\D", "");
        return associates.search(normalized, digits).stream().limit(100).map(this::toAssociateSummary).toList();
    }

    @Transactional(readOnly = true)
    public AssociateDetail associate(UUID id) {
        Associate a = requireAssociate(id);
        List<VehicleSummary> list = vehicles.findAllByAssociate_IdOrderByCreatedAtDesc(id).stream().map(this::toVehicleSummary).toList();
        return new AssociateDetail(a.getId(), a.getName(), a.getDocument(), a.getWhatsapp(), a.getEmail(), a.isActive(), list);
    }

    @Transactional
    public CommercialVehicle registerQuotation(Quotation q, UUID requestedAssociateId, AdhesionPaymentMethod method) {
        Associate associate;
        if (requestedAssociateId != null) {
            associate = requireAssociate(requestedAssociateId);
        } else {
            String document = safeDocument(q.getCustomerCpf());
            associate = document == null ? null : associates.findByDocument(document).orElse(null);
            if (associate == null) {
                associate = associates.save(Associate.create(q.getCustomerName(), document, q.getWhatsapp(), null));
            } else {
                associate.update(q.getCustomerName(), document, q.getWhatsapp(), associate.getEmail());
                associate = associates.save(associate);
            }
        }

        CommercialVehicle vehicle = vehicles.findByQuotation_Id(q.getId()).orElse(null);
        if (vehicle == null) {
            vehicle = CommercialVehicle.fromQuotation(q, associate, method);
        } else {
            vehicle.setAssociate(associate);
            vehicle.setAdhesionPaymentMethod(method);
            vehicle.syncFromQuotation(q);
        }
        return vehicles.save(vehicle);
    }

    @Transactional
    public void activateQuotation(Quotation q) {
        CommercialVehicle v = vehicles.findByQuotation_Id(q.getId()).orElseGet(() -> registerQuotation(q, null, AdhesionPaymentMethod.BOLETO));
        v.syncFromQuotation(q);
        v.activate();
        vehicles.save(v);

        if (v.getAdhesionValue().signum() > 0) {
            FinancialCharge existingAdhesion = charges.findFirstByVehicle_IdAndChargeTypeOrderByCreatedAtAsc(v.getId(), FinancialChargeType.ADHESION).orElse(null);
            if (existingAdhesion == null) {
                if (v.getAdhesionPaymentMethod() == AdhesionPaymentMethod.CASH_HAND) {
                    FinancialCharge cash = FinancialCharge.create(v, FinancialChargeType.ADHESION, FinancialBillingType.CASH_HAND, v.getAdhesionValue(), LocalDate.now());
                    cash.markReceived(OffsetDateTime.now());
                    charges.save(cash);
                } else if (asaas.enabled()) {
                    createAsaasCharge(v, FinancialChargeType.ADHESION,
                            v.getAdhesionPaymentMethod() == AdhesionPaymentMethod.PIX ? FinancialBillingType.PIX : FinancialBillingType.BOLETO,
                            v.getAdhesionValue(), LocalDate.now());
                }
            } else if (asaas.enabled() && existingAdhesion.getStatus() == FinancialChargeStatus.ERROR && existingAdhesion.getProviderPaymentId() == null) {
                sendExistingAsaasCharge(existingAdhesion);
            }
        }

        if (asaas.enabled() && v.getMonthlyValue().signum() > 0 && v.getAsaasSubscriptionId() == null) {
            try {
                String customerId = ensureAsaasCustomer(v.getAssociate());
                LocalDate firstDue = v.getFirstBillingDueDate() == null ? LocalDate.now().plusMonths(1) : v.getFirstBillingDueDate();
                String subscriptionId = asaas.createMonthlySubscription(
                        customerId, v.getRecurringBillingType(), withPaymentFee(v.getMonthlyValue(), v.getRecurringBillingType()), firstDue,
                        "Mensalidade " + v.getModel() + (v.getPlate() == null ? "" : " - " + v.getPlate()),
                        "vehicle:" + v.getId()
                );
                v.setAsaasSubscriptionId(subscriptionId);
                vehicles.save(v);
            } catch (RuntimeException ignored) {
                // A aceitação da cotação não é desfeita por indisponibilidade externa.
                // O painel do veículo permite tentar a ativação financeira novamente.
            }
        }
    }

    @Transactional
    public VehicleDetail retryFinancialActivation(UUID vehicleId) {
        CommercialVehicle v = requireVehicle(vehicleId);
        if (v.getQuotation() == null) throw new IllegalArgumentException("Veículo sem cotação de origem para ativação automática.");
        activateQuotation(v.getQuotation());
        return vehicle(vehicleId);
    }

    @Transactional(readOnly = true)
    public List<VehicleSummary> vehiclesFor(UUID consultantId, PortalRole role) {
        List<CommercialVehicle> list = role == PortalRole.ADMIN
                ? vehicles.findAllByOrderByCreatedAtDesc()
                : vehicles.findAllByConsultant_IdOrderByCreatedAtDesc(consultantId);
        return list.stream().map(this::toVehicleSummary).toList();
    }

    @Transactional(readOnly = true)
    public VehicleDetail vehicle(UUID id) {
        CommercialVehicle v = requireVehicle(id);
        List<ChargeResponse> list = charges.findAllByVehicle_IdOrderByDueDateDescCreatedAtDesc(id).stream().map(this::toChargeResponse).toList();
        return new VehicleDetail(toVehicleSummary(v), v.getBillingDueDay(), v.getFirstBillingDueDate(), v.getRecurringBillingType(), asaas.enabled(), v.getAsaasSubscriptionId(), list);
    }

    @Transactional
    public ChargeResponse createCharge(UUID vehicleId, CreateChargeRequest request) {
        CommercialVehicle v = requireVehicle(vehicleId);
        if (request.type() == FinancialChargeType.CLOSING) throw new IllegalArgumentException("O fechamento é identificado automaticamente no primeiro pagamento recorrente quitado.");
        if (request.type() == FinancialChargeType.ADHESION && charges.existsByVehicle_IdAndChargeType(vehicleId, FinancialChargeType.ADHESION)) {
            throw new IllegalArgumentException("Este veículo já possui uma cobrança de adesão registrada.");
        }
        if (request.type() == FinancialChargeType.ADHESION && request.billingType() == FinancialBillingType.CASH_HAND) {
            FinancialCharge c = FinancialCharge.create(v, request.type(), request.billingType(), request.value(), request.dueDate());
            c.markReceived(OffsetDateTime.now());
            return toChargeResponse(charges.save(c));
        }
        if (request.billingType() == FinancialBillingType.CASH_HAND) {
            throw new IllegalArgumentException("Somente a adesão pode ser registrada como recebida em mãos.");
        }
        if (!asaas.enabled()) throw new IllegalStateException("Configure o Asaas antes de gerar Pix ou boleto.");
        return toChargeResponse(createAsaasCharge(v, request.type(), request.billingType(), request.value(), request.dueDate()));
    }

    @Transactional
    public ChargeResponse refreshCharge(UUID chargeId, UUID consultantId, PortalRole role) {
        FinancialCharge c = charges.findById(chargeId).orElseThrow(() -> new IllegalArgumentException("Cobrança não encontrada."));
        assertVehicleAccess(c.getVehicle().getId(), consultantId, role);
        if (c.getProviderPaymentId() == null) return toChargeResponse(c);
        var p = asaas.refreshPayment(c.getProviderPaymentId());
        FinancialChargeStatus old = c.getStatus();
        FinancialChargeStatus status = mapProviderPaymentStatus(p.status());
        c.applyProvider(p.id(), p.subscriptionId(), p.invoiceUrl(), p.bankSlipUrl(), p.identificationField(), p.barCode(), p.pixPayload(), p.pixEncodedImage());
        if (status != null) {
            c.updateFromWebhook(status, null, null, null, p.invoiceUrl(), p.bankSlipUrl());
            if (status == FinancialChargeStatus.RECEIVED && c.getChargeType() != FinancialChargeType.ADHESION) {
                long paidClosingForVehicle = charges.countByVehicle_IdAndChargeTypeAndStatus(c.getVehicle().getId(), FinancialChargeType.CLOSING, FinancialChargeStatus.RECEIVED);
                boolean adhesionSettled = isAdhesionSettled(c.getVehicle());
                c.setChargeType(adhesionSettled && paidClosingForVehicle == 0 ? FinancialChargeType.CLOSING : FinancialChargeType.MONTHLY);
            }
        }
        c = charges.save(c);
        if (status == FinancialChargeStatus.RECEIVED && old != FinancialChargeStatus.RECEIVED) creditCommission(c);
        if (status == FinancialChargeStatus.REFUNDED && old != FinancialChargeStatus.REFUNDED) reverseCommission(c);
        return toChargeResponse(c);
    }

    @Transactional
    public ConsultantFinanceSummary configurePix(UUID consultantId, UpdatePixRequest request) {
        Consultant c = requireConsultant(consultantId);
        c.configurePayoutPix(request.type(), request.key());
        consultants.save(c);
        return finance(consultantId);
    }

    @Transactional
    public ConsultantFinanceSummary configureCommission(UUID consultantId, UpdateCommissionRequest request) {
        Consultant c = requireConsultant(consultantId);
        if (request.mode() == ConsultantCommissionMode.PERCENTAGE && request.value().compareTo(new BigDecimal("100")) > 0) {
            throw new IllegalArgumentException("A comissão percentual não pode ultrapassar 100%.");
        }
        c.configureCommission(request.mode(), request.value());
        consultants.save(c);
        return finance(consultantId);
    }

    @Transactional(readOnly = true)
    public ConsultantFinanceSummary finance(UUID consultantId) {
        Consultant c = requireConsultant(consultantId);
        long closings = paidClosings(consultantId);
        var breakdown = commissionGuide.calculate(consultantId, YearMonth.now());
        BigDecimal totalEarned = commissionGuide.totalEarned(consultantId);
        BigDecimal committed = nz(withdrawals.sumCommitted(consultantId));
        BigDecimal remaining = totalEarned.subtract(committed).max(BigDecimal.ZERO).setScale(2, RoundingMode.HALF_UP);
        boolean unlocked = closings >= REQUIRED_CLOSINGS;
        BigDecimal locked = unlocked ? BigDecimal.ZERO.setScale(2) : remaining;
        BigDecimal available = unlocked ? remaining : BigDecimal.ZERO.setScale(2);
        BigDecimal paid = nz(withdrawals.sumPaid(consultantId));
        List<WithdrawalResponse> history = withdrawals.findAllByConsultant_IdOrderByCreatedAtDesc(consultantId).stream().map(this::toWithdrawalResponse).toList();
        return new ConsultantFinanceSummary(c.getId(), c.getName(), c.getCommissionMode(), breakdown.percentageApplied(), c.getPayoutPixKeyType(), maskPix(c.getPayoutPixKey()),
                closings, REQUIRED_CLOSINGS, unlocked, locked, available, remaining, paid, asaas.enabled(), normalizedWithdrawalFee(),
                breakdown.paidNewSales(), commissionGuide.career(consultantId, YearMonth.now()), commissionGuide.activePayingBase(consultantId, YearMonth.now()),
                commissionGuide.activeBaseRevenue(consultantId, YearMonth.now()), breakdown.total(), breakdown, history);
    }

    @Transactional(readOnly = true)
    public List<AdminConsultantFinanceRow> adminFinanceRows() {
        return consultants.findByActiveTrueAndRoleOrderByNameAsc(CollaboratorRole.CONSULTANT).stream().map(c -> {
            var f = finance(c.getId());
            return new AdminConsultantFinanceRow(c.getId(), c.getName(), f.paidClosings(), f.lockedBalance(), f.availableBalance(), f.paidWithdrawals(), f.commissionMode(), f.commissionValue());
        }).toList();
    }

    @Transactional
    public WithdrawalResponse requestWithdrawal(UUID consultantId, WithdrawalRequest request) {
        Consultant c = requireConsultant(consultantId);
        BigDecimal amount = request == null ? null : request.amount();
        ConsultantPayoutMethod payoutMethod = request == null || request.payoutMethod() == null ? ConsultantPayoutMethod.PIX : request.payoutMethod();
        if (paidClosings(consultantId) < REQUIRED_CLOSINGS) throw new IllegalArgumentException("O saque é liberado após 10 boletos de fechamento quitados.");
        if (amount == null || amount.signum() <= 0) throw new IllegalArgumentException("Informe um valor de saque válido.");
        BigDecimal available = finance(consultantId).availableBalance();
        if (amount.compareTo(available) > 0) throw new IllegalArgumentException("O valor solicitado é maior que o saldo disponível.");
        BigDecimal requested = amount.setScale(2, RoundingMode.HALF_UP);

        ConsultantWithdrawal w;
        if (payoutMethod == ConsultantPayoutMethod.TED) {
            if (request.bankDetails() == null || request.bankDetails().isBlank()) throw new IllegalArgumentException("Informe banco, agência, conta, titular e CPF/CNPJ para solicitar TED.");
            w = withdrawals.save(ConsultantWithdrawal.createTed(c, requested, request.bankDetails()));
            w.financeNote("Solicitação TED aguardando processamento do Financeiro.");
            return toWithdrawalResponse(withdrawals.save(w));
        }

        if (c.getPayoutPixKeyType() == null || c.getPayoutPixKey() == null) throw new IllegalArgumentException("Cadastre uma chave Pix antes de solicitar o saque.");
        BigDecimal fee = normalizedWithdrawalFee();
        BigDecimal netAmount = requested.subtract(fee).setScale(2, RoundingMode.HALF_UP);
        if (netAmount.signum() <= 0) throw new IllegalArgumentException("O valor solicitado deve ser maior que a tarifa de transferência de " + fee + ".");
        w = withdrawals.save(ConsultantWithdrawal.createPix(c, requested, fee, c.getPayoutPixKeyType(), c.getPayoutPixKey()));
        w.financeNote("Solicitação Pix enviada ao Financeiro. Taxa da operação informada ao consultor; pagamento aguardando aprovação/processamento.");
        return toWithdrawalResponse(withdrawals.save(w));
    }

    @Transactional
    public void handleAsaasWebhook(JsonNode event) {
        String eventId = text(event, "id");
        String eventType = text(event, "event");
        if (eventId == null || eventType == null) throw new IllegalArgumentException("Webhook Asaas inválido.");
        if (webhookEvents.existsById(eventId)) return;
        AsaasWebhookEvent audit;
        try {
            audit = webhookEvents.saveAndFlush(AsaasWebhookEvent.received(eventId, eventType, mapper.writeValueAsString(event)));
        } catch (Exception ex) {
            if (webhookEvents.existsById(eventId)) return;
            throw new IllegalStateException("Não foi possível registrar o webhook financeiro.");
        }
        try {
            if (eventType.startsWith("PAYMENT_")) processPaymentEvent(eventType, event.path("payment"));
            if (eventType.startsWith("TRANSFER_")) processTransferEvent(eventType, event.path("transfer"));
            audit.processed();
        } catch (RuntimeException ex) {
            audit.failed(ex.getMessage());
            throw ex;
        } finally {
            webhookEvents.save(audit);
        }
    }

    private void processPaymentEvent(String eventType, JsonNode payment) {
        String paymentId = text(payment,"id");
        if (paymentId == null) return;
        String subscriptionId = text(payment,"subscription");
        String externalReference = text(payment,"externalReference");
        FinancialCharge c = charges.findByProviderPaymentId(paymentId).orElse(null);
        if (c == null) {
            CommercialVehicle v = locateVehicle(subscriptionId, externalReference);
            if (v == null) return;
            FinancialChargeType type;
            if (externalReference != null && externalReference.startsWith("adhesion:")) type = FinancialChargeType.ADHESION;
            else type = FinancialChargeType.MONTHLY;
            FinancialBillingType billing = parseBilling(text(payment,"billingType"));
            BigDecimal baseValue = type == FinancialChargeType.ADHESION ? v.getAdhesionValue() : v.getMonthlyValue();
            BigDecimal providerGross = decimal(payment,"value", withPaymentFee(baseValue, billing));
            LocalDate dueDate = date(text(payment,"dueDate"));
            c = FinancialCharge.create(v, type, billing, baseValue, dueDate);
            c.applyProviderFee(providerGross.subtract(baseValue).max(BigDecimal.ZERO));
            c.applyProvider(paymentId, subscriptionId, text(payment,"invoiceUrl"), text(payment,"bankSlipUrl"), null, null, null, null);
        }
        FinancialChargeStatus old = c.getStatus();
        FinancialChargeStatus status = mapPaymentStatus(eventType, text(payment,"status"));
        OffsetDateTime paidAt = dateTime(text(payment,"paymentDate"));
        if (paidAt == null) paidAt = dateTime(text(payment,"clientPaymentDate"));
        c.updateFromWebhook(status, decimal(payment,"value",null), date(text(payment,"dueDate")), paidAt, text(payment,"invoiceUrl"), text(payment,"bankSlipUrl"));
        if (status == FinancialChargeStatus.RECEIVED && c.getChargeType() != FinancialChargeType.ADHESION) {
            long paidClosingForVehicle = charges.countByVehicle_IdAndChargeTypeAndStatus(c.getVehicle().getId(), FinancialChargeType.CLOSING, FinancialChargeStatus.RECEIVED);
            boolean adhesionSettled = isAdhesionSettled(c.getVehicle());
            c.setChargeType(adhesionSettled && paidClosingForVehicle == 0 ? FinancialChargeType.CLOSING : FinancialChargeType.MONTHLY);
        }
        c = charges.save(c);

        if (status == FinancialChargeStatus.RECEIVED && old != FinancialChargeStatus.RECEIVED) creditCommission(c);
        if (status == FinancialChargeStatus.REFUNDED && old != FinancialChargeStatus.REFUNDED) reverseCommission(c);
    }

    private void processTransferEvent(String eventType, JsonNode transfer) {
        String transferId = text(transfer,"id");
        if (transferId == null) return;
        ConsultantWithdrawal w = withdrawals.findByProviderTransferId(transferId).orElse(null);
        if (w == null) return;
        if ("TRANSFER_DONE".equals(eventType)) w.paid();
        else if ("TRANSFER_FAILED".equals(eventType) || "TRANSFER_CANCELLED".equals(eventType)) {
            if ("TRANSFER_CANCELLED".equals(eventType)) w.cancelled(text(transfer,"failReason")); else w.failed(text(transfer,"failReason"));
            if (!wallet.existsByWithdrawal_IdAndMovementType(w.getId(), WalletMovementType.REVERSAL)) wallet.save(ConsultantWalletMovement.withdrawalReversal(w.getConsultant(), w));
        }
        withdrawals.save(w);
    }

    private FinancialCharge sendExistingAsaasCharge(FinancialCharge c) {
        try {
            String customer = ensureAsaasCustomer(c.getAssociate());
            c.applyProviderFee(paymentFeeFor(c.getBillingType()));
            var p = asaas.createCharge(customer, c.getBillingType(), c.getGrossValue(), c.getDueDate() == null ? LocalDate.now() : c.getDueDate(),
                    c.getChargeType() == FinancialChargeType.ADHESION ? "Adesão " + c.getVehicle().getModel() : "Cobrança " + c.getVehicle().getModel(), c.getExternalReference());
            c.applyProvider(p.id(), p.subscriptionId(), p.invoiceUrl(), p.bankSlipUrl(), p.identificationField(), p.barCode(), p.pixPayload(), p.pixEncodedImage());
            c.markPending();
        } catch (RuntimeException ex) {
            c.markError();
        }
        return charges.save(c);
    }

    private FinancialCharge createAsaasCharge(CommercialVehicle v, FinancialChargeType type, FinancialBillingType billing, BigDecimal value, LocalDate dueDate) {
        FinancialCharge c = FinancialCharge.create(v, type, billing, value, dueDate);
        c.applyProviderFee(paymentFeeFor(billing));
        c = charges.save(c);
        try {
            String customer = ensureAsaasCustomer(v.getAssociate());
            var p = asaas.createCharge(customer, billing, c.getGrossValue(), dueDate,
                    type == FinancialChargeType.ADHESION ? "Adesão " + v.getModel() : "Cobrança " + v.getModel(), c.getExternalReference());
            c.applyProvider(p.id(), p.subscriptionId(), p.invoiceUrl(), p.bankSlipUrl(), p.identificationField(), p.barCode(), p.pixPayload(), p.pixEncodedImage());
        } catch (RuntimeException ex) {
            c.markError();
        }
        return charges.save(c);
    }

    private String ensureAsaasCustomer(Associate a) {
        if (a.getAsaasCustomerId() != null) return a.getAsaasCustomerId();
        if (a.getDocument() == null) throw new IllegalArgumentException("Informe o CPF/CNPJ do associado para gerar cobranças no Asaas.");
        String id = asaas.createCustomer(a);
        a.setAsaasCustomerId(id);
        associates.save(a);
        return id;
    }

    private void creditCommission(FinancialCharge c) {
        // V83-108: o saldo do consultor é derivado do guia 2026 e dos pagamentos reais.
        // Não criamos mais uma comissão fixa por cobrança para evitar dupla contagem.
    }

    private void reverseCommission(FinancialCharge c) {
        // O cálculo é dinâmico: ao estornar a cobrança, ela deixa automaticamente de compor os ganhos.
    }

    private void unlockWallet(UUID consultantId) {
        List<ConsultantWalletMovement> list = wallet.findAllByConsultant_IdAndLockedTrue(consultantId);
        list.forEach(ConsultantWalletMovement::unlock);
        wallet.saveAll(list);
    }

    private long paidClosings(UUID consultantId) {
        return charges.countByConsultant_IdAndChargeTypeAndStatus(consultantId, FinancialChargeType.CLOSING, FinancialChargeStatus.RECEIVED);
    }

    private boolean isAdhesionSettled(CommercialVehicle vehicle) {
        if (vehicle.getAdhesionValue() == null || vehicle.getAdhesionValue().signum() <= 0) return true;
        return charges.countByVehicle_IdAndChargeTypeAndStatus(vehicle.getId(), FinancialChargeType.ADHESION, FinancialChargeStatus.RECEIVED) > 0;
    }

    private CommercialVehicle locateVehicle(String subscriptionId, String externalReference) {
        if (subscriptionId != null) {
            var bySub = vehicles.findByAsaasSubscriptionId(subscriptionId);
            if (bySub.isPresent()) return bySub.get();
        }
        if (externalReference != null) {
            for (String prefix : List.of("vehicle:", "adhesion:")) {
                if (externalReference.startsWith(prefix)) {
                    String raw = externalReference.substring(prefix.length()).split(":")[0];
                    try { return vehicles.findById(UUID.fromString(raw)).orElse(null); } catch (Exception ignored) { }
                }
            }
        }
        return null;
    }


    @Transactional(readOnly = true)
    public List<AssociateVehicleSummary> consultantPortfolio(UUID consultantId) {
        requireConsultant(consultantId);
        return vehicles.findAllByConsultant_IdOrderByCreatedAtDesc(consultantId).stream()
                .map(this::toAssociateVehicleSummary).toList();
    }

    @Transactional
    public ChargeResponse refreshAssociateCharge(UUID associateId, UUID chargeId) {
        FinancialCharge charge = charges.findById(chargeId).orElseThrow(() -> new IllegalArgumentException("Cobrança não encontrada."));
        if (!charge.getAssociate().getId().equals(associateId)) throw new IllegalArgumentException("Esta cobrança não pertence ao associado autenticado.");
        if (charge.getProviderPaymentId() == null || !asaas.enabled()) return toChargeResponse(charge);
        // Reutiliza a atualização de cobranças já implementada para o painel administrativo.
        // Ela obtém boleto/Pix do Asaas, atualiza o status e mantém as regras de
        // crédito e estorno de comissões no mesmo fluxo transacional.
        // A titularidade da cobrança foi validada acima, antes da delegação.
        return refreshCharge(chargeId, null, PortalRole.ADMIN);
    }

    @Transactional(readOnly = true)
    public AssociatePortalProfile associatePortal(UUID associateId) {
        Associate a = requireAssociate(associateId);
        List<AssociateVehicleSummary> list = vehicles.findAllByAssociate_IdOrderByCreatedAtDesc(associateId).stream()
                .map(this::toAssociateVehicleSummary).toList();
        return new AssociatePortalProfile(a.getId(), a.getName(), a.getDocument(), a.getWhatsapp(), a.getEmail(),
                a.getBirthDate(), a.getSecondaryPhone(), a.getPostalCode(), a.getAddressLine(), a.getAddressNumber(),
                a.getAddressComplement(), a.getNeighborhood(), a.getCity(), a.getState(), a.isActive(), list);
    }

    @Transactional
    public AssociatePortalProfile updateAssociateSelf(UUID associateId, UpdateAssociateSelfRequest request) {
        Associate a = requireAssociate(associateId);
        a.updateProfile(request.name(), request.whatsapp(), request.email(), request.birthDate(), request.secondaryPhone(),
                request.postalCode(), request.addressLine(), request.addressNumber(), request.addressComplement(),
                request.neighborhood(), request.city(), request.state());
        associates.save(a);
        return associatePortal(associateId);
    }

    @Transactional(readOnly = true)
    public AssociateVehicleDetail associateVehicle(UUID associateId, UUID vehicleId) {
        CommercialVehicle v = requireVehicle(vehicleId);
        if (!v.getAssociate().getId().equals(associateId)) throw new IllegalArgumentException("Este veículo não pertence ao associado autenticado.");
        return new AssociateVehicleDetail(toAssociateVehicleSummary(v), asaas.enabled(), charges.findAllByVehicle_IdOrderByDueDateDescCreatedAtDesc(vehicleId).stream().map(this::toChargeResponse).toList());
    }

    @Transactional
    public ChargeResponse createAssociateSelfServiceCharge(UUID associateId, UUID vehicleId, SelfServiceChargeRequest request) {
        CommercialVehicle v = requireVehicle(vehicleId);
        if (!v.getAssociate().getId().equals(associateId)) throw new IllegalArgumentException("Este veículo não pertence ao associado autenticado.");
        FinancialBillingType billing = request.billingType();
        if (billing != FinancialBillingType.BOLETO && billing != FinancialBillingType.PIX && billing != FinancialBillingType.CREDIT_CARD) {
            throw new IllegalArgumentException("Escolha Boleto, Pix ou Cartão.");
        }
        List<FinancialCharge> existing = charges.findAllByVehicle_IdOrderByDueDateDescCreatedAtDesc(vehicleId);
        for (FinancialCharge c : existing) {
            if (c.getChargeType() == FinancialChargeType.MONTHLY
                    && (c.getStatus() == FinancialChargeStatus.PENDING || c.getStatus() == FinancialChargeStatus.CONFIRMED || c.getStatus() == FinancialChargeStatus.OVERDUE)) {
                // Nunca criamos uma segunda cobrança para a mesma obrigação em aberto: evita pagamento duplicado.
                if (c.getProviderPaymentId() != null && asaas.enabled()) {
                    try { return refreshCharge(c.getId(), null, PortalRole.ADMIN); } catch (RuntimeException ignored) { }
                }
                return toChargeResponse(c);
            }
        }
        if (!asaas.enabled()) throw new IllegalStateException("Configure o Asaas antes de gerar a cobrança.");
        return toChargeResponse(createAsaasCharge(v, FinancialChargeType.MONTHLY, billing, v.getMonthlyValue(), LocalDate.now()));
    }

    private AssociateVehicleSummary toAssociateVehicleSummary(CommercialVehicle v) {
        List<FinancialCharge> list = charges.findAllByVehicle_IdOrderByDueDateDescCreatedAtDesc(v.getId());
        VehicleBusinessStatus status = effectiveBusinessStatus(v, list);
        long paid = list.stream().filter(c -> c.getStatus() == FinancialChargeStatus.RECEIVED).count();
        long pending = list.stream().filter(c -> c.getStatus() == FinancialChargeStatus.PENDING || c.getStatus() == FinancialChargeStatus.CONFIRMED).count();
        long overdue = list.stream().filter(c -> c.getStatus() == FinancialChargeStatus.OVERDUE).count();
        LocalDate next = list.stream().filter(c -> c.getDueDate()!=null && (c.getStatus()==FinancialChargeStatus.PENDING || c.getStatus()==FinancialChargeStatus.CONFIRMED))
                .map(FinancialCharge::getDueDate).filter(d -> !d.isBefore(LocalDate.now())).min(LocalDate::compareTo).orElse(null);
        return new AssociateVehicleSummary(v.getId(), v.getAssociate().getId(), v.getAssociate().getName(), v.getPlate(), v.getModel(), v.getManufactureYear(), v.getSelectedPlanName(),
                v.getMonthlyValue(), status, businessStatusLabel(status), next, paid, pending, overdue);
    }

    private VehicleBusinessStatus effectiveBusinessStatus(CommercialVehicle v, List<FinancialCharge> list) {
        if (v.getStatus() == CommercialVehicleStatus.CANCELLED) return VehicleBusinessStatus.DISABLED_CANCELLED;
        LocalDate today = LocalDate.now();
        boolean disabledByDelinquency = list.stream().anyMatch(c -> c.getDueDate()!=null
                && (c.getStatus()==FinancialChargeStatus.OVERDUE || c.getStatus()==FinancialChargeStatus.PENDING || c.getStatus()==FinancialChargeStatus.CONFIRMED)
                && c.getDueDate().isBefore(today.minusDays(6)));
        if (disabledByDelinquency) return VehicleBusinessStatus.DISABLED_DELINQUENCY;
        boolean delinquent = list.stream().anyMatch(c -> c.getDueDate()!=null
                && (c.getStatus()==FinancialChargeStatus.OVERDUE || c.getStatus()==FinancialChargeStatus.PENDING || c.getStatus()==FinancialChargeStatus.CONFIRMED)
                && c.getDueDate().isBefore(today));
        if (delinquent) return VehicleBusinessStatus.DELINQUENT;
        boolean pending = list.stream().anyMatch(c -> c.getDueDate()!=null
                && (c.getStatus()==FinancialChargeStatus.PENDING || c.getStatus()==FinancialChargeStatus.CONFIRMED)
                && c.getDueDate().isEqual(today));
        if (pending) return VehicleBusinessStatus.PAYMENT_PENDING;
        return VehicleBusinessStatus.ACTIVE;
    }

    private String businessStatusLabel(VehicleBusinessStatus status) {
        return switch (status) {
            case ACTIVE -> "Ativo";
            case PAYMENT_PENDING -> "Pagamento pendente";
            case DELINQUENT -> "Inadimplente";
            case DISABLED_CANCELLED -> "Desativado - cancelado";
            case DISABLED_DELINQUENCY -> "Desativado - inadimplência superior a 6 dias";
        };
    }

    public void assertVehicleAccess(UUID vehicleId, UUID consultantId, PortalRole role) {
        if (role == PortalRole.ADMIN || role == PortalRole.ANALYST || role == PortalRole.SUPERVISION_ANALYSIS) return;
        CommercialVehicle v = requireVehicle(vehicleId);
        if (role != PortalRole.CONSULTANT || consultantId == null || v.getConsultant() == null || !consultantId.equals(v.getConsultant().getId())) {
            throw new IllegalArgumentException("Este veículo pertence a outro consultor.");
        }
    }

    private AssociateSummary toAssociateSummary(Associate a) {
        return new AssociateSummary(a.getId(), a.getName(), a.getDocument(), a.getWhatsapp(), a.getEmail(), a.isActive(), vehicles.findAllByAssociate_IdOrderByCreatedAtDesc(a.getId()).size());
    }
    private VehicleSummary toVehicleSummary(CommercialVehicle v) {
        return new VehicleSummary(v.getId(), v.getAssociate().getId(), v.getAssociate().getName(), v.getConsultant()==null?null:v.getConsultant().getId(), v.getConsultant()==null?null:v.getConsultant().getName(),
                v.getPlate(),v.getModel(),v.getManufactureYear(),v.getSelectedPlanCode(),v.getSelectedPlanName(),v.getMonthlyValue(),v.getAdhesionValue(),v.getStatus(),v.getAdhesionPaymentMethod());
    }
    private ChargeResponse toChargeResponse(FinancialCharge c) {
        return new ChargeResponse(c.getId(),c.getChargeType(),c.getBillingType(),c.getValue(),c.getProviderFee(),c.getGrossValue(),c.getDueDate(),c.getStatus(),c.getPaidAt(),c.getInvoiceUrl(),c.getBankSlipUrl(),c.getIdentificationField(),c.getBarCode(),c.getPixPayload(),c.getPixEncodedImage());
    }
    private WithdrawalResponse toWithdrawalResponse(ConsultantWithdrawal w) {
        return new WithdrawalResponse(w.getId(),w.getAmount(),w.getTransactionFee(),w.getNetAmount(),w.getPayoutMethod(),w.getPixKeyType(),maskPix(w.getPixKey()),w.getBankDetails(),w.getStatus(),w.getFailReason(),w.getCreatedAt(),w.getCompletedAt());
    }

    private BigDecimal paymentFeeFor(FinancialBillingType billing){
        if (billing == null || billing == FinancialBillingType.CASH_HAND) return BigDecimal.ZERO.setScale(2, RoundingMode.HALF_UP);
        return asaasPaymentFee == null ? new BigDecimal("2.00") : asaasPaymentFee.max(BigDecimal.ZERO).setScale(2, RoundingMode.HALF_UP);
    }
    private BigDecimal normalizedWithdrawalFee(){
        return asaasWithdrawalFee == null ? new BigDecimal("2.00") : asaasWithdrawalFee.max(BigDecimal.ZERO).setScale(2, RoundingMode.HALF_UP);
    }
    private BigDecimal withPaymentFee(BigDecimal base, FinancialBillingType billing){
        BigDecimal normalized = base == null ? BigDecimal.ZERO : base.setScale(2, RoundingMode.HALF_UP);
        return normalized.add(paymentFeeFor(billing)).setScale(2, RoundingMode.HALF_UP);
    }

    private Associate requireAssociate(UUID id){return associates.findById(id).filter(Associate::isActive).orElseThrow(()->new IllegalArgumentException("Associado não encontrado."));}
    private CommercialVehicle requireVehicle(UUID id){return vehicles.findById(id).orElseThrow(()->new IllegalArgumentException("Veículo não encontrado."));}
    private Consultant requireConsultant(UUID id){return consultants.findById(id).filter(Consultant::isActive).orElseThrow(()->new IllegalArgumentException("Consultor não encontrado."));}
    private static String safeDocument(String value){try{return Associate.normalizeDocument(value);}catch(Exception e){return null;}}
    private static BigDecimal nz(BigDecimal v){return v==null?BigDecimal.ZERO:v.setScale(2,RoundingMode.HALF_UP);}
    private static String maskPix(String v){if(v==null||v.isBlank())return null;if(v.length()<=4)return "****";return "••••"+v.substring(v.length()-4);}
    private static FinancialBillingType parseBilling(String value){try{return FinancialBillingType.valueOf(value);}catch(Exception e){return FinancialBillingType.BOLETO;}}
    private static FinancialChargeStatus mapPaymentStatus(String event,String providerStatus){
        if("PAYMENT_RECEIVED".equals(event))return FinancialChargeStatus.RECEIVED;
        if("PAYMENT_CONFIRMED".equals(event))return FinancialChargeStatus.CONFIRMED;
        if("PAYMENT_OVERDUE".equals(event))return FinancialChargeStatus.OVERDUE;
        if("PAYMENT_REFUNDED".equals(event)||"PAYMENT_PARTIALLY_REFUNDED".equals(event)||"PAYMENT_RECEIVED_IN_CASH_UNDONE".equals(event))return FinancialChargeStatus.REFUNDED;
        if("PAYMENT_DELETED".equals(event))return FinancialChargeStatus.CANCELLED;
        // O cancelamento do registro bancário do boleto não cancela necessariamente a cobrança.
        if("PAYMENT_BANK_SLIP_CANCELLED".equals(event)){
            FinancialChargeStatus provider = mapProviderPaymentStatus(providerStatus);
            return provider == null ? FinancialChargeStatus.PENDING : provider;
        }
        FinancialChargeStatus provider = mapProviderPaymentStatus(providerStatus);
        return provider == null ? FinancialChargeStatus.PENDING : provider;
    }
    private static FinancialChargeStatus mapProviderPaymentStatus(String providerStatus){
        if(providerStatus==null)return null;
        return switch(providerStatus){
            case "PENDING", "AWAITING_RISK_ANALYSIS" -> FinancialChargeStatus.PENDING;
            case "CONFIRMED" -> FinancialChargeStatus.CONFIRMED;
            case "RECEIVED", "RECEIVED_IN_CASH" -> FinancialChargeStatus.RECEIVED;
            case "OVERDUE" -> FinancialChargeStatus.OVERDUE;
            case "REFUNDED", "PARTIALLY_REFUNDED", "REFUND_REQUESTED", "REFUND_IN_PROGRESS" -> FinancialChargeStatus.REFUNDED;
            case "DELETED" -> FinancialChargeStatus.CANCELLED;
            default -> null;
        };
    }
    private static String text(JsonNode n,String f){if(n==null||n.isMissingNode()||n.path(f).isMissingNode()||n.path(f).isNull())return null;String v=n.path(f).asText();return v==null||v.isBlank()?null:v;}
    private static BigDecimal decimal(JsonNode n,String f,BigDecimal fallback){return n!=null&&n.path(f).isNumber()?n.path(f).decimalValue():fallback;}
    private static LocalDate date(String v){try{return v==null?null:LocalDate.parse(v.substring(0,10));}catch(Exception e){return null;}}
    private static OffsetDateTime dateTime(String v){if(v==null)return null;try{return OffsetDateTime.parse(v);}catch(Exception ignored){}try{return LocalDate.parse(v.substring(0,10)).atStartOfDay().atOffset(ZoneOffset.ofHours(-3));}catch(Exception e){return null;}}
}
