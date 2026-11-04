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

    @Test
    void parseAsaasValidationWithoutLeakingDocumentOrEmail() {
        var failure = AsaasSubaccountService.parseAsaasFailure(new ObjectMapper(),
                """
                {"errors":[{"code":"invalid_cpfCnpj","description":"CNPJ 11.222.333/0001-81 e e-mail pessoa@example.com inválidos."}]}
                """);
        assertEquals("invalid_cpfCnpj", failure.code());
        assertTrue(failure.message().contains("[número oculto]"));
        assertTrue(failure.message().contains("[e-mail oculto]"));
        assertFalse(failure.message().contains("11.222"));
        assertFalse(failure.message().contains("pessoa@example.com"));
        assertFalse(failure.possibleDuplicate());
    }

    @Test
    void protectAgainstDuplicateAccountResponse() {
        var failure = AsaasSubaccountService.parseAsaasFailure(new ObjectMapper(),
                """
                {"errors":[{"code":"already_exists","description":"Já existe uma conta cadastrada com esse CNPJ."}]}
                """);
        assertTrue(failure.possibleDuplicate());
    }

    @Test
    void fallbackForHtmlAndRedactCredentialHints() {
        var html = AsaasSubaccountService.parseAsaasFailure(new ObjectMapper(), "<html>502</html>");
        assertNull(html.code());
        assertFalse(html.message().contains("<html>"));
        var credential = AsaasSubaccountService.parseAsaasFailure(new ObjectMapper(),
                """
                {"errors":[{"code":"unauthorized","description":"access_token: $aact_secretvalue"}]}
                """);
        assertFalse(credential.message().contains("$aact_secretvalue"));
    }
}
