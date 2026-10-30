package br.com.nh.cotacao.service;

import br.com.nh.cotacao.entity.PortalUser;
import br.com.nh.cotacao.platform.GestaoTenantConfigService;
import br.com.nh.cotacao.repository.PortalUserRepository;
import br.com.nh.cotacao.security.PortalRole;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.HexFormat;
import java.util.Locale;
import java.util.UUID;

@Service
@ConditionalOnProperty(name="app.tenant.mode",havingValue="true")
public class TenantAdminPasswordResetService {
    private final PortalUserRepository repository;
    private final JdbcTemplate jdbc;
    private final GestaoTenantConfigService gestao;
    private final PasswordResetMailer mailer;
    private final String tenantAdminUsername;
    private final String siteUrl;
    private final BCryptPasswordEncoder encoder=new BCryptPasswordEncoder();
    private final SecureRandom secureRandom=new SecureRandom();
    public TenantAdminPasswordResetService(PortalUserRepository repository,JdbcTemplate jdbc,
        GestaoTenantConfigService gestao,PasswordResetMailer mailer,
        @Value("${app.auth.admin-username}") String tenantAdminUsername,
        @Value("${app.public-web-url:}") String siteUrl){
        this.repository=repository;this.jdbc=jdbc;this.gestao=gestao;this.mailer=mailer;
        this.tenantAdminUsername=PortalUser.normalizeUsername(tenantAdminUsername);
        this.siteUrl=siteUrl;
    }
    @Transactional
    public void request(String username){
        String normalized=PortalUser.normalizeUsername(username);
        // Somente o administrador definido pelo provisionamento deste tenant.
        if(!normalized.equals(tenantAdminUsername))return;
        var account=repository.findByNormalizedUsername(normalized)
            .filter(PortalUser::isActive)
            .filter(x->x.getRole()==PortalRole.ADMIN);
        if(account.isEmpty())return;
        var config=gestao.config();
        if(!Boolean.TRUE.equals(config.get("active"))||config.get("companyId")==null)return;
        String email=String.valueOf(config.getOrDefault("contactEmail","")).trim();
        if(!email.matches("^[^\\s@]+@[^\\s@]+\\.[^\\s@]+$"))return;
        UUID id=account.get().getId();
        Integer recent=jdbc.queryForObject("""
            SELECT COUNT(*) FROM portal_password_reset_tokens
             WHERE user_id=? AND requested_at>NOW()-INTERVAL '90 seconds'
            """,Integer.class,id);
        Integer hourly=jdbc.queryForObject("""
            SELECT COUNT(*) FROM portal_password_reset_tokens
             WHERE user_id=? AND requested_at>NOW()-INTERVAL '1 hour'
            """,Integer.class,id);
        if((recent!=null&&recent>0)||(hourly!=null&&hourly>=4))return;
        byte[] bytes=new byte[32];secureRandom.nextBytes(bytes);
        String token=Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
        jdbc.update("UPDATE portal_password_reset_tokens SET used_at=NOW() WHERE user_id=? AND used_at IS NULL",id);
        jdbc.update("""
            INSERT INTO portal_password_reset_tokens(token_hash,user_id,expires_at)
             VALUES(?,?,NOW()+INTERVAL '20 minutes')
            """,sha(token),id);
        String origin=siteUrl.replaceAll("/+$","");
        if(!origin.startsWith("https://")&&!origin.startsWith("http://localhost:"))
            throw new IllegalStateException("PUBLIC_WEB_URL do tenant precisa usar HTTPS.");
        mailer.send(email,origin+"/admin/redefinir-senha.html?token="+token);
    }
    @Transactional
    public void confirm(String token,String password){
        if(token==null||!token.matches("[A-Za-z0-9_-]{40,100}"))
            throw new IllegalArgumentException("Link inválido.");
        if(password==null||password.length()<8||password.length()>120)
            throw new IllegalArgumentException("Senha deve ter 8 a 120 caracteres.");
        var userIds=jdbc.query("""
            UPDATE portal_password_reset_tokens SET used_at=NOW()
             WHERE token_hash=? AND used_at IS NULL AND expires_at>NOW()
             RETURNING user_id
            """,(rs,i)->rs.getObject(1,UUID.class),sha(token));
        if(userIds.isEmpty())throw new IllegalArgumentException("Link inválido, expirado ou utilizado.");
        var user=repository.findById(userIds.getFirst()).filter(PortalUser::isActive)
           .filter(u->u.getRole()==PortalRole.ADMIN
             &&PortalUser.normalizeUsername(u.getUsername()).equals(tenantAdminUsername))
           .orElseThrow(()->new IllegalArgumentException("Administrador indisponível."));
        user.changePassword(encoder.encode(password));
        repository.saveAndFlush(user);
        jdbc.update("UPDATE portal_password_reset_tokens SET used_at=NOW() WHERE user_id=? AND used_at IS NULL",user.getId());
    }
    private String sha(String value){
        try{return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
             .digest(value.getBytes(StandardCharsets.UTF_8)));
        }catch(Exception e){throw new IllegalStateException("SHA indisponível",e);}
    }
}
