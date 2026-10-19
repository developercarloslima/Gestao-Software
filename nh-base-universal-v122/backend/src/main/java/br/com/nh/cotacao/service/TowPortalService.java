package br.com.nh.cotacao.service;

import br.com.nh.cotacao.dto.TowPortalDtos.*;
import br.com.nh.cotacao.security.PortalPrincipal;
import br.com.nh.cotacao.security.PortalRole;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.OffsetDateTime;
import java.time.format.DateTimeFormatter;
import java.util.*;

@Service
public class TowPortalService {
    private static final long MAX_UPLOAD_BYTES = 15L * 1024L * 1024L;
    private static final Set<String> TOW_ANSWERS = Set.of("YES","NO","NOT_APPLICABLE");
    private static final Set<String> PHOTO_KINDS = Set.of("FRONT","LEFT_SIDE","RIGHT_SIDE","REAR","OTHER");
    private static final Set<String> REQUIRED_PHOTO_KINDS = Set.of("FRONT","LEFT_SIDE","RIGHT_SIDE","REAR");
    private static final Set<String> VEHICLE_CATEGORIES = Set.of("MOTORCYCLE","LIGHT_CAR","UTILITY","TRUCK");
    private static final Set<String> ALLOWED_IMAGE_TYPES = Set.of("image/jpeg","image/png","image/webp");

    private final JdbcTemplate jdbc;
    private final PortalUserService portalUserService;

    public TowPortalService(JdbcTemplate jdbc, PortalUserService portalUserService) {
        this.jdbc=jdbc; this.portalUserService=portalUserService;
    }

    @Transactional
    public List<TowTemplateItem> template(PortalPrincipal principal) {
        Actor actor=actor(principal); assertTowAccess(actor);
        ensureTowTemplate();
        return jdbc.query("select id,section,label,sort_order from nh_tow_checklist_template_items where active=true order by sort_order,label",
                (rs,rowNum)->new TowTemplateItem(uuid(rs,"id"),rs.getString("section"),rs.getString("label"),rs.getInt("sort_order")));
    }

    @Transactional(readOnly=true)
    public List<TowQueueSummary> list(PortalPrincipal principal,String query,String status) {
        Actor actor=actor(principal); assertTowAccess(actor);
        StringBuilder sql=new StringBuilder("""
                select r.id,r.code,r.vehicle_plate,r.vehicle_model,r.vehicle_category,r.provider_name,r.driver_name,r.status,r.created_at,r.completed_at,r.completed_by_name,
                       (select count(*) from nh_tow_record_items i where i.tow_record_id=r.id and i.answer<>'UNANSWERED') answered_items,
                       (select count(*) from nh_tow_record_items i where i.tow_record_id=r.id) total_items,
                       (select count(*) from nh_tow_record_photos p where p.tow_record_id=r.id) photo_count
                  from nh_tow_records r where 1=1
                """);
        List<Object> args=new ArrayList<>();
        if(status!=null&&!status.isBlank()){
            String s=status.trim().toUpperCase(Locale.ROOT);
            if(!Set.of("DRAFT","COMPLETED").contains(s)) throw new IllegalArgumentException("Status do reboque inválido.");
            sql.append(" and r.status=?"); args.add(s);
        }
        if(query!=null&&!query.isBlank()){
            String p="%"+query.trim().toLowerCase(Locale.ROOT)+"%";
            sql.append(" and (lower(r.code) like ? or lower(r.vehicle_plate) like ? or lower(coalesce(r.vehicle_model,'')) like ? or lower(coalesce(r.driver_name,'')) like ? or lower(coalesce(r.provider_name,'')) like ?)");
            for(int i=0;i<5;i++)args.add(p);
        }
        sql.append(" order by case when r.status='DRAFT' then 0 else 1 end,r.created_at desc limit 500");
        return jdbc.query(sql.toString(),this::mapSummary,args.toArray());
    }

    @Transactional
    public TowRecordDetail create(CreateTowRecordRequest request,PortalPrincipal principal){
        Actor actor=actor(principal); assertTowAccess(actor);
        String plate=normalizePlate(request.vehiclePlate());
        String category=normalize(request.vehicleCategory());
        if(!category.isBlank()&&!VEHICLE_CATEGORIES.contains(category)) throw new IllegalArgumentException("Categoria do veículo inválida.");

        ensureTowTemplate();
        UUID id=jdbc.query("""
                select id from nh_tow_records
                 where vehicle_plate=? and status='DRAFT'
                 order by updated_at desc,created_at desc limit 1
                """,rs->rs.next()?uuid(rs,"id"):null,plate);
        if(id==null){
            id=UUID.randomUUID(); String code=nextCode();
            jdbc.update("""
                    insert into nh_tow_records(id,code,vehicle_plate,vehicle_model,vehicle_category,provider_name,driver_name,driver_phone,general_notes,status,created_by_username,created_by_name,created_at,updated_at)
                    values (?,?,?,?,?,?,?,?,?,'DRAFT',?,?,now(),now())
                    """,id,code,plate,clean(request.vehicleModel(),180),category.isBlank()?null:category,clean(request.providerName(),180),clean(request.driverName(),180),clean(request.driverPhone(),40),clean(request.generalNotes(),3000),actor.username(),actor.name());
            List<TemplateRow> templates=jdbc.query("select id,section,label,sort_order from nh_tow_checklist_template_items where active=true order by sort_order,label",
                    (rs,rowNum)->new TemplateRow(uuid(rs,"id"),rs.getString("section"),rs.getString("label"),rs.getInt("sort_order")));
            for(TemplateRow t:templates){
                jdbc.update("insert into nh_tow_record_items(id,tow_record_id,template_item_id,section,label,sort_order,answer,updated_at) values (?,?,?,?,?,?,'UNANSWERED',now())",
                        UUID.randomUUID(),id,t.id(),t.section(),t.label(),t.sortOrder());
            }
        }else{
            jdbc.update("""
                    update nh_tow_records set vehicle_model=coalesce(?,vehicle_model),vehicle_category=coalesce(?,vehicle_category),
                        provider_name=coalesce(?,provider_name),driver_name=coalesce(?,driver_name),driver_phone=coalesce(?,driver_phone),
                        general_notes=coalesce(?,general_notes),updated_at=now() where id=?
                    """,clean(request.vehicleModel(),180),category.isBlank()?null:category,clean(request.providerName(),180),clean(request.driverName(),180),clean(request.driverPhone(),40),clean(request.generalNotes(),3000),id);
        }
        applyInitialAnswers(id,request.checklist(),actor);
        return detail(id,principal);
    }

    @Transactional
    public TowRecordDetail detail(UUID id,PortalPrincipal principal){
        Actor actor=actor(principal); assertTowAccess(actor); TowRow r=require(id);
        ensureTowTemplate();
        if (!"COMPLETED".equals(r.status())) ensureRecordItems(id);
        List<TowItem> items=items(id); List<TowPhoto> photos=photos(id);
        int answered=(int)items.stream().filter(i->!"UNANSWERED".equals(i.answer())).count();
        return new TowRecordDetail(r.id(),r.code(),r.plate(),r.model(),r.category(),r.provider(),r.driver(),r.phone(),r.notes(),r.status(),r.createdByName(),r.createdAt(),r.updatedAt(),r.completedAt(),r.completedByName(),answered,items.size(),photos.size(),items,photos,!"COMPLETED".equals(r.status()));
    }

    @Transactional
    public TowRecordDetail updateItem(UUID id,UUID itemId,UpdateTowItemRequest request,PortalPrincipal principal){
        Actor actor=actor(principal); assertTowAccess(actor); TowRow r=require(id); assertEditable(r);
        String answer=normalize(request.answer()); if(!TOW_ANSWERS.contains(answer)) throw new IllegalArgumentException("Marque Sim, Não ou Não se aplica.");
        int updated=jdbc.update("update nh_tow_record_items set answer=?,notes=?,updated_by=?,updated_at=now() where id=? and tow_record_id=?",
                answer,clean(request.notes(),3000),actor.name(),itemId,id);
        if(updated!=1)throw new IllegalArgumentException("Item do checklist de reboque não encontrado.");
        touch(id); return detail(id,principal);
    }

    @Transactional
    public TowRecordDetail uploadPhotos(UUID id,String photoKind,UUID checklistItemId,List<MultipartFile> files,String notes,PortalPrincipal principal){
        Actor actor=actor(principal); assertTowAccess(actor); TowRow r=require(id); assertEditable(r);
        String kind=normalize(photoKind); if(!PHOTO_KINDS.contains(kind))throw new IllegalArgumentException("Tipo de foto inválido.");
        if(checklistItemId!=null){
            Integer linked=jdbc.queryForObject("select count(*) from nh_tow_record_items where id=? and tow_record_id=?",Integer.class,checklistItemId,id);
            if(linked==null||linked!=1)throw new IllegalArgumentException("Item do checklist informado para a foto não pertence a este atendimento.");
        }
        if(files==null||files.isEmpty())throw new IllegalArgumentException("Selecione pelo menos uma foto.");
        int accepted=0;
        for(MultipartFile file:files){
            if(file==null||file.isEmpty())continue; if(file.getSize()>MAX_UPLOAD_BYTES)throw new IllegalArgumentException("Cada foto deve possuir no máximo 15 MB.");
            String contentType=normalizeImageType(file.getContentType(),file.getOriginalFilename()); if(!ALLOWED_IMAGE_TYPES.contains(contentType))throw new IllegalArgumentException("Envie somente fotos JPG, PNG ou WEBP.");
            byte[] bytes; try{bytes=file.getBytes();}catch(IOException e){throw new IllegalStateException("Não foi possível ler uma das fotos enviadas.");}
            if(bytes.length==0||bytes.length>MAX_UPLOAD_BYTES)throw new IllegalArgumentException("Foto vazia ou acima do limite de 15 MB.");
            jdbc.update("""
                    insert into nh_tow_record_photos(id,tow_record_id,checklist_item_id,photo_kind,original_name,content_type,file_size,file_data,notes,uploaded_by,created_at)
                    values (?,?,?,?,?,?,?,?,?,?,now())
                    """,UUID.randomUUID(),id,checklistItemId,kind,safeFileName(file.getOriginalFilename()),contentType,(long)bytes.length,bytes,clean(notes,1000),actor.name());
            accepted++;
        }
        if(accepted==0)throw new IllegalArgumentException("Nenhuma foto válida foi recebida."); touch(id); return detail(id,principal);
    }

    @Transactional(readOnly=true)
    public TowPhotoContent photo(UUID id,UUID photoId,PortalPrincipal principal){
        Actor actor=actor(principal); assertTowAccess(actor); require(id);
        return jdbc.query("select original_name,content_type,file_data from nh_tow_record_photos where id=? and tow_record_id=?",rs->{
            if(!rs.next())throw new IllegalArgumentException("Foto do reboque não encontrada."); return new TowPhotoContent(rs.getString(1),rs.getString(2),rs.getBytes(3));
        },photoId,id);
    }

    @Transactional
    public TowRecordDetail deletePhoto(UUID id,UUID photoId,PortalPrincipal principal){
        Actor actor=actor(principal); assertTowAccess(actor); TowRow r=require(id); assertEditable(r);
        if(jdbc.update("delete from nh_tow_record_photos where id=? and tow_record_id=?",photoId,id)!=1)throw new IllegalArgumentException("Foto do reboque não encontrada.");
        touch(id); return detail(id,principal);
    }

    @Transactional
    public TowRecordDetail complete(UUID id,PortalPrincipal principal){
        Actor actor=actor(principal); assertTowAccess(actor); TowRow r=require(id); assertEditable(r);
        Integer missing=jdbc.queryForObject("select count(*) from nh_tow_record_items where tow_record_id=? and answer='UNANSWERED'",Integer.class,id);
        if(missing!=null&&missing>0)throw new IllegalArgumentException("Preencha todos os acessórios/itens antes de concluir o checklist do reboque.");
        Set<String> kinds=new HashSet<>(jdbc.query("select distinct photo_kind from nh_tow_record_photos where tow_record_id=?",(rs,rowNum)->rs.getString(1),id));
        List<String> labels=new ArrayList<>();
        if(!kinds.contains("FRONT"))labels.add("frente"); if(!kinds.contains("LEFT_SIDE"))labels.add("lateral esquerda"); if(!kinds.contains("RIGHT_SIDE"))labels.add("lateral direita"); if(!kinds.contains("REAR"))labels.add("traseira");
        if(!labels.isEmpty())throw new IllegalArgumentException("Envie as fotos obrigatórias do veículo: "+String.join(", ",labels)+". A ordem não importa.");
        jdbc.update("update nh_tow_records set status='COMPLETED',completed_at=now(),completed_by_name=?,updated_at=now() where id=?",actor.name(),id);
        return detail(id,principal);
    }

    private void applyInitialAnswers(UUID recordId,List<InitialTowAnswer> answers,Actor actor){
        if(answers==null||answers.isEmpty())return;
        for(InitialTowAnswer item:answers){
            if(item==null||item.templateItemId()==null)continue;
            String answer=normalize(item.answer());
            if(!TOW_ANSWERS.contains(answer))throw new IllegalArgumentException("Marque Sim, Não ou Não se aplica nos itens informados.");
            jdbc.update("""
                    update nh_tow_record_items set answer=?,notes=?,updated_by=?,updated_at=now()
                     where tow_record_id=? and template_item_id=?
                    """,answer,clean(item.notes(),3000),actor.name(),recordId,item.templateItemId());
        }
        touch(recordId);
    }

    private TowQueueSummary mapSummary(ResultSet rs,int rowNum)throws SQLException{
        return new TowQueueSummary(uuid(rs,"id"),rs.getString("code"),rs.getString("vehicle_plate"),rs.getString("vehicle_model"),rs.getString("vehicle_category"),rs.getString("provider_name"),rs.getString("driver_name"),rs.getString("status"),rs.getInt("answered_items"),rs.getInt("total_items"),rs.getInt("photo_count"),offset(rs,"created_at"),offset(rs,"completed_at"),rs.getString("completed_by_name"));
    }
    private TowRow require(UUID id){
        TowRow r=jdbc.query("select id,code,vehicle_plate,vehicle_model,vehicle_category,provider_name,driver_name,driver_phone,general_notes,status,created_by_name,created_at,updated_at,completed_at,completed_by_name from nh_tow_records where id=?",rs->rs.next()?new TowRow(uuid(rs,"id"),rs.getString("code"),rs.getString("vehicle_plate"),rs.getString("vehicle_model"),rs.getString("vehicle_category"),rs.getString("provider_name"),rs.getString("driver_name"),rs.getString("driver_phone"),rs.getString("general_notes"),rs.getString("status"),rs.getString("created_by_name"),offset(rs,"created_at"),offset(rs,"updated_at"),offset(rs,"completed_at"),rs.getString("completed_by_name")):null,id);
        if(r==null)throw new IllegalArgumentException("Checklist de reboque não encontrado."); return r;
    }
    private List<TowItem> items(UUID id){return jdbc.query("select id,template_item_id,section,label,sort_order,answer,notes,updated_by,updated_at from nh_tow_record_items where tow_record_id=? order by sort_order,label",(rs,rowNum)->new TowItem(uuid(rs,"id"),uuidNullable(rs,"template_item_id"),rs.getString("section"),rs.getString("label"),rs.getInt("sort_order"),rs.getString("answer"),rs.getString("notes"),rs.getString("updated_by"),offset(rs,"updated_at")),id);}
    private List<TowPhoto> photos(UUID id){return jdbc.query("select id,checklist_item_id,photo_kind,original_name,content_type,file_size,notes,uploaded_by,created_at from nh_tow_record_photos where tow_record_id=? order by created_at",(rs,rowNum)->new TowPhoto(uuid(rs,"id"),uuidNullable(rs,"checklist_item_id"),rs.getString("photo_kind"),rs.getString("original_name"),rs.getString("content_type"),rs.getLong("file_size"),rs.getString("notes"),rs.getString("uploaded_by"),offset(rs,"created_at")),id);}
    private void ensureTowTemplate(){
        Object[][] defaults={
                {"Acessórios","Chave do veículo",1},{"Acessórios","Estepe",2},{"Acessórios","Triângulo",3},{"Acessórios","Macaco",4},{"Acessórios","Chave de roda",5},
                {"Acessórios","Central multimídia",6},{"Acessórios","Som/Rádio",7},{"Acessórios","Tapetes",8},{"Acessórios","Antena",9},{"Acessórios","Manual/Documentos do veículo",10},{"Acessórios","Chave reserva",11},
                {"Pertences","Objetos pessoais",20},{"Pertences","Ferramentas ou itens soltos",21},{"Pertences","Bagagem/Carga",22},{"Pertences","Capacete (quando motocicleta)",23}
        };
        Set<String> expected=new HashSet<>();
        for(Object[] item:defaults)expected.add(String.valueOf(item[1]));
        List<String> active=jdbc.query("select label from nh_tow_checklist_template_items where active=true",(rs,rowNum)->rs.getString(1));
        if(active.size()==expected.size()&&expected.containsAll(active))return;
        jdbc.update("update nh_tow_checklist_template_items set active=false,updated_at=now() where active=true");
        for(Object[] item:defaults){
            jdbc.update("""
                    insert into nh_tow_checklist_template_items(id,section,label,sort_order,active,created_at,updated_at)
                    values (?,?,?,?,true,now(),now())
                    on conflict (label) do update set section=excluded.section,sort_order=excluded.sort_order,active=true,updated_at=now()
                    """,UUID.randomUUID(),item[0],item[1],item[2]);
        }
    }
    private void ensureRecordItems(UUID recordId){
        jdbc.update("""
                delete from nh_tow_record_items i
                 where i.tow_record_id=?
                   and i.template_item_id is not null
                   and not exists (
                       select 1 from nh_tow_checklist_template_items t
                        where t.id=i.template_item_id and t.active=true
                   )
                """,recordId);
        jdbc.update("""
                insert into nh_tow_record_items(id,tow_record_id,template_item_id,section,label,sort_order,answer,updated_at)
                select gen_random_uuid(), ?, t.id, t.section, t.label, t.sort_order, 'UNANSWERED', now()
                  from nh_tow_checklist_template_items t
                 where t.active=true
                on conflict (tow_record_id,label) do nothing
                """,recordId);
    }
    private void touch(UUID id){jdbc.update("update nh_tow_records set updated_at=now() where id=?",id);}
    private Actor actor(PortalPrincipal principal){var s=portalUserService.session(principal.username());return new Actor(s.username(),firstNonBlank(s.displayName(),s.username()),principal.role());}
    private static void assertTowAccess(Actor a){if(a.role()!=PortalRole.TOW_DRIVER&&a.role()!=PortalRole.ADMIN)throw new IllegalArgumentException("Este usuário não possui acesso à Área do Guincho/Reboque.");}
    private static void assertEditable(TowRow r){if("COMPLETED".equals(r.status()))throw new IllegalArgumentException("Este checklist de reboque já foi concluído e está disponível apenas para consulta.");}
    private String nextCode(){String prefix="RBQ-"+OffsetDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd"))+"-";for(int i=0;i<20;i++){String c=prefix+UUID.randomUUID().toString().replace("-","").substring(0,6).toUpperCase(Locale.ROOT);Integer n=jdbc.queryForObject("select count(*) from nh_tow_records where code=?",Integer.class,c);if(n==null||n==0)return c;}return prefix+System.nanoTime();}
    private static String normalizePlate(String v){String s=v==null?"":v.toUpperCase(Locale.ROOT).replaceAll("[^A-Z0-9]","");if(s.length()<5||s.length()>10)throw new IllegalArgumentException("Informe uma placa válida.");return s;}
    private static String normalize(String v){return v==null?"":v.trim().toUpperCase(Locale.ROOT);}
    private static String clean(String v,int max){if(v==null)return null;String s=v.trim().replaceAll("\\s+"," ");return s.isBlank()?null:s.substring(0,Math.min(max,s.length()));}
    private static String firstNonBlank(String...vs){for(String v:vs)if(v!=null&&!v.isBlank())return v.trim();return "Guincho/Reboque";}
    private static String normalizeImageType(String c,String f){String n=c==null?"":c.trim().toLowerCase(Locale.ROOT);if(ALLOWED_IMAGE_TYPES.contains(n))return n;String l=f==null?"":f.toLowerCase(Locale.ROOT);if(l.endsWith(".jpg")||l.endsWith(".jpeg"))return"image/jpeg";if(l.endsWith(".png"))return"image/png";if(l.endsWith(".webp"))return"image/webp";return n;}
    private static String safeFileName(String v){String n=v==null||v.isBlank()?"foto":v.trim().replace('\\','/');int s=n.lastIndexOf('/');if(s>=0)n=n.substring(s+1);n=n.replaceAll("[\\r\\n\\t]","_");return n.substring(0,Math.min(240,n.length()));}
    private static UUID uuid(ResultSet rs,String c)throws SQLException{Object v=rs.getObject(c);return v instanceof UUID u?u:UUID.fromString(String.valueOf(v));}
    private static UUID uuidNullable(ResultSet rs,String c)throws SQLException{Object v=rs.getObject(c);if(v==null)return null;return v instanceof UUID u?u:UUID.fromString(String.valueOf(v));}
    private static OffsetDateTime offset(ResultSet rs,String c)throws SQLException{Object v=rs.getObject(c);if(v==null)return null;if(v instanceof OffsetDateTime o)return o;if(v instanceof java.sql.Timestamp t)return t.toInstant().atOffset(java.time.ZoneOffset.UTC);return OffsetDateTime.parse(String.valueOf(v));}
    private record Actor(String username,String name,PortalRole role){}
    private record TemplateRow(UUID id,String section,String label,int sortOrder){}
    private record TowRow(UUID id,String code,String plate,String model,String category,String provider,String driver,String phone,String notes,String status,String createdByName,OffsetDateTime createdAt,OffsetDateTime updatedAt,OffsetDateTime completedAt,String completedByName){}
}
