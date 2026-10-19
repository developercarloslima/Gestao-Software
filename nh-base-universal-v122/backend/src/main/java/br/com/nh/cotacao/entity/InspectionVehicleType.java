package br.com.nh.cotacao.entity;

import java.util.Locale;

public enum InspectionVehicleType {
    MOTORCYCLE(7, "Moto ou veículo com menos de 4 rodas"),
    FOUR_WHEELS_OR_MORE(15, "Carro, utilitário ou veículo com 4 rodas ou mais");

    private final int requiredPhotoCount;
    private final String displayName;

    InspectionVehicleType(int requiredPhotoCount, String displayName) {
        this.requiredPhotoCount = requiredPhotoCount;
        this.displayName = displayName;
    }

    public int requiredPhotoCount() {
        return requiredPhotoCount;
    }

    public String displayName() {
        return displayName;
    }

    public static InspectionVehicleType fromCategoryCode(String categoryCode) {
        String normalized = categoryCode == null ? "" : categoryCode.trim().toUpperCase(Locale.ROOT);
        return (normalized.startsWith("MOTORCYCLE") || normalized.equals("SCOOTER_ELECTRIC"))
                ? MOTORCYCLE
                : FOUR_WHEELS_OR_MORE;
    }
}
