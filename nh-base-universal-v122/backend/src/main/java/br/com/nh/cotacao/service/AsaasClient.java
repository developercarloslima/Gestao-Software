package br.com.nh.cotacao.service;

import br.com.nh.cotacao.entity.*;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.math.BigDecimal;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.LocalDate;

@Service
public class AsaasClient implements FinancialProvider {
    private static final Logger log = LoggerFactory.getLogger(AsaasClient.class);
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();
    private final ObjectMapper mapper;
    private final boolean enabled;
    private final String baseUrl;
    private final String apiKey;
    private final String webhookToken;
    private final String userAgent;

    public AsaasClient(
            ObjectMapper mapper,
            @Value("${app.asaas.enabled:false}") boolean enabled,
            @Value("${app.asaas.base-url:https://api-sandbox.asaas.com/v3}") String baseUrl,
            @Value("${app.asaas.api-key:}") String apiKey,
            @Value("${app.asaas.webhook-token:}") String webhookToken,
            @Value("${app.asaas.user-agent:NH-Gestao/1.0}") String userAgent,
            @Value("${app.asaas.expected-wallet-id:}") String expectedWalletId
    ) {
        this.mapper = mapper;
        this.baseUrl = baseUrl == null ? "https://api-sandbox.asaas.com/v3" : baseUrl.replaceAll("/+$", "");
        this.apiKey = apiKey == null ? "" : apiKey.trim();
        this.webhookToken = webhookToken == null ? "" : webhookToken.trim();
        this.userAgent = userAgent == null || userAgent.isBlank() ? "NH-Gestao/1.0" : userAgent.trim();
        String expected = expectedWalletId == null ? "" : expectedWalletId.trim();
        boolean credentialsPresent = enabled && !this.apiKey.isBlank() && !this.webhookToken.isBlank();
        boolean sandbox = "api-sandbox.asaas.com".equalsIgnoreCase(URI.create(this.baseUrl).getHost());
        // Em Sandbox, a carteira esperada e obrigatoria. Quando configurada em
        // outro ambiente, a mesma validacao protege o isolamento do tenant.
        this.enabled = credentialsPresent && ((!sandbox && expected.isBlank()) ||
                (!expected.isBlank() && verifyWallet(expected)));
        if (credentialsPresent && !this.enabled) {
            log.warn("Asaas bloqueado: carteira do tenant ausente, divergente ou nao verificavel.");
        }
    }

    private boolean verifyWallet(String expectedWalletId) {
        try {
            HttpRequest req = HttpRequest.newBuilder(URI.create(baseUrl + "/wallets"))
                    .timeout(Duration.ofSeconds(15))
                    .header("accept", "application/json")
                    .header("access_token", apiKey)
                    .header("User-Agent", userAgent)
                    .GET().build();
            HttpResponse<String> response = http.send(req, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() != 200) {
                log.warn("Asaas: verificacao da carteira retornou HTTP {}.", response.statusCode());
                return false;
            }
            JsonNode root = mapper.readTree(response.body());
            JsonNode wallets = root.isArray() ? root : root.path("data");
            if (wallets.isArray()) {
                for (JsonNode wallet : wallets) {
                    if (expectedWalletId.equalsIgnoreCase(wallet.path("id").asText()) ||
                            expectedWalletId.equalsIgnoreCase(wallet.path("walletId").asText())) return true;
                }
            }
            return expectedWalletId.equalsIgnoreCase(root.path("id").asText()) ||
                    expectedWalletId.equalsIgnoreCase(root.path("walletId").asText());
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            return false;
        } catch (Exception ex) {
            log.warn("Asaas: nao foi possivel verificar a carteira do tenant ({}).", ex.getClass().getSimpleName());
            return false;
        }
    }

    @Override public String code() { return "ASAAS"; }
    @Override public boolean enabled() { return enabled; }
    public boolean validWebhookToken(String received) {
        return !webhookToken.isBlank() && received != null && constantTimeEquals(webhookToken, received.trim());
    }

    public String createCustomer(Associate associate) {
        requireEnabled();
        ObjectNode body = mapper.createObjectNode();
        body.put("name", associate.getName());
        body.put("cpfCnpj", associate.getDocument());
        if (associate.getWhatsapp() != null) body.put("mobilePhone", associate.getWhatsapp());
        if (associate.getEmail() != null) body.put("email", associate.getEmail());
        body.put("externalReference", "associate:" + associate.getId());
        JsonNode result = post("/customers", body);
        String id = text(result, "id");
        if (id == null) throw new IllegalStateException("Asaas não retornou o identificador do associado.");
        return id;
    }

    public PaymentData createCharge(String customerId, FinancialBillingType billingType, BigDecimal value, LocalDate dueDate, String description, String externalReference) {
        requireEnabled();
        if (billingType != FinancialBillingType.BOLETO && billingType != FinancialBillingType.PIX && billingType != FinancialBillingType.CREDIT_CARD) {
            throw new IllegalArgumentException("Cobrança Asaas deve ser BOLETO, PIX ou cartão de crédito.");
        }
        ObjectNode body = mapper.createObjectNode();
        body.put("customer", customerId);
        body.put("billingType", billingType.name());
        body.put("value", value);
        body.put("dueDate", dueDate.toString());
        if (description != null) body.put("description", description);
        if (externalReference != null) body.put("externalReference", externalReference);
        JsonNode payment = post("/payments", body);
        String id = text(payment, "id");
        PaymentData result = fromPayment(payment);
        if (id != null) result = enrichPayment(id, result);
        return result;
    }

    public String createMonthlySubscription(String customerId, FinancialBillingType billingType, BigDecimal value, LocalDate nextDueDate, String description, String externalReference) {
        requireEnabled();
        ObjectNode body = mapper.createObjectNode();
        body.put("customer", customerId);
        body.put("billingType", billingType.name());
        body.put("value", value);
        body.put("nextDueDate", nextDueDate.toString());
        body.put("cycle", "MONTHLY");
        if (description != null) body.put("description", description);
        if (externalReference != null) body.put("externalReference", externalReference);
        String id = text(post("/subscriptions", body), "id");
        if (id == null) throw new IllegalStateException("Asaas não retornou o identificador da cobrança recorrente.");
        return id;
    }

    public PaymentData refreshPayment(String paymentId) {
        requireEnabled();
        return enrichPayment(paymentId, fromPayment(get("/payments/" + paymentId)));
    }

    @Override
    public PaymentData updatePaymentDueDate(String paymentId, LocalDate dueDate) {
        requireEnabled();
        if (paymentId == null || !paymentId.matches("[a-zA-Z0-9_-]{3,100}")) {
            throw new IllegalArgumentException("Identificador Asaas inválido.");
        }
        if (dueDate == null || !dueDate.isAfter(LocalDate.now())) {
            throw new IllegalArgumentException("Informe novo vencimento após a data de hoje.");
        }
        ObjectNode body = mapper.createObjectNode();
        body.put("dueDate", dueDate.toString());
        JsonNode updated = request("PUT", "/payments/" + paymentId, body);
        if (!paymentId.equals(text(updated, "id"))) {
            throw new IllegalStateException("Asaas não confirmou o identificador da cobrança atualizada.");
        }
        // O código do boleto muda após atualização de vencimento.
        return enrichPayment(paymentId, fromPayment(updated));
    }

    public BigDecimal balance() {
        requireEnabled();
        JsonNode node = get("/finance/balance");
        return node.path("balance").isNumber() ? node.path("balance").decimalValue() : BigDecimal.ZERO;
    }

    public TransferData transferPix(BigDecimal amount, PixKeyType keyType, String key, String description, String externalReference) {
        requireEnabled();
        ObjectNode body = mapper.createObjectNode();
        body.put("value", amount);
        body.put("pixAddressKey", key);
        body.put("pixAddressKeyType", keyType.name());
        if (description != null) body.put("description", description);
        if (externalReference != null) body.put("externalReference", externalReference);
        JsonNode node = post("/transfers", body);
        return new TransferData(text(node,"id"), text(node,"status"));
    }

    /** Saída da própria subconta para a conta bancária verificada da empresa. */
    public TransferData transferBank(BigDecimal amount, ObjectNode bankAccount, String externalReference) {
        requireEnabled();
        if (bankAccount == null || bankAccount.isEmpty()) throw new IllegalArgumentException("Conta bancária ausente.");
        ObjectNode body = mapper.createObjectNode();
        body.put("value", amount);
        body.set("bankAccount", bankAccount.deepCopy());
        if (externalReference != null) body.put("externalReference", externalReference);
        JsonNode node = post("/transfers", body);
        return new TransferData(text(node,"id"), text(node,"status"));
    }

    private PaymentData enrichPayment(String paymentId, PaymentData base) {
        String identification = base.identificationField();
        String barCode = base.barCode();
        String pixPayload = base.pixPayload();
        String pixImage = base.pixEncodedImage();
        try {
            JsonNode bank = get("/payments/" + paymentId + "/identificationField");
            identification = text(bank, "identificationField");
            barCode = text(bank, "barCode");
        } catch (RuntimeException ignored) { }
        try {
            JsonNode pix = get("/payments/" + paymentId + "/pixQrCode");
            pixPayload = text(pix, "payload");
            pixImage = text(pix, "encodedImage");
        } catch (RuntimeException ignored) { }
        return new PaymentData(base.id(), base.status(), base.invoiceUrl(), base.bankSlipUrl(), identification, barCode, pixPayload, pixImage, base.subscriptionId(), base.externalReference());
    }

    private PaymentData fromPayment(JsonNode p) {
        return new PaymentData(text(p,"id"), text(p,"status"), text(p,"invoiceUrl"), text(p,"bankSlipUrl"), null, null, null, null, text(p,"subscription"), text(p,"externalReference"));
    }

    private JsonNode get(String path) { return request("GET", path, null); }
    private JsonNode post(String path, JsonNode body) { return request("POST", path, body); }

    private JsonNode request(String method, String path, JsonNode body) {
        requireEnabled();
        try {
            HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create(baseUrl + path))
                    .timeout(Duration.ofSeconds(20))
                    .header("accept", "application/json")
                    .header("access_token", apiKey)
                    .header("User-Agent", userAgent);
            if (body != null) {
                builder.header("content-type", "application/json")
                        .method(method, HttpRequest.BodyPublishers.ofString(mapper.writeValueAsString(body)));
            } else {
                builder.method(method, HttpRequest.BodyPublishers.noBody());
            }
            HttpResponse<String> response = http.send(builder.build(), HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() < 200 || response.statusCode() >= 300) {
                throw new IllegalStateException("Operação financeira não concluída pelo Asaas (HTTP " + response.statusCode() + ").");
            }
            if (response.body() == null || response.body().isBlank()) return mapper.createObjectNode();
            return mapper.readTree(response.body());
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Operação financeira interrompida.");
        } catch (Exception ex) {
            if (ex instanceof IllegalStateException ise) throw ise;
            throw new IllegalStateException("Não foi possível comunicar com o provedor financeiro.");
        }
    }

    private void requireEnabled() {
        if (!enabled) throw new IllegalStateException("Integração Asaas ainda não configurada para esta instalação.");
    }
    private static String text(JsonNode n,String field){ if(n==null||n.path(field).isMissingNode()||n.path(field).isNull())return null;String v=n.path(field).asText();return v==null||v.isBlank()?null:v; }
    private static boolean constantTimeEquals(String a,String b){ if(a.length()!=b.length())return false;int r=0;for(int i=0;i<a.length();i++)r|=a.charAt(i)^b.charAt(i);return r==0; }

}
