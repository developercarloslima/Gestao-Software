package br.com.gestaosoftware.control.service;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Locale;
import java.util.Map;

@Service
public class PlatformUserService {
    private final JdbcTemplate jdbc;
    private final PasswordEncoder encoder;

    public PlatformUserService(JdbcTemplate jdbc, PasswordEncoder encoder) { this.jdbc = jdbc; this.encoder = encoder; }

    @Transactional(readOnly = true)
    public UserSession authenticate(String username, String password) {
        String normalized = normalize(username);
        var rows = jdbc.query("SELECT username, display_name, password_hash, must_change_password, active FROM gs_users WHERE username=?", (rs,row)-> Map.<String,Object>of(
                "username", rs.getString("username"), "displayName", rs.getString("display_name"), "passwordHash", rs.getString("password_hash"),
                "mustChange", rs.getBoolean("must_change_password"), "active", rs.getBoolean("active")
        ), normalized);
        if (rows.isEmpty()) throw new IllegalArgumentException("Usuário ou senha inválidos.");
        Map<String,Object> user = rows.getFirst();
        if (!Boolean.TRUE.equals(user.get("active")) || !encoder.matches(password == null ? "" : password, String.valueOf(user.get("passwordHash")))) {
            throw new IllegalArgumentException("Usuário ou senha inválidos.");
        }
        return new UserSession(String.valueOf(user.get("username")), String.valueOf(user.get("displayName")), Boolean.TRUE.equals(user.get("mustChange")));
    }

    @Transactional(readOnly = true)
    public UserSession session(String username) {
        return jdbc.query("SELECT username, display_name, must_change_password FROM gs_users WHERE username=? AND active=TRUE", rs -> {
            if (!rs.next()) throw new IllegalArgumentException("Conta não encontrada ou inativa.");
            return new UserSession(rs.getString("username"), rs.getString("display_name"), rs.getBoolean("must_change_password"));
        }, normalize(username));
    }

    @Transactional(readOnly = true)
    public boolean active(String username) {
        Integer count = jdbc.queryForObject("SELECT COUNT(*) FROM gs_users WHERE username=? AND active=TRUE", Integer.class, normalize(username));
        return count != null && count > 0;
    }

    @Transactional(readOnly = true)
    public boolean requiresPasswordChange(String username) { return session(username).passwordChangeRequired(); }

    @Transactional
    public UserSession changePassword(String username, String currentPassword, String newPassword) {
        if (newPassword == null || newPassword.length() < 8) throw new IllegalArgumentException("A nova senha precisa ter pelo menos 8 caracteres.");
        UserSession current = authenticate(username, currentPassword);
        jdbc.update("UPDATE gs_users SET password_hash=?, must_change_password=FALSE, updated_at=NOW() WHERE username=?", encoder.encode(newPassword), current.username());
        return session(current.username());
    }

    @Transactional
    public void ensureOwners() {
        ensureOwner("carlos.lima", "Carlos Lima");
        ensureOwner("pedro.henrique", "Pedro Henrique");
    }

    private void ensureOwner(String username, String displayName) {
        Integer count = jdbc.queryForObject("SELECT COUNT(*) FROM gs_users WHERE username=?", Integer.class, username);
        if (count != null && count > 0) return;
        jdbc.update("INSERT INTO gs_users(username, display_name, password_hash, must_change_password, active, created_at, updated_at) VALUES (?,?,?,TRUE,TRUE,NOW(),NOW())",
                username, displayName, encoder.encode("nh2027"));
    }

    private String normalize(String value) { return value == null ? "" : value.trim().toLowerCase(Locale.ROOT); }
    public record UserSession(String username, String displayName, boolean passwordChangeRequired) {}
}
