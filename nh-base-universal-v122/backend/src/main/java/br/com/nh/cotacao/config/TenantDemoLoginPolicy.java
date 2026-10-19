package br.com.nh.cotacao.config;

/** Exceção SOMENTE para a demonstração Render isolada. Nunca utilizar em produção. */
public final class TenantDemoLoginPolicy {
    private TenantDemoLoginPolicy() {}
    public static boolean permits(String username, String password, String host, String publicUrl, boolean enabled) {
        return enabled
                && "gestao1234".equals(password)
                && "nh122-hml-web.onrender.com".equalsIgnoreCase(host)
                && "https://nh122-hml-web.onrender.com".equalsIgnoreCase(publicUrl)
                && username != null && username.matches("(?i)^[a-z0-9._%+-]+@[a-z0-9.-]+\\.[a-z]{2,}$")
                && !username.toLowerCase(java.util.Locale.ROOT).endsWith("@nh.local");
    }
}
