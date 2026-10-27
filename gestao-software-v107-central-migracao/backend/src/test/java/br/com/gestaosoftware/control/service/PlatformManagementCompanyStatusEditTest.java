package br.com.gestaosoftware.control.service;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

import static org.junit.jupiter.api.Assertions.*;

class PlatformManagementCompanyStatusEditTest {
    @Test
    void ownersCanDeactivateOrReactivateBySavingCompanyEdit() {
        assertEquals("DEACTIVATE", PlatformManagementService.updateLifecycleTransition("ACTIVE", "SUSPENDED", "carlos.lima"));
        assertEquals("DEACTIVATE", PlatformManagementService.updateLifecycleTransition("TRIAL", "SUSPENDED", "pedro.henrique"));
        assertEquals("REACTIVATE", PlatformManagementService.updateLifecycleTransition("SUSPENDED", "ACTIVE", "CARLOS.LIMA"));
        assertEquals("REACTIVATE", PlatformManagementService.updateLifecycleTransition("SUSPENDED", "TRIAL", "PEDRO.HENRIQUE"));
    }

    @Test
    void savingOtherFieldsOrSwitchingBetweenActiveAndTrialNeedsNoLifecycleCommand() {
        assertEquals("NONE", PlatformManagementService.updateLifecycleTransition("SUSPENDED", "SUSPENDED", "outro.admin"));
        assertEquals("NONE", PlatformManagementService.updateLifecycleTransition("ACTIVE", "TRIAL", "outro.admin"));
    }

    @Test
    void otherAdminsCannotDeactivateOrReactivateThroughCompanyEdit() {
        ResponseStatusException denied = assertThrows(ResponseStatusException.class,
                () -> PlatformManagementService.updateLifecycleTransition("SUSPENDED", "ACTIVE", "outro.admin"));
        assertEquals(HttpStatus.FORBIDDEN, denied.getStatusCode());
        assertEquals(HttpStatus.FORBIDDEN, assertThrows(ResponseStatusException.class,
                () -> PlatformManagementService.updateLifecycleTransition("ACTIVE", "SUSPENDED", "")).getStatusCode());
    }

    @Test
    void archivingAndRestoringRemainExplicitAuditedActions() {
        assertEquals(HttpStatus.CONFLICT, assertThrows(ResponseStatusException.class,
                () -> PlatformManagementService.updateLifecycleTransition("CANCELLED", "ACTIVE", "carlos.lima")).getStatusCode());
        assertEquals(HttpStatus.CONFLICT, assertThrows(ResponseStatusException.class,
                () -> PlatformManagementService.updateLifecycleTransition("ACTIVE", "CANCELLED", "pedro.henrique")).getStatusCode());
    }
}
