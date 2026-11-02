package br.com.gestaosoftware.control.service;

import br.com.gestaosoftware.control.security.AuthAttemptLimiter;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.web.server.ResponseStatusException;

import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.*;

class AuthAttemptLimiterTest {
    @Test
    void repeatedSuccessfulLoginsDoNotLockSharedAccount() {
        AuthAttemptLimiter limiter = new AuthAttemptLimiter();
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setRemoteAddr("203.0.113.50");
        for (int i = 0; i < 150; i++) {
            assertDoesNotThrow(() -> limiter.login("shared-account", request));
            limiter.successfulLogin("shared-account");
        }
    }

    @Test
    void blocksRepeatedLoginAcrossIpsAndRecoversAfterWindow() {
        AtomicLong now = new AtomicLong(1_000);
        AuthAttemptLimiter limiter = new AuthAttemptLimiter(now::get);
        for (int i = 0; i < 24; i++) {
            MockHttpServletRequest request = new MockHttpServletRequest();
            request.setRemoteAddr("198.51.100." + i);
            limiter.login("Carlos.Lima", request);
            limiter.failedLogin("Carlos.Lima", request);
        }
        MockHttpServletRequest next = new MockHttpServletRequest();
        next.setRemoteAddr("203.0.113.20");
        ResponseStatusException ex = assertThrows(ResponseStatusException.class,
                () -> limiter.login("carlos.lima", next));
        assertEquals(HttpStatus.TOO_MANY_REQUESTS, ex.getStatusCode());
        now.addAndGet(10 * 60 * 1000L);
        assertDoesNotThrow(() -> limiter.login("carlos.lima", next));
        limiter.successfulLogin("carlos.lima");
        assertDoesNotThrow(() -> limiter.login("carlos.lima", next));
    }

    @Test
    void limitsPasswordResetRequests() {
        AuthAttemptLimiter limiter = new AuthAttemptLimiter();
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setRemoteAddr("203.0.113.15");
        for (int i = 0; i < 4; i++) limiter.recovery("user", request);
        ResponseStatusException ex = assertThrows(ResponseStatusException.class,
                () -> limiter.recovery("user", request));
        assertEquals(HttpStatus.TOO_MANY_REQUESTS, ex.getStatusCode());
    }
}
