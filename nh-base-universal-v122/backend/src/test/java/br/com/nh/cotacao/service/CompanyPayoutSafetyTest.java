package br.com.nh.cotacao.service;

import org.junit.jupiter.api.Test;
import java.math.BigDecimal;
import java.security.SecureRandom;
import java.util.Base64;

import static org.junit.jupiter.api.Assertions.*;

class CompanyPayoutSafetyTest {
    @Test void protectsPendingWithdrawalsAndReserveBeforeDailyTransfer() {
        assertEquals(new BigDecimal("645.00"),
                CompanyPayoutCalculator.amount(new BigDecimal("1000.00"),
                        new BigDecimal("200.00"),new BigDecimal("150.00"),new BigDecimal("5.00")));
    }

    @Test void cannotSpendFundsWhenReservesExceedBalance() {
        assertEquals(new BigDecimal("0.00"),
                CompanyPayoutCalculator.amount(new BigDecimal("120.00"),
                        new BigDecimal("200.00"),new BigDecimal("50.00"),new BigDecimal("1.00")));
    }

    @Test void neverAcceptsUnknownOrNegativeAmounts() {
        assertThrows(IllegalArgumentException.class,()->
                CompanyPayoutCalculator.amount(new BigDecimal("-1"),BigDecimal.ZERO,BigDecimal.ZERO,BigDecimal.ZERO));
        assertThrows(IllegalArgumentException.class,()->
                CompanyPayoutCalculator.amount(BigDecimal.ONE,null,BigDecimal.ZERO,BigDecimal.ZERO));
    }

    @Test void bankAndPixDestinationsAreEncryptedAndAuthenticated() {
        byte[] key=new byte[32];new SecureRandom().nextBytes(key);
        var vault=new FinancialDestinationCipher(Base64.getEncoder().encodeToString(key));
        String secret="{\"pixKey\":\"financeiro@empresa.com\"}";
        String encrypted=vault.encrypt(secret);
        assertTrue(encrypted.startsWith("v1:"));
        assertFalse(encrypted.contains("financeiro@"));
        assertEquals(secret,vault.decrypt(encrypted));
        assertNotEquals(encrypted,vault.encrypt(secret),"AES-GCM deve utilizar nonce aleatório");
        String invalid=encrypted.substring(0,encrypted.length()-3)+"XYZ";
        assertThrows(IllegalStateException.class,()->vault.decrypt(invalid));
    }

    @Test void noEncryptionKeyMeansFailClosed() {
        var vault=new FinancialDestinationCipher("");
        assertThrows(IllegalStateException.class,()->vault.encrypt("dados"));
    }
}
