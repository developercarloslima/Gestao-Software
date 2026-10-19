package br.com.nh.cotacao.entity;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.junit.jupiter.api.Assertions.*;

class QuotationTest {

    @Test
    void shouldOnlyAddSelectedOptionalsToMonthlyTotal() {
        Quotation quotation = quotationWithBaseValue("100.00");

        assertEquals(new BigDecimal("100.00"), quotation.getMonthlyValue());
        assertTrue(quotation.getSelectedOptionals().isEmpty());

        quotation.addOptional(
                "FUNERAL",
                "Auxílio funeral individual",
                "R$ 3.000,00 para o associado",
                new BigDecimal("5.00")
        );

        assertEquals(new BigDecimal("105.00"), quotation.getMonthlyValue());
        assertEquals(1, quotation.getSelectedOptionals().size());
    }

    @Test
    void shouldRejectDuplicatedOptional() {
        Quotation quotation = quotationWithBaseValue("100.00");
        quotation.addOptional("FUNERAL", "Auxílio funeral", null, new BigDecimal("5.00"));

        IllegalArgumentException error = assertThrows(
                IllegalArgumentException.class,
                () -> quotation.addOptional("FUNERAL", "Auxílio funeral", null, new BigDecimal("5.00"))
        );

        assertEquals("O mesmo opcional não pode ser selecionado mais de uma vez.", error.getMessage());
        assertEquals(new BigDecimal("105.00"), quotation.getMonthlyValue());
    }


    @Test
    void shouldRegisterInspectionPhotosAndFinalDocument() {
        Quotation quotation = quotationWithBaseValue("100.00");
        quotation.decide(QuoteStatus.ACCEPTED);
        quotation.registerDriveFolder("folder-1", "https://drive.google.com/folder-1");
        quotation.addInspectionPhoto(
                "Frente do veículo",
                "01 - Frente do veículo.jpg",
                "image/jpeg",
                1024,
                1,
                "photo-1",
                "https://drive.google.com/photo-1"
        );
        quotation.completeInspection("pdf-1", "https://drive.google.com/pdf-1");

        assertEquals(1, quotation.getInspectionPhotos().size());
        assertNotNull(quotation.getInspectionCompletedAt());
        assertEquals("https://drive.google.com/folder-1", quotation.getDriveFolderUrl());
        assertEquals("https://drive.google.com/pdf-1", quotation.getDrivePdfUrl());
    }

    @Test
    void shouldClearPreviousPhotosBeforeAReplacementInspection() {
        Quotation quotation = quotationWithBaseValue("100.00");
        quotation.addInspectionPhoto(
                "Frente do veículo",
                "01.jpg",
                "image/jpeg",
                1024,
                1,
                "photo-1",
                "https://drive.google.com/photo-1"
        );
        quotation.completeInspection("pdf-1", "https://drive.google.com/pdf-1");

        quotation.replaceInspectionPhotos();

        assertTrue(quotation.getInspectionPhotos().isEmpty());
        assertNull(quotation.getInspectionCompletedAt());
    }

    @Test
    void shouldEditOnlyNonPricingDataWithoutChangingQuoteValue() {
        Quotation quotation = quotationWithBaseValue("100.00");
        quotation.addOptional("FUNERAL", "Auxílio funeral", null, new BigDecimal("5.00"));

        BigDecimal fipeBefore = quotation.getFipeValue();
        BigDecimal baseBefore = quotation.getBaseMonthlyValue();
        BigDecimal monthlyBefore = quotation.getMonthlyValue();
        BigDecimal mandatoryBefore = quotation.getMandatoryMonthlyFee();
        BigDecimal oneTimeBefore = quotation.getOneTimeFee();
        String planBefore = quotation.getSelectedPlanCode();
        String categoryBefore = quotation.getCategoryCode();
        int optionalsBefore = quotation.getSelectedOptionals().size();

        quotation.updateNonPricingData(
                "Cliente Atualizado",
                "52998224725",
                "82988887777",
                "DEF4G56",
                "Veículo atualizado",
                2026,
                false
        );

        assertEquals("Cliente Atualizado", quotation.getCustomerName());
        assertEquals("52998224725", quotation.getCustomerCpf());
        assertEquals("82988887777", quotation.getWhatsapp());
        assertEquals("DEF4G56", quotation.getPlate());
        assertEquals("Veículo atualizado", quotation.getModel());
        assertEquals(2026, quotation.getManufactureYear());
        assertEquals(fipeBefore, quotation.getFipeValue());
        assertEquals(baseBefore, quotation.getBaseMonthlyValue());
        assertEquals(monthlyBefore, quotation.getMonthlyValue());
        assertEquals(mandatoryBefore, quotation.getMandatoryMonthlyFee());
        assertEquals(oneTimeBefore, quotation.getOneTimeFee());
        assertEquals(planBefore, quotation.getSelectedPlanCode());
        assertEquals(categoryBefore, quotation.getCategoryCode());
        assertEquals(optionalsBefore, quotation.getSelectedOptionals().size());
    }


    @Test
    void shouldRejectNewInspectionWhenQuotationIsNotAccepted() {
        Consultant consultant = Consultant.create("Consultor", "TEST");
        Quotation quotation = Quotation.createForConsultant(
                "NH-2026-BLOCK001", consultant, "Cliente", "52998224725", "82999999999",
                "ABC1D23", "Veículo teste", 2025, false, new BigDecimal("50000.00"),
                "CAR_NATIONAL", Region.NATIONAL, null, "CAR_ECONOMICO", "Plano Econômico",
                new BigDecimal("100.00"), BigDecimal.ZERO, BigDecimal.ZERO, null
        );

        IllegalArgumentException error = assertThrows(
                IllegalArgumentException.class,
                () -> InspectionRequest.createForQuotation("token-block", quotation)
        );

        assertTrue(error.getMessage().contains("aceita"));
    }

    @Test
    void shouldSynchronizeSafeQuoteDataWithExistingInspection() {
        Consultant consultant = Consultant.create("Consultor", "TEST");
        Quotation quotation = Quotation.createForConsultant(
                "NH-2026-SYNC001", consultant, "Cliente", "52998224725", "82999999999",
                "ABC1D23", "Veículo teste", 2025, false, new BigDecimal("50000.00"),
                "CAR_NATIONAL", Region.NATIONAL, null, "CAR_ECONOMICO", "Plano Econômico",
                new BigDecimal("100.00"), BigDecimal.ZERO, BigDecimal.ZERO, null
        );
        quotation.decide(QuoteStatus.ACCEPTED);
        InspectionRequest inspection = InspectionRequest.createForQuotation("token-sync", quotation);

        quotation.updateNonPricingData("Novo Nome", "52998224725", "82988887777", "DEF4G56", "Modelo novo", 2026, false);
        inspection.updateAssociateData(
                quotation.getCustomerName(), quotation.getCustomerCpf(),
                quotation.getWhatsapp(), quotation.getPlate()
        );
        inspection.updateEditableAssociateVehicleData(
                quotation.getCustomerName(), quotation.getWhatsapp(), quotation.getModel(), quotation.getManufactureYear()
        );

        assertEquals("Novo Nome", inspection.getAssociateName());
        assertEquals("52998224725", inspection.getCpf());
        assertEquals("82988887777", inspection.getWhatsapp());
        assertEquals("DEF4G56", inspection.getPlate());
        assertEquals("Modelo novo", inspection.getVehicleModel());
        assertEquals(2026, inspection.getModelYear());
        assertEquals(new BigDecimal("100.00"), quotation.getMonthlyValue());
    }

    @Test
    void shouldEditAllAssociateAndVehicleDataInInspectionWithoutChangingFipe() {
        Consultant consultant = Consultant.create("Consultor", "TEST");
        Quotation quotation = Quotation.createForConsultant(
                "NH-2026-EDIT001", consultant, "Cliente", "52998224725", "82999999999",
                "ABC1D23", "Veículo teste", 2025, false, new BigDecimal("50000.00"),
                "CAR_NATIONAL", Region.NATIONAL, null, "CAR_ECONOMICO", "Plano Econômico",
                new BigDecimal("100.00"), BigDecimal.ZERO, BigDecimal.ZERO, null
        );
        quotation.decide(QuoteStatus.ACCEPTED);
        InspectionRequest inspection = InspectionRequest.createForQuotation("token-edit", quotation);
        BigDecimal fipeBefore = quotation.getFipeValue();

        inspection.updateEditableAssociateVehicleData(
                "Cliente Corrigido", "11144477735", "82988887777", "DEF4G56",
                "Modelo corrigido", 2026, false, "Rua Teste, 100 - Centro"
        );
        quotation.updateNonPricingData(
                inspection.getAssociateName(), inspection.getCpf(), inspection.getWhatsapp(), inspection.getPlate(),
                inspection.getVehicleModel(), inspection.getModelYear(), false, quotation.getObservation()
        );

        assertEquals("Cliente Corrigido", inspection.getAssociateName());
        assertEquals("11144477735", inspection.getCpf());
        assertEquals("82988887777", inspection.getWhatsapp());
        assertEquals("DEF4G56", inspection.getPlate());
        assertEquals("Modelo corrigido", inspection.getVehicleModel());
        assertEquals(2026, inspection.getModelYear());
        assertEquals("Rua Teste, 100 - Centro", inspection.getResidenceAddress());
        assertEquals(fipeBefore, quotation.getFipeValue());
        assertEquals(new BigDecimal("100.00"), quotation.getMonthlyValue());
    }

    @Test
    void shouldApplyConsultantDiscountToMonthlyValue() {
        Quotation quotation = quotationWithBaseValue("100.00");

        quotation.applyDiscount(10, RearWindowBranding.NOT_APPLICABLE);

        assertEquals(new BigDecimal("100.00"), quotation.getPreDiscountMonthlyValue());
        assertEquals(new BigDecimal("90.00"), quotation.getMonthlyValue());
        assertEquals(10, quotation.getDiscountPercent());
        assertEquals(RearWindowBranding.NOT_APPLICABLE, quotation.getRearWindowBranding());
    }

    @Test
    void shouldRequireTwoLogosForFifteenPercentDiscount() {
        Quotation quotation = quotationWithBaseValue("100.00");

        assertThrows(
                IllegalArgumentException.class,
                () -> quotation.applyDiscount(15, RearWindowBranding.NH_ONLY)
        );

        quotation.applyDiscount(15, RearWindowBranding.NH_AND_OTHER_COMPANY);

        assertEquals(new BigDecimal("85.00"), quotation.getMonthlyValue());
        assertEquals(RearWindowBranding.NH_AND_OTHER_COMPANY, quotation.getRearWindowBranding());
    }

    @Test
    void shouldRequireOnlyNhLogoForThirtyPercentDiscount() {
        Quotation quotation = quotationWithBaseValue("100.00");

        assertThrows(
                IllegalArgumentException.class,
                () -> quotation.applyDiscount(30, RearWindowBranding.NH_AND_OTHER_COMPANY)
        );

        quotation.applyDiscount(30, RearWindowBranding.NH_ONLY);

        assertEquals(new BigDecimal("70.00"), quotation.getMonthlyValue());
        assertEquals(RearWindowBranding.NH_ONLY, quotation.getRearWindowBranding());
    }

    @Test
    void shouldRejectRearWindowDiscountForMotorcyclesAndElectricScooters() {
        Consultant consultant = Consultant.create("Consultor", "TEST");
        Quotation scooter = Quotation.createForConsultant(
                "NH-2026-SCOOTER001",
                consultant,
                "Cliente",
                "52998224725",
                "82999999999",
                "ABC1D23",
                "Scooter elétrica",
                2026,
                false,
                new BigDecimal("10000.00"),
                "SCOOTER_ELECTRIC",
                Region.NATIONAL,
                null,
                "SCOOTER_ELECTRIC_STANDARD",
                "Scooters e motos elétricas",
                new BigDecimal("100.00"),
                BigDecimal.ZERO,
                BigDecimal.ZERO,
                null
        );

        assertThrows(
                IllegalArgumentException.class,
                () -> scooter.applyDiscount(15, RearWindowBranding.NH_AND_OTHER_COMPANY)
        );
        assertThrows(
                IllegalArgumentException.class,
                () -> scooter.applyDiscount(30, RearWindowBranding.NH_ONLY)
        );
    }

    @Test
    void shouldRecalculateDiscountAfterAddingOptionalCoverage() {
        Quotation quotation = quotationWithBaseValue("100.00");
        quotation.applyDiscount(10, RearWindowBranding.NOT_APPLICABLE);

        quotation.addOptional("FUNERAL", "Auxílio funeral", null, new BigDecimal("10.00"));

        assertEquals(new BigDecimal("110.00"), quotation.getPreDiscountMonthlyValue());
        assertEquals(new BigDecimal("99.00"), quotation.getMonthlyValue());
    }

    @Test
    void shouldStoreZeroKilometerInformation() {
        Consultant consultant = Consultant.create("Consultor", "TEST");
        Quotation quotation = Quotation.createForConsultant(
                "NH-2026-TESTE0KM",
                consultant,
                "Cliente",
                "52998224725",
                "82999999999",
                "ABC1D23",
                "Veículo zero",
                2026,
                true,
                new BigDecimal("90000.00"),
                "CAR_NATIONAL",
                Region.NATIONAL,
                null,
                "CAR_COMPLETO",
                "Plano Completo",
                new BigDecimal("200.00"),
                BigDecimal.ZERO,
                BigDecimal.ZERO,
                null
        );

        assertTrue(quotation.isZeroKm());
    }

    @Test
    void shouldAllowZeroKilometerVehicleWithoutPlate() {
        Consultant consultant = Consultant.create("Consultor", "TEST");
        Quotation quotation = Quotation.createForConsultant(
                "NH-2026-SEMPLACA",
                consultant,
                "Cliente",
                "52998224725",
                "82999999999",
                null,
                "Veículo zero",
                2026,
                true,
                new BigDecimal("90000.00"),
                "CAR_NATIONAL",
                Region.NATIONAL,
                null,
                "CAR_COMPLETO",
                "Plano Completo",
                new BigDecimal("200.00"),
                BigDecimal.ZERO,
                BigDecimal.ZERO,
                null
        );

        assertTrue(quotation.isZeroKm());
        assertNull(quotation.getPlate());
    }


    @Test
    void shouldAssignSelfServiceQuoteAndInspectionToConsultant() {
        Consultant consultant = Consultant.create("Jose Consultor", "TEST");
        Quotation quotation = Quotation.createSelfService(
                "NH-2026-PUBLICA001",
                consultant,
                "Cliente Público",
                "52998224725",
                "82999999999",
                "ABC1D23",
                "Veículo teste",
                2025,
                false,
                new BigDecimal("50000.00"),
                "CAR_NATIONAL",
                Region.NATIONAL,
                null,
                "CAR_ECONOMICO",
                "Plano Econômico",
                new BigDecimal("100.00"),
                BigDecimal.ZERO,
                BigDecimal.ZERO,
                null
        );

        quotation.decide(QuoteStatus.ACCEPTED);
        InspectionRequest inspection = InspectionRequest.createForSelfServiceQuote("token-publico", quotation);

        assertEquals(consultant.getId(), quotation.getConsultant().getId());
        assertEquals(consultant.getName(), quotation.getConsultantName());
        assertEquals(consultant.getId(), inspection.getConsultant().getId());
        assertEquals(consultant.getName(), inspection.getConsultantName());
        assertEquals(InspectionRequestStatus.WAITING_FILES, inspection.getStatus());
        IllegalArgumentException error = assertThrows(
                IllegalArgumentException.class,
                () -> inspection.adminReview(InspectionRequestStatus.APPROVED, "Aprovação indevida")
        );
        assertTrue(error.getMessage().contains("Aguardando arquivos"));
    }

    @Test
    void shouldApplyFipeThirdPartyRuleWithoutReducingItWhenDiscounted() {
        Quotation quotation = quotationWithBaseValue("100.00");
        quotation.addCoverageSnapshot("THIRD_PARTY_BASE", "Terceiros", CoverageStatus.INCLUDED, "Legado", null, 1);
        quotation.addCoverageSnapshot("THIRD_PARTY", "Terceiros adicional", CoverageStatus.OPTIONAL, "Legado", new BigDecimal("10.00"), 2);

        quotation.updateFipeValue(new BigDecimal("50999.99"));
        quotation.applyDiscount(10, RearWindowBranding.NOT_APPLICABLE);

        QuotationCoverageSnapshot base = quotation.getCoverageSnapshots().stream()
                .filter(item -> "THIRD_PARTY_BASE".equals(item.getCoverageCode()))
                .findFirst().orElseThrow();
        QuotationCoverageSnapshot supplemental = quotation.getCoverageSnapshots().stream()
                .filter(item -> "THIRD_PARTY".equals(item.getCoverageCode()))
                .findFirst().orElseThrow();
        assertTrue(base.isFinalSelected());
        assertEquals("Cobertura de até R$ 50 mil", base.getDetail());
        assertFalse(supplemental.isFinalSelected());

        quotation.updateFipeValue(new BigDecimal("51000.00"));
        assertEquals("Cobertura de até R$ 50 mil", base.getDetail());
        assertTrue(base.isFinalSelected());

        quotation.applyDiscount(0, RearWindowBranding.NOT_APPLICABLE);
        assertEquals("Cobertura de até R$ 100 mil", base.getDetail());
    }

    @Test
    void shouldRemoveNaturalPhenomenaAndSmallRepairsWheneverThereIsDiscount() {
        Quotation quotation = quotationWithBaseValue("100.00");
        quotation.addCoverageSnapshot("NATURAL_PHENOMENA", "Fenômenos da natureza", CoverageStatus.INCLUDED, null, null, 1);
        quotation.addCoverageSnapshot("SMALL_REPAIRS", "Pequenos reparos", CoverageStatus.INCLUDED, null, null, 2);

        quotation.applyDiscount(5, RearWindowBranding.NOT_APPLICABLE);

        assertFalse(quotation.getCoverageSnapshots().stream()
                .filter(item -> "NATURAL_PHENOMENA".equals(item.getCoverageCode()))
                .findFirst().orElseThrow().isFinalSelected());
        assertFalse(quotation.getCoverageSnapshots().stream()
                .filter(item -> "SMALL_REPAIRS".equals(item.getCoverageCode()))
                .findFirst().orElseThrow().isFinalSelected());
    }

    @Test
    void shouldKeepExpiredInspectionAliveWhenItAlreadyHasDatabaseFile() throws Exception {
        Consultant consultant = Consultant.create("Consultor", "TEST");
        Quotation quotation = Quotation.createForConsultant(
                "NH-2026-FILE001", consultant, "Cliente", "52998224725", "82999999999",
                "ABC1D23", "Veículo teste", 2025, false, new BigDecimal("50000.00"),
                "CAR_NATIONAL", Region.NATIONAL, null, "CAR_ECONOMICO", "Plano Econômico",
                new BigDecimal("100.00"), BigDecimal.ZERO, BigDecimal.ZERO, null
        );
        quotation.decide(QuoteStatus.ACCEPTED);
        InspectionRequest inspection = InspectionRequest.createForQuotation("token-file-db", quotation);
        var expiresAt = InspectionRequest.class.getDeclaredField("expiresAt");
        expiresAt.setAccessible(true);
        expiresAt.set(inspection, java.time.OffsetDateTime.now().minusDays(1));
        inspection.addAsset(InspectionAsset.createDatabase(
                inspection, InspectionAssetType.PHOTO, "Frente", "frente.jpg", "image/jpeg", 1000, 1,
                java.time.OffsetDateTime.now().minusDays(10), null
        ));

        assertTrue(inspection.hasAnyPreservedFile());
        assertFalse(inspection.isExpired());
    }

    @Test
    void shouldRecognizeLegacyDriveFileAsPreservedInspectionFile() throws Exception {
        Consultant consultant = Consultant.create("Consultor", "TEST");
        Quotation quotation = Quotation.createForConsultant(
                "NH-2026-FILE002", consultant, "Cliente", "52998224725", "82999999999",
                "ABC1D23", "Veículo teste", 2025, false, new BigDecimal("50000.00"),
                "CAR_NATIONAL", Region.NATIONAL, null, "CAR_ECONOMICO", "Plano Econômico",
                new BigDecimal("100.00"), BigDecimal.ZERO, BigDecimal.ZERO, null
        );
        quotation.decide(QuoteStatus.ACCEPTED);
        InspectionRequest inspection = InspectionRequest.createForQuotation("token-file-drive", quotation);
        var expiresAt = InspectionRequest.class.getDeclaredField("expiresAt");
        expiresAt.setAccessible(true);
        expiresAt.set(inspection, java.time.OffsetDateTime.now().minusDays(1));
        inspection.addAsset(InspectionAsset.create(
                inspection, InspectionAssetType.PHOTO, "Frente", "frente.jpg", "image/jpeg", 1000, 1,
                "drive-file-1", "https://drive.google.com/file/d/drive-file-1"
        ));

        assertTrue(inspection.hasAnyPreservedFile());
        assertFalse(inspection.isExpired());
    }

    @Test
    void shouldBlockDossierChangesAfterDigitalAcceptance() {
        Consultant consultant = Consultant.create("Consultor", "TEST");
        Quotation quotation = Quotation.createForConsultant(
                "NH-2026-DIGITAL001", consultant, "Cliente", "52998224725", "82999999999",
                "ABC1D23", "Veículo teste", 2025, false, new BigDecimal("50000.00"),
                "CAR_NATIONAL", Region.NATIONAL, null, "CAR_ECONOMICO", "Plano Econômico",
                new BigDecimal("100.00"), BigDecimal.ZERO, BigDecimal.ZERO, null
        );
        quotation.decide(QuoteStatus.ACCEPTED);
        InspectionRequest inspection = InspectionRequest.createForQuotation("token-digital-lock", quotation);

        assertDoesNotThrow(inspection::invalidatePendingDigitalAcceptance);
        inspection.completeDigitalAcceptance(
                1L, "signature", "authenticator", "client-data", "proof-hash", true,
                java.time.OffsetDateTime.now()
        );

        IllegalArgumentException error = assertThrows(
                IllegalArgumentException.class,
                inspection::invalidatePendingDigitalAcceptance
        );
        assertTrue(error.getMessage().contains("aceite digital"));
        assertThrows(IllegalArgumentException.class, inspection::reopenForMissingFiles);
    }

    private Quotation quotationWithBaseValue(String baseValue) {
        Consultant consultant = Consultant.create("Consultor", "TEST");
        return Quotation.createForConsultant(
                "NH-2026-TESTE001",
                consultant,
                "Cliente",
                "52998224725",
                "82999999999",
                "ABC1D23",
                "Veículo teste",
                2025,
                false,
                new BigDecimal("50000.00"),
                "CAR_NATIONAL",
                Region.NATIONAL,
                null,
                "CAR_ECONOMICO",
                "Plano Econômico",
                new BigDecimal(baseValue),
                BigDecimal.ZERO,
                BigDecimal.ZERO,
                null
        );
    }
}
