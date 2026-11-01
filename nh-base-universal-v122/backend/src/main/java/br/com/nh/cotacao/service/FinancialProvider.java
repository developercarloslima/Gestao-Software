package br.com.nh.cotacao.service;

import br.com.nh.cotacao.entity.*;
import java.math.BigDecimal;
import java.time.LocalDate;

/** Contrato da financeira. Cada nova instituição implementa este adaptador. */
public interface FinancialProvider {
    String code();
    boolean enabled();
    String createCustomer(Associate associate);
    PaymentData createCharge(String customerId, FinancialBillingType billingType, BigDecimal amount,
                             LocalDate dueDate, String description, String externalReference);
    String createMonthlySubscription(String customerId, FinancialBillingType billingType, BigDecimal amount,
                                     LocalDate nextDueDate, String description, String externalReference);
    PaymentData refreshPayment(String paymentId);
    BigDecimal balance();
    TransferData transferPix(BigDecimal amount, PixKeyType keyType, String key,
                             String description, String externalReference);

    record PaymentData(String id, String status, String invoiceUrl, String bankSlipUrl,
                       String identificationField, String barCode, String pixPayload,
                       String pixEncodedImage, String subscriptionId, String externalReference) {}
    record TransferData(String id, String status) {}
}
