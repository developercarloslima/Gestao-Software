package br.com.gestaosoftware.control.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

import java.util.Base64;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

class AsaasSubaccountServiceTest {
    private static final String SANDBOX = "https://api-sandbox.asaas.com/v3";
    private static final String KEY = Base64.getEncoder().encodeToString(new byte[32]);

    @Test
    void failClosedWithoutParentKey() {
        var service = new AsaasSubaccountService(null, new ObjectMapper(), "", SANDBOX, KEY);
        var error = assertThrows(ResponseStatusException.class,
                () -> service.create(UUID.randomUUID(), null, "carlos.lima"));
        assertEquals(HttpStatus.SERVICE_UNAVAILABLE, error.getStatusCode());
    }

    @Test
    void failClosedWithoutEncryptionSecret() {
        var service = new AsaasSubaccountService(null, new ObjectMapper(), "sandbox-parent", SANDBOX, "");
        var error = assertThrows(ResponseStatusException.class,
                () -> service.create(UUID.randomUUID(), null, "carlos.lima"));
        assertEquals(HttpStatus.SERVICE_UNAVAILABLE, error.getStatusCode());
    }

    @Test
    void refuseUnapprovedApiEndpoint() {
        var service = new AsaasSubaccountService(null, new ObjectMapper(), "sandbox-parent",
                "https://untrusted.example/v3", KEY);
        var error = assertThrows(ResponseStatusException.class,
                () -> service.create(UUID.randomUUID(), null, "carlos.lima"));
        assertEquals(HttpStatus.SERVICE_UNAVAILABLE, error.getStatusCode());
    }

    @Test
    void rejectEmptyRegistrationBeforeDatabaseOrAsaasCall() {
        var service = new AsaasSubaccountService(null, new ObjectMapper(), "sandbox-parent", SANDBOX, KEY);
        var error = assertThrows(ResponseStatusException.class,
                () -> service.create(UUID.randomUUID(), null, "carlos.lima"));
        assertEquals(HttpStatus.BAD_REQUEST, error.getStatusCode());
    }
}
