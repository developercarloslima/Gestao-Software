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
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Conta-pai Asaas: consulta e criação das subcontas.
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
    static record ExistingAccount(boolean found, String accountId, String walletId, boolean emailMatches) {}

    // Os detalhes exibidos são saneados: nunca persistir a resposta bruta, chaves ou dados pessoais.
    static record AsaasFailure(String code, String message, boolean possibleDuplicate) {}

    static AsaasFailure parseAsaasFailure(ObjectMapper mapper, String body) {
        String code = null;
        String description = null;
        try {
            JsonNode errors = mapper.readTree(body == null ? "" : body).path("errors");
            if (errors.isArray() && !errors.isEmpty()) {
                JsonNode first = errors.get(0);
                code = first.path("code").asText("");
                description = first.path("description").asText("");
            }
        } catch (Exception ignored) {
            // Respostas não JSON (proxy, HTML, manutenção) não são expostas ao operador.
        }
        if (code == null || !code.matches("[a-zA-Z0-9_.-]{1,60}")) code = null;
        String comparison = ((code == null ? "" : code) + " " + (description == null ? "" : description))
                .toLowerCase(Locale.ROOT);
        boolean duplicate = isDuplicateDescription(comparison);
        return new AsaasFailure(code, safeAsaasDescription(description), duplicate);
    }

    private static boolean isDuplicateDescription(String description) {
        if (description == null) return false;
        String value = description.toLowerCase(Locale.ROOT);
        return value.matches("(?s).*(duplicad|already.exists|already.in.use|já existe|ja existe|"
                + "conta existente|já cadastrad[oa]|ja cadastrad[oa]|já está em uso|ja esta em uso|"
                + "cnpj.*em uso|cpf.*em uso).*");
    }

    static ExistingAccount parseExistingAccount(ObjectMapper mapper, String body,
                                                String document, String registeredEmail) throws Exception {
        JsonNode root = mapper.readTree(body);
        JsonNode accounts = root == null ? null : root.path("data");
        if (accounts == null || !accounts.isArray())
            throw new IllegalArgumentException("Resposta de listagem Asaas inválida.");
        if (accounts.isEmpty()) {
            if (root.path("hasMore").asBoolean(false))
                throw new IllegalArgumentException("Listagem incompleta.");
            return new ExistingAccount(false, null, null, false);
        }
        // A consulta filtra por CNPJ; se houver resposta inconsistente, não criar nada.
        for (JsonNode account : accounts) {
            String returnedDocument = digits(account.path("cpfCnpj").asText(""));
            if (!returnedDocument.isEmpty() && !document.equals(returnedDocument))
                throw new IllegalArgumentException("Asaas retornou outro documento.");
        }
        JsonNode account = accounts.get(0);
        String accountId = optional(account.path("id").asText(""));
        String walletId = optional(account.path("walletId").asText(""));
        String existingEmail = optional(account.path("email").asText(""));
        return new ExistingAccount(true, accountId, walletId,
                existingEmail != null && registeredEmail != null
                        && existingEmail.equalsIgnoreCase(registeredEmail));
    }

    private static String safeAsaasDescription(String raw) {
        if (raw == null || raw.isBlank()) return "O Asaas rejeitou a solicitação sem informar um motivo detalhado.";
        // Descartar descrições com indícios de credenciais; a API externa não é fonte confiável.
        if (raw.matches("(?is).*(?:api.?key|access.?token|authorization|bearer|password|senha|secret|\\$aact_).*"))
            return "O Asaas rejeitou a solicitação. Verifique a autorização da conta principal.";
        String clean = raw.replaceAll("[\\p{Cntrl}<>]", " ").trim()
                .replaceAll("(?i)https?://\\S+", "[link oculto]")
                .replaceAll("(?i)[a-z0-9._%+-]+@[a-z0-9.-]+\\.[a-z]{2,}", "[e-mail oculto]")
                .replaceAll("(?<![\\p{Alnum}])(?:\\d[.\\-/\\s]?){8,}\\d(?![\\p{Alnum}])", "[número oculto]")
                .replaceAll("\\s{2,}", " ");
        if (clean.length() > 240) clean = clean.substring(0, 240) + "…";
        return clean.isBlank() ? "O Asaas rejeitou a solicitação." : clean;
    }

    private Company company(UUID id) {
        List<Company> rows = jdbc.query(
                "SELECT trade_name, document, contact_email, status FROM platform_companies WHERE id=?",
                (rs, n) -> new Company(rs.getString(1), rs.getString(2), rs.getString(3), rs.getString(4)), id);
        if (rows.isEmpty())
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Empresa-cliente não encontrada.");
        return rows.get(0);
    }

    public Map<String, Object> status(UUID companyId) {
        Company company = company(companyId);
        List<Map<String, Object>> rows = jdbc.query("""
                SELECT state, asaas_account_id, wallet_id, owner_email, created_at, updated_at,
                       attempt_count, last_http_status, last_error_code, last_error_message, last_cnpj
                FROM platform_asaas_subaccounts WHERE company_id=?
                """, (rs, n) -> {
            Map<String, Object> data = new LinkedHashMap<>();
            data.put("state", rs.getString(1));
            data.put("accountId", rs.getString(2));
            data.put("walletId", rs.getString(3));
            data.put("ownerEmail", rs.getString(4));
            data.put("createdAt", rs.getObject(5).toString());
            data.put("updatedAt", rs.getObject(6).toString());
            data.put("attemptCount", rs.getInt(7));
            data.put("lastHttpStatus", rs.getObject(8, Integer.class));
            data.put("lastErrorCode", rs.getString(9));
            data.put("lastErrorMessage", rs.getString(10));
            data.put("knownDuplicate", digits(company.document()).equals(rs.getString(11))
                    && isDuplicateDescription(rs.getString(9) + " " + rs.getString(10)));
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

    private ExistingAccount findInParentAccount(String document, String email) {
        if (!parentConfigured())
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE,
                    "A conta principal Asaas não está habilitada neste servidor.");
        try {
            HttpRequest request = HttpRequest.newBuilder(
                            URI.create(baseUrl + "/accounts?cpfCnpj=" + document + "&limit=100"))
                    .timeout(Duration.ofSeconds(15))
                    .header("access_token", parentKey)
                    .header("User-Agent", "GestaoSoftware/1.0")
                    .header("Accept", "application/json")
                    .GET().build();
            HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() != 200)
                throw new ResponseStatusException(HttpStatus.BAD_GATEWAY,
                        "A consulta de subcontas ao Asaas falhou (HTTP " + response.statusCode()
                                + "). Nenhuma criação foi enviada.");
            return parseExistingAccount(json, response.body(), document, email);
        } catch (ResponseStatusException e) {
            throw e;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new ResponseStatusException(HttpStatus.BAD_GATEWAY,
                    "A consulta ao Asaas foi interrompida. Nenhuma criação foi enviada.");
        } catch (Exception e) {
            throw new ResponseStatusException(HttpStatus.BAD_GATEWAY,
                    "Não foi possível confirmar as subcontas no Asaas. Nenhuma criação foi enviada.");
        }
    }

    private boolean previouslyRejectedAsDuplicate(UUID companyId, String document) {
        return jdbc.query("""
                SELECT last_error_code, last_error_message
                  FROM platform_asaas_subaccounts
                 WHERE company_id=? AND state='REJECTED' AND last_cnpj=?
                """, (rs, n) -> isDuplicateDescription(rs.getString(1) + " " + rs.getString(2)),
                companyId, document).stream().anyMatch(Boolean.TRUE::equals);
    }

    /** Consulta de leitura; não cria subcontas nem altera o número de tentativas. */
    public Map<String, Object> checkExisting(UUID companyId) {
        Company company = company(companyId);
        String document = digits(company.document());
        if (!document.matches("[0-9]{14}"))
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "A empresa precisa de CNPJ válido.");
        ExistingAccount found = findInParentAccount(document, company.email());
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("foundInParent", found.found());
        result.put("accountId", found.accountId());
        result.put("walletId", found.walletId());
        result.put("emailMatches", found.emailMatches());
        result.put("previousDuplicate", previouslyRejectedAsDuplicate(companyId, document));
        result.put("manualReviewRequired", found.found() || (boolean) result.get("previousDuplicate"));
        result.put("noAccountCreated", true);
        return result;
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
        if (!email.matches("^[^\\s@]+@[^\\s@]+\\.[^\\s@]+$"))
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

        // Verificar com a chave da conta-pai ANTES de reservar ou enviar POST.
        // Uma subconta existente não pode ser vinculada automaticamente: a API key
        // original não é recuperada por uma listagem, e a titularidade exige revisão.
        ExistingAccount existing = findInParentAccount(document, email);
        if (existing.found()) {
            jdbc.update("""
                    UPDATE platform_asaas_subaccounts SET state='REVIEW_REQUIRED',updated_at=NOW()
                     WHERE company_id=? AND state='REJECTED'
                    """, companyId);
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "Já existe subconta com este CNPJ na conta principal Asaas."
                            + " Confira o vínculo e a credencial antes de prosseguir. Nenhuma criação foi enviada.");
        }
        if (previouslyRejectedAsDuplicate(companyId, document)) {
            jdbc.update("""
                    UPDATE platform_asaas_subaccounts SET state='REVIEW_REQUIRED',updated_at=NOW()
                     WHERE company_id=? AND state='REJECTED' AND last_cnpj=?
                    """, companyId, document);
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "Este CNPJ já foi recusado pelo Asaas por estar em uso."
                            + " Use outro CNPJ de teste ou esclareça o cadastro com o Asaas. Nenhuma criação foi enviada.");
        }

        // Só é seguro repetir quando a resposta anterior foi rejeição HTTP 4xx:
        // a requisição não criou uma subconta. Um timeout/5xx permanece bloqueado
        // como REVIEW_REQUIRED até conciliação manual no Asaas.
        int reserved = jdbc.update("""
                UPDATE platform_asaas_subaccounts
                   SET state='CREATING',owner_email=?,requested_by=?,last_cnpj=?,updated_at=NOW(),
                       attempt_count=attempt_count+1,last_http_status=NULL,
                       last_error_code=NULL,last_error_message=NULL,
                       asaas_account_id=NULL,wallet_id=NULL,encrypted_api_key=NULL
                 WHERE company_id=? AND state='REJECTED'
                """, email, operator, document, companyId);
        if (reserved == 0) {
            reserved = jdbc.update("""
                    INSERT INTO platform_asaas_subaccounts(company_id,owner_email,state,requested_by,last_cnpj)
                    VALUES (?,?, 'CREATING',?,?) ON CONFLICT (company_id) DO NOTHING
                    """, companyId, email, operator, document);
        }
        if (reserved != 1)
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "Já existe subconta ou uma tentativa em andamento. Resultados incertos exigem conciliação manual.");

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
                int asaasHttp = response.statusCode();
                boolean clientError = asaasHttp >= 400 && asaasHttp < 500;
                AsaasFailure failure = clientError
                        ? parseAsaasFailure(json, response.body())
                        : new AsaasFailure(null, "Resultado incerto no Asaas; verifique a conta principal.", false);
                // 409 e retornos que sugerem conta já existente exigem conciliação, não reenvio.
                boolean safelyRejected = clientError && asaasHttp != 409 && !failure.possibleDuplicate();
                jdbc.update("""
                        UPDATE platform_asaas_subaccounts
                           SET state=?,last_http_status=?,last_error_code=?,last_error_message=?,updated_at=NOW()
                         WHERE company_id=? AND state='CREATING'
                        """, safelyRejected ? "REJECTED" : "REVIEW_REQUIRED", asaasHttp,
                        failure.code(), failure.message(), companyId);
                if (safelyRejected)
                    throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY,
                            "Cadastro rejeitado pelo Asaas (HTTP " + asaasHttp + "): " + failure.message());
                throw new ResponseStatusException(HttpStatus.BAD_GATEWAY,
                        "Resultado incerto no Asaas (HTTP " + asaasHttp + "). Confira a conta principal antes de reenviar.");
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
