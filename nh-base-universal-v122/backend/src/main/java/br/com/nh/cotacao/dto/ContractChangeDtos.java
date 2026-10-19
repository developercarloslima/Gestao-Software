package br.com.nh.cotacao.dto;

import jakarta.validation.constraints.NotNull;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.List;

public final class ContractChangeDtos {
    private ContractChangeDtos() {}

    public record OptionalItem(
            String code,
            String name,
            String detail,
            BigDecimal monthlyPrice
    ) {}

    public record PublicContractChangeResponse(
            boolean pending,
            String associateName,
            String vehicleLabel,
            String planName,
            BigDecimal currentFipeValue,
            BigDecimal proposedFipeValue,
            BigDecimal currentMonthlyValue,
            BigDecimal proposedMonthlyWithOptionals,
            BigDecimal proposedMonthlyWithoutOptionals,
            BigDecimal optionalsMonthlyValue,
            List<OptionalItem> selectedOptionals,
            OffsetDateTime requestedAt
    ) {}

    public record ConfirmContractChangeRequest(
            @NotNull Boolean accepted,
            Boolean keepOptionals
    ) {}

    public record ContractChangeDecisionResponse(
            boolean accepted,
            Boolean keepOptionals,
            BigDecimal appliedFipeValue,
            BigDecimal appliedMonthlyValue,
            String message
    ) {}
}
