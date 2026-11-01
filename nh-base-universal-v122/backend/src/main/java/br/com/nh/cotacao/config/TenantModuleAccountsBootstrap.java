package br.com.nh.cotacao.config;

import br.com.nh.cotacao.platform.GestaoTenantConfigService;
import br.com.nh.cotacao.security.PortalRole;
import br.com.nh.cotacao.service.PortalUserService;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Usuários genéricos por módulo para tenant. Usa a configuração da Gestão e
 * aguarda a API central estar acessível em vez de criar contas de outros módulos.
 */
@Component
@ConditionalOnProperty(name="app.tenant.mode",havingValue="true")
public class TenantModuleAccountsBootstrap {
    private final GestaoTenantConfigService gestao;
    private final PortalUserService users;
    private final String password;
    public TenantModuleAccountsBootstrap(GestaoTenantConfigService gestao,PortalUserService users,
            @Value("${app.tenant.module-temporary-password:gestao1234}") String password) {
        this.gestao=gestao;this.users=users;this.password=password;
    }
    @Scheduled(initialDelay=35000,fixedDelay=180000)
    public void provision() {
        if(gestao.enabled("COTA") || gestao.enabled("VISTORIA_EVENTOS")) {
            users.bootstrapTenantModuleUser("colaborador","Colaborador",PortalRole.CONSULTANT,password);
        }
        if(gestao.enabled("VISTORIA_EVENTOS")) {
            users.bootstrapTenantModuleUser("analista","Analista padrão",PortalRole.ANALYST,password);
            users.bootstrapTenantModuleUser("supervisor","Supervisor padrão",PortalRole.SUPERVISION_ANALYSIS,password);
            users.bootstrapTenantModuleUser("guincho","Reboque / Guincho padrão",PortalRole.TOW_DRIVER,password);
            users.bootstrapTenantModuleUser("oficina","Oficina padrão",PortalRole.WORKSHOP_MANAGER,password);
            users.bootstrapTenantModuleUser("eventos","Eventos padrão",PortalRole.EVENT_OPERATOR,password);
        }
        if(gestao.enabled("GESTAO_FINANCEIRA_COMERCIAL")) {
            users.bootstrapTenantModuleUser("financeiro","Financeiro padrão",PortalRole.BUYER,password);
        }
    }
}
