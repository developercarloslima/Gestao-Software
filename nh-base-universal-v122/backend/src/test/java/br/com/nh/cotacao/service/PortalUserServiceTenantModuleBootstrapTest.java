package br.com.nh.cotacao.service;

import br.com.nh.cotacao.dto.AdminUserDtos.ChangePortalUserPasswordRequest;
import br.com.nh.cotacao.dto.AdminUserDtos.CreatePortalUserRequest;
import br.com.nh.cotacao.entity.Consultant;
import br.com.nh.cotacao.entity.PortalUser;
import br.com.nh.cotacao.repository.AssociateRepository;
import br.com.nh.cotacao.repository.CatalogChangeAuditRepository;
import br.com.nh.cotacao.repository.ConsultantRepository;
import br.com.nh.cotacao.repository.InspectionRequestRepository;
import br.com.nh.cotacao.repository.PortalUserRepository;
import br.com.nh.cotacao.security.PortalRole;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class PortalUserServiceTenantModuleBootstrapTest {
    private final PortalUserRepository users = mock(PortalUserRepository.class);
    private final ConsultantRepository consultants = mock(ConsultantRepository.class);
    private final PortalUserService service = new PortalUserService(
            users,
            mock(CatalogChangeAuditRepository.class),
            consultants,
            mock(AssociateRepository.class),
            mock(InspectionRequestRepository.class),
            mock(ConsultantService.class)
    );
    private final BCryptPasswordEncoder encoder = new BCryptPasswordEncoder();

    @BeforeEach
    void saveEntities() {
        when(users.save(any(PortalUser.class))).thenAnswer(call -> call.getArgument(0));
    }

    @Test
    void newGenericModuleAccountKeepsDefaultPasswordWithoutFirstLoginReset() {
        when(users.findByNormalizedUsername("consultor")).thenReturn(Optional.empty());
        when(consultants.findByNormalizedName(anyString())).thenReturn(Optional.empty());
        when(consultants.save(any(Consultant.class))).thenAnswer(call -> call.getArgument(0));

        service.bootstrapTenantModuleUser(
                "consultor", "Consultor padrão", PortalRole.CONSULTANT, "gestao1234");

        var saved = org.mockito.ArgumentCaptor.forClass(PortalUser.class);
        verify(users).save(saved.capture());
        PortalUser account = saved.getValue();
        assertEquals(PortalRole.CONSULTANT, account.getRole());
        assertEquals("GESTAO_MODULE_BOOTSTRAP", account.getCreatedBy());
        assertNotNull(account.getConsultantId());
        assertFalse(account.isMustChangePassword());
        assertTrue(encoder.matches("gestao1234", account.getPasswordHash()));
    }

    @Test
    void oldGenericModuleAccountIsNormalizedWithoutResettingAdminPassword() {
        PortalUser account = PortalUser.create("analista", "Analista padrão",
                encoder.encode("SenhaAlteradaPeloAdm2026"), PortalRole.ANALYST, "GESTAO_MODULE_BOOTSTRAP");
        account.requirePasswordChange();
        String customHash = account.getPasswordHash();
        when(users.findByNormalizedUsername("analista")).thenReturn(Optional.of(account));

        service.bootstrapTenantModuleUser(
                "analista", "Analista padrão", PortalRole.ANALYST, "gestao1234");

        assertFalse(account.isMustChangePassword());
        assertEquals(customHash, account.getPasswordHash());
        assertTrue(encoder.matches("SenhaAlteradaPeloAdm2026", account.getPasswordHash()));
        verify(users).save(same(account));
        verifyNoInteractions(consultants);
        assertFalse(service.requiresPasswordChange("analista"));
    }

    @Test
    void existingPersonalAccountWithGenericLoginIsNeverModified() {
        PortalUser account = PortalUser.create("supervisor", "Pessoa cadastrada",
                encoder.encode("SenhaPessoa123"), PortalRole.SUPERVISION_ANALYSIS, "adm.empresa");
        account.requirePasswordChange();
        when(users.findByNormalizedUsername("supervisor")).thenReturn(Optional.of(account));

        service.bootstrapTenantModuleUser(
                "supervisor", "Supervisor padrão", PortalRole.SUPERVISION_ANALYSIS, "gestao1234");

        assertTrue(account.isMustChangePassword());
        verify(users, never()).save(any(PortalUser.class));
        verifyNoInteractions(consultants);
        assertTrue(service.requiresPasswordChange("supervisor"));
    }

    @Test
    void adminCreatedPersonalAccountMustChangePasswordOnFirstLogin() {
        when(users.findByNormalizedUsername("operador")).thenReturn(Optional.empty());

        var result = service.create(new CreatePortalUserRequest(
                "operador", "Operador cadastrado", "SenhaInicial2026",
                PortalRole.TOW_DRIVER, null, null), "adm.empresa");

        var saved = org.mockito.ArgumentCaptor.forClass(PortalUser.class);
        verify(users).save(saved.capture());
        assertTrue(saved.getValue().isMustChangePassword());
        assertTrue(result.mustChangePassword());
        assertEquals("adm.empresa", saved.getValue().getCreatedBy());
    }

    @Test
    void mainTenantAdminStillChangesPasswordAtFirstLogin() {
        when(users.findByNormalizedUsername("adm.empresa")).thenReturn(Optional.empty());

        service.bootstrapTenantAdmin("adm.empresa", "SenhaForteInicial2026",
                "Empresa Exemplo", false);

        var saved = org.mockito.ArgumentCaptor.forClass(PortalUser.class);
        verify(users).save(saved.capture());
        assertEquals(PortalRole.ADMIN, saved.getValue().getRole());
        assertTrue(saved.getValue().isMustChangePassword());
    }

    @Test
    void adminCanUpdateGenericPasswordWithoutTriggeringFirstLoginReset() {
        PortalUser account = PortalUser.create("guincho", "Guincho padrão",
                encoder.encode("gestao1234"), PortalRole.TOW_DRIVER, "GESTAO_MODULE_BOOTSTRAP");
        when(users.findById(account.getId())).thenReturn(Optional.of(account));

        var result = service.changePassword(account.getId(),
                new ChangePortalUserPasswordRequest("NovaSenhaForte2026"), "adm.empresa");

        assertTrue(encoder.matches("NovaSenhaForte2026", account.getPasswordHash()));
        assertFalse(account.isMustChangePassword());
        assertFalse(result.mustChangePassword());
        verify(users).save(same(account));
    }
}
