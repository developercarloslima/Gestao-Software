package br.com.nh.cotacao.service;

import java.math.BigDecimal;
import java.math.RoundingMode;

/** Apenas saldo confirmado no Asaas pode ser objeto de repasse. */
public final class CompanyPayoutCalculator {
    private CompanyPayoutCalculator() {}
    public static BigDecimal amount(BigDecimal available, BigDecimal configuredReserve,
                                    BigDecimal obligations, BigDecimal transferFee) {
        if (available == null || configuredReserve == null || obligations == null || transferFee == null)
            throw new IllegalArgumentException("Os valores de caixa e tarifas devem ser conhecidos.");
        if (available.signum() < 0 || configuredReserve.signum() < 0 || obligations.signum() < 0 || transferFee.signum() < 0)
            throw new IllegalArgumentException("Não podem existir valores negativos na apuração de repasse.");
        return available.subtract(configuredReserve).subtract(obligations).subtract(transferFee)
                .max(BigDecimal.ZERO).setScale(2, RoundingMode.DOWN);
    }
}
