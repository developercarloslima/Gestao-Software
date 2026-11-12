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
        PlatformManagementController controller = new PlatformManagementController(service, mock(PlatformInvoiceCancellationService.class));
        UUID id = UUID.randomUUID();

        controller.deactivateCompany(id, auth("carlos.lima"));
        controller.reactivateCompany(id, auth("CARLOS.LIMA"));

        verify(service).deactivateCompany(id, "carlos.lima");
        verify(service).reactivateCompany(id, "carlos.lima");
    }

    @Test
    void pedroCanArchiveAnyCompanyWithoutControllerPreconditions() {
        PlatformManagementService service = mock(PlatformManagementService.class);
        PlatformManagementController controller = new PlatformManagementController(service, mock(PlatformInvoiceCancellationService.class));
        UUID id = UUID.randomUUID();

        controller.deleteCompany(id, auth("pedro.henrique"));

        verify(service).deleteCompany(id, "pedro.henrique");
    }

    @Test
    void bothOwnersCanSaveCompanyEditsUsingTheirAuthenticatedIdentity() {
        PlatformManagementService service = mock(PlatformManagementService.class);
        PlatformManagementController controller = new PlatformManagementController(service, mock(PlatformInvoiceCancellationService.class));
        UUID id = UUID.randomUUID();
        PlatformManagementService.CompanyInput input = mock(PlatformManagementService.CompanyInput.class);

        controller.updateCompany(id, input, auth("carlos.lima"));
        controller.updateCompany(id, input, auth("pedro.henrique"));

        verify(service).updateCompany(id, input, "carlos.lima");
        verify(service).updateCompany(id, input, "pedro.henrique");
        assertThrows(ResponseStatusException.class, () -> controller.updateCompany(id, input, null));
    }

    @Test
    void onlyOwnersCanDeleteUnpaidInvoices() {
        PlatformManagementService service = mock(PlatformManagementService.class);
        PlatformInvoiceCancellationService cancellation = mock(PlatformInvoiceCancellationService.class);
        PlatformManagementController controller = new PlatformManagementController(service, cancellation);
        UUID invoiceId = UUID.randomUUID();

        controller.deleteInvoice(invoiceId, auth("carlos.lima"));
        controller.deleteInvoice(invoiceId, auth("pedro.henrique"));
        verify(cancellation).cancelInvoice(invoiceId, "carlos.lima");
        verify(cancellation).cancelInvoice(invoiceId, "pedro.henrique");

        assertThrows(ResponseStatusException.class, () -> controller.deleteInvoice(invoiceId, auth("outro.admin")));
        assertThrows(ResponseStatusException.class, () -> controller.deleteInvoice(invoiceId, null));
        verifyNoInteractions(service);
    }

    @Test
    void otherAdministratorsCannotChangeCompanyLifecycle() {
        PlatformManagementService service = mock(PlatformManagementService.class);
        PlatformManagementController controller = new PlatformManagementController(service, mock(PlatformInvoiceCancellationService.class));
        UUID id = UUID.randomUUID();
        Authentication otherAdmin = auth("outro.admin");

        assertThrows(ResponseStatusException.class, () -> controller.deactivateCompany(id, otherAdmin));
        assertThrows(ResponseStatusException.class, () -> controller.reactivateCompany(id, otherAdmin));
        assertThrows(ResponseStatusException.class, () -> controller.deleteCompany(id, otherAdmin));
        assertThrows(ResponseStatusException.class, () -> controller.deleteCompany(id, null));
        verifyNoInteractions(service);
    }
}
