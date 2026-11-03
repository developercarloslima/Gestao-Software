package br.com.gestaosoftware.control.controller;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.server.ResponseStatusException;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.util.HexFormat;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class TenantCompanyInvoicesControllerTest {
    private static final String SECRET = "um-segredo-de-integracao-com-mais-de-32-caracteres";

    private static String tokenFor(String host) throws Exception {
        Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec(SECRET.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
        return HexFormat.of().formatHex(mac.doFinal(host.getBytes(StandardCharsets.UTF_8)));
    }

    @Test void tokenMustBeBoundToExactlyOneHost() throws Exception {
        String token = tokenFor("empresa.exemplo.com.br");
        assertTrue(TenantCompanyInvoicesController.authorized(SECRET, "empresa.exemplo.com.br", token));
        assertFalse(TenantCompanyInvoicesController.authorized(SECRET, "outra.exemplo.com.br", token));
        assertFalse(TenantCompanyInvoicesController.authorized(SECRET, "empresa.exemplo.com.br", "invalid"));
        assertFalse(TenantCompanyInvoicesController.authorized("fraco", "empresa.exemplo.com.br", token));
    }

    @Test void invalidTokenMustNeverReachDatabase() {
        JdbcTemplate db = mock(JdbcTemplate.class);
        var controller = new TenantCompanyInvoicesController(db, SECRET);
        var ex = assertThrows(ResponseStatusException.class,
                () -> controller.list("empresa.exemplo.com.br", "outro-token"));
        assertEquals(HttpStatus.UNAUTHORIZED, ex.getStatusCode());
        verifyNoInteractions(db);
    }

    @Test void missingMasterFailsClosed() {
        JdbcTemplate db = mock(JdbcTemplate.class);
        var controller = new TenantCompanyInvoicesController(db, "");
        var ex = assertThrows(ResponseStatusException.class,
                () -> controller.list("empresa.exemplo.com.br", "token"));
        assertEquals(HttpStatus.SERVICE_UNAVAILABLE, ex.getStatusCode());
        verifyNoInteractions(db);
    }
}
