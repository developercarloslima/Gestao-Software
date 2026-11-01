package br.com.nh.cotacao.service;

import br.com.nh.cotacao.entity.CatalogChangeAudit;
import br.com.nh.cotacao.entity.Consultant;
import br.com.nh.cotacao.entity.PortalUser;
import br.com.nh.cotacao.repository.AssociateRepository;
import br.com.nh.cotacao.repository.CatalogChangeAuditRepository;
import br.com.nh.cotacao.repository.ConsultantRepository;
import br.com.nh.cotacao.repository.InspectionRequestRepository;
import br.com.nh.cotacao.repository.PortalUserRepository;
import br.com.nh.cotacao.security.PortalRole;
import org.junit.jupiter.api.Test;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class PortalUserServiceDeleteTest {
    private final PortalUserRepository users = mock(PortalUserRepository.class);
    private final CatalogChangeAuditRepository audit = mock(CatalogChangeAuditRepository.class);
    private final ConsultantRepository collaborators = mock(ConsultantRepository.class);
    private final PortalUserService service = new PortalUserService(
            users, audit, collaborators, mock(AssociateRepository.class),
            mock(InspectionRequestRepository.class), mock(ConsultantService.class)
    );

    @Test
    void removesOnlyPersonalLoginAndAuditsWithoutTouchingCollaborator() {
        Consultant person = Consultant.create("Maria Costa", "CREATED_IN_PORTAL");
        PortalUser login = PortalUser.create("maria.costa", "Maria Costa",
                "bcrypt-value", PortalRole.CONSULTANT, "adm.empresa");
        login.linkConsultant(person.getId());
        when(users.findById(login.getId())).thenReturn(Optional.of(login));

        service.delete(login.getId(), "adm.empresa");

        verify(users).delete(same(login));
        verify(users).flush();
        verify(audit).save(any(CatalogChangeAudit.class));
        verifyNoInteractions(collaborators);
        assertEquals(person.getId(), login.getConsultantId());
        assertTrue(person.isActive());
    }

    @Test
    void inactiveLegacyAutomaticallyCreatedConsultantLoginCanBeDeleted() {
        PortalUser old = PortalUser.create("joao.silva", "João Silva",
                "bcrypt-value", PortalRole.CONSULTANT, "CONSULTANT_AUTO_BOOTSTRAP");
        old.setActive(false);
        when(users.findById(old.getId())).thenReturn(Optional.of(old));

        service.delete(old.getId(), "adm.empresa");

        verify(users).delete(same(old));
        verify(audit).save(any(CatalogChangeAudit.class));
    }

    @Test
    void cannotDeleteModuleAccountThatWouldBeRecreatedOnRestart() {
        PortalUser generic = PortalUser.create("colaborador", "Colaborador",
                "bcrypt-value", PortalRole.CONSULTANT, "GESTAO_MODULE_BOOTSTRAP");
        when(users.findById(generic.getId())).thenReturn(Optional.of(generic));

        assertThrows(IllegalArgumentException.class, () -> service.delete(generic.getId(), "adm.empresa"));
        verify(users, never()).delete(any(PortalUser.class));
    }

    @Test
    void cannotDeleteDeveloperOrTenantBootstrapAccounts() {
        for (String source : new String[]{"DEVELOPER_BOOTSTRAP", "TENANT_BOOTSTRAP", "BOOTSTRAP"}) {
            PortalUser account = PortalUser.create("principal" + source.toLowerCase(), "Principal",
                    "bcrypt-value", PortalRole.ADMIN, source);
            when(users.findById(account.getId())).thenReturn(Optional.of(account));
            assertThrows(IllegalArgumentException.class, () -> service.delete(account.getId(), "adm.empresa"));
        }
        verify(users, never()).delete(any(PortalUser.class));
    }

    @Test
    void cannotDeleteLoggedInAccount() {
        PortalUser own = PortalUser.create("adm.empresa", "Administrador",
                "bcrypt-value", PortalRole.ADMIN, "OUTRO_ADMIN");
        when(users.findById(own.getId())).thenReturn(Optional.of(own));

        var error = assertThrows(IllegalArgumentException.class,
                () -> service.delete(own.getId(), "ADM.EMPRESA"));
        assertTrue(error.getMessage().contains("próprio"));
        verify(users, never()).delete(any(PortalUser.class));
    }

    @Test
    void cannotDeleteLastActiveAdministrator() {
        PortalUser only = PortalUser.create("admin2", "Administrador",
                "bcrypt-value", PortalRole.ADMIN, "adm.empresa");
        when(users.findById(only.getId())).thenReturn(Optional.of(only));
        when(users.countByRoleAndActiveTrue(PortalRole.ADMIN)).thenReturn(1L);

        assertThrows(IllegalArgumentException.class, () -> service.delete(only.getId(), "adm.empresa"));
        verify(users, never()).delete(any(PortalUser.class));
    }

    @Test
    void allowsDeletingAdditionalAdministrator() {
        PortalUser other = PortalUser.create("admin2", "Administrador",
                "bcrypt-value", PortalRole.ADMIN, "adm.empresa");
        when(users.findById(other.getId())).thenReturn(Optional.of(other));
        when(users.countByRoleAndActiveTrue(PortalRole.ADMIN)).thenReturn(2L);

        service.delete(other.getId(), "adm.empresa");

        verify(users).delete(same(other));
        verify(audit).save(any(CatalogChangeAudit.class));
    }

    @Test
    void bootstrapNeverRecreatesAutomaticallyGeneratedPersonalLogins() {
        service.bootstrapConsultantAccounts();
        verifyNoInteractions(users, collaborators);
    }
}
