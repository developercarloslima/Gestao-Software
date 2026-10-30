package br.com.gestaosoftware.control.service;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.HexFormat;
import java.util.Locale;

/** Tokens aleatórios, somente SHA-256 no DB, uso único e validade de 20 min. */
@Service
public class PlatformPasswordResetService {
    private final JdbcTemplate jdbc;
    private final PasswordEncoder encoder;
    private final PasswordResetMailer mailer;
    private final SecureRandom random=new SecureRandom();
    public PlatformPasswordResetService(JdbcTemplate jdbc,PasswordEncoder encoder,PasswordResetMailer mailer){
        this.jdbc=jdbc;this.encoder=encoder;this.mailer=mailer;
    }
    @Transactional
    public void request(String requestedLogin){
        String login=String.valueOf(requestedLogin==null?"":requestedLogin).trim().toLowerCase(Locale.ROOT);
        if(login.length()>120||login.length()<3)return;
        var accounts=jdbc.query("""
          SELECT username,recovery_email FROM gs_users
           WHERE username=? AND active=TRUE AND recovery_email IS NOT NULL AND recovery_email<>''
          """,(rs,i)->new String[]{rs.getString(1),rs.getString(2)},login);
        if(accounts.isEmpty())return;
        Integer recent=jdbc.queryForObject("""
          SELECT COUNT(*) FROM gs_password_reset_tokens WHERE username=? AND requested_at>NOW()-INTERVAL '90 seconds'
          """,Integer.class,login);
        Integer hourly=jdbc.queryForObject("""
          SELECT COUNT(*) FROM gs_password_reset_tokens WHERE username=? AND requested_at>NOW()-INTERVAL '1 hour'
          """,Integer.class,login);
        if((recent!=null&&recent>0)||(hourly!=null&&hourly>=4))return;
        byte[] bytes=new byte[32];random.nextBytes(bytes);
        String token=Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
        String hash=sha(token);
        jdbc.update("UPDATE gs_password_reset_tokens SET used_at=NOW() WHERE username=? AND used_at IS NULL",login);
        jdbc.update("""
          INSERT INTO gs_password_reset_tokens(token_hash,username,expires_at)
          VALUES (?,?,NOW()+INTERVAL '20 minutes')
          """,hash,login);
        String origin=System.getenv().getOrDefault("PASSWORD_RESET_WEB_URL",
            "https://gs107-hml-web-rewg.onrender.com").replaceAll("/+$","");
        if(!origin.startsWith("https://")&&!origin.startsWith("http://localhost:"))
            throw new IllegalStateException("PASSWORD_RESET_WEB_URL precisa usar HTTPS.");
        mailer.send(accounts.getFirst()[1],origin+"/admin/redefinir-senha.html?token="+token);
    }
    @Transactional
    public void confirm(String token,String password){
        if(token==null||!token.matches("[A-Za-z0-9_-]{40,100}"))
            throw new IllegalArgumentException("Link inválido ou expirado.");
        if(password==null||password.length()<8||password.length()>120)
            throw new IllegalArgumentException("A nova senha deve ter de 8 a 120 caracteres.");
        String hash=sha(token);
        var users=jdbc.query("""
          UPDATE gs_password_reset_tokens SET used_at=NOW()
           WHERE token_hash=? AND used_at IS NULL AND expires_at>NOW()
           RETURNING username
          """,(rs,i)->rs.getString(1),hash);
        if(users.isEmpty())throw new IllegalArgumentException("Link inválido, expirado ou já utilizado.");
        String login=users.getFirst();
        int count=jdbc.update("""
          UPDATE gs_users SET password_hash=?,must_change_password=FALSE,updated_at=NOW()
           WHERE username=? AND active=TRUE
          """,encoder.encode(password),login);
        if(count!=1)throw new IllegalArgumentException("Conta não está mais ativa.");
        jdbc.update("UPDATE gs_password_reset_tokens SET used_at=NOW() WHERE username=? AND used_at IS NULL",login);
    }
    private String sha(String token){
        try{return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
            .digest(token.getBytes(StandardCharsets.UTF_8)));
        }catch(Exception e){throw new IllegalStateException("SHA-256 indisponível",e);}
    }
}
