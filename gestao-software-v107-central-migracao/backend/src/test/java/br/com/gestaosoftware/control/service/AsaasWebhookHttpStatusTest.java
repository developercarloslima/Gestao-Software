package br.com.gestaosoftware.control.service;

import br.com.gestaosoftware.control.controller.ProvisioningController;
import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.ResponseStatus;

import static org.junit.jupiter.api.Assertions.*;

class AsaasWebhookHttpStatusTest {
    @Test
    void webhookMustReturnHttp200ToConfirmAsaasDelivery() throws Exception {
        var method = ProvisioningController.class.getMethod("webhook", String.class, JsonNode.class);
        ResponseStatus status = method.getAnnotation(ResponseStatus.class);
        assertNotNull(status, "Webhook deve declarar resposta HTTP explicitamente");
        assertEquals(HttpStatus.OK, status.value(), "Asaas exige HTTP 200 (nao HTTP 204)");
    }
}
