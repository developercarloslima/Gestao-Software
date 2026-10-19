package br.com.nh.cotacao.config;

import br.com.nh.cotacao.service.PortalUserService;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;

@Component
@ConditionalOnProperty(name="app.tenant.mode", havingValue="false", matchIfMissing=true)
public class PortalUserBootstrapConfig implements ApplicationRunner {
    private final PortalUserService service;
    private final String consultantUsername;
    private final String consultantPassword;
    private final String analystUsername;
    private final String analystPassword;
    private final String adminUsername;
    private final String adminPassword;
    private final String developerUsername;
    private final String developerPassword;
    private final String towUsername;
    private final String towPassword;
    private final String workshopUsername;
    private final String workshopPassword;
    private final String eventUsername;
    private final String eventPassword;
    private final String buyerUsername;
    private final String buyerPassword;

    public PortalUserBootstrapConfig(
            PortalUserService service,
            @Value("${app.auth.consultant-username}") String consultantUsername,
            @Value("${app.auth.consultant-password}") String consultantPassword,
            @Value("${app.auth.analyst-username}") String analystUsername,
            @Value("${app.auth.analyst-password}") String analystPassword,
            @Value("${app.auth.admin-username}") String adminUsername,
            @Value("${app.auth.admin-password}") String adminPassword,
            @Value("${app.auth.developer-username:}") String developerUsername,
            @Value("${app.auth.developer-password:}") String developerPassword,
            @Value("${app.auth.tow-username:guincho}") String towUsername,
            @Value("${app.auth.tow-password:}") String towPassword,
            @Value("${app.auth.workshop-username:oficina}") String workshopUsername,
            @Value("${app.auth.workshop-password:}") String workshopPassword,
            @Value("${app.auth.event-username:eventos}") String eventUsername,
            @Value("${app.auth.event-password:}") String eventPassword,
            @Value("${app.auth.buyer-username:financeiro}") String buyerUsername,
            @Value("${app.auth.buyer-password:}") String buyerPassword
    ) {
        this.service = service;
        this.consultantUsername = consultantUsername;
        this.consultantPassword = consultantPassword;
        this.analystUsername = analystUsername;
        this.analystPassword = analystPassword;
        this.adminUsername = adminUsername;
        this.adminPassword = adminPassword;
        this.developerUsername = developerUsername;
        this.developerPassword = developerPassword;
        this.towUsername = towUsername;
        this.towPassword = towPassword;
        this.workshopUsername = workshopUsername;
        this.workshopPassword = workshopPassword;
        this.eventUsername = eventUsername;
        this.eventPassword = eventPassword;
        this.buyerUsername = buyerUsername;
        this.buyerPassword = buyerPassword;
    }

    @Override
    public void run(ApplicationArguments args) {
        // Mantém os logins atuais do .env na primeira execução da V28, sem sobrescrever
        // senhas que forem alteradas posteriormente pelo painel administrativo.
        service.bootstrapDefaults(
                adminUsername, adminPassword,
                analystUsername, analystPassword,
                consultantUsername, consultantPassword
        );
        service.bootstrapDeveloperAdmin(developerUsername, developerPassword);
        service.bootstrapAnalysisTeam();
        service.bootstrapTowDriver(towUsername, towPassword);
        service.bootstrapWorkshopManager(workshopUsername, workshopPassword);
        service.bootstrapEventOperator(eventUsername, eventPassword);
        service.bootstrapBuyer(buyerUsername, buyerPassword);
        service.bootstrapConsultantAccounts();
    }
}
