package br.com.nh.cotacao.entity;

public enum RearWindowBranding {
    NOT_APPLICABLE("Não se aplica"),
    ANY_PERFORATED("Perfurado no vigia traseiro"),
    NH_AND_OTHER_COMPANY("Perfurado com " + TenantIdentity.displayName() + " + outra empresa"),
    NH_ONLY("Perfurado somente com a logomarca " + TenantIdentity.displayName() + "");

    private final String displayName;

    RearWindowBranding(String displayName) {
        this.displayName = displayName;
    }

    public String displayName() {
        return displayName;
    }
}
