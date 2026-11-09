package br.com.nh.cotacao.service;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.UUID;

/** BI comercial da empresa: emissão não é venda; somente a adesão quitada conta. */
@Service
public class CommercialSalesBiService {
    private static final ZoneId ZONE = ZoneId.of("America/Sao_Paulo");
    private final JdbcTemplate jdbc;

    public record ConsultantRow(UUID consultantId, String consultantName, long issuedBoletos,
                                long salesByBoleto, long salesByPix, long otherPaidSales) {}
    public record Report(LocalDate from, LocalDate to, long issuedBoletos,
                         long salesByBoleto, long salesByPix, long otherPaidSales,
                         long totalSales, long legacyBoletosWithoutIssueDate,
                         String definition, List<ConsultantRow> consultants) {}

    public CommercialSalesBiService(JdbcTemplate jdbc) { this.jdbc = jdbc; }

    @Transactional(readOnly = true)
    public Report report(LocalDate requestedFrom, LocalDate requestedTo) {
        LocalDate today = LocalDate.now(ZONE);
        LocalDate from = requestedFrom == null ? today.withDayOfMonth(1) : requestedFrom;
        LocalDate to = requestedTo == null ? today : requestedTo;
        if (to.isBefore(from) || ChronoUnit.DAYS.between(from, to) > 366L * 5)
            throw new IllegalArgumentException("Selecione um período válido de até cinco anos.");

        OffsetDateTime start = from.atStartOfDay(ZONE).toOffsetDateTime();
        OffsetDateTime exclusiveEnd = to.plusDays(1).atStartOfDay(ZONE).toOffsetDateTime();
        var totals = jdbc.queryForObject("""
            WITH charge_events AS (
              SELECT f.*,
                  (SELECT MIN(i.accepted_at) FROM inspection_requests i
                    WHERE i.quotation_id=v.quotation_id AND i.status='APPROVED'
                      AND i.request_type='NEW_INSPECTION' AND i.accepted_at IS NOT NULL) AS accepted_at
              FROM financial_charges f
              JOIN commercial_vehicles v ON v.id=f.vehicle_id
            )
            SELECT COUNT(*) FILTER (WHERE billing_type='BOLETO' AND provider_payment_id IS NOT NULL
                  AND issued_at>=? AND issued_at<?) AS boletos,
                   COUNT(DISTINCT vehicle_id) FILTER (WHERE charge_type='ADHESION' AND status='RECEIVED'
                  AND accepted_at IS NOT NULL AND payment_method='BOLETO'
                  AND GREATEST(paid_at,accepted_at)>=? AND GREATEST(paid_at,accepted_at)<?) AS boleto_sales,
                   COUNT(DISTINCT vehicle_id) FILTER (WHERE charge_type='ADHESION' AND status='RECEIVED'
                  AND accepted_at IS NOT NULL AND payment_method='PIX'
                  AND GREATEST(paid_at,accepted_at)>=? AND GREATEST(paid_at,accepted_at)<?) AS pix_sales,
                   COUNT(DISTINCT vehicle_id) FILTER (WHERE charge_type='ADHESION' AND status='RECEIVED'
                  AND accepted_at IS NOT NULL
                  AND (payment_method IS NULL OR payment_method NOT IN ('BOLETO','PIX'))
                  AND GREATEST(paid_at,accepted_at)>=? AND GREATEST(paid_at,accepted_at)<?) AS other_sales,
                   COUNT(DISTINCT vehicle_id) FILTER (WHERE charge_type='ADHESION' AND status='RECEIVED'
                  AND accepted_at IS NOT NULL
                  AND GREATEST(paid_at,accepted_at)>=? AND GREATEST(paid_at,accepted_at)<?) AS sales,
                   COUNT(*) FILTER (WHERE billing_type='BOLETO' AND provider_payment_id IS NOT NULL
                  AND issued_at IS NULL) AS legacy_issued
              FROM charge_events
            """, (rs, ignored) -> new long[]{rs.getLong("boletos"), rs.getLong("boleto_sales"),
                    rs.getLong("pix_sales"), rs.getLong("other_sales"), rs.getLong("sales"),
                    rs.getLong("legacy_issued")},
                start, exclusiveEnd, start, exclusiveEnd, start, exclusiveEnd,
                start, exclusiveEnd, start, exclusiveEnd);

        List<ConsultantRow> consultants = jdbc.query("""
            WITH charge_events AS (
              SELECT f.*,
                  (SELECT MIN(i.accepted_at) FROM inspection_requests i
                    WHERE i.quotation_id=v.quotation_id AND i.status='APPROVED'
                      AND i.request_type='NEW_INSPECTION' AND i.accepted_at IS NOT NULL) AS accepted_at
              FROM financial_charges f
              JOIN commercial_vehicles v ON v.id=f.vehicle_id
            )
            SELECT c.id, c.name,
                COUNT(f.id) FILTER (WHERE f.billing_type='BOLETO' AND f.provider_payment_id IS NOT NULL
                       AND f.issued_at>=? AND f.issued_at<?) AS boletos,
                COUNT(DISTINCT f.vehicle_id) FILTER (WHERE f.charge_type='ADHESION' AND f.status='RECEIVED'
                       AND f.accepted_at IS NOT NULL AND f.payment_method='BOLETO'
                       AND GREATEST(f.paid_at,f.accepted_at)>=?
                       AND GREATEST(f.paid_at,f.accepted_at)<?) AS boleto_sales,
                COUNT(DISTINCT f.vehicle_id) FILTER (WHERE f.charge_type='ADHESION' AND f.status='RECEIVED'
                       AND f.accepted_at IS NOT NULL AND f.payment_method='PIX'
                       AND GREATEST(f.paid_at,f.accepted_at)>=?
                       AND GREATEST(f.paid_at,f.accepted_at)<?) AS pix_sales,
                COUNT(DISTINCT f.vehicle_id) FILTER (WHERE f.charge_type='ADHESION' AND f.status='RECEIVED'
                       AND f.accepted_at IS NOT NULL
                       AND (f.payment_method IS NULL OR f.payment_method NOT IN ('BOLETO','PIX'))
                       AND GREATEST(f.paid_at,f.accepted_at)>=?
                       AND GREATEST(f.paid_at,f.accepted_at)<?) AS other_sales
              FROM consultants c LEFT JOIN charge_events f ON f.consultant_id=c.id
             WHERE c.collaborator_role='CONSULTANT'
             GROUP BY c.id,c.name
             ORDER BY boleto_sales DESC,pix_sales DESC,other_sales DESC,c.name ASC
             LIMIT 200
            """, (rs, ignored) -> new ConsultantRow(rs.getObject("id", UUID.class), rs.getString("name"),
                    rs.getLong("boletos"), rs.getLong("boleto_sales"), rs.getLong("pix_sales"),
                    rs.getLong("other_sales")),
                start, exclusiveEnd, start, exclusiveEnd, start, exclusiveEnd,
                start, exclusiveEnd);

        if (totals == null) throw new IllegalStateException("Não foi possível consolidar o BI financeiro.");
        return new Report(from, to, totals[0], totals[1], totals[2], totals[3], totals[4], totals[5],
                "Venda = adesão efetivamente recebida e não estornada; boleto emitido não é venda. "
                + "Emissões antigas sem data confiável de emissão não são atribuídas ao período.", consultants);
    }
}
