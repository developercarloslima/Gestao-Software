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
    void recognizeActualAsaasAlreadyInUseResponse() {
        var failure = AsaasSubaccountService.parseAsaasFailure(new ObjectMapper(),
                """
                {"errors":[{"code":"invalid_object","description":"O CNPJ 11222333000181 já está em uso."}]}
                """);
        assertTrue(failure.possibleDuplicate());
        assertEquals("invalid_object", failure.code());
        assertFalse(failure.message().contains("11222333000181"));
    }

    @Test
    void inspectEmptyParentListWithoutClaimingGlobalAvailability() throws Exception {
        var found = AsaasSubaccountService.parseExistingAccount(new ObjectMapper(),
                """
                {"object":"list","hasMore":false,"data":[]}
                """, "11222333000181", "pessoa@example.com");
        assertFalse(found.found());
        assertNull(found.accountId());
    }

    @Test
    void inspectExistingParentAccountWithoutRecoveringApiKey() throws Exception {
        var found = AsaasSubaccountService.parseExistingAccount(new ObjectMapper(),
                """
                {"object":"list","hasMore":false,"data":[
                  {"id":"acc_123","walletId":"wallet_456","cpfCnpj":"11222333000181","email":"PESSOA@example.com"}
                ]}
                """, "11222333000181", "pessoa@example.com");
        assertTrue(found.found());
        assertEquals("acc_123", found.accountId());
        assertEquals("wallet_456", found.walletId());
        assertTrue(found.emailMatches());
    }

    @Test
    void rejectMalformedOrInconsistentAsaasLookup() {
        assertThrows(IllegalArgumentException.class, () -> AsaasSubaccountService.parseExistingAccount(
                new ObjectMapper(), "{\"error\":\"not a list\"}", "11222333000181", "x@example.com"));
        assertThrows(IllegalArgumentException.class, () -> AsaasSubaccountService.parseExistingAccount(
                new ObjectMapper(), "{\"data\":[{\"cpfCnpj\":\"00000000000191\"}]}",
                "11222333000181", "x@example.com"));
        assertThrows(IllegalArgumentException.class, () -> AsaasSubaccountService.parseExistingAccount(
                new ObjectMapper(), "{\"data\":[],\"hasMore\":true}",
                "11222333000181", "x@example.com"));
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
