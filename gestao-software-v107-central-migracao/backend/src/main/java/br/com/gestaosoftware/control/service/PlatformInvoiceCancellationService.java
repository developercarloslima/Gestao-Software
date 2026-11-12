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

        List<Map<String,Object>> checkouts = db.query("""
                SELECT status,provider_payment_id,updated_at
                  FROM gs_invoice_checkout WHERE invoice_id=?
                """, (rs,row) -> {
                    Map<String,Object> values = new HashMap<>();
                    values.put("status",rs.getString("status"));
                    values.put("paymentId",rs.getString("provider_payment_id"));
                    values.put("updatedAt",rs.getTimestamp("updated_at").toInstant());
                    return values;
                }, invoiceId);

        String paymentId = null;
        int remotelyCancelled=0;
        if (!checkouts.isEmpty()) {
            Map<String,Object> checkout=checkouts.get(0);
            String checkoutStatus=(String)checkout.get("status");
            paymentId=(String)checkout.get("paymentId");
            if (!Set.of("CREATED","CREATING","CANCELLED").contains(checkoutStatus))
                throw new ResponseStatusException(HttpStatus.CONFLICT,
                        "Estado local da emissão desconhecido. Conciliação manual necessária.");
            if ("CREATING".equals(checkoutStatus)
                    && !staleCheckout((java.time.Instant)checkout.get("updatedAt"),java.time.Instant.now())) {
                throw new ResponseStatusException(HttpStatus.CONFLICT,
                        "Emissão ainda recente. Aguarde a conclusão da operação antes de excluir o boleto.");
            }
            // Mesmo sem ID salvo localmente, consulta o Asaas pela referência da mensalidade.
            // Se algum título estiver pago ou em estado incerto, cancela NENHUM deles.
            remotelyCancelled=asaas.cancelInvoicePayments(invoiceId,paymentId,(BigDecimal)invoice.get("amount"));
            int checkoutUpdated=db.update("""
                    UPDATE gs_invoice_checkout SET status='CANCELLED',invoice_url=NULL,updated_at=NOW()
                     WHERE invoice_id=? AND status IN ('CREATED','CREATING','CANCELLED')
                    """,invoiceId);
            if (checkoutUpdated!=1)
                throw new ResponseStatusException(HttpStatus.CONFLICT,
                        "O estado da emissão mudou durante a exclusão. Atualize a página.");
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
                    + (checkouts.isEmpty() ? " (sem emissão registrada)." :
                         " (conciliada no Asaas, cobranças removidas nesta tentativa=" + remotelyCancelled + ").")
                    + " ID interno=" + invoiceId);
    }

    /**
     * Garante que uma chamada de emissão ainda em andamento não seja cancelada.
     * O timeout máximo por chamada ao Asaas é 25s; 5 minutos cobre a sequência.
     */
    static boolean staleCheckout(java.time.Instant updatedAt, java.time.Instant now) {
        return updatedAt != null && now != null &&
                java.time.Duration.between(updatedAt,now).toMinutes() >= 5;
    }

    static void assertLocallyCancelable(String status, boolean paid, boolean verified) {
        if (!Set.of("PENDING","OVERDUE").contains(status) || paid || verified)
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "Somente mensalidades não pagas e sem confirmação financeira podem ser excluídas.");
    }
}
