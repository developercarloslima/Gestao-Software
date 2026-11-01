package br.com.nh.cotacao.service;

import org.junit.jupiter.api.Test;
import org.springframework.web.server.ResponseStatusException;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class FinancialProviderGatewayTest {
    @Test void noneNeverSendsAChargeEvenIfAsaasIsConfigured() {
        FinancialProvider adapter=mock(FinancialProvider.class);
        when(adapter.code()).thenReturn("ASAAS");when(adapter.enabled()).thenReturn(true);
        var gateway=new FinancialProviderGateway(List.of(adapter),"NONE");
        assertFalse(gateway.enabled());
        assertEquals("NONE",gateway.status().get("provider"));
        assertThrows(ResponseStatusException.class,()->gateway.createCustomer(null));
        verify(adapter,never()).createCustomer(any());
    }

    @Test void unknownProviderIsNotSilentlyReplacedByAsaas() {
        FinancialProvider adapter=mock(FinancialProvider.class);
        when(adapter.code()).thenReturn("ASAAS");when(adapter.enabled()).thenReturn(true);
        var gateway=new FinancialProviderGateway(List.of(adapter),"FUTURA_FINANCEIRA");
        assertFalse(gateway.enabled());
        assertThrows(ResponseStatusException.class,()->gateway.balance());
    }
}
