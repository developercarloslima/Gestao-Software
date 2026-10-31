package br.com.gestaosoftware.control.controller;

import br.com.gestaosoftware.control.service.PlatformManagementService;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Base64;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

/**
 * Sincronização estritamente do branding do tenant para a Gestão.
 * Não oferece escrita pública: cada instalação recebe um token HMAC exclusivo
 * do host, derivado de um segredo que permanece apenas na Gestão.
 */
@RestController
@RequestMapping("/api/public/platform/tenant-branding")
public class TenantBrandingSyncController {
    private final JdbcTemplate db;
    private final PlatformManagementService platform;
    private final String master;

    public TenantBrandingSyncController(JdbcTemplate db, PlatformManagementService platform,
            @Value("${GESTAO_TENANT_BRANDING_MASTER:}") String master) {
        this.db = db;
        this.platform = platform;
        this.master = master;
    }

    public record BrandingInput(String platformDisplayName, String siteTitle, String companyTagline,
                                String loginSubtitle, String primaryColor, String secondaryColor,
                                String accentColor, String logoBase64, String logoContentType) {}

    @PutMapping
    @Transactional
    public Map<String, Object> update(@RequestHeader("X-Gestao-Tenant-Host") String hostHeader,
            @RequestHeader("X-Gestao-Tenant-Token") String token,
            @RequestBody BrandingInput input) {
        String host = String.valueOf(hostHeader).trim().toLowerCase(Locale.ROOT);
        if (!host.matches("[a-z0-9][a-z0-9.-]{2,250}") || host.contains("..")) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Host inválido.");
        }
        requireToken(host, token);
        UUID companyId = db.query("""
                SELECT c.id FROM platform_companies c
                  JOIN platform_company_hosting h ON h.company_id=c.id
                 WHERE (LOWER(COALESCE(h.domain,''))=? OR LOWER(COALESCE(h.subdomain,''))=?)
                   AND c.status IN ('ACTIVE','TRIAL')
                 LIMIT 1
                """, rs -> rs.next() ? rs.getObject(1, UUID.class) : null, host, host);
        if (companyId == null) throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Empresa ativa não vinculada ao host.");
        if (input == null) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Personalização vazia.");
        String name = mandatory(input.platformDisplayName(), 180, "Nome de exibição");
        String title = mandatory(input.siteTitle(), 180, "Título do site");
        String primary = color(input.primaryColor());
        String secondary = color(input.secondaryColor());
        String accent = color(input.accentColor());
        String slogan = optional(input.companyTagline(), 220);
        String subtitle = optional(input.loginSubtitle(), 220);

        db.update("""
            UPDATE platform_companies SET platform_display_name=?, site_title=?, company_tagline=?,
              login_subtitle=?, primary_color=?, secondary_color=?, accent_color=?, updated_at=NOW()
              WHERE id=?
            """, name, title, slogan, subtitle, primary, secondary, accent, companyId);

        if (input.logoBase64() != null && !input.logoBase64().isBlank()) {
            String type = String.valueOf(input.logoContentType()).toLowerCase(Locale.ROOT);
            byte[] image;
            try { image = Base64.getDecoder().decode(input.logoBase64()); }
            catch (IllegalArgumentException e) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Arquivo de logo inválido.");
            }
            if (image.length == 0 || image.length > 2 * 1024 * 1024 || !validImage(type, image)) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "A logo precisa ser PNG, JPEG ou WebP de até 2 MB.");
            }
            platform.saveCompanyFile(companyId, "LOGO", "logo-empresa", type, image);
        }
        // Em todas as abas, o favicon é sempre a logo cadastrada para o tenant.
        db.update("UPDATE platform_companies SET favicon_url=logo_url, updated_at=NOW() WHERE id=?", companyId);
        return Map.of("saved", true, "companyId", companyId.toString());
    }

    private void requireToken(String host, String received) {
        if (master == null || master.length() < 32) {
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "Integração de personalização não configurada.");
        }
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(master.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            byte[] expected = mac.doFinal(host.getBytes(StandardCharsets.UTF_8));
            String supplied = received == null ? "" : received.trim().toLowerCase(Locale.ROOT);
            byte[] got = java.util.HexFormat.of().parseHex(supplied);
            if (!MessageDigest.isEqual(expected, got)) throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Empresa não autorizada.");
        } catch (ResponseStatusException e) { throw e; }
        catch (Exception e) { throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Empresa não autorizada."); }
    }

    private static String mandatory(String raw, int max, String label) {
        String v = optional(raw, max);
        if (v == null) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, label + " é obrigatório.");
        return v;
    }
    private static String optional(String raw, int max) {
        String v = raw == null ? "" : raw.trim();
        if (v.length() > max || v.chars().anyMatch(c -> c < 32))
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Campo de personalização inválido.");
        return v.isBlank() ? null : v;
    }
    private static String color(String raw) {
        if (raw == null || !raw.matches("#[0-9a-fA-F]{6}"))
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Cor inválida. Use #RRGGBB.");
        return raw;
    }
    private static boolean validImage(String type, byte[] image) {
        if ("image/png".equals(type)) return image.length > 8 && image[0] == (byte) 0x89
                && image[1]=='P' && image[2]=='N' && image[3]=='G';
        if ("image/jpeg".equals(type)) return image.length > 3 && image[0]==(byte)0xff
                && image[1]==(byte)0xd8 && image[2]==(byte)0xff;
        if ("image/webp".equals(type)) return image.length > 12
                && new String(image, 0, 4, StandardCharsets.US_ASCII).equals("RIFF")
                && new String(image, 8, 4, StandardCharsets.US_ASCII).equals("WEBP");
        return false;
    }
}
