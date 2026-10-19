package br.com.nh.cotacao.platform;

import java.util.Locale;

/** Identidade fornecida pela implantação; nunca herda dados de outro cliente. */
public final class TenantIdentity {
    private TenantIdentity() {}
    public static String displayName() { return fromEnv("TENANT_DISPLAY_NAME", "Empresa"); }
    public static String legalName() { return fromEnv("TENANT_LEGAL_NAME", displayName()); }
    public static String adminDisplayName() { return fromEnv("TENANT_ADMIN_DISPLAY_NAME", "Administrador"); }
    public static String document() {
        String digits=fromEnv("TENANT_DOCUMENT", "").replaceAll("\\D", "");
        if (digits.length()!=14) return "Documento não configurado";
        return digits.substring(0,2)+"."+digits.substring(2,5)+"."+digits.substring(5,8)+"/"+
                digits.substring(8,12)+"-"+digits.substring(12);
    }
    private static String fromEnv(String key, String fallback) {
        String val=System.getenv(key);
        return val==null || val.isBlank() ? fallback : val.trim();
    }
}
