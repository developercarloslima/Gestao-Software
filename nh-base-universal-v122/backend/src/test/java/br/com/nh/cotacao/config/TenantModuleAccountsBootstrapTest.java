package br.com.nh.cotacao.config;

import br.com.nh.cotacao.platform.GestaoTenantConfigService;
import br.com.nh.cotacao.security.PortalRole;
import br.com.nh.cotacao.service.PortalUserService;
import org.junit.jupiter.api.Test;

import static org.mockito.Mockito.*;

class TenantModuleAccountsBootstrapTest {
    private final GestaoTenantConfigService modules = mock(GestaoTenantConfigService.class);
    private final PortalUserService users = mock(PortalUserService.class);
    private final TenantModuleAccountsBootstrap bootstrap =
            new TenantModuleAccountsBootstrap(modules, users, "gestao1234");

    @Test
    void provisionsOnlyGenericUsersOfContractedModules() {
        when(modules.enabled("COTA")).thenReturn(true);
        bootstrap.provision();

        verify(users).bootstrapTenantModuleUser(
                "colaborador", "Colaborador", PortalRole.CONSULTANT, "gestao1234");
        verifyNoMoreInteractions(users);
    }

    @Test
    void provisionsAllModuleRolesWhenLicensed() {
        when(modules.enabled(anyString())).thenReturn(true);
        bootstrap.provision();

        verify(users).bootstrapTenantModuleUser(
                "colaborador", "Colaborador", PortalRole.CONSULTANT, "gestao1234");
        verify(users).bootstrapTenantModuleUser(
                "analista", "Analista padrão", PortalRole.ANALYST, "gestao1234");
        verify(users).bootstrapTenantModuleUser(
                "supervisor", "Supervisor padrão", PortalRole.SUPERVISION_ANALYSIS, "gestao1234");
        verify(users).bootstrapTenantModuleUser(
                "guincho", "Reboque / Guincho padrão", PortalRole.TOW_DRIVER, "gestao1234");
        verify(users).bootstrapTenantModuleUser(
                "oficina", "Oficina padrão", PortalRole.WORKSHOP_MANAGER, "gestao1234");
        verify(users).bootstrapTenantModuleUser(
                "eventos", "Eventos padrão", PortalRole.EVENT_OPERATOR, "gestao1234");
        verify(users).bootstrapTenantModuleUser(
                "financeiro", "Financeiro padrão", PortalRole.BUYER, "gestao1234");
        verifyNoMoreInteractions(users);
    }

    @Test
    void neverCreatesGenericUsersWhenModulesAreNotContracted() {
        bootstrap.provision();
        verifyNoInteractions(users);
    }
}
