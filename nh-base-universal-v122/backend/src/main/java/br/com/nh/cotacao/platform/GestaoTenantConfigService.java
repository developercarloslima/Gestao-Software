package br.com.nh.cotacao.platform;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.scheduling.annotation.Scheduled;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.*;

@Service
public class GestaoTenantConfigService {
    private static final Logger log = LoggerFactory.getLogger(GestaoTenantConfigService.class);
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(6)).build();
    private final ObjectMapper mapper;
    private final JdbcTemplate jdbc;
    private final String baseUrl;
    private final String publicBaseUrl;
    private final String tenantHost;
    private final long cacheSeconds;
    private volatile Map<String,Object> cached;
    private volatile Instant cachedUntil = Instant.EPOCH;

    public GestaoTenantConfigService(
            ObjectMapper mapper,
            JdbcTemplate jdbc,
            @Value("${app.gestao-control.base-url:http://localhost:3001}") String baseUrl,
            @Value("${app.gestao-control.public-url:}") String publicBaseUrl,
            @Value("${app.gestao-control.tenant-host:nh.gestaosoftware.com.br}") String tenantHost,
            @Value("${app.gestao-control.cache-seconds:60}") long cacheSeconds) {
        this.mapper = mapper;
        this.jdbc = jdbc;
        this.baseUrl = String.valueOf(baseUrl).replaceAll("/+$", "");
        this.publicBaseUrl = publicBaseUrl == null || publicBaseUrl.isBlank() ? this.baseUrl : publicBaseUrl.replaceAll("/+$", "");
        this.tenantHost = tenantHost;
        this.cacheSeconds = Math.max(15, cacheSeconds);
        this.cached = loadPersisted();
        if (this.cached != null) this.cachedUntil = Instant.now().plusSeconds(Math.min(30, this.cacheSeconds));
    }

    public Map<String,Object> config() {
        if (cached != null && Instant.now().isBefore(cachedUntil)) return cached;
        synchronized (this) {
            if (cached != null && Instant.now().isBefore(cachedUntil)) return cached;

            Map<String,Object> remote = fetchRemote();
            if (remote != null) {
                storeRemote(remote);
                return cached;
            }

            Map<String,Object> persisted = loadPersisted();
            if (persisted != null) {
                persisted.put("source", "LOCAL_LAST_CONFIRMED");
                cached = Collections.unmodifiableMap(new LinkedHashMap<>(persisted));
                cachedUntil = Instant.now().plusSeconds(15);
                return cached;
            }

            cached = Collections.unmodifiableMap(unconfiguredTenant());
            cachedUntil = Instant.now().plusSeconds(30);
            return cached;
        }
    }

    // Consulta em segundo plano: acorda a API central no Render Free e
    // atualiza a configuração mesmo sem o usuário recarregar a página.
    // Uma falha de rede NUNCA cria licença nem substitui os dados de outro host.
    @Scheduled(fixedDelay = 45000, initialDelay = 10000)
    public void refreshFromGestao() {
        synchronized (this) {
            Map<String,Object> remote = fetchRemote();
            if (remote != null) storeRemote(remote);
        }
    }

    private void storeRemote(Map<String,Object> remote) {
        remote.put("source", "GESTAO_CONTROL");
        Map<String,Object> immutable = Collections.unmodifiableMap(new LinkedHashMap<>(remote));
        persist(immutable);
        cached = immutable;
        cachedUntil = Instant.now().plusSeconds(cacheSeconds);
    }

    private Map<String,Object> fetchRemote() {
        try {
            String url = baseUrl + "/api/public/platform/branding?host="
                    + URLEncoder.encode(tenantHost, StandardCharsets.UTF_8);
            HttpRequest req = HttpRequest.newBuilder(URI.create(url))
                    .timeout(Duration.ofSeconds(12))
                    .GET()
                    .build();
            HttpResponse<String> res = http.send(req, HttpResponse.BodyHandlers.ofString());
            if (res.statusCode() >= 200 && res.statusCode() < 300) {
                Map<String,Object> data = mapper.readValue(res.body(), new TypeReference<>() {});
                // Arquivos de branding armazenados pela Gestão são retornados como rota relativa
                // do Control Plane. Transformamos em URL absoluta para que o navegador da empresa
                // cliente carregue a logo/favicons a partir do domínio da Gestão Software.
                absolutizeAssetUrl(data, "logoUrl");
                absolutizeAssetUrl(data, "faviconUrl");
                Object siteObject = data.get("siteContent");
                if (siteObject instanceof Map<?,?> siteValues) {
                    Map<String,Object> site = new LinkedHashMap<>();
                    siteValues.forEach((key,value) -> {if(key instanceof String k) site.put(k,value);});
                    absolutizeAssetUrl(site,"heroImageUrl");
                    absolutizeAssetUrl(site,"appImageUrl");
                    data.put("siteContent",site);
                }
                // Respostas sem empresa vinculada não substituem uma licença já confirmada.
                if (data.get("companyId") == null && !Boolean.FALSE.equals(data.get("active"))) {
                    log.warn("Gestao nao identificou empresa para o host configurado (HTTP 200).");
                    return null;
                }
                return data;
            }
            log.warn("Consulta de branding na Gestao retornou HTTP {} para o host configurado.", res.statusCode());
        } catch (Exception e) {
            // Nunca registrar token, dados da empresa nem credenciais.
            log.warn("Sem comunicacao com a Gestao: {}. Nova tentativa automatica.", e.getClass().getSimpleName());
        }
        return null;
    }


    private void absolutizeAssetUrl(Map<String,Object> data, String key) {
        Object raw = data.get(key);
        if (raw == null) return;
        String value = String.valueOf(raw).trim();
        if (value.startsWith("/")) data.put(key, publicBaseUrl + value);
    }

    private void persist(Map<String,Object> config) {
        try {
            String json = mapper.writeValueAsString(config);
            jdbc.update("""
                    INSERT INTO gestao_tenant_config_cache(singleton_id, tenant_host, config_json, fetched_at, updated_at)
                    VALUES (1, ?, ?, NOW(), NOW())
                    ON CONFLICT (singleton_id) DO UPDATE SET
                        tenant_host=EXCLUDED.tenant_host,
                        config_json=EXCLUDED.config_json,
                        fetched_at=NOW(),
                        updated_at=NOW()
                    """, tenantHost, json);
        } catch (Exception ignored) {
            // O cache em memória segue válido mesmo se houver falha transitória ao persistir.
        }
    }

    private Map<String,Object> loadPersisted() {
        try {
            List<String> rows = jdbc.query(
                    "SELECT config_json FROM gestao_tenant_config_cache WHERE singleton_id=1 AND tenant_host=? LIMIT 1",
                    (rs, row) -> rs.getString(1), tenantHost);
            if (rows.isEmpty() || rows.get(0) == null || rows.get(0).isBlank()) return null;
            return mapper.readValue(rows.get(0), new TypeReference<>() {});
        } catch (Exception ignored) {
            return null;
        }
    }

    private Map<String,Object> unconfiguredTenant() {
        // Fail closed: uma empresa nova não pode herdar módulos nem a marca de outra.
        Map<String,Object> fallback = new LinkedHashMap<>();
        fallback.put("companyName", "Configuração pendente");
        fallback.put("platformDisplayName", "Configuração pendente");
        fallback.put("platformLegalName", "Gestão Software LTDA");
        fallback.put("primaryColor", "#070C40");
        fallback.put("secondaryColor", "#F0F000");
        fallback.put("accentColor", "#FFFFFF");
        fallback.put("enabledModules", List.of());
        fallback.put("active", false);
        fallback.put("source", "UNCONFIGURED_FAIL_CLOSED");
        return fallback;
    }

    public boolean enabled(String module) {
        Map<String,Object> cfg = config();
        if (Boolean.FALSE.equals(cfg.get("active"))) return false;
        Object raw = cfg.get("enabledModules");
        if (raw instanceof Collection<?> c) {
            return c.stream().map(String::valueOf).anyMatch(module::equalsIgnoreCase);
        }
        return true;
    }
}
