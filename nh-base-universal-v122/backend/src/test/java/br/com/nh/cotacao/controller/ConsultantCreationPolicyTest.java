package br.com.nh.cotacao.controller;

import br.com.nh.cotacao.dto.PortalDtos.ConsultantResponse;
import br.com.nh.cotacao.dto.PortalDtos.CreateConsultantRequest;
import br.com.nh.cotacao.entity.CollaboratorRole;
import br.com.nh.cotacao.security.PortalPrincipal;
import br.com.nh.cotacao.security.PortalRole;
import br.com.nh.cotacao.service.ConsultantService;
import br.com.nh.cotacao.service.PortalUserService;
import org.junit.jupiter.api.Test;
import org.springframework.security.core.Authentication;

import java.time.OffsetDateTime;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertSame;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class ConsultantCreationPolicyTest {
    private final ConsultantService consultants = mock(ConsultantService.class);
    private final PortalUserService users = mock(PortalUserService.class);

    private ConsultantResponse consultant() {
        return new ConsultantResponse(
                UUID.randomUUID(), "Carla Dias", true, CollaboratorRole.CONSULTANT,
                null, null, null, null, 0, "CREATED_IN_PORTAL",
                OffsetDateTime.now(), null, 0, 0
        );
    }

    private Authentication session(String username, PortalRole role) {
        Authentication auth = mock(Authentication.class);
        when(auth.getPrincipal()).thenReturn(new PortalPrincipal(username, role));
        return auth;
    }

    @Test
    void collaboratorAreaCreatesSelectableNameWithoutIndividualLogin() {
        when(users.linkedConsultantId("colaborador")).thenReturn(Optional.empty());
        ConsultantResponse created = consultant();
        when(consultants.create("Carla Dias", "CREATED_IN_PORTAL", "colaborador"))
                .thenReturn(created);

        var controller = new ConsultantController(consultants, users);
        var result = controller.create(
                new CreateConsultantRequest("Carla Dias", null, null, null, null),
                session("colaborador", PortalRole.CONSULTANT)
        );

        assertSame(created, result);
        verify(consultants).create("Carla Dias", "CREATED_IN_PORTAL", "colaborador");
        verify(users, never()).ensureConsultantAccount(any());
    }

    @Test
    void adminCreationStillGeneratesPersonalConsultantLogin() {
        ConsultantResponse created = consultant();
        when(consultants.create("Carla Dias", CollaboratorRole.CONSULTANT,
                null, null, null, "ADMIN", "adm.empresa")).thenReturn(created);

        var controller = new AdminConsultantController(consultants, users);
        var result = controller.create(
                new CreateConsultantRequest("Carla Dias", CollaboratorRole.CONSULTANT,
                        null, null, null),
                session("adm.empresa", PortalRole.ADMIN)
        );

        assertSame(created, result);
        verify(users).ensureConsultantAccountByAdmin(created.id(), "adm.empresa");
    }
}
