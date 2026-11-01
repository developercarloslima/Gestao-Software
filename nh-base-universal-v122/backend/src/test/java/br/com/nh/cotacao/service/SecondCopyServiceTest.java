package br.com.nh.cotacao.service;

import br.com.nh.cotacao.dto.CommercialFinanceDtos.ChargeResponse;
import br.com.nh.cotacao.entity.FinancialBillingType;
import br.com.nh.cotacao.entity.FinancialChargeStatus;
import br.com.nh.cotacao.entity.FinancialChargeType;
import org.junit.jupiter.api.Test;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.UUID;
import static org.junit.jupiter.api.Assertions.*;

class SecondCopyServiceTest {
    private static ChargeResponse charge(FinancialChargeStatus status,LocalDate due,String url) {
        return new ChargeResponse(UUID.randomUUID(),FinancialChargeType.MONTHLY,FinancialBillingType.BOLETO,
                new BigDecimal("105.00"),BigDecimal.ZERO,new BigDecimal("105.00"),due,status,
                null,url,null,"00190500954014481606906809350314337370000000100",null,null,null);
    }

    @Test void onlyCurrentUnpaidHttpsInvoicesCanBeOpened() {
        var current=SecondCopyService.convert(charge(FinancialChargeStatus.PENDING,LocalDate.now().plusDays(10),"https://bank.example/boleto"),"ABC1234","Onix");
        assertTrue(current.disponivel());
        assertEquals("https://bank.example/boleto",current.pdf());

        var overdue=SecondCopyService.convert(charge(FinancialChargeStatus.PENDING,LocalDate.now().minusDays(1),"https://bank.example/old"),"ABC1234","Onix");
        assertFalse(overdue.disponivel());
        assertNull(overdue.pdf());

        var paid=SecondCopyService.convert(charge(FinancialChargeStatus.RECEIVED,LocalDate.now().plusDays(10),"https://bank.example/paid"),"ABC1234","Onix");
        assertFalse(paid.disponivel());
        assertNull(paid.linhaDigitavel());
    }

    @Test void unsafeDownloadLinkIsNeverExposed() {
        var unsafe=SecondCopyService.convert(charge(FinancialChargeStatus.PENDING,LocalDate.now().plusDays(10),"http://internal/boleto"),"ABC1234","Onix");
        assertNull(unsafe.pdf());
    }
}
