package br.com.nh.cotacao.security;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ResponseStatusException;

import java.util.Locale;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.LongSupplier;

/** Defesa local contra tentativas automatizadas; usar também rate limit distribuído na borda. */
@Component
public class AuthAttemptLimiter {
    private static final long WINDOW_MS = 10 * 60 * 1000L;
    private static final int MAX_KEYS = 20_000;
    private final ConcurrentHashMap<String, Counter> attempts = new ConcurrentHashMap<>();
    private final LongSupplier now;

    public AuthAttemptLimiter() { this(System::currentTimeMillis); }
    public AuthAttemptLimiter(LongSupplier now) { this.now = now; }

    public void login(String username, HttpServletRequest request) {
        assertBelowLimit("login:user:" + account(username), 24);
        assertBelowLimit("login:ip:" + remoteAddress(request), 90);
    }

    public void failedLogin(String username, HttpServletRequest request) {
        recordAttempt("login:user:" + account(username));
        recordAttempt("login:ip:" + remoteAddress(request));
    }

    public void successfulLogin(String username) {
        // Logins bem-sucedidos não penalizam a conta genérica compartilhada.
        attempts.remove("login:user:" + account(username));
    }

    public void recovery(String username, HttpServletRequest request) {
        enforce("recovery:user:" + account(username), 4);
        enforce("recovery:ip:" + remoteAddress(request), 20);
    }

    public void verifyRecovery(HttpServletRequest request) {
        enforce("recovery-confirm:ip:" + remoteAddress(request), 20);
    }

    public void sensitiveAction(HttpServletRequest request) {
        enforce("sensitive:ip:" + remoteAddress(request), 50);
    }

    private void enforce(String key, int limit) {
        assertBelowLimit(key, limit);
        recordAttempt(key);
    }

    private void assertBelowLimit(String key, int limit) {
        Counter counter = attempts.get(key);
        if (counter != null && now.getAsLong() < counter.expiresAt && counter.count >= limit) deny();
    }

    private void recordAttempt(String key) {
        long instant = now.getAsLong();
        if (attempts.size() >= MAX_KEYS && !attempts.containsKey(key)) {
            attempts.entrySet().removeIf(entry -> entry.getValue().expiresAt <= instant);
            if (attempts.size() >= MAX_KEYS) deny();
        }
        attempts.compute(key, (ignored, current) ->
                current == null || instant >= current.expiresAt
                        ? new Counter(1, instant + WINDOW_MS)
                        : new Counter(current.count + 1, current.expiresAt));
    }

    private static String account(String username) {
        String normalized = username == null ? "" : username.trim().toLowerCase(Locale.ROOT);
        return normalized.length() > 128 ? normalized.substring(0, 128) : normalized;
    }

    // Não usar X-Forwarded-For sem configurar explicitamente proxies confiáveis.
    private static String remoteAddress(HttpServletRequest request) {
        String address = request.getRemoteAddr();
        return address == null || address.isBlank() ? "unknown" : address;
    }

    private static void deny() {
        throw new ResponseStatusException(HttpStatus.TOO_MANY_REQUESTS,
                "Muitas tentativas. Aguarde alguns minutos antes de tentar novamente.");
    }

    private record Counter(int count, long expiresAt) {}
}
