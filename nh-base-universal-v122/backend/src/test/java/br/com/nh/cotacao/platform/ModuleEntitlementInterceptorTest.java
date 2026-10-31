package br.com.nh.cotacao.platform;

import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class ModuleEntitlementInterceptorTest {
    private final GestaoTenantConfigService config = mock(GestaoTenantConfigService.class);
    private final ModuleEntitlementInterceptor interceptor = new ModuleEntitlementInterceptor(config);

    private boolean allowed(String path) throws Exception {
        MockHttpServletRequest req = new MockHttpServletRequest("GET", path);
        MockHttpServletResponse res = new MockHttpServletResponse();
        return interceptor.preHandle(req, res, new Object());
    }

    @Test
    void quoteAndInspectionHistoryDoNotRequireFinancialModule() throws Exception {
        when(config.config()).thenReturn(Map.of("active", true));
        when(config.enabled("COTA")).thenReturn(true);
        when(config.enabled("GESTAO_FINANCEIRA_COMERCIAL")).thenReturn(false);

        assertTrue(allowed("/api/consultant-dashboard/11111111-1111-1111-1111-111111111111"));
        assertTrue(allowed("/api/consultant-dashboard/inspections/22222222-2222-2222-2222-222222222222/report"));
        assertFalse(allowed("/api/commercial/me/finance"));
    }

    @Test
    void inspectionOnlyTenantCanSeeInspectionHistory() throws Exception {
        when(config.config()).thenReturn(Map.of("active", true));
        when(config.enabled("COTA")).thenReturn(false);
        when(config.enabled("VISTORIA_EVENTOS")).thenReturn(true);
        assertTrue(allowed("/api/consultant-dashboard/11111111-1111-1111-1111-111111111111"));
    }

    @Test
    void unlicensedTenantCannotUseConsultantDashboard() throws Exception {
        when(config.config()).thenReturn(Map.of("active", true));
        assertFalse(allowed("/api/consultant-dashboard/11111111-1111-1111-1111-111111111111"));
    }
}
