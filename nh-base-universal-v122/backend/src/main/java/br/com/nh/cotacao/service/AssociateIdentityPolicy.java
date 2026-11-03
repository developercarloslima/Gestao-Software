package br.com.nh.cotacao.service;

import br.com.nh.cotacao.entity.Associate;
import br.com.nh.cotacao.entity.Consultant;
import java.util.Optional;

/** Confere a identidade por CPF e nome completo antes de vincular veículos ou vistorias. */
public final class AssociateIdentityPolicy {
    private AssociateIdentityPolicy() {}

    public static String requireCpf(String raw) {
        String cpf = raw == null ? "" : raw.replaceAll("\\D", "");
        if (!validCpf(cpf)) throw new IllegalArgumentException("Informe um CPF válido.");
        return cpf;
    }

    public static boolean namesMatch(String existingName, String informedName) {
        String existing = Consultant.normalize(existingName);
        String informed = Consultant.normalize(informedName);
        return !existing.isEmpty() && existing.equals(informed);
    }

    public static void assertCompatible(Associate existing, String informedName) {
        if (existing != null && !namesMatch(existing.getName(), informedName)) {
            throw new IllegalArgumentException("CPF utilizado por outro associado. Confira o nome informado.");
        }
    }

    public static String matchStatus(Optional<Associate> existing, String informedName) {
        if (informedName == null || informedName.isBlank()) {
            throw new IllegalArgumentException("Informe o nome completo do associado.");
        }
        if (existing.isEmpty()) return "NEW";
        return namesMatch(existing.get().getName(), informedName) ? "EXISTING_MATCH" : "NAME_CONFLICT";
    }

    private static boolean validCpf(String cpf) {
        if (!cpf.matches("\\d{11}") || cpf.chars().distinct().count() == 1) return false;
        int sum = 0;
        for (int i = 0; i < 9; i++) sum += Character.digit(cpf.charAt(i), 10) * (10 - i);
        int first = 11 - (sum % 11);
        if (first >= 10) first = 0;
        sum = 0;
        for (int i = 0; i < 10; i++) sum += Character.digit(cpf.charAt(i), 10) * (11 - i);
        int second = 11 - (sum % 11);
        if (second >= 10) second = 0;
        return first == Character.digit(cpf.charAt(9), 10)
                && second == Character.digit(cpf.charAt(10), 10);
    }
}
