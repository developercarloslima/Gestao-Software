package br.com.gestaosoftware.control.service;

import org.junit.jupiter.api.Test;
import org.springframework.web.server.ResponseStatusException;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class PlatformPaymentGatewayTest {
    @Test void noProviderSelectedMeansNoExternalCharge() {
        PlatformPaymentProvider asaas=mock(PlatformPaymentProvider.class);
        when(asaas.code()).thenReturn("ASAAS");when(asaas.ready()).thenReturn(true);
        var gateway=new PlatformPaymentGateway(List.of(asaas),"NONE");
        assertFalse(gateway.ready());
        assertThrows(ResponseStatusException.class,()->gateway.checkout(UUID.randomUUID()));
        verify(asaas,never()).checkout(any());
    }

    @Test void correctProviderCanBeSelected() {
        PlatformPaymentProvider asaas=mock(PlatformPaymentProvider.class);
        when(asaas.code()).thenReturn("ASAAS");when(asaas.ready()).thenReturn(true);
        UUID id=UUID.randomUUID();
        when(asaas.checkout(id)).thenReturn(Map.of("status","CREATED"));
        var gateway=new PlatformPaymentGateway(List.of(asaas),"ASAAS");
        assertEquals("CREATED",gateway.checkout(id).get("status"));
    }
}
