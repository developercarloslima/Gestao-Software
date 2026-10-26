package br.com.gestaosoftware.control.service;

import br.com.gestaosoftware.control.controller.PlatformManagementController;
import br.com.gestaosoftware.control.security.PlatformPrincipal;
import org.junit.jupiter.api.Test;
import org.springframework.security.core.Authentication;
import org.springframework.web.server.ResponseStatusException;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.*;

class PlatformManagementControllerCompanyLifecycleTest {
    private static Authentication auth(String username) {
        Authentication auth = mock(Authentication.class);
        when(auth.getPrincipal()).thenReturn(new PlatformPrincipal(username));
        return auth;
    }

    @Test
    void carlosCanDeactivateAndReactivate() {
        PlatformManagementService service = mock(PlatformManagementService.class);
        PlatformManagementController controller = new PlatformManagementController(service);
        UUID id = UUID.randomUUID();

        controller.deactivateCompany(id, auth("carlos.lima"));
        controller.reactivateCompany(id, auth("CARLOS.LIMA"));

        verify(service).deactivateCompany(id, "carlos.lima");
        verify(service).reactivateCompany(id, "carlos.lima");
    }

    @Test
    void pedroCanArchiveAnyCompanyWithoutControllerPreconditions() {
        PlatformManagementService service = mock(PlatformManagementService.class);
        PlatformManagementController controller = new PlatformManagementController(service);
        UUID id = UUID.randomUUID();

        controller.deleteCompany(id, auth("pedro.henrique"));

        verify(service).deleteCompany(id, "pedro.henrique");
    }

    @Test
    void otherAdministratorsCannotChangeCompanyLifecycle() {
        PlatformManagementService service = mock(PlatformManagementService.class);
        PlatformManagementController controller = new PlatformManagementController(service);
        UUID id = UUID.randomUUID();
        Authentication otherAdmin = auth("outro.admin");

        assertThrows(ResponseStatusException.class, () -> controller.deactivateCompany(id, otherAdmin));
        assertThrows(ResponseStatusException.class, () -> controller.reactivateCompany(id, otherAdmin));
        assertThrows(ResponseStatusException.class, () -> controller.deleteCompany(id, otherAdmin));
        assertThrows(ResponseStatusException.class, () -> controller.deleteCompany(id, null));
        verifyNoInteractions(service);
    }
}
