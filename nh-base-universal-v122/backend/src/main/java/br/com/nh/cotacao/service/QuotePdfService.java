package br.com.nh.cotacao.service;

import br.com.nh.cotacao.platform.TenantIdentity;
import br.com.nh.cotacao.entity.RearWindowBranding;
import br.com.nh.cotacao.entity.CoverageStatus;
import br.com.nh.cotacao.entity.InspectionPhoto;
import br.com.nh.cotacao.entity.Quotation;
import br.com.nh.cotacao.entity.QuotationOptionalCoverage;
import com.lowagie.text.*;
import com.lowagie.text.pdf.ColumnText;
import com.lowagie.text.pdf.PdfContentByte;
import com.lowagie.text.pdf.PdfPCell;
import com.lowagie.text.pdf.PdfPTable;
import com.lowagie.text.pdf.PdfPageEventHelper;
import com.lowagie.text.pdf.PdfWriter;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Service;

import java.awt.Color;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.math.BigDecimal;
import java.time.format.DateTimeFormatter;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;

@Service
public class QuotePdfService {

    private static final Color NAVY = new Color(8, 15, 99);
    private static final Color NAVY_LIGHT = new Color(20, 31, 139);
    private static final Color YELLOW = new Color(255, 236, 0);
    private static final Color TEXT = new Color(28, 31, 48);
    private static final Color MUTED = new Color(95, 101, 119);
    private static final Color LIGHT = new Color(246, 247, 252);
    private static final Color LINE = new Color(220, 224, 234);
    private static final Color GREEN_LIGHT = new Color(229, 247, 234);
    private static final Color YELLOW_LIGHT = new Color(255, 247, 214);

    private static final DateTimeFormatter DATE_TIME = DateTimeFormatter.ofPattern("dd/MM/yyyy 'às' HH:mm");

    private final QuoteService quoteService;
    private final GoogleDriveStorageService driveStorage;

    public QuotePdfService(QuoteService quoteService, GoogleDriveStorageService driveStorage) {
        this.quoteService = quoteService;
        this.driveStorage = driveStorage;
    }

    public byte[] generate(Quotation quotation) {
        if (quotation.getInspectionCompletedAt() != null
                && quotation.getDrivePdfFileId() != null
                && !quotation.getDrivePdfFileId().isBlank()
                && driveStorage.isConfigured()) {
            try {
                return driveStorage.download(quotation.getDrivePdfFileId());
            } catch (RuntimeException ignored) {
                // Em caso de indisponibilidade temporária do Drive, o PDF é reconstruído abaixo.
            }
        }

        try (ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            Document document = new Document(PageSize.A4, 36, 36, 42, 48);
            PdfWriter writer = PdfWriter.getInstance(document, output);
            writer.setPageEvent(new ProfessionalPageEvent(quotation.getQuoteNumber()));
            document.addTitle("Cotação " + quotation.getQuoteNumber() + " - " + TenantIdentity.displayName() + "");
            document.addAuthor("" + TenantIdentity.legalName() + "");
            document.addSubject("Cotação de proteção veicular e relatório de vistoria");
            document.open();

            addHeader(document, quotation);
            addCustomerAndVehicle(document, quotation);
            addPlan(document, quotation);
            addCoverages(document, quotation);
            addFormalConditions(document, quotation);
            addInspectionPhotos(document, quotation);

            document.close();
            return output.toByteArray();
        } catch (Exception exception) {
            throw new IllegalStateException("Não foi possível gerar o PDF da cotação.", exception);
        }
    }

    private void addHeader(Document document, Quotation quotation) throws Exception {
        PdfPTable banner = new PdfPTable(new float[]{1.0f, 4.55f, 1.65f});
        banner.setWidthPercentage(100);
        banner.setSpacingAfter(0);

        PdfPCell logoCell;
        try (InputStream logoStream = new ClassPathResource("identidade-pendente.png").getInputStream()) {
            Image logo = Image.getInstance(logoStream.readAllBytes());
            logo.scaleToFit(60, 60);
            logoCell = new PdfPCell(logo, false);
        }
        styleBannerCell(logoCell);
        logoCell.setPaddingLeft(10);
        logoCell.setPaddingRight(6);
        logoCell.setPaddingTop(10);
        logoCell.setPaddingBottom(10);
        logoCell.setHorizontalAlignment(Element.ALIGN_CENTER);
        logoCell.setVerticalAlignment(Element.ALIGN_MIDDLE);
        banner.addCell(logoCell);

        PdfPCell brandCell = new PdfPCell();
        styleBannerCell(brandCell);
        brandCell.setPaddingLeft(4);
        brandCell.setPaddingRight(4);
        brandCell.setPaddingTop(8);
        brandCell.setPaddingBottom(8);
        brandCell.setVerticalAlignment(Element.ALIGN_MIDDLE);

        Paragraph brandName = new Paragraph("" + TenantIdentity.displayName().toUpperCase(java.util.Locale.ROOT) + "", font(31, Font.BOLD, Color.WHITE));
        brandName.setAlignment(Element.ALIGN_CENTER);
        brandName.setLeading(31);
        brandName.setSpacingAfter(0);
        brandCell.addElement(brandName);

        Paragraph brandSubtitle = new Paragraph("PROTEÇÃO VEICULAR", font(15, Font.BOLD, YELLOW));
        brandSubtitle.setAlignment(Element.ALIGN_CENTER);
        brandSubtitle.setLeading(15.5f);
        brandSubtitle.setSpacingBefore(0);
        brandSubtitle.setSpacingAfter(1);
        brandCell.addElement(brandSubtitle);

        Paragraph cnpj = new Paragraph("CNPJ 38.078.339/0001-83", font(8.7f, Font.BOLD, new Color(231, 234, 246)));
        cnpj.setAlignment(Element.ALIGN_CENTER);
        cnpj.setLeading(9.2f);
        cnpj.setSpacingBefore(0);
        cnpj.setSpacingAfter(0);
        brandCell.addElement(cnpj);

        banner.addCell(brandCell);

        Paragraph identifier = new Paragraph();
        identifier.setAlignment(Element.ALIGN_LEFT);
        identifier.setLeading(10.5f);
        identifier.add(new Chunk("COTAÇÃO Nº\n", font(7, Font.BOLD, YELLOW)));
        identifier.add(new Chunk(quotation.getQuoteNumber() + "\n", font(9.6f, Font.BOLD, Color.WHITE)));
        identifier.add(new Chunk("VALIDADE\n", font(7, Font.BOLD, YELLOW)));
        identifier.add(new Chunk(quotation.getValidUntil().format(DATE_TIME), font(7.4f, Font.BOLD, Color.WHITE)));

        PdfPCell identifierCell = new PdfPCell(identifier);
        styleBannerCell(identifierCell);
        identifierCell.setPaddingLeft(12);
        identifierCell.setPaddingRight(8);
        identifierCell.setPaddingTop(8);
        identifierCell.setPaddingBottom(10);
        identifierCell.setBorderWidthLeft(1.2f);
        identifierCell.setBorderColorLeft(NAVY_LIGHT);
        identifierCell.setVerticalAlignment(Element.ALIGN_BOTTOM);
        banner.addCell(identifierCell);

        document.add(banner);

        PdfPTable accent = new PdfPTable(1);
        accent.setWidthPercentage(100);
        PdfPCell accentCell = new PdfPCell();
        accentCell.setFixedHeight(4f);
        accentCell.setBackgroundColor(YELLOW);
        accentCell.setBorder(Rectangle.NO_BORDER);
        accent.addCell(accentCell);
        accent.setSpacingAfter(12);
        document.add(accent);

        PdfPTable intro = new PdfPTable(new float[]{4.55f, 1.45f});
        intro.setWidthPercentage(100);
        intro.setSpacingAfter(6);

        PdfPCell introText = new PdfPCell();
        introText.setBorder(Rectangle.NO_BORDER);
        introText.setPadding(0);
        Paragraph proposal = new Paragraph();
        proposal.setLeading(17);
        proposal.add(new Chunk("PROPOSTA PERSONALIZADA\n", font(7.5f, Font.BOLD, MUTED)));
        proposal.add(new Chunk("Proteção veicular para o seu veículo", font(15.5f, Font.BOLD, NAVY)));
        introText.addElement(proposal);
        intro.addCell(introText);

        PdfPCell status = new PdfPCell(new Phrase(statusLabel(quotation), font(8, Font.BOLD, NAVY)));
        status.setHorizontalAlignment(Element.ALIGN_CENTER);
        status.setVerticalAlignment(Element.ALIGN_MIDDLE);
        status.setBackgroundColor(YELLOW);
        status.setBorderColor(YELLOW);
        status.setPadding(8);
        intro.addCell(status);
        document.add(intro);

        Paragraph subtitle = new Paragraph(
                "Documento comercial emitido em " + quotation.getCreatedAt().format(DATE_TIME)
                        + ". Validade de 5 dias, sujeita às regras vigentes e à validação da vistoria.",
                font(8.2f, Font.NORMAL, MUTED)
        );
        subtitle.setLeading(12);
        subtitle.setSpacingAfter(15);
        document.add(subtitle);
    }

    private void addCustomerAndVehicle(Document document, Quotation quotation) throws DocumentException {
        document.add(sectionTitle("DADOS DO CLIENTE E DO VEÍCULO"));
        PdfPTable table = new PdfPTable(new float[]{1.15f, 2.35f, 1.15f, 2.35f});
        table.setWidthPercentage(100);
        table.setSpacingAfter(16);
        addLabelValue(table, "Cliente", quotation.getCustomerName());
        addLabelValue(table, "Consultor", quotation.getConsultantName());
        addLabelValue(table, "Placa", plateLabel(quotation));
        addLabelValue(table, "Modelo", quotation.getModel());
        addLabelValue(table, "Ano", quotation.getManufactureYear().toString());
        addLabelValue(table, "Veículo 0 km", quotation.isZeroKm() ? "Sim" : "Não");
        addLabelValue(table, "Valor FIPE", formatCurrency(quotation.getFipeValue()));
        addLabelValue(table, "Valor em caso de ressarcimento integral", quotation.getIndemnityFipePercent() + "% da FIPE");
        if (Boolean.TRUE.equals(quotation.getAuctionOrChassisRemarked())) {
            addLabelValue(table, "Leilão / remarcação de chassi", "Sim — limite de ressarcimento reduzido para 70% da FIPE");
        }
        addLabelValue(table, "Emitida em", quotation.getCreatedAt().format(DATE_TIME));
        addLabelValue(table, "Válida até", quotation.getValidUntil().format(DATE_TIME));
        addLabelValue(table, "Validade", "5 dias a partir da emissão");
        if (quotation.getBillingDueDay() != null && quotation.getFirstBillingDueDate() != null) {
            addLabelValue(table, "Vencimento mensal", "Dia " + quotation.getBillingDueDay());
            addLabelValue(table, "Primeiro vencimento", quotation.getFirstBillingDueDate()
                    .format(java.time.format.DateTimeFormatter.ofPattern("dd/MM/yyyy")));
        }
        document.add(table);

        if (quotation.getObservation() != null && !quotation.getObservation().isBlank()) {
            document.add(sectionTitle("OBSERVAÇÃO DA COTAÇÃO"));
            PdfPTable observation = new PdfPTable(1);
            observation.setWidthPercentage(100);
            observation.setSpacingAfter(16);
            PdfPCell cell = new PdfPCell(new Phrase(quotation.getObservation(), font(9, Font.NORMAL, TEXT)));
            cell.setBackgroundColor(LIGHT);
            cell.setBorderColor(LINE);
            cell.setPadding(10);
            observation.addCell(cell);
            document.add(observation);
        }
    }

    private void addPlan(Document document, Quotation quotation) throws DocumentException {
        document.add(sectionTitle("PLANO CONTRATADO"));
        PdfPTable planTable = new PdfPTable(new float[]{3.7f, 1.8f});
        planTable.setWidthPercentage(100);

        Paragraph planText = new Paragraph();
        planText.setLeading(17);
        planText.add(new Chunk(quotation.getSelectedPlanName() + "\n", font(14, Font.BOLD, NAVY)));
        planText.add(new Chunk("Proteção selecionada para " + quotation.getModel(), font(8.5f, Font.NORMAL, MUTED)));
        PdfPCell planCell = new PdfPCell(planText);
        planCell.setPadding(13);
        planCell.setBackgroundColor(LIGHT);
        planCell.setBorderColor(LINE);
        planTable.addCell(planCell);

        Paragraph value = new Paragraph();
        value.setAlignment(Element.ALIGN_CENTER);
        value.setLeading(18);
        value.add(new Chunk(formatCurrency(quotation.getMonthlyValue()) + "\n", font(17, Font.BOLD, NAVY)));
        value.add(new Chunk("TOTAL MENSAL", font(7.5f, Font.BOLD, NAVY)));
        PdfPCell valueCell = new PdfPCell(value);
        valueCell.setPadding(11);
        valueCell.setVerticalAlignment(Element.ALIGN_MIDDLE);
        valueCell.setBackgroundColor(YELLOW);
        valueCell.setBorderColor(YELLOW);
        planTable.addCell(valueCell);
        document.add(planTable);

        PdfPTable values = new PdfPTable(new float[]{3.8f, 1.7f});
        values.setWidthPercentage(100);
        values.setSpacingBefore(8);
        values.setSpacingAfter(16);
        values.addCell(valueDescriptionCell("Mensalidade da tabela do plano"));
        values.addCell(currencyCell(quotation.getBaseMonthlyValue(), false));
        if (quotation.getMandatoryMonthlyFee() != null && quotation.getMandatoryMonthlyFee().signum() > 0) {
            String description = quotation.getMandatoryFeeDescription() == null
                    ? "Acréscimo obrigatório"
                    : quotation.getMandatoryFeeDescription();
            values.addCell(valueDescriptionCell(description));
            values.addCell(currencyCell(quotation.getMandatoryMonthlyFee(), false));
        }
        for (QuotationOptionalCoverage optional : quotation.getSelectedOptionals()) {
            values.addCell(valueDescriptionCell("Adicional contratado: " + optional.getCoverageName()));
            values.addCell(currencyCell(optional.getMonthlyPrice(), false));
        }
        if (quotation.getDiscountPercent() > 0) {
            values.addCell(valueDescriptionCell("Subtotal mensal antes do desconto"));
            values.addCell(currencyCell(quotation.getPreDiscountMonthlyValue(), false));
            BigDecimal discountValue = quotation.getPreDiscountMonthlyValue().subtract(quotation.getMonthlyValue());
            PdfPCell discountLabel = valueDescriptionCell("Desconto comercial (" + quotation.getDiscountPercent() + "%)");
            discountLabel.setBackgroundColor(GREEN_LIGHT);
            values.addCell(discountLabel);
            PdfPCell discountCell = currencyCell(discountValue.negate(), false);
            discountCell.setBackgroundColor(GREEN_LIGHT);
            values.addCell(discountCell);
        }
        PdfPCell totalLabel = valueDescriptionCell("VALOR TOTAL MENSAL");
        totalLabel.setPhrase(new Phrase("VALOR TOTAL MENSAL", font(8.5f, Font.BOLD, NAVY)));
        totalLabel.setBackgroundColor(LIGHT);
        values.addCell(totalLabel);
        values.addCell(currencyCell(quotation.getMonthlyValue(), true));
        if (quotation.getOneTimeFee() != null && quotation.getOneTimeFee().signum() > 0) {
            PdfPCell oneTimeLabel = valueDescriptionCell("Taxa única de instalação do rastreador");
            oneTimeLabel.setBackgroundColor(YELLOW_LIGHT);
            values.addCell(oneTimeLabel);
            PdfPCell oneTimeValue = currencyCell(quotation.getOneTimeFee(), false);
            oneTimeValue.setBackgroundColor(YELLOW_LIGHT);
            values.addCell(oneTimeValue);
        }
        document.add(values);
    }

    private void addCoverages(Document document, Quotation quotation) throws DocumentException {
        document.add(sectionTitle("COBERTURAS E BENEFÍCIOS"));
        PdfPTable table = new PdfPTable(new float[]{1.35f, 2.65f, 3.0f});
        table.setWidthPercentage(100);
        table.setHeaderRows(1);
        table.setSpacingAfter(14);
        table.addCell(headerCell("STATUS"));
        table.addCell(headerCell("COBERTURA"));
        table.addCell(headerCell("CONDIÇÕES / LIMITES"));

        int visibleIndex = 0;
        for (var item : quotation.getCoverageSnapshots()) {
            boolean included = item.isFinalSelected() && item.getCoverageStatus() != CoverageStatus.OPTIONAL;
            if (!item.isFinalSelected()) continue;
            if (isDuplicateThirdPartySnapshot(quotation, item.getCoverageCode(), item.getCoverageName())) continue;

            Color rowColor = visibleIndex++ % 2 == 0 ? Color.WHITE : new Color(250, 251, 254);
            PdfPCell statusCell = statusCell(included ? "INCLUÍDO" : "CONTRATADO");
            statusCell.setBackgroundColor(included ? GREEN_LIGHT : YELLOW_LIGHT);
            table.addCell(statusCell);
            table.addCell(bodyCell(item.getCoverageName(), rowColor));

            String detail = item.getDetail() == null || item.getDetail().isBlank()
                    ? "Conforme regulamento vigente"
                    : item.getDetail();
            table.addCell(bodyCell(detail, rowColor));
        }
        document.add(table);
    }


    private boolean isDuplicateThirdPartySnapshot(Quotation quotation, String coverageCode, String coverageName) {
        if (quotation == null || !quotation.usesFipeThirdPartyRule()) return false;
        if (coverageCode != null && "THIRD_PARTY_BASE".equalsIgnoreCase(coverageCode.trim())) return false;
        if (coverageName == null) return false;
        String normalized = java.text.Normalizer.normalize(coverageName, java.text.Normalizer.Form.NFD)
                .replaceAll("\\p{M}", "")
                .trim()
                .toLowerCase(Locale.ROOT)
                .replaceAll("\\s+", " ");
        return "danos ao veiculo de terceiros".equals(normalized);
    }

    private void addFormalConditions(Document document, Quotation quotation) throws DocumentException {
        PdfPTable noteBox = new PdfPTable(1);
        noteBox.setWidthPercentage(100);
        noteBox.setSpacingBefore(3);
        PdfPCell note = new PdfPCell();
        note.setPadding(11);
        note.setBackgroundColor(new Color(249, 250, 254));
        note.setBorderColor(LINE);

        Paragraph heading = new Paragraph("CONDIÇÕES DA PROPOSTA", font(8.5f, Font.BOLD, NAVY));
        heading.setSpacingAfter(5);
        note.addElement(heading);
        String discountCondition = "";
        RearWindowBranding branding = quotation.getRearWindowBranding();
        if (quotation.getDiscountPercent() > 0 && branding != null && branding != RearWindowBranding.NOT_APPLICABLE) {
            discountCondition = " O desconto de " + quotation.getDiscountPercent()
                    + "% está condicionado à confirmação, na vistoria, de " + branding.displayName().toLowerCase(Locale.ROOT) + ".";
        }
        Paragraph text = new Paragraph(
                "Esta cotação possui caráter comercial e foi elaborada com base nos dados fornecidos. "
                        + "A ativação da proteção está condicionada à conferência cadastral, ao aceite da proposta, "
                        + "à conclusão da vistoria veicular e às regras vigentes da " + TenantIdentity.legalName() + "."
                        + discountCondition,
                font(8, Font.NORMAL, TEXT)
        );
        text.setLeading(12);
        note.addElement(text);

        if (quotation.getDriveFolderUrl() != null && quotation.getInspectionCompletedAt() != null) {
            Paragraph driveLine = new Paragraph();
            driveLine.setSpacingBefore(7);
            driveLine.add(new Chunk("Vistoria concluída em " + quotation.getInspectionCompletedAt().format(DATE_TIME) + ". ", font(8, Font.BOLD, NAVY)));
            Anchor link = new Anchor("Acessar pasta de documentos", font(8, Font.UNDERLINE, NAVY_LIGHT));
            link.setReference(quotation.getDriveFolderUrl());
            driveLine.add(link);
            note.addElement(driveLine);
        }

        noteBox.addCell(note);
        document.add(noteBox);
    }

    private void addInspectionPhotos(Document document, Quotation quotation) throws DocumentException {
        if (quotation.getInspectionPhotos().isEmpty()) {
            return;
        }

        document.newPage();
        document.add(sectionTitle("RELATÓRIO FOTOGRÁFICO DA VISTORIA"));
        Paragraph introduction = new Paragraph(
                "Registro fotográfico enviado pelo consultor e vinculado à cotação " + quotation.getQuoteNumber()
                        + ". As imagens abaixo integram este documento para fins de validação da vistoria.",
                font(8.5f, Font.NORMAL, MUTED)
        );
        introduction.setLeading(13);
        introduction.setSpacingAfter(12);
        document.add(introduction);

        PdfPTable identity = new PdfPTable(new float[]{1.1f, 2.1f, 1.0f, 1.8f});
        identity.setWidthPercentage(100);
        identity.setSpacingAfter(14);
        addLabelValue(identity, "Cliente", quotation.getCustomerName());
        addLabelValue(identity, "Placa", plateLabel(quotation));
        addLabelValue(identity, "Veículo", quotation.getModel());
        addLabelValue(identity, "Consultor", quotation.getConsultantName());
        document.add(identity);

        List<InspectionPhoto> photos = quotation.getInspectionPhotos().stream()
                .sorted(Comparator.comparingInt(InspectionPhoto::getSortOrder))
                .toList();

        PdfPTable gallery = new PdfPTable(2);
        gallery.setWidthPercentage(100);
        gallery.setWidths(new float[]{1, 1});
        gallery.setSplitLate(false);
        gallery.setSpacingAfter(8);

        for (InspectionPhoto photo : photos) {
            gallery.addCell(photoCell(photo));
        }
        if (photos.size() % 2 != 0) {
            PdfPCell empty = new PdfPCell();
            empty.setBorder(Rectangle.NO_BORDER);
            gallery.addCell(empty);
        }
        document.add(gallery);
    }

    private PdfPCell photoCell(InspectionPhoto photo) {
        PdfPCell cell = new PdfPCell();
        cell.setPadding(7);
        cell.setBorderColor(LINE);
        cell.setBackgroundColor(Color.WHITE);
        cell.setVerticalAlignment(Element.ALIGN_TOP);
        cell.setMinimumHeight(214);

        try {
            byte[] bytes = driveStorage.download(photo.getDriveFileId());
            Image image = Image.getInstance(bytes);
            image.scaleToFit(236, 170);
            image.setAlignment(Element.ALIGN_CENTER);
            cell.addElement(image);
        } catch (Exception exception) {
            Paragraph unavailable = new Paragraph("Imagem indisponível no momento", font(8, Font.ITALIC, MUTED));
            unavailable.setAlignment(Element.ALIGN_CENTER);
            unavailable.setSpacingBefore(70);
            cell.addElement(unavailable);
        }

        Paragraph caption = new Paragraph(
                String.format(Locale.ROOT, "%02d. %s", photo.getSortOrder(), photo.getLabel()),
                font(8, Font.BOLD, NAVY)
        );
        caption.setAlignment(Element.ALIGN_CENTER);
        caption.setSpacingBefore(6);
        cell.addElement(caption);
        return cell;
    }

    private void styleBannerCell(PdfPCell cell) {
        cell.setBackgroundColor(NAVY);
        cell.setBorderColor(NAVY);
        cell.setPadding(10);
    }

    private Paragraph sectionTitle(String text) {
        Paragraph paragraph = new Paragraph();
        paragraph.setSpacingBefore(2);
        paragraph.setSpacingAfter(7);
        paragraph.add(new Chunk("  ", font(3, Font.NORMAL, YELLOW)).setBackground(YELLOW));
        paragraph.add(new Chunk("  " + text, font(10.5f, Font.BOLD, NAVY)));
        return paragraph;
    }

    private void addLabelValue(PdfPTable table, String label, String value) {
        PdfPCell labelCell = new PdfPCell(new Phrase(label.toUpperCase(Locale.ROOT), font(7, Font.BOLD, NAVY)));
        labelCell.setBackgroundColor(LIGHT);
        labelCell.setPadding(7);
        labelCell.setBorderColor(LINE);
        labelCell.setVerticalAlignment(Element.ALIGN_MIDDLE);
        table.addCell(labelCell);

        PdfPCell valueCell = new PdfPCell(new Phrase(value, font(8.3f, Font.NORMAL, TEXT)));
        valueCell.setPadding(7);
        valueCell.setBorderColor(LINE);
        valueCell.setVerticalAlignment(Element.ALIGN_MIDDLE);
        table.addCell(valueCell);
    }

    private PdfPCell headerCell(String text) {
        PdfPCell cell = new PdfPCell(new Phrase(text, font(8, Font.BOLD, Color.WHITE)));
        cell.setBackgroundColor(NAVY);
        cell.setBorderColor(NAVY);
        cell.setPadding(8);
        cell.setVerticalAlignment(Element.ALIGN_MIDDLE);
        return cell;
    }

    private PdfPCell bodyCell(String text, Color background) {
        PdfPCell cell = new PdfPCell(new Phrase(text, font(7.7f, Font.NORMAL, TEXT)));
        cell.setPadding(7);
        cell.setVerticalAlignment(Element.ALIGN_MIDDLE);
        cell.setBorderColor(LINE);
        cell.setBackgroundColor(background);
        return cell;
    }

    private PdfPCell valueDescriptionCell(String text) {
        PdfPCell cell = new PdfPCell(new Phrase(text, font(8, Font.NORMAL, TEXT)));
        cell.setPadding(7);
        cell.setBorderColor(LINE);
        cell.setVerticalAlignment(Element.ALIGN_MIDDLE);
        return cell;
    }

    private PdfPCell statusCell(String text) {
        PdfPCell cell = new PdfPCell(new Phrase(text, font(7.2f, Font.BOLD, NAVY)));
        cell.setPadding(7);
        cell.setVerticalAlignment(Element.ALIGN_MIDDLE);
        cell.setHorizontalAlignment(Element.ALIGN_CENTER);
        cell.setBorderColor(LINE);
        cell.setNoWrap(true);
        return cell;
    }

    private PdfPCell currencyCell(BigDecimal value, boolean total) {
        PdfPCell cell = new PdfPCell(new Phrase(
                formatCurrency(value),
                font(total ? 9.5f : 8, total ? Font.BOLD : Font.NORMAL, NAVY)
        ));
        cell.setHorizontalAlignment(Element.ALIGN_RIGHT);
        cell.setVerticalAlignment(Element.ALIGN_MIDDLE);
        cell.setPadding(7);
        cell.setBorderColor(total ? YELLOW : LINE);
        if (total) {
            cell.setBackgroundColor(YELLOW);
        }
        return cell;
    }

    private Font font(float size, int style, Color color) {
        return new Font(Font.HELVETICA, size, style, color);
    }

    private String statusLabel(Quotation quotation) {
        if ((quotation.getStatus() == br.com.nh.cotacao.entity.QuoteStatus.CREATED
                || quotation.getStatus() == br.com.nh.cotacao.entity.QuoteStatus.UNDER_REVIEW)
                && java.time.OffsetDateTime.now().isAfter(quotation.getValidUntil())) {
            return "EXPIRADA";
        }
        return switch (quotation.getStatus()) {
            case CREATED -> "AGUARDANDO ACEITE";
            case UNDER_REVIEW -> "EM ANÁLISE";
            case ACCEPTED -> quotation.getInspectionCompletedAt() == null ? "PROPOSTA ACEITA" : "VISTORIA CONCLUÍDA";
            case DECLINED -> "PROPOSTA NÃO ACEITA";
            case CANCELLED -> "CANCELADA";
        };
    }

    private String formatCurrency(BigDecimal value) {
        return java.text.NumberFormat.getCurrencyInstance(Locale.forLanguageTag("pt-BR"))
                .format(value)
                .replace('\u00A0', ' ');
    }

    private static class ProfessionalPageEvent extends PdfPageEventHelper {
        private final String quoteNumber;

        private ProfessionalPageEvent(String quoteNumber) {
            this.quoteNumber = quoteNumber;
        }

        @Override
        public void onEndPage(PdfWriter writer, Document document) {
            PdfContentByte canvas = writer.getDirectContent();
            canvas.setColorStroke(YELLOW);
            canvas.setLineWidth(2f);
            canvas.moveTo(document.left(), 32);
            canvas.lineTo(document.right(), 32);
            canvas.stroke();

            Phrase left = new Phrase(
                    "" + TenantIdentity.legalName().toUpperCase(java.util.Locale.ROOT) + "  •  " + quoteNumber,
                    new Font(Font.HELVETICA, 6.8f, Font.BOLD, NAVY)
            );
            Phrase right = new Phrase(
                    "Página " + writer.getPageNumber(),
                    new Font(Font.HELVETICA, 6.8f, Font.NORMAL, MUTED)
            );
            ColumnText.showTextAligned(canvas, Element.ALIGN_LEFT, left, document.left(), 20, 0);
            ColumnText.showTextAligned(canvas, Element.ALIGN_RIGHT, right, document.right(), 20, 0);
        }
    }

    private String plateLabel(Quotation quotation) {
        return quotation.getPlate() == null || quotation.getPlate().isBlank()
                ? "Veículo 0 km — sem placa"
                : quotation.getPlate();
    }

}
