package br.com.gestaosoftware.control.controller;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.server.ResponseStatusException;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.util.HexFormat;
import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class TenantCompanyInvoicesControllerTest {
    private static final String SECRET = "um-segredo-de-integracao-com-mais-de-32-caracteres";

    private static String tokenFor(String host) throws Exception {
        Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec(SECRET.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
        return HexFormat.of().formatHex(mac.doFinal(host.getBytes(StandardCharsets.UTF_8)));
    }

    @Test void onlyInvoiceBelongingToTheAuthenticatedCompanyCanBeIssued() throws Exception {
        JdbcTemplate db = mock(JdbcTemplate.class);
        var gateway = mock(br.com.gestaosoftware.control.service.PlatformPaymentGateway.class);
        var controller = spy(new TenantCompanyInvoicesController(db,
                mock(br.com.gestaosoftware.control.service.ProvisioningService.class),
                mock(br.com.gestaosoftware.control.service.PlatformBillingScheduleService.class),
                gateway, SECRET));
        java.util.UUID companyId=java.util.UUID.randomUUID();
        java.util.UUID invoiceId=java.util.UUID.randomUUID();
        String host="empresa.exemplo.com.br", token=tokenFor(host);
        doReturn(Map.of("companyId",companyId.toString())).when(controller).list(host,token);
        when(db.queryForObject(org.mockito.ArgumentMatchers.contains("FROM platform_company_invoices"),
                org.mockito.ArgumentMatchers.eq(Integer.class),org.mockito.ArgumentMatchers.eq(invoiceId),
                org.mockito.ArgumentMatchers.eq(companyId))).thenReturn(1);
        when(gateway.checkout(invoiceId)).thenReturn(Map.of("invoiceUrl","https://sandbox.asaas.com/i/test"));

        assertEquals("https://sandbox.asaas.com/i/test",controller.issueBoleto(host,token,invoiceId).get("invoiceUrl"));
        verify(gateway).checkout(invoiceId);
    }

    @Test void cannotIssueInvoiceOutsideCompany() throws Exception {
        JdbcTemplate db=mock(JdbcTemplate.class);
        var gateway=mock(br.com.gestaosoftware.control.service.PlatformPaymentGateway.class);
        var controller=spy(new TenantCompanyInvoicesController(db,
                mock(br.com.gestaosoftware.control.service.ProvisioningService.class),
                mock(br.com.gestaosoftware.control.service.PlatformBillingScheduleService.class),
                gateway, SECRET));
        java.util.UUID companyId=java.util.UUID.randomUUID(), invoiceId=java.util.UUID.randomUUID();
        String host="empresa.exemplo.com.br", token=tokenFor(host);
        doReturn(Map.of("companyId",companyId.toString())).when(controller).list(host,token);
        when(db.queryForObject(org.mockito.ArgumentMatchers.contains("FROM platform_company_invoices"),
                org.mockito.ArgumentMatchers.eq(Integer.class),org.mockito.ArgumentMatchers.eq(invoiceId),
                org.mockito.ArgumentMatchers.eq(companyId))).thenReturn(0);

        var ex=assertThrows(ResponseStatusException.class,()->controller.issueBoleto(host,token,invoiceId));
        assertEquals(HttpStatus.NOT_FOUND,ex.getStatusCode());
        verifyNoInteractions(gateway);
    }

    @Test void tokenMustBeBoundToExactlyOneHost() throws Exception {
        String token = tokenFor("empresa.exemplo.com.br");
        assertTrue(TenantCompanyInvoicesController.authorized(SECRET, "empresa.exemplo.com.br", token));
        assertFalse(TenantCompanyInvoicesController.authorized(SECRET, "outra.exemplo.com.br", token));
        assertFalse(TenantCompanyInvoicesController.authorized(SECRET, "empresa.exemplo.com.br", "invalid"));
        assertFalse(TenantCompanyInvoicesController.authorized("fraco", "empresa.exemplo.com.br", token));
    }

    @Test void invalidTokenMustNeverReachDatabase() {
        JdbcTemplate db = mock(JdbcTemplate.class);
        var controller = new TenantCompanyInvoicesController(db, mock(br.com.gestaosoftware.control.service.ProvisioningService.class), mock(br.com.gestaosoftware.control.service.PlatformBillingScheduleService.class), mock(br.com.gestaosoftware.control.service.PlatformPaymentGateway.class), SECRET);
        var ex = assertThrows(ResponseStatusException.class,
                () -> controller.list("empresa.exemplo.com.br", "outro-token"));
        assertEquals(HttpStatus.UNAUTHORIZED, ex.getStatusCode());
        verifyNoInteractions(db);
    }

    @Test void missingMasterFailsClosed() {
        JdbcTemplate db = mock(JdbcTemplate.class);
        var controller = new TenantCompanyInvoicesController(db, mock(br.com.gestaosoftware.control.service.ProvisioningService.class), mock(br.com.gestaosoftware.control.service.PlatformBillingScheduleService.class), mock(br.com.gestaosoftware.control.service.PlatformPaymentGateway.class), "");
        var ex = assertThrows(ResponseStatusException.class,
                () -> controller.list("empresa.exemplo.com.br", "token"));
        assertEquals(HttpStatus.SERVICE_UNAVAILABLE, ex.getStatusCode());
        verifyNoInteractions(db);
    }
    @Test void monthlyInstallmentPreviewShowsTwelveWithoutDuplicatingRealInvoices() {
        Map<String, Object> company = Map.of(
                "contractTerm", "MONTHLY",
                "contractStartDate", "2026-11-03",
                "nextRenewalDate", "2027-11-03",
                "billingDueDay", 10,
                "monthlyFee", BigDecimal.ZERO);
        Map<String, Object> november = new LinkedHashMap<>(Map.of(
                "referenceMonth", "2026-11-01", "status", "PENDING",
                "amount", new BigDecimal("0.10"), "dueDate", "2026-11-10"));
        Map<String, Object> december = new LinkedHashMap<>(Map.of(
                "referenceMonth", "2026-12-01", "status", "PAID_IN_CASH",
                "amount", new BigDecimal("12.00"), "dueDate", "2026-12-10"));
        List<Map<String, Object>> schedule = TenantCompanyInvoicesController.withExpectedMonthlyInstallments(
                company, List.of(december, november));

        assertEquals(12, schedule.size());
        assertEquals(10, schedule.stream().filter(i -> "SCHEDULED".equals(i.get("status"))).count());
        assertEquals("2027-10-01", schedule.get(0).get("referenceMonth"));
        assertEquals("2027-10-10", schedule.get(0).get("dueDate"));
        assertNull(schedule.get(0).get("invoiceUrl"));
        assertNull(schedule.get(0).get("id"));
        assertSame(december, schedule.stream().filter(i -> "2026-12-01".equals(i.get("referenceMonth"))).findFirst().orElseThrow());
        assertSame(november, schedule.stream().filter(i -> "2026-11-01".equals(i.get("referenceMonth"))).findFirst().orElseThrow());
        assertEquals(new BigDecimal("12.00"), december.get("amount"));
    }

    @Test void annualPlanDoesNotInventMonthlyInvoices() {
        Map<String, Object> annual = Map.of(
                "contractTerm", "ANNUAL", "contractStartDate", "2026-11-03",
                "billingDueDay", 10, "monthlyFee", new BigDecimal("50"));
        assertTrue(TenantCompanyInvoicesController.withExpectedMonthlyInstallments(annual, List.of()).isEmpty());
    }


}
