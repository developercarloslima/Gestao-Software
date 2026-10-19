package br.com.nh.cotacao.service;

import java.time.OffsetDateTime;

/** Prazo uniforme para os quatro fluxos públicos de aceite criptográfico. */
public final class AcceptanceLinkPolicy {
    public static final int VALID_DAYS = 7;
    private AcceptanceLinkPolicy() {}

    public static OffsetDateTime expiresAt(OffsetDateTime availableAt) {
        return availableAt == null ? null : availableAt.plusDays(VALID_DAYS);
    }

    public static boolean isValid(OffsetDateTime availableAt, OffsetDateTime at) {
        return availableAt != null && at != null && !at.isAfter(expiresAt(availableAt));
    }

    public static void requireValid(OffsetDateTime availableAt, OffsetDateTime at) {
        if (availableAt == null) {
            throw new IllegalArgumentException("O link de aceite ainda não foi disponibilizado. Solicite a liberação do documento.");
        }
        if (!isValid(availableAt, at)) {
            throw new IllegalArgumentException("O prazo de 7 dias para o aceite digital terminou. Solicite um novo link à NH.");
        }
    }
}
