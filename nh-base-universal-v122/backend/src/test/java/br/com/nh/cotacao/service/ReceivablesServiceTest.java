package br.com.nh.cotacao.service;

import br.com.nh.cotacao.entity.CommercialVehicleStatus;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.assertEquals;

class ReceivablesServiceTest {
    @Test
    void delinquencyThresholdsAreStrict() {
        assertEquals("EM_DIA", ReceivablesService.businessStatus(CommercialVehicleStatus.ACTIVE, 0));
        assertEquals("ATRASADO", ReceivablesService.businessStatus(CommercialVehicleStatus.ACTIVE, 1));
        assertEquals("ATRASADO", ReceivablesService.businessStatus(CommercialVehicleStatus.ACTIVE, 6));
        assertEquals("INADIMPLENTE", ReceivablesService.businessStatus(CommercialVehicleStatus.ACTIVE, 7));
        assertEquals("INADIMPLENTE", ReceivablesService.businessStatus(CommercialVehicleStatus.ACTIVE, 60));
        assertEquals("CANCELADO", ReceivablesService.businessStatus(CommercialVehicleStatus.ACTIVE, 61));
    }

    @Test
    void explicitlyCancelledPlanAlwaysAppearsCancelled() {
        assertEquals("CANCELADO", ReceivablesService.businessStatus(CommercialVehicleStatus.CANCELLED, 0));
        assertEquals("CANCELADO", ReceivablesService.businessStatus(CommercialVehicleStatus.CANCELLED, 1));
    }
}
