package br.com.nh.cotacao.entity;

import jakarta.persistence.*;

import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Set;

@Entity
@Table(name = "coverages")
public class Coverage {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, unique = true, length = 80)
    private String code;

    @Column(nullable = false, length = 180)
    private String name;

    /**
     * Categorias nas quais este benefício pode ser comercializado como adicional
     * (status OPTIONAL). Benefícios integrados ao plano não dependem desta lista.
     * Os códigos ficam persistidos em CSV para manter o catálogo simples e evitar
     * uma segunda árvore de vínculos paralela a plan_coverages.
     */
    @Column(name = "optional_vehicle_categories", nullable = false, length = 1000)
    private String optionalVehicleCategories = "";

    protected Coverage() {
    }

    public static Coverage create(String code, String name) {
        Coverage coverage = new Coverage();
        coverage.updateAdmin(code, name);
        return coverage;
    }

    public void updateAdmin(String code, String name) {
        this.code = requireText(code, "Código da cobertura");
        this.name = requireText(name, "Nome da cobertura");
    }

    public void updateOptionalVehicleCategories(Collection<String> categoryCodes) {
        LinkedHashSet<String> normalized = new LinkedHashSet<>();
        if (categoryCodes != null) {
            categoryCodes.stream()
                    .filter(java.util.Objects::nonNull)
                    .map(value -> value.trim().toUpperCase(Locale.ROOT))
                    .filter(value -> !value.isBlank())
                    .sorted()
                    .forEach(normalized::add);
        }
        this.optionalVehicleCategories = String.join(",", normalized);
    }

    public Set<String> getOptionalVehicleCategoryCodes() {
        LinkedHashSet<String> result = new LinkedHashSet<>();
        if (optionalVehicleCategories == null || optionalVehicleCategories.isBlank()) return result;
        for (String value : optionalVehicleCategories.split(",")) {
            String normalized = value == null ? "" : value.trim().toUpperCase(Locale.ROOT);
            if (!normalized.isBlank()) result.add(normalized);
        }
        return result;
    }

    public boolean allowsOptionalForVehicleCategory(String categoryCode) {
        if (categoryCode == null || categoryCode.isBlank()) return false;
        return getOptionalVehicleCategoryCodes().contains(categoryCode.trim().toUpperCase(Locale.ROOT));
    }

    private static String requireText(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(field + " é obrigatório.");
        }
        return value.trim();
    }

    public Long getId() { return id; }
    public String getCode() { return code; }
    public String getName() { return name; }
    public String getOptionalVehicleCategories() { return optionalVehicleCategories == null ? "" : optionalVehicleCategories; }
}
