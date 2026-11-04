package br.com.nh.cotacao.service;

import br.com.nh.cotacao.dto.AdminUserDtos.CreatePortalUserRequest;
import br.com.nh.cotacao.entity.Associate;
import br.com.nh.cotacao.entity.PortalUser;
import br.com.nh.cotacao.repository.AssociateRepository;
import br.com.nh.cotacao.repository.CatalogChangeAuditRepository;
import br.com.nh.cotacao.repository.ConsultantRepository;
import br.com.nh.cotacao.repository.InspectionRequestRepository;
import br.com.nh.cotacao.repository.PortalUserRepository;
import br.com.nh.cotacao.security.PortalRole;
import org.junit.jupiter.api.Test;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class PortalUserServiceAcceptanceAccountTest {
    private static final String CPF = "52998224725";
    private final PortalUserRepository users = mock(PortalUserRepository.class);
    private final AssociateRepository associates = mock(AssociateRepository.class);
    private final PortalUserService service = new PortalUserService(
            users, mock(CatalogChangeAuditRepository.class),
            mock(ConsultantRepository.class), associates,
            mock(InspectionRequestRepository.class), mock(ConsultantService.class));

    @Test
    void createsCpfLoginInactiveWithStandardPasswordAndMandatoryReset() {
        Associate associate = Associate.create("Maria Silva", CPF, null, null);
        when(associates.findById(associate.getId())).thenReturn(Optional.of(associate));
        when(users.findByAssociateId(associate.getId())).thenReturn(Optional.empty());
        when(users.findByNormalizedUsername(CPF)).thenReturn(Optional.empty());
        when(users.save(any(PortalUser.class))).thenAnswer(call -> call.getArgument(0));

        String username = service.prepareAssociateAccountForAcceptance(associate.getId());

        assertEquals(CPF, username);
        var saved = org.mockito.ArgumentCaptor.forClass(PortalUser.class);
        verify(users).save(saved.capture());
        PortalUser account = saved.getValue();
        assertEquals(PortalRole.ASSOCIATE, account.getRole());
        assertEquals(associate.getId(), account.getAssociateId());
        assertFalse(account.isActive(), "O envio não deve liberar o portal antes do aceite");
        assertTrue(account.isMustChangePassword());
        assertTrue(new BCryptPasswordEncoder().matches("gestao1234", account.getPasswordHash()));
    }

    @Test
    void keepsExistingActiveAccountAndPasswordForAnotherVehicle() {
        Associate associate = Associate.create("Maria Silva", CPF, null, null);
        PortalUser existing = PortalUser.create(CPF, "Maria Silva", "old-hash",
                PortalRole.ASSOCIATE, "ASSOCIATE_AUTO_BOOTSTRAP");
        existing.linkAssociate(associate.getId());
        when(associates.findById(associate.getId())).thenReturn(Optional.of(associate));
        when(users.findByAssociateId(associate.getId())).thenReturn(Optional.of(existing));

        assertEquals(CPF, service.prepareAssociateAccountForAcceptance(associate.getId()));

        assertTrue(existing.isActive());
        assertEquals("old-hash", existing.getPasswordHash());
        verify(users, never()).save(any());
    }

    @Test
    void rejectsCpfOccupiedByAnotherRole() {
        Associate associate = Associate.create("Maria Silva", CPF, null, null);
        PortalUser occupied = PortalUser.create(CPF, "Administrador", "hash", PortalRole.ADMIN, "admin");
        when(associates.findById(associate.getId())).thenReturn(Optional.of(associate));
        when(users.findByAssociateId(associate.getId())).thenReturn(Optional.empty());
        when(users.findByNormalizedUsername(CPF)).thenReturn(Optional.of(occupied));

        assertThrows(IllegalArgumentException.class,
                () -> service.prepareAssociateAccountForAcceptance(associate.getId()));
        verify(users, never()).save(any());
    }

    @Test
    void activatesPendingAutoAccountWithoutChangingPassword() {
        Associate associate = Associate.create("Maria Silva", CPF, null, null);
        PortalUser pending = PortalUser.create(CPF, "Maria Silva", "original-hash",
                PortalRole.ASSOCIATE, "ASSOCIATE_AUTO_BOOTSTRAP");
        pending.linkAssociate(associate.getId());
        pending.setActive(false);
        when(associates.findById(associate.getId())).thenReturn(Optional.of(associate));
        when(users.findByAssociateId(associate.getId())).thenReturn(Optional.of(pending));
        when(users.findByNormalizedUsername(CPF)).thenReturn(Optional.of(pending));

        service.activateAssociateAccountAfterAcceptance(associate.getId());

        assertTrue(pending.isActive());
        assertEquals("original-hash", pending.getPasswordHash());
        verify(users).save(same(pending));
    }
    @Test
    void rejectsLoginBeforeAnyAcceptanceEvenWithDefaultPassword() {
        Associate associate = Associate.create("Maria Silva", CPF, null, null);
        PortalUser pending = PortalUser.create(CPF, "Maria Silva",
                new BCryptPasswordEncoder().encode("gestao1234"),
                PortalRole.ASSOCIATE, "ASSOCIATE_AUTO_BOOTSTRAP");
        pending.linkAssociate(associate.getId());
        pending.setActive(false);
        when(users.findByNormalizedUsername(CPF)).thenReturn(Optional.of(pending));

        assertThrows(IllegalArgumentException.class,
                () -> service.authenticate(CPF, "gestao1234"));
    }

    @Test
    void administratorListsEmployeesButNotAutomaticallyCreatedAssociateAccounts() {
        PortalUser associate = PortalUser.create(CPF, "Maria Silva", "hashed",
                PortalRole.ASSOCIATE, "ASSOCIATE_AUTO_BOOTSTRAP");
        associate.setActive(true); // Aceite já concluído: acesso continua ativo.
        PortalUser admin = PortalUser.create("funcionario.financeiro", "Financeiro", "hashed",
                PortalRole.ADMIN, "admin");
        when(users.findAllByOrderByRoleAscUsernameAsc()).thenReturn(List.of(associate, admin));

        var visible = service.list();

        assertEquals(1, visible.size());
        assertEquals("funcionario.financeiro", visible.get(0).username());
        assertTrue(associate.isActive(), "Ocultar da lista não pode desativar o login do associado");
    }

    @Test
    void initialPasswordIsShownOnlyWhenItIsStillTheActualPassword() {
        PortalUser firstAccess = PortalUser.create(CPF, "Maria Silva",
                new BCryptPasswordEncoder().encode("gestao1234"),
                PortalRole.ASSOCIATE, "ASSOCIATE_AUTO_BOOTSTRAP");
        firstAccess.requirePasswordChange();
        when(users.findByNormalizedUsername(CPF)).thenReturn(Optional.of(firstAccess));
        assertEquals("gestao1234", service.initialAssociatePasswordIfStillDefault(CPF));

        PortalUser changed = PortalUser.create(CPF, "Maria Silva",
                new BCryptPasswordEncoder().encode("outra-senha-forte"),
                PortalRole.ASSOCIATE, "ASSOCIATE_AUTO_BOOTSTRAP");
        when(users.findByNormalizedUsername(CPF)).thenReturn(Optional.of(changed));
        assertNull(service.initialAssociatePasswordIfStillDefault(CPF));
    }

    @Test
    void initialPasswordIsNotRevealedForAccountThatDoesNotExist() {
        when(users.findByNormalizedUsername(CPF)).thenReturn(Optional.empty());
        assertNull(service.initialAssociatePasswordIfStillDefault(CPF));
    }

    @Test
    void manuallyCreatedPersonalUserAlwaysStartsWithStandardPasswordAndMustChangeIt() {
        when(users.findByNormalizedUsername("funcionario.teste")).thenReturn(Optional.empty());
        when(users.save(any(PortalUser.class))).thenAnswer(call -> call.getArgument(0));

        service.create(new CreatePortalUserRequest(
                "funcionario.teste", "Funcionário Teste", null, PortalRole.BUYER, null, null), "admin");

        var saved = org.mockito.ArgumentCaptor.forClass(PortalUser.class);
        verify(users).save(saved.capture());
        PortalUser user = saved.getValue();
        assertTrue(new BCryptPasswordEncoder().matches("gestao1234", user.getPasswordHash()));
        assertTrue(user.isMustChangePassword(), "A troca deve ser exigida no primeiro login");
        assertTrue(user.isActive());
    }

    @Test
    void cannotAutoRegisterByEnteringCpfAndSharedPasswordBeforeSending() {
        when(users.findByNormalizedUsername(CPF)).thenReturn(Optional.empty());

        assertThrows(IllegalArgumentException.class,
                () -> service.authenticate(CPF, "gestao1234"));
        verifyNoInteractions(associates);
        verify(users, never()).save(any());
    }

}
