package br.com.nh.cotacao.config;

import br.com.nh.cotacao.service.PortalUserService;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/** Uma empresa nova recebe apenas um administrador; não replica contas da NH. */
@Component
@ConditionalOnProperty(name="app.tenant.mode", havingValue="true")
public class TenantPortalBootstrapConfig implements ApplicationRunner {
    private final PortalUserService users;
    private final String login;
    private final String secret;
    private final String displayName;

    public TenantPortalBootstrapConfig(PortalUserService users,
            @Value("${app.auth.admin-username}") String login,
            @Value("${app.auth.admin-password}") String secret,
            @Value("${app.tenant.display-name:}") String displayName) {
        this.users=users;
        this.login=login;
        this.secret=secret;
        this.displayName=displayName;
    }

    @Override public void run(ApplicationArguments args) {
        users.bootstrapTenantAdmin(login,secret,displayName);
    }
}
