package br.com.gestaosoftware.control.service;

import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Exclusão de mensalidades SaaS a partir do Control Plane.
 * A competência permanece no banco como CANCELLED para preservar auditoria e
 * impedir que o faturamento automático recrie o mesmo boleto.
 *
 * Para cobranças emitidas, confirma com o Asaas antes de arquivar localmente.
 * Em caso de timeout/erro, a transação local reverte e a operação pode ser
 * repetida; o Asaas já cancelado é reconhecido no próximo GET.
 */
@Service
public class PlatformInvoiceCancellationService {
    private final JdbcTemplate db;
    private final AsaasCheckoutService asaas;

    public PlatformInvoiceCancellationService(JdbcTemplate db, AsaasCheckoutService asaas) {
        this.db = db;
        this.asaas = asaas;
    }

    @Transactional
    public void cancelInvoice(UUID invoiceId, String operator) {
        Map<String,Object> invoice = db.query("""
                SELECT company_id,reference_month,amount,status,paid_at
                  FROM platform_company_invoices
                 WHERE id=? FOR UPDATE
                """, rs -> rs.next() ? Map.<String,Object>of(
                        "companyId", rs.getObject("company_id",UUID.class),
                        "reference", rs.getDate("reference_month").toLocalDate(),
                        "amount", rs.getBigDecimal("amount"),
                        "status", rs.getString("status"),
                        "paid", rs.getTimestamp("paid_at") != null
                    ) : null, invoiceId);
        if (invoice == null) throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Mensalidade não encontrada.");
        if ("CANCELLED".equals(invoice.get("status"))) return; // Reenvio seguro.
        boolean verified = Boolean.TRUE.equals(db.queryForObject(
                "SELECT EXISTS(SELECT 1 FROM gs_verified_payment WHERE invoice_id=?)",
                Boolean.class, invoiceId));
        assertLocallyCancelable((String)invoice.get("status"), (boolean)invoice.get("paid"), verified);

        List<Map<String,String>> checkouts = db.query("""
                SELECT status, provider_payment_id
                  FROM gs_invoice_checkout
                 WHERE invoice_id=?
                """, (rs,row) -> {
            java.util.Map<String,String> fields = new java.util.HashMap<>();
            fields.put("status", rs.getString("status"));
            fields.put("paymentId", rs.getString("provider_payment_id"));
            return fields;
        }, invoiceId);

        String paymentId = null;
        if (!checkouts.isEmpty()) {
            Map<String,String> checkout = checkouts.get(0);
            paymentId = checkout.get("paymentId");
            if (!"CREATED".equals(checkout.get("status")) || paymentId == null || paymentId.isBlank())
                throw new ResponseStatusException(HttpStatus.CONFLICT,
                        "Cobrança com emissão inconclusiva. Concilie o estado do Asaas antes de excluir.");
            // Não alterar o banco enquanto o provedor não confirmar o cancelamento.
            asaas.cancelUnpaidPayment(invoiceId, paymentId, (BigDecimal)invoice.get("amount"));
            int checkoutChanged = db.update("""
                    UPDATE gs_invoice_checkout SET status='CANCELLED',invoice_url=NULL,updated_at=NOW()
                     WHERE invoice_id=? AND status='CREATED' AND provider_payment_id=?
                    """, invoiceId, paymentId);
            if (checkoutChanged != 1)
                throw new ResponseStatusException(HttpStatus.CONFLICT, "Cobrança alterada durante o cancelamento.");
        }

        int changed = db.update("""
                UPDATE platform_company_invoices SET status='CANCELLED',updated_at=NOW()
                 WHERE id=? AND status IN ('PENDING','OVERDUE') AND paid_at IS NULL
                   AND NOT EXISTS (SELECT 1 FROM gs_verified_payment WHERE invoice_id=platform_company_invoices.id)
                """, invoiceId);
        if (changed != 1)
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "A mensalidade mudou de situação antes do cancelamento. Atualize e tente novamente.");

        db.update("""
                INSERT INTO gs_cloud_audit(id,company_id,action,operator,message,created_at)
                VALUES (?,?,?,?,?,NOW())
                """, UUID.randomUUID(), invoice.get("companyId"), "COMPANY_INVOICE_CANCELLED",
                operator, "Competência " + invoice.get("reference") + " cancelada no Control Plane"
                        + (paymentId == null ? " (não emitida)." : " e no Asaas (pagamento " + paymentId + ").")
                        + " ID interno=" + invoiceId);
    }

    static void assertLocallyCancelable(String status, boolean alreadyPaid, boolean verified) {
        if (!Set.of("PENDING", "OVERDUE").contains(status) || alreadyPaid || verified)
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "A exclusão só é permitida para mensalidades não pagas e sem confirmação de recebimento.");
    }
}
