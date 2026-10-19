package br.com.nh.cotacao.service;

import br.com.nh.cotacao.platform.TenantIdentity;
import br.com.nh.cotacao.dto.ChecklistEventDtos.*;
import com.lowagie.text.*;
import com.lowagie.text.pdf.BaseFont;
import com.lowagie.text.pdf.PdfPCell;
import com.lowagie.text.pdf.PdfPTable;
import com.lowagie.text.pdf.PdfWriter;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.awt.Color;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.math.BigDecimal;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.format.DateTimeFormatter;
import java.text.NumberFormat;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

@Service
public class ChecklistEventPdfService {
    private static final Color NAVY = new Color(7, 12, 64);
    private static final Color YELLOW = new Color(240, 240, 0);
    private static final Color LIGHT = new Color(246, 247, 251);
    private static final DateTimeFormatter DATE = DateTimeFormatter.ofPattern("dd/MM/yyyy HH:mm");
    private static final DateTimeFormatter SIMPLE_DATE = DateTimeFormatter.ofPattern("dd/MM/yyyy");
    private final JdbcTemplate jdbc;
    private final ReportEnvelopeService reportEnvelopeService;

    public ChecklistEventPdfService(JdbcTemplate jdbc, ReportEnvelopeService reportEnvelopeService) { this.jdbc = jdbc; this.reportEnvelopeService = reportEnvelopeService; }

    public enum DossierMode { ASSOCIATE, INTERNAL }

    /** Dossiê entregue ao associado. Nunca contém dados de compras/fornecedores/custos. */
    public byte[] generate(EventDetail event, List<AttachmentContent> eventImages) {
        return generate(event, eventImages, DossierMode.ASSOCIATE);
    }

    /** Dossiê interno: contém tudo do dossiê do associado + compras efetivamente salvas pela oficina. */
    public byte[] generateInternal(EventDetail event, List<AttachmentContent> eventImages) {
        return generate(event, eventImages, DossierMode.INTERNAL);
    }

    private byte[] generate(EventDetail event, List<AttachmentContent> eventImages, DossierMode mode) {
        try (ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            Document document = new Document(PageSize.A4, 34, 34, 38, 38);
            PdfWriter.getInstance(document, output);
            document.open();

            Font regular = font(BaseFont.HELVETICA, 9.2f, Font.NORMAL, NAVY);
            Font bold = font(BaseFont.HELVETICA_BOLD, 9.2f, Font.BOLD, NAVY);
            Font whiteBold = font(BaseFont.HELVETICA_BOLD, 9.5f, Font.BOLD, Color.WHITE);
            Font title = font(BaseFont.HELVETICA_BOLD, 18f, Font.BOLD, Color.WHITE);
            Font yellow = font(BaseFont.HELVETICA_BOLD, 9f, Font.BOLD, YELLOW);

            addHeader(document, event, title, yellow, mode);
            if (mode == DossierMode.INTERNAL) addInternalNotice(document, bold, regular);
            addSectionTitle(document, "Dados da ocorrência", whiteBold);
            PdfPTable summary = table(2, new float[]{1,1});
            addPair(summary,"Protocolo",event.protocol(),bold,regular);
            addPair(summary,"Status",label(ChecklistEventService.STATUS_LABELS,event.status()),bold,regular);
            addPair(summary,"Tipo",label(ChecklistEventService.EVENT_TYPE_LABELS,event.eventType()),bold,regular);
            addPair(summary,"Data da ocorrência",format(event.occurredAt()),bold,regular);
            addPair(summary,"Associado",event.associateName(),bold,regular);
            addPair(summary,"Número",event.associateNumber(),bold,regular);
            addPair(summary,"Plano",event.planName(),bold,regular);
            if(event.planCode()!=null) addPair(summary,"Código do plano",event.planCode(),bold,regular);
            if(event.planMonthlyAmount()!=null) addPair(summary,"Mensalidade",money(event.planMonthlyAmount()),bold,regular);
            addPair(summary,"Participação",money(event.participationAmount()),bold,regular);
            addPair(summary,"Veículo",event.vehiclePlate()+" · "+safe(event.vehicleBrand())+" "+event.vehicleModel(),bold,regular);
            addPair(summary,"Versão",safe(event.vehicleVersion()),bold,regular);
            addPair(summary,"Categoria",label(ChecklistEventService.VEHICLE_CATEGORY_LABELS,event.vehicleCategory()),bold,regular);
            addPair(summary,"Pick-up","PICKUP".equals(event.vehicleSubtype())?"Sim":(event.vehicleSubtype()==null||event.vehicleSubtype().isBlank()?"Não":event.vehicleSubtype()),bold,regular);
            addPair(summary,"Ano / Cor",safe(event.vehicleYear())+" / "+safe(event.vehicleColor()),bold,regular);
            addPair(summary,"Combustível / Câmbio",safe(event.vehicleFuel())+" / "+safe(event.vehicleTransmission()),bold,regular);
            addPair(summary,"Quilometragem",event.vehicleOdometer()==null?"—":event.vehicleOdometer()+" km",bold,regular);
            addPair(summary,"Valor FIPE",event.fipeValue()==null?"—":money(event.fipeValue()),bold,regular);
            addPair(summary,"Chassi",safe(event.chassis()),bold,regular);
            addPair(summary,"Terceiro envolvido",event.hasThirdParty()?"Sim":"Não",bold,regular);
            addPair(summary,"Registrado por",event.createdByName(),bold,regular);
            document.add(summary);
            if(event.location()!=null||event.description()!=null||event.coverageNotes()!=null){
                Paragraph p=new Paragraph();p.setSpacingBefore(8);
                if(event.location()!=null){p.add(new Chunk("Local: ",bold));p.add(new Chunk(event.location()+"\n",regular));}
                if(event.description()!=null){p.add(new Chunk("Descrição: ",bold));p.add(new Chunk(event.description()+"\n",regular));}
                if(event.coverageNotes()!=null){p.add(new Chunk("Coberturas/observações do plano: ",bold));p.add(new Chunk(event.coverageNotes(),regular));}
                document.add(p);
            }

            addWorkshopSection(document,event.id(),whiteBold,regular);
            if (mode == DossierMode.INTERNAL) addPurchasesSection(document,event.id(),whiteBold,regular);
            TowPdf tow=findTowForEvent(event.id(),event.vehiclePlate());
            if(tow!=null) addTowSection(document,tow,whiteBold,regular,bold);

            addSectionTitle(document,"Documentos e arquivos da ocorrência",whiteBold);
            if(event.attachments().isEmpty()) document.add(new Paragraph("Nenhum arquivo anexado.",regular));
            else {
                PdfPTable files=table(4,new float[]{1.4f,2.5f,1.2f,1.4f});
                addHeaderCell(files,"Tipo",whiteBold);addHeaderCell(files,"Arquivo",whiteBold);addHeaderCell(files,"Tamanho",whiteBold);addHeaderCell(files,"Enviado por",whiteBold);
                for(AttachmentResponse a:event.attachments()){
                    if("TOW_PHOTO".equals(a.attachmentKind())) continue;
                    addCell(files,label(ChecklistEventService.ATTACHMENT_KIND_LABELS,a.attachmentKind()),regular,false);
                    addCell(files,a.originalName(),regular,false);addCell(files,bytes(a.fileSize()),regular,false);addCell(files,a.uploadedBy(),regular,false);
                }
                document.add(files);
            }

            if(!eventImages.isEmpty()){
                addSectionTitle(document,"Registro fotográfico da ocorrência",whiteBold);
                for(AttachmentContent image:eventImages) addImage(document,image.fileName(),image.bytes(),regular);
            }

            if(tow!=null&&!tow.photos().isEmpty()){
                addSectionTitle(document,"Registro fotográfico do reboque",whiteBold);
                for(TowPdfPhoto photo:tow.photos()) addImage(document,photo.kindLabel()+" · "+photo.fileName(),photo.bytes(),regular);
            }

            addAcceptanceSection(document,event.id(),whiteBold,regular,bold);
            Paragraph footer=new Paragraph(TenantIdentity.legalName() + " · Documento gerado eletronicamente\n" +
                    (mode == DossierMode.INTERNAL ? "Dossiê interno operacional" : "Dossiê do associado") +
                    " · Gerado em "+format(OffsetDateTime.now()),regular);
            footer.setSpacingBefore(18);footer.setAlignment(Element.ALIGN_CENTER);document.add(footer);
            document.close();return reportEnvelopeService.decorate(output.toByteArray());
        } catch(Exception e){ throw new IllegalStateException("Não foi possível gerar o dossiê PDF do evento.",e); }
    }

    private void addWorkshopSection(Document document, UUID eventId, Font whiteBold, Font regular) throws DocumentException {
        List<WorkshopPdfRow> rows=jdbc.query("""
                select i.section,i.label,i.repair_action,i.workshop_notes,t.vehicle_model third_model
                  from nh_event_checklist_items i
                  left join nh_event_third_parties t on t.id=i.third_party_id
                 where i.event_id=? and i.damage_state='YES'
                 order by case when i.third_party_id is null then 0 else 1 end,i.sort_order,i.label
                """,(rs,n)->new WorkshopPdfRow(rs.getString("section"),rs.getString("label"),rs.getString("repair_action"),rs.getString("workshop_notes"),rs.getString("third_model")),eventId);
        addSectionTitle(document,"Checklist técnico da oficina — itens com avaria",whiteBold);
        if(rows.isEmpty()){document.add(new Paragraph("Nenhum item foi marcado com avaria pela oficina.",regular));return;}
        PdfPTable t=table(5,new float[]{1.25f,1.25f,2.2f,1.25f,2.3f});
        addHeaderCell(t,"Veículo",whiteBold);addHeaderCell(t,"Seção",whiteBold);addHeaderCell(t,"Item",whiteBold);addHeaderCell(t,"Ação",whiteBold);addHeaderCell(t,"Observação",whiteBold);
        for(WorkshopPdfRow r:rows){
            addCell(t,r.thirdModel()==null?"Associado":"Terceiro · "+r.thirdModel(),regular,false);addCell(t,r.section(),regular,false);addCell(t,r.label(),regular,false);
            addCell(t,"REPLACE".equals(r.action())?"Trocar":"Recuperar/Reparar",regular,false);addCell(t,safe(r.notes()),regular,false);
        }
        document.add(t);
    }

    private void addPurchasesSection(Document document,UUID eventId,Font whiteBold,Font regular)throws DocumentException{
        List<PurchasePdfRow> rows=jdbc.query("""
                select p.item_label,p.supplier,p.amount,p.delivery_deadline,p.status,p.notes,p.details_saved_at,p.details_saved_by
                  from nh_event_purchase_items p
                  join nh_event_checklist_items i on i.id=p.checklist_item_id and i.event_id=p.event_id
                 where p.event_id=? and p.details_saved_at is not null
                   and i.damage_state='YES' and i.repair_action='REPLACE'
                 order by p.details_saved_at,p.item_label
                """,
                (rs,n)->new PurchasePdfRow(rs.getString("item_label"),rs.getString("supplier"),rs.getBigDecimal("amount"),
                        rs.getObject("delivery_deadline",LocalDate.class),rs.getString("status"),rs.getString("notes"),
                        offset(rs,"details_saved_at"),rs.getString("details_saved_by")),eventId);
        if(rows.isEmpty())return;
        addSectionTitle(document,"Compras do evento — uso exclusivamente interno",whiteBold);
        Paragraph warning=new Paragraph("Informações de fornecedor, custo e logística. Esta seção não integra o dossiê entregue ao associado.",regular);
        warning.setSpacingAfter(7);document.add(warning);
        PdfPTable t=table(7,new float[]{1.8f,1.5f,1.0f,1.1f,1.0f,1.8f,1.4f});
        for(String h:List.of("Item solicitado","Fornecedor","Valor","Prazo","Status","Observação","Registrado"))addHeaderCell(t,h,whiteBold);
        for(PurchasePdfRow r:rows){
            addCell(t,r.label(),regular,false);
            addCell(t,safe(r.supplier()),regular,false);
            addCell(t,money(r.amount()),regular,false);
            addCell(t,r.deadline()==null?"—":SIMPLE_DATE.format(r.deadline()),regular,false);
            addCell(t,purchaseStatus(r.status()),regular,false);
            addCell(t,safe(r.notes()),regular,false);
            addCell(t,safe(r.savedBy())+"\n"+format(r.savedAt()),regular,false);
        }
        document.add(t);
    }

    private void addInternalNotice(Document document, Font bold, Font regular) throws DocumentException {
        PdfPTable box = new PdfPTable(1); box.setWidthPercentage(100); box.setSpacingAfter(10);
        PdfPCell cell = new PdfPCell(); cell.setBackgroundColor(new Color(255, 249, 214)); cell.setBorderColor(YELLOW); cell.setBorderWidth(1.2f); cell.setPadding(8);
        Paragraph p = new Paragraph();
        p.add(new Chunk("DOCUMENTO INTERNO — NÃO ENTREGAR AO ASSOCIADO\n", bold));
        p.add(new Chunk("Contém o mesmo conteúdo técnico do dossiê do associado acrescido de fornecedor, valores, prazo, status e observações de compras do evento.", regular));
        cell.addElement(p); box.addCell(cell); document.add(box);
    }

    private void addAcceptanceSection(Document document, UUID eventId, Font whiteBold, Font regular, Font bold) throws DocumentException {
        AcceptancePdf acceptance = jdbc.query("""
                select accepted_at,acceptance_user_verified,acceptance_proof_hash,acceptance_dossier_sha256
                  from nh_event_records where id=?
                """, rs -> rs.next() && rs.getObject("accepted_at") != null
                ? new AcceptancePdf(offset(rs,"accepted_at"),rs.getBoolean("acceptance_user_verified"),
                    rs.getString("acceptance_proof_hash"),rs.getString("acceptance_dossier_sha256")) : null, eventId);
        if (acceptance == null) return;
        addSectionTitle(document,"Aceite digital do associado",whiteBold);
        PdfPTable info=table(2,new float[]{1,1});
        addPair(info,"Confirmado em",format(acceptance.acceptedAt()),bold,regular);
        addPair(info,"Verificação do aparelho",acceptance.userVerified()?"Usuário verificado por biometria/PIN (WebAuthn)":"Registrada",bold,regular);
        addPair(info,"Hash do dossiê aceito (SHA-256)",safe(acceptance.dossierHash()),bold,regular);
        addPair(info,"Hash da prova WebAuthn",safe(acceptance.proofHash()),bold,regular);
        document.add(info);
    }

    private TowPdf findTowForEvent(UUID eventId,String plate){
        UUID linked=jdbc.query("select linked_tow_record_id from nh_event_records where id=?",rs->{
            if(!rs.next())return null;
            Object v=rs.getObject(1);
            return v==null?null:(v instanceof UUID u?u:UUID.fromString(String.valueOf(v)));
        },eventId);
        String normalized=plate==null?"":plate.toUpperCase(Locale.ROOT).replaceAll("[^A-Z0-9]","");
        String sql;
        Object[] args;
        if(linked!=null){
            sql="""
                    select id,code,vehicle_plate,vehicle_model,provider_name,driver_name,general_notes,completed_at
                      from nh_tow_records
                     where status='COMPLETED' and id=?
                     limit 1
                    """;
            args=new Object[]{linked};
        }else{
            if(normalized.isBlank())return null;
            sql="""
                    select id,code,vehicle_plate,vehicle_model,provider_name,driver_name,general_notes,completed_at
                      from nh_tow_records
                     where status='COMPLETED' and vehicle_plate=?
                     order by completed_at desc nulls last,created_at desc
                     limit 1
                    """;
            args=new Object[]{normalized};
        }
        return jdbc.query(sql,rs->{
            if(!rs.next())return null;
            UUID id=uuid(rs,"id");
            List<TowPdfItem> items=jdbc.query("select label,answer,notes from nh_tow_record_items where tow_record_id=? and answer<>'UNANSWERED' order by sort_order,label",
                    (ir,n)->new TowPdfItem(ir.getString("label"),ir.getString("answer"),ir.getString("notes")),id);
            List<TowPdfPhoto> photos=jdbc.query("select photo_kind,original_name,file_data from nh_tow_record_photos where tow_record_id=? order by created_at",
                    (pr,n)->new TowPdfPhoto(photoKind(pr.getString("photo_kind")),pr.getString("original_name"),pr.getBytes("file_data")),id);
            return new TowPdf(id,rs.getString("code"),rs.getString("vehicle_plate"),rs.getString("vehicle_model"),rs.getString("provider_name"),rs.getString("driver_name"),rs.getString("general_notes"),offset(rs,"completed_at"),items,photos);
        },args);
    }

    private void addTowSection(Document document,TowPdf tow,Font whiteBold,Font regular,Font bold)throws DocumentException{
        addSectionTitle(document,"Checklist do guincho / reboque",whiteBold);
        PdfPTable info=table(2,new float[]{1,1});addPair(info,"Código",tow.code(),bold,regular);addPair(info,"Placa",tow.plate(),bold,regular);addPair(info,"Modelo",safe(tow.model()),bold,regular);addPair(info,"Prestador / Motorista",safe(tow.provider())+" / "+safe(tow.driver()),bold,regular);addPair(info,"Concluído em",format(tow.completedAt()),bold,regular);addPair(info,"Observação",safe(tow.notes()),bold,regular);document.add(info);
        PdfPTable t=table(3,new float[]{2.2f,1.1f,2.7f});addHeaderCell(t,"Item/Acessório",whiteBold);addHeaderCell(t,"Havia?",whiteBold);addHeaderCell(t,"Observação",whiteBold);
        for(TowPdfItem i:tow.items()){addCell(t,i.label(),regular,false);addCell(t,towAnswer(i.answer()),regular,false);addCell(t,safe(i.notes()),regular,false);}document.add(t);
    }

    private void addImage(Document document,String name,byte[] bytes,Font regular){try{Image image=Image.getInstance(bytes);image.scaleToFit(500,610);image.setAlignment(Image.ALIGN_CENTER);document.add(image);Paragraph c=new Paragraph(name,regular);c.setAlignment(Element.ALIGN_CENTER);c.setSpacingAfter(12);document.add(c);}catch(Exception ignored){}}
    private Font font(String name,float size,int style,Color color)throws Exception{return new Font(BaseFont.createFont(name,BaseFont.CP1252,false),size,style,color);}
    private void addHeader(Document document,EventDetail event,Font title,Font yellow,DossierMode mode)throws Exception{PdfPTable header=new PdfPTable(new float[]{1.2f,3.8f});header.setWidthPercentage(100);PdfPCell left=new PdfPCell();left.setBorder(Rectangle.NO_BORDER);left.setBackgroundColor(NAVY);left.setPadding(10);try(InputStream input=new ClassPathResource("identidade-pendente.png").getInputStream()){Image logo=Image.getInstance(input.readAllBytes());logo.scaleToFit(62,62);left.addElement(logo);}header.addCell(left);PdfPCell right=new PdfPCell();right.setBorder(Rectangle.NO_BORDER);right.setBackgroundColor(NAVY);right.setPadding(11);String heading=mode==DossierMode.INTERNAL?"Dossiê Interno da Ocorrência":"Dossiê da Ocorrência para o Associado";Paragraph h=new Paragraph(heading,title);h.setAlignment(Element.ALIGN_RIGHT);right.addElement(h);Paragraph protocol=new Paragraph(event.protocol(),yellow);protocol.setAlignment(Element.ALIGN_RIGHT);right.addElement(protocol);Paragraph slogan=new Paragraph("Sua segurança, nosso compromisso!",new Font(Font.HELVETICA,8.5f,Font.BOLD,Color.WHITE));slogan.setAlignment(Element.ALIGN_RIGHT);right.addElement(slogan);header.addCell(right);header.setSpacingAfter(12);document.add(header);}
    private void addSectionTitle(Document document,String text,Font font)throws DocumentException{PdfPTable t=new PdfPTable(1);t.setWidthPercentage(100);t.setSpacingBefore(12);t.setSpacingAfter(6);PdfPCell c=new PdfPCell(new Phrase(text,font));c.setBackgroundColor(NAVY);c.setBorderColor(YELLOW);c.setBorderWidthLeft(5);c.setPadding(7);t.addCell(c);document.add(t);}
    private PdfPTable table(int columns,float[] widths){PdfPTable t=new PdfPTable(columns);t.setWidthPercentage(100);try{t.setWidths(widths);}catch(DocumentException ignored){}t.setSpacingAfter(6);return t;}
    private void addPair(PdfPTable table,String label,String value,Font bold,Font regular){PdfPCell cell=new PdfPCell();cell.setBackgroundColor(LIGHT);cell.setBorderColor(new Color(225,228,238));cell.setPadding(6);Paragraph p=new Paragraph();p.add(new Chunk(label+": ",bold));p.add(new Chunk(safe(value),regular));cell.addElement(p);table.addCell(cell);}
    private void addHeaderCell(PdfPTable table,String value,Font font){PdfPCell c=new PdfPCell(new Phrase(value,font));c.setBackgroundColor(NAVY);c.setBorderColor(YELLOW);c.setPadding(5);table.addCell(c);}
    private void addCell(PdfPTable table,String value,Font font,boolean highlight){PdfPCell c=new PdfPCell(new Phrase(safe(value),font));c.setBackgroundColor(highlight?LIGHT:Color.WHITE);c.setBorderColor(new Color(225,228,238));c.setPadding(5);table.addCell(c);}
    private String label(Map<String,String> labels,String v){return labels.getOrDefault(v,safe(v));}
    private String safe(String v){return v==null||v.isBlank()?"—":v;}
    private String format(OffsetDateTime v){return v==null?"—":DATE.format(v);}
    private String money(BigDecimal value){if(value==null)return "—";NumberFormat nf=NumberFormat.getCurrencyInstance(new Locale("pt","BR"));return nf.format(value);}
    private String bytes(long v){if(v<1024)return v+" B";if(v<1024*1024)return String.format("%.1f KB",v/1024.0);return String.format("%.1f MB",v/(1024.0*1024.0));}
    private String towAnswer(String v){return switch(v==null?"":v){case"YES"->"Sim";case"NO"->"Não";case"NOT_APPLICABLE"->"Não se aplica";default->safe(v);};}
    private String purchaseStatus(String v){return switch(v==null?"":v){case"REQUESTED"->"Solicitado";case"ORDERED"->"Pedido";case"RECEIVED"->"Recebido";case"CANCELLED"->"Cancelado";case"FINALIZED"->"Finalizado";default->safe(v);};}
    private static String photoKind(String v){return switch(v==null?"":v){case"FRONT"->"Frente";case"LEFT_SIDE"->"Lateral esquerda";case"RIGHT_SIDE"->"Lateral direita";case"REAR"->"Traseira";default->"Outra";};}
    private static UUID uuid(ResultSet rs,String c)throws SQLException{Object v=rs.getObject(c);return v instanceof UUID u?u:UUID.fromString(String.valueOf(v));}
    private static OffsetDateTime offset(ResultSet rs,String c)throws SQLException{Object v=rs.getObject(c);if(v==null)return null;if(v instanceof OffsetDateTime o)return o;if(v instanceof java.sql.Timestamp t)return t.toInstant().atOffset(java.time.ZoneOffset.UTC);return OffsetDateTime.parse(String.valueOf(v));}
    private record WorkshopPdfRow(String section,String label,String action,String notes,String thirdModel){}
    private record PurchasePdfRow(String label,String supplier,BigDecimal amount,LocalDate deadline,String status,String notes,OffsetDateTime savedAt,String savedBy){}
    private record AcceptancePdf(OffsetDateTime acceptedAt,boolean userVerified,String proofHash,String dossierHash){}
    private record TowPdf(UUID id,String code,String plate,String model,String provider,String driver,String notes,OffsetDateTime completedAt,List<TowPdfItem> items,List<TowPdfPhoto> photos){}
    private record TowPdfItem(String label,String answer,String notes){}
    private record TowPdfPhoto(String kindLabel,String fileName,byte[] bytes){}
}
