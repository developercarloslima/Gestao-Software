package br.com.gestaosoftware.control.service;

import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;
import java.math.BigDecimal;
import java.util.*;

/**
 * Exclusão segura de mensalidades SaaS:
 * 1. bloqueia a linha local e protege títulos recebidos;
 * 2. cancela no Asaas a cobrança não paga, quando emitida;
 * 3. arquiva a competência local em vez de removê-la fisicamente.
 *
 * Em caso de falha remota, o banco não é alterado. Se ocorrer timeout depois
 * de cancelar no Asaas, uma nova tentativa reconhece DELETED (idempotência).
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
                        "paid", rs.getTimestamp("paid_at") != null) : null, invoiceId);
        if (invoice == null)
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Mensalidade não encontrada.");
        if ("CANCELLED".equals(invoice.get("status"))) return; // Reenvio idempotente.

        boolean verified = Boolean.TRUE.equals(db.queryForObject(
                "SELECT EXISTS(SELECT 1 FROM gs_verified_payment WHERE invoice_id=?)",
                Boolean.class, invoiceId));
        assertLocallyCancelable((String)invoice.get("status"), (boolean)invoice.get("paid"), verified);

        List<Map<String,String>> checkouts = db.query("""
                SELECT status,provider_payment_id FROM gs_invoice_checkout WHERE invoice_id=?
                """, (rs,row) -> {
                    Map<String,String> values = new HashMap<>();
                    values.put("status",rs.getString("status"));
                    values.put("paymentId",rs.getString("provider_payment_id"));
                    return values;
                }, invoiceId);

        String paymentId = null;
        if (!checkouts.isEmpty()) {
            Map<String,String> checkout = checkouts.get(0);
            paymentId = checkout.get("paymentId");
            if (!"CREATED".equals(checkout.get("status")) || paymentId == null || paymentId.isBlank())
                throw new ResponseStatusException(HttpStatus.CONFLICT,
                        "Emissão inconclusiva. Concilie o boleto no Asaas antes de excluir.");
            // Nunca ocultar a cobrança local antes de confirmar a remoção no provedor.
            asaas.cancelUnpaidPayment(invoiceId, paymentId, (BigDecimal)invoice.get("amount"));
            int updated = db.update("""
                    UPDATE gs_invoice_checkout SET status='CANCELLED',invoice_url=NULL,updated_at=NOW()
                     WHERE invoice_id=? AND status='CREATED' AND provider_payment_id=?
                    """, invoiceId, paymentId);
            if(updated != 1)
                throw new ResponseStatusException(HttpStatus.CONFLICT,
                        "Cobrança alterada durante o cancelamento. Atualize e tente novamente.");
        }

        int updated = db.update("""
                UPDATE platform_company_invoices SET status='CANCELLED',updated_at=NOW()
                 WHERE id=? AND status IN ('PENDING','OVERDUE') AND paid_at IS NULL
                   AND NOT EXISTS(SELECT 1 FROM gs_verified_payment WHERE invoice_id=platform_company_invoices.id)
                """, invoiceId);
        if(updated != 1)
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "A mensalidade mudou de situação antes do cancelamento.");

        db.update("""
                INSERT INTO gs_cloud_audit(id,company_id,action,operator,message,created_at)
                VALUES (?,?,?,?,?,NOW())
                """, UUID.randomUUID(), invoice.get("companyId"), "COMPANY_INVOICE_CANCELLED",
                operator, "Mensalidade " + invoice.get("reference") + " arquivada no Control Plane"
                    + (paymentId == null ? " (não emitida)." : " e cancelada no Asaas (pagamento " + paymentId + ").")
                    + " ID interno=" + invoiceId);
    }

    static void assertLocallyCancelable(String status, boolean paid, boolean verified) {
        if (!Set.of("PENDING","OVERDUE").contains(status) || paid || verified)
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "Somente mensalidades não pagas e sem confirmação financeira podem ser excluídas.");
    }
}
