package br.com.nh.cotacao.service;

import br.com.nh.cotacao.platform.TenantIdentity;

import com.lowagie.text.Document;
import com.lowagie.text.DocumentException;
import com.lowagie.text.Element;
import com.lowagie.text.Font;
import com.lowagie.text.Image;
import com.lowagie.text.PageSize;
import com.lowagie.text.Paragraph;
import com.lowagie.text.Phrase;
import com.lowagie.text.Rectangle;
import com.lowagie.text.pdf.PdfPCell;
import com.lowagie.text.pdf.PdfPTable;
import com.lowagie.text.pdf.PdfWriter;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Service;

import java.awt.Color;
import java.io.ByteArrayOutputStream;
import java.time.OffsetDateTime;
import java.time.format.DateTimeFormatter;

@Service
public class ResponsibilityTermPdfService {
    private static final Color NAVY = new Color(8, 15, 99);
    private static final Color YELLOW = new Color(255, 236, 0);
    private static final Color TEXT = new Color(34, 37, 49);
    private static final Color MUTED = new Color(95, 101, 119);
    private static final Color LINE = new Color(218, 222, 232);
    private static final Color LIGHT = new Color(247, 248, 252);
    private static final DateTimeFormatter DATE = DateTimeFormatter.ofPattern("dd/MM/yyyy");
    private static final DateTimeFormatter DATE_TIME = DateTimeFormatter.ofPattern("dd/MM/yyyy HH:mm:ss XXX");
    private static final String ASSOCIATION = TenantIdentity.legalName();
    private static final String ASSOCIATION_CNPJ = TenantIdentity.document();

    public byte[] generate(TermDocumentData data) {
        try {
            ByteArrayOutputStream output = new ByteArrayOutputStream();
            Document document = new Document(PageSize.A4, 36, 36, 42, 42);
            PdfWriter.getInstance(document, output);
            document.open();

            addHeader(document, data);
            addIdentity(document, data);
            addDamageDetails(document, data.damageDetails());
            addClauses(document);
            addSignatures(document, data);
            addAudit(document, data);

            document.close();
            return output.toByteArray();
        } catch (Exception exception) {
            throw new IllegalStateException("Não foi possível gerar o Termo de Responsabilidade.", exception);
        }
    }

    private void addHeader(Document document, TermDocumentData data) throws Exception {
        PdfPTable banner = new PdfPTable(new float[]{1.05f, 4.55f, 1.55f});
        banner.setWidthPercentage(100);
        banner.setSpacingAfter(16);
        banner.setKeepTogether(true);

        PdfPCell logoCell = new PdfPCell();
        styleBannerCell(logoCell);
        logoCell.setHorizontalAlignment(Element.ALIGN_CENTER);
        logoCell.setVerticalAlignment(Element.ALIGN_MIDDLE);
        try {
            Image logo = Image.getInstance(new ClassPathResource("identidade-pendente.png").getURL());
            logo.scaleToFit(70, 70);
            logoCell.addElement(logo);
        } catch (Exception ignored) {
            Paragraph fallback = new Paragraph("EMPRESA", font(11, Font.BOLD, Color.WHITE));
            fallback.setAlignment(Element.ALIGN_CENTER);
            logoCell.addElement(fallback);
        }
        banner.addCell(logoCell);

        Paragraph title = new Paragraph();
        title.setAlignment(Element.ALIGN_CENTER);
        title.setLeading(22);
        title.add(new com.lowagie.text.Chunk(TenantIdentity.displayName().toUpperCase(java.util.Locale.ROOT) + "\n", font(27, Font.BOLD, Color.WHITE)));
        title.add(new com.lowagie.text.Chunk("TERMO DIGITAL\n", font(11.5f, Font.BOLD, YELLOW)));
        title.add(new com.lowagie.text.Chunk("TERMO DE RESPONSABILIDADE", font(10.5f, Font.NORMAL, Color.WHITE)));
        PdfPCell titleCell = new PdfPCell(title);
        styleBannerCell(titleCell);
        titleCell.setPaddingTop(10);
        titleCell.setPaddingBottom(10);
        titleCell.setVerticalAlignment(Element.ALIGN_MIDDLE);
        banner.addCell(titleCell);

        Paragraph status = new Paragraph();
        status.setAlignment(Element.ALIGN_RIGHT);
        status.setLeading(13);
        status.add(new com.lowagie.text.Chunk("STATUS\n", font(7.2f, Font.BOLD, YELLOW)));
        status.add(new com.lowagie.text.Chunk(data.acceptedAt() == null ? "ACEITE PENDENTE" : "ACEITE CONCLUÍDO", font(8.1f, Font.BOLD, Color.WHITE)));
        PdfPCell statusCell = new PdfPCell(status);
        styleBannerCell(statusCell);
        statusCell.setPaddingRight(10);
        statusCell.setVerticalAlignment(Element.ALIGN_MIDDLE);
        banner.addCell(statusCell);
        document.add(banner);

        PdfPTable intro = new PdfPTable(new float[]{4.5f, 1.5f});
        intro.setWidthPercentage(100);
        PdfPCell introText = new PdfPCell(new Phrase(
                "Termo digital de responsabilidade por avaria pré-existente",
                font(14.5f, Font.BOLD, NAVY)
        ));
        introText.setBorder(Rectangle.NO_BORDER);
        introText.setPaddingBottom(5);
        intro.addCell(introText);
        PdfPCell statusTag = new PdfPCell(new Phrase(
                data.acceptedAt() == null ? "ACEITE PENDENTE" : "ACEITE CONCLUÍDO",
                font(8, Font.BOLD, NAVY)
        ));
        statusTag.setHorizontalAlignment(Element.ALIGN_CENTER);
        statusTag.setVerticalAlignment(Element.ALIGN_MIDDLE);
        statusTag.setBackgroundColor(YELLOW);
        statusTag.setBorderColor(YELLOW);
        statusTag.setPadding(7);
        intro.addCell(statusTag);
        document.add(intro);

        Paragraph subtitle = new Paragraph(
                data.acceptedAt() == null
                        ? "Documento independente para leitura e aceite digital do associado."
                        : "Documento consolidado com evidências técnicas do aceite digital realizado pelo associado.",
                font(8.5f, Font.NORMAL, MUTED)
        );
        subtitle.setSpacingAfter(14);
        document.add(subtitle);
    }

    private void styleBannerCell(PdfPCell cell) {
        cell.setBorder(Rectangle.NO_BORDER);
        cell.setBackgroundColor(NAVY);
        cell.setPadding(8);
    }

    private void addIdentity(Document document, TermDocumentData data) throws DocumentException {
        section(document, "DADOS DO ASSOCIADO E DO VEÍCULO");
        PdfPTable table = new PdfPTable(new float[]{1.1f, 2.2f, 1.0f, 1.8f});
        table.setWidthPercentage(100);
        table.setSpacingAfter(10);
        addPair(table, "Nome", data.associateName());
        addPair(table, "CPF", formatCpf(data.cpf()));
        addPair(table, "Placa", blank(data.plate()) ? "Veículo 0 km - sem placa" : data.plate());
        addPair(table, "Modelo", value(data.vehicleModel()));
        addPair(table, "Ano", data.modelYear() == null ? "-" : data.modelYear().toString());
        addPair(table, "Data cadastro", data.inspectionCreatedAt() == null ? "-" : data.inspectionCreatedAt().format(DATE));
        if (!blank(data.contractedPlan())) {
            PdfPCell label = labelCell("Plano");
            PdfPCell val = valueCell(data.contractedPlan());
            val.setColspan(3);
            table.addCell(label);
            table.addCell(val);
        }
        document.add(table);
    }

    private void addDamageDetails(Document document, String details) throws DocumentException {
        section(document, "PEÇA(S) / AVARIA(S) PRÉ-EXISTENTE(S)");
        PdfPTable table = new PdfPTable(1);
        table.setWidthPercentage(100);
        table.setSpacingAfter(10);
        boolean any = false;
        for (String raw : value(details).split("\\n")) {
            String line = raw == null ? "" : raw.trim();
            if (line.isBlank() || "-".equals(line)) continue;
            PdfPCell cell = new PdfPCell(new Phrase("• " + line, font(8.5f, Font.NORMAL, TEXT)));
            cell.setPadding(7);
            cell.setBorderColor(LINE);
            table.addCell(cell);
            any = true;
        }
        if (!any) {
            PdfPCell cell = new PdfPCell(new Phrase("Avaria pré-existente registrada na vistoria.", font(8.5f, Font.NORMAL, TEXT)));
            cell.setPadding(7);
            cell.setBorderColor(LINE);
            table.addCell(cell);
        }
        document.add(table);
    }

    private void addClauses(Document document) throws DocumentException {
        Paragraph intro = paragraph(
                "Declaro estar ciente de que, em caso de evento, o valor das avarias pré-existentes descritas acima poderá ser deduzido do valor de reparo e/ou indenização. Pelo presente instrumento, o(a) associado(a) identificado(a) declara estar ciente e de acordo que assume total responsabilidade pela peça danificada identificada em seu veículo, conforme verificação realizada pela empresa " + TenantIdentity.legalName() + "."
        );
        intro.setSpacingAfter(7);
        document.add(intro);

        // Termos jurídicos comerciais devem pertencer à empresa implantada.
        // Nunca reproduzir automaticamente exclusões, percentuais ou prazos da antiga operação.
        String tenantClauses = System.getenv("TENANT_RESPONSIBILITY_TERM_CLAUSES");
        if (tenantClauses == null || tenantClauses.isBlank() || tenantClauses.trim().length() < 40) {
            throw new IllegalStateException("As cláusulas do Termo de Responsabilidade desta empresa não foram configuradas ou revisadas.");
        }
        String[] clauses = tenantClauses.split("\\|\\|", -1);
        if (clauses.length > 20) throw new IllegalArgumentException("O Termo de Responsabilidade excedeu o limite de 20 cláusulas.");
        for (String clause : clauses) {
            Paragraph p = paragraph(clause);
            p.setSpacingAfter(4);
            document.add(p);
        }

        Paragraph purpose = paragraph(
                "O presente termo tem a finalidade de registrar que o(a) associado(a) foi devidamente informado(a) sobre a condição da(s) peça(s) e sobre as responsabilidades assumidas. Por estarem de pleno acordo, firmam o presente termo por meio de aceite digital exclusivo deste documento."
        );
        purpose.setSpacingBefore(3);
        purpose.setSpacingAfter(8);
        document.add(purpose);
    }

    private void addSignatures(Document document, TermDocumentData data) throws DocumentException {
        PdfPTable table = new PdfPTable(new float[]{1f, 1f});
        table.setWidthPercentage(100);
        table.setSpacingBefore(7);
        table.setSpacingAfter(7);
        PdfPCell associate = signatureCell("________________________________________\n" + data.associateName() + "\nASSOCIADO(A)");
        PdfPCell association = signatureCell("________________________________________\n" + ASSOCIATION);
        table.addCell(associate);
        table.addCell(association);
        document.add(table);
    }

    private void addAudit(Document document, TermDocumentData data) throws DocumentException {
        section(document, "REGISTRO DO ACEITE DIGITAL");
        PdfPTable box = new PdfPTable(new float[]{1.45f, 3.55f});
        box.setWidthPercentage(100);
        box.setSpacingBefore(2);
        if (data.acceptedAt() == null) {
            PdfPCell pending = new PdfPCell(new Phrase(
                    "Status: TERMO COM ACEITE PENDENTE. O documento já está disponível para download e permanece disponível após a confirmação do associado.",
                    font(7.6f, Font.NORMAL, MUTED)
            ));
            pending.setColspan(2);
            pending.setPadding(8);
            pending.setBackgroundColor(LIGHT);
            pending.setBorderColor(LINE);
            box.addCell(pending);
        } else {
            addAuditPair(box, "Status", "ACEITE CONCLUÍDO");
            boolean inheritedFromDossier = "DOSSIER_ACCEPTANCE".equalsIgnoreCase(value(data.acceptanceSource()));
            addAuditPair(box, "Origem do aceite", inheritedFromDossier
                    ? "Aceite digital do dossiê original que já continha este Termo de Responsabilidade"
                    : "Aceite digital exclusivo do Termo de Responsabilidade");
            addAuditPair(box, "Data e hora", data.acceptedAt().format(DATE_TIME));
            addAuditPair(box, "Número de envio", value(data.whatsappSentTo()));
            addAuditPair(box, "Enviado em", data.sentAt() == null ? "-" : data.sentAt().format(DATE_TIME));
            addAuditPair(box, "Endereço IP", value(data.acceptanceIp()));
            String gps = data.acceptanceLatitude() == null || data.acceptanceLongitude() == null
                    ? "Não registrado"
                    : String.format(java.util.Locale.ROOT, "%.7f, %.7f%s", data.acceptanceLatitude(), data.acceptanceLongitude(),
                        data.acceptanceAccuracyMeters() == null ? "" : " | precisão aprox. " + Math.round(data.acceptanceAccuracyMeters()) + " m");
            addAuditPair(box, "GPS do aceite", gps);
            addAuditPair(box, "GPS capturado em", data.acceptanceLocationCapturedAt() == null ? "-" : data.acceptanceLocationCapturedAt().format(DATE_TIME));
            addAuditPair(box, "Identificador do dispositivo", value(data.acceptanceDeviceId()));
            addAuditPair(box, "Informações do dispositivo", value(data.acceptanceDeviceSummary()));
            addAuditPair(box, "Verificação WebAuthn", data.userVerified() ? "Confirmada" : "Registrada");
            if (inheritedFromDossier) {
                addAuditPair(box, "Hash do dossiê originalmente aceito", value(data.originalDossierSha256()));
                addAuditPair(box, "Hash da evidência do aceite do dossiê", value(data.evidenceHash()));
                addAuditPair(box, "Hash da prova WebAuthn", value(data.proofHash()));
            } else {
                addAuditPair(box, "Hash do PDF apresentado", value(data.termPdfSha256()));
                addAuditPair(box, "Hash da evidência", value(data.evidenceHash()));
                addAuditPair(box, "Hash da prova WebAuthn", value(data.proofHash()));
            }
        }
        document.add(box);

        Paragraph footer = new Paragraph(
                ASSOCIATION + " | CNPJ " + ASSOCIATION_CNPJ + "",
                font(7.2f, Font.NORMAL, MUTED)
        );
        footer.setAlignment(Element.ALIGN_CENTER);
        footer.setSpacingBefore(10);
        document.add(footer);
    }

    private void addAuditPair(PdfPTable table, String label, String value) {
        table.addCell(labelCell(label));
        table.addCell(valueCell(value));
    }

    private void section(Document document, String text) throws DocumentException {
        Paragraph p = new Paragraph(text, font(9, Font.BOLD, NAVY));
        p.setSpacingBefore(2);
        p.setSpacingAfter(5);
        document.add(p);
    }

    private Paragraph paragraph(String text) {
        Paragraph p = new Paragraph(text, font(8.1f, Font.NORMAL, TEXT));
        p.setLeading(11.2f);
        p.setAlignment(Element.ALIGN_JUSTIFIED);
        return p;
    }

    private void addPair(PdfPTable table, String label, String value) {
        table.addCell(labelCell(label));
        table.addCell(valueCell(value));
    }

    private PdfPCell labelCell(String label) {
        PdfPCell cell = new PdfPCell(new Phrase(label, font(7.3f, Font.BOLD, NAVY)));
        cell.setPadding(6);
        cell.setBackgroundColor(LIGHT);
        cell.setBorderColor(LINE);
        return cell;
    }

    private PdfPCell valueCell(String value) {
        PdfPCell cell = new PdfPCell(new Phrase(value(value), font(7.7f, Font.NORMAL, TEXT)));
        cell.setPadding(6);
        cell.setBorderColor(LINE);
        return cell;
    }

    private PdfPCell signatureCell(String text) {
        PdfPCell cell = new PdfPCell(new Phrase(text, font(7.5f, Font.BOLD, NAVY)));
        cell.setBorder(Rectangle.NO_BORDER);
        cell.setHorizontalAlignment(Element.ALIGN_CENTER);
        cell.setPaddingTop(8);
        return cell;
    }

    private Font font(float size, int style, Color color) {
        return new Font(Font.HELVETICA, size, style, color);
    }

    private String value(String value) {
        return blank(value) ? "-" : value.trim();
    }

    private boolean blank(String value) {
        return value == null || value.isBlank();
    }

    private String formatCpf(String cpf) {
        if (cpf == null) return "-";
        String digits = cpf.replaceAll("\\D", "");
        if (digits.length() != 11) return cpf;
        return digits.substring(0, 3) + "." + digits.substring(3, 6) + "." + digits.substring(6, 9) + "-" + digits.substring(9);
    }

    public record TermDocumentData(
            String associateName,
            String cpf,
            String plate,
            String vehicleModel,
            Integer modelYear,
            String contractedPlan,
            OffsetDateTime inspectionCreatedAt,
            String damageDetails,
            String whatsappSentTo,
            OffsetDateTime sentAt,
            OffsetDateTime acceptedAt,
            String termPdfSha256,
            String evidenceHash,
            String proofHash,
            boolean userVerified,
            String acceptanceIp,
            Double acceptanceLatitude,
            Double acceptanceLongitude,
            Double acceptanceAccuracyMeters,
            OffsetDateTime acceptanceLocationCapturedAt,
            String acceptanceDeviceId,
            String acceptanceDeviceSummary,
            String acceptanceSource,
            String originalDossierSha256
    ) {}
}
