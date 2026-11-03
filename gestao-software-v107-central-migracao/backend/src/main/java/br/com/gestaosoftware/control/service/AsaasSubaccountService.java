package br.com.gestaosoftware.control.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.math.BigDecimal;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.time.Duration;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Conta-pai Asaas: apenas criação das subcontas.
 * Cobranças de associados e saques de consultores são sempre feitos com a chave
 * da subconta da empresa no backend isolado do tenant. Não há split automático.
 */
@Service
public class AsaasSubaccountService {
    private static final String SANDBOX = "https://api-sandbox.asaas.com/v3";
    private static final String PRODUCTION = "https://api.asaas.com/v3";
    private static final SecureRandom RANDOM = new SecureRandom();
    private final JdbcTemplate jdbc;
    private final ObjectMapper json;
    private final String parentKey;
    private final String baseUrl;
    private final String encryptionKey;
    private final HttpClient http = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(8)).followRedirects(HttpClient.Redirect.NEVER).build();

    public AsaasSubaccountService(JdbcTemplate jdbc, ObjectMapper json,
            @Value("${app.provisioning.asaas-key:}") String parentKey,
            @Value("${app.provisioning.asaas-base:https://api-sandbox.asaas.com/v3}") String baseUrl,
            @Value("${app.provisioning.asaas-subaccount-encryption-key:}") String encryptionKey) {
        this.jdbc = jdbc;
        this.json = json;
        this.parentKey = parentKey;
        this.baseUrl = baseUrl;
        this.encryptionKey = encryptionKey;
    }

    public record Registration(
            String email, String mobilePhone, BigDecimal incomeValue,
            String address, String addressNumber, String province, String postalCode,
            String complement, String companyType, String taxRegime) {}

    private record Company(String name, String document, String email, String state) {}

    private Company company(UUID id) {
        List<Company> rows = jdbc.query(
                "SELECT trade_name, document, contact_email, status FROM platform_companies WHERE id=?",
                (rs, n) -> new Company(rs.getString(1), rs.getString(2), rs.getString(3), rs.getString(4)), id);
        if (rows.isEmpty())
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Empresa-cliente não encontrada.");
        return rows.get(0);
    }

    public Map<String, Object> status(UUID companyId) {
        company(companyId);
        List<Map<String, Object>> rows = jdbc.query("""
                SELECT state, asaas_account_id, wallet_id, owner_email, created_at, updated_at
                FROM platform_asaas_subaccounts WHERE company_id=?
                """, (rs, n) -> {
            Map<String, Object> data = new LinkedHashMap<>();
            data.put("state", rs.getString(1));
            data.put("accountId", rs.getString(2));
            data.put("walletId", rs.getString(3));
            data.put("ownerEmail", rs.getString(4));
            data.put("createdAt", rs.getObject(5).toString());
            data.put("updatedAt", rs.getObject(6).toString());
            return data;
        }, companyId);
        if (rows.isEmpty()) return Map.of(
                "state", "NOT_CREATED",
                "parentConfigured", parentConfigured(),
                "sandbox", SANDBOX.equals(baseUrl),
                "fundsOwner", "COMPANY_SUBACCOUNT",
                "tenantKeyConfigured", false);
        Map<String, Object> result = new LinkedHashMap<>(rows.get(0));
        result.put("parentConfigured", parentConfigured());
        result.put("sandbox", SANDBOX.equals(baseUrl));
        result.put("fundsOwner", "COMPANY_SUBACCOUNT");
        // Habilitação ocorre separadamente no backend da empresa; nunca presumir
        // que o tenant já está configurado apenas porque existe subconta.
        result.put("tenantKeyConfigured", false);
        return result;
    }

    private boolean parentConfigured() {
        return parentKey != null && !parentKey.isBlank()
                && (SANDBOX.equals(baseUrl) || PRODUCTION.equals(baseUrl));
    }

    private byte[] aesKey() {
        try {
            byte[] key = Base64.getDecoder().decode(encryptionKey == null ? "" : encryptionKey);
            if (key.length == 32) return key;
        } catch (IllegalArgumentException ignored) {
            // A chave deve ser gerada e instalada no servidor, nunca no código.
        }
        throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE,
                "Configure GESTAO_ASAAS_SUBACCOUNT_ENCRYPTION_KEY (32 bytes Base64).");
    }

    private String encrypt(String plain, byte[] key) {
        try {
            byte[] iv = new byte[12];
            RANDOM.nextBytes(iv);
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.ENCRYPT_MODE, new SecretKeySpec(key, "AES"), new GCMParameterSpec(128, iv));
            byte[] ciphertext = cipher.doFinal(plain.getBytes(StandardCharsets.UTF_8));
            return Base64.getEncoder().encodeToString(iv) + ":" + Base64.getEncoder().encodeToString(ciphertext);
        } catch (Exception e) {
            throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR,
                    "Não foi possível proteger a credencial da subconta.");
        }
    }

    private static String required(String value, String field) {
        if (value == null || value.isBlank())
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Informe " + field + ".");
        return value.trim();
    }

    private static String digits(String value) {
        return value == null ? "" : value.replaceAll("\\D", "");
    }

    private static String optional(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }

    public Map<String, Object> create(UUID companyId, Registration data, String operator) {
        if (!parentConfigured())
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE,
                    "A conta principal Asaas ainda não está habilitada neste servidor.");
        byte[] key = aesKey(); // Validar antes de qualquer chamada ou reserva externa.
        if (data == null)
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Informe os dados da subconta.");
        Company c = company(companyId);
        if (!Set.of("ACTIVE", "TRIAL").contains(c.state()))
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Empresa inativa ou arquivada.");
        String document = digits(c.document());
        if (!document.matches("[0-9]{14}"))
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "Cadastre o CNPJ da empresa-cliente no Gestão antes de criar sua subconta.");
        String email = required(data.email(), "o e-mail da empresa");
        String contactEmail = optional(c.email());
        if (contactEmail == null || !email.equalsIgnoreCase(contactEmail))
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "O e-mail da subconta deve ser igual ao e-mail do cadastro da empresa-cliente.");
        if (!email.matches("^[^\\s@]+${^\\s@]+\\.[^\\s@]+$"))
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Informe um e-mail válido.");
        String mobile = digits(data.mobilePhone());
        if (!mobile.matches("[0-9]{10,11}"))
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Informe um celular com DDD.");
        String zip = digits(data.postalCode());
        if (!zip.matches("[0-9]{8}"))
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Informe um CEP válido com 8 dígitos.");
        if (data.incomeValue() == null || data.incomeValue().signum() <= 0)
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Informe o faturamento mensal estimado.");
        String companyType = optional(data.companyType());
        if (companyType != null && !Set.of("MEI", "LIMITED", "INDIVIDUAL", "ASSOCIATION").contains(companyType))
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Tipo jurídico inválido.");
        String taxRegime = optional(data.taxRegime());
        if (taxRegime != null && !Set.of("MEI", "NATIONAL_SIMPLE", "NORMAL_REGIME", "UNKNOWN").contains(taxRegime))
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Regime tributário inválido.");

        Map<String, Object> request = new LinkedHashMap<>();
        request.put("name", c.name());
        request.put("email", email);
        request.put("cpfCnpj", document);
        request.put("mobilePhone", mobile);
        request.put("incomeValue", data.incomeValue());
        request.put("address", required(data.address(), "o logradouro"));
        request.put("addressNumber", required(data.addressNumber(), "o número"));
        request.put("province", required(data.province(), "o bairro"));
        request.put("postalCode", zip);
        if (companyType != null) request.put("companyType", companyType);
        if (taxRegime != null) request.put("taxRegime", taxRegime);
        if (optional(data.complement()) != null) request.put("complement", optional(data.complement()));

        // A reserva única impede dupla criação. Falhas incertas exigem conciliação
        // manual no Asaas; não repetimos POST por tentativa automática.
        int reserved = jdbc.update("""
                INSERT INTO platform_asaas_subaccounts(company_id,owner_email,state,requested_by)
                VALUES (?,?, 'CREATING',?) ON CONFLICT (company_id) DO NOTHING
                """, companyId, email, operator);
        if (reserved != 1)
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "Já existe cadastro ou tentativa de criação para esta empresa. Consulte o status antes de repetir.");

        try {
            HttpRequest asaasRequest = HttpRequest.newBuilder(URI.create(baseUrl + "/accounts"))
                    .timeout(Duration.ofSeconds(30))
                    .header("access_token", parentKey)
                    .header("User-Agent", "GestaoSoftware/1.0")
                    .header("Accept", "application/json")
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(json.writeValueAsString(request)))
                    .build();
            HttpResponse<String> response = http.send(asaasRequest, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() < 200 || response.statusCode() >= 300) {
                jdbc.update("UPDATE platform_asaas_subaccounts SET state=?, updated_at=NOW() WHERE company_id=?",
                        response.statusCode() >= 400 && response.statusCode() < 500 ? "REJECTED" : "REVIEW_REQUIRED",
                        companyId);
                throw new ResponseStatusException(HttpStatus.BAD_GATEWAY,
                        "O Asaas não aceitou o cadastro. Confira os dados e concilie o status antes de nova tentativa.");
            }
            JsonNode created = json.readTree(response.body());
            String accountId = created.path("id").asText("");
            String walletId = created.path("walletId").asText("");
            String apiKey = created.path("apiKey").asText("");
            if (accountId.isBlank() || walletId.isBlank() || apiKey.isBlank()) {
                jdbc.update("UPDATE platform_asaas_subaccounts SET state='REVIEW_REQUIRED', updated_at=NOW() WHERE company_id=?", companyId);
                throw new ResponseStatusException(HttpStatus.BAD_GATEWAY,
                        "Subconta retornou dados incompletos. Não repita a criação antes de verificar no Asaas.");
            }
            jdbc.update("""
                    UPDATE platform_asaas_subaccounts
                    SET asaas_account_id=?, wallet_id=?, encrypted_api_key=?,
                        state='PENDING_ACTIVATION',updated_at=NOW()
                    WHERE company_id=? AND state='CREATING'
                    """, accountId, walletId, encrypt(apiKey, key), companyId);
            return status(companyId);
        } catch (ResponseStatusException e) {
            throw e;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            markReviewRequired(companyId);
            throw new ResponseStatusException(HttpStatus.BAD_GATEWAY,
                    "Criação interrompida; verifique no Asaas antes de tentar novamente.");
        } catch (Exception e) {
            markReviewRequired(companyId);
            throw new ResponseStatusException(HttpStatus.BAD_GATEWAY,
                    "Resultado incerto. Verifique a subconta no Asaas antes de tentar novamente.");
        }
    }

    private void markReviewRequired(UUID companyId) {
        jdbc.update("""
                UPDATE platform_asaas_subaccounts SET state='REVIEW_REQUIRED',updated_at=NOW()
                WHERE company_id=? AND state='CREATING'
                """, companyId);
    }
}
