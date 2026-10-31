package br.com.nh.cotacao.service;

import br.com.nh.cotacao.entity.Consultant;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.time.LocalDate;
import java.util.*;

/** Importação transacional e exclusivamente no banco do tenant. Nunca atualiza registros existentes. */
@Service
@ConditionalOnProperty(name="app.tenant.mode", havingValue="true")
public class SecureCustomerImportService {
    private final JdbcTemplate db;
    private final SecretKeySpec encryptionKey;
    private final SecureRandom random = new SecureRandom();

    public SecureCustomerImportService(JdbcTemplate db, @Value("${IMPORT_AES_KEY_BASE64:}") String secret) {
        this.db = db;
        if (secret == null || secret.isBlank()) this.encryptionKey = null;
        else {
            try {
                byte[] value = Base64.getDecoder().decode(secret);
                if (value.length != 32) throw new IllegalArgumentException("Chave precisa ter 32 bytes.");
                this.encryptionKey = new SecretKeySpec(value, "AES");
                Arrays.fill(value,(byte)0);
            } catch(Exception e) { throw new IllegalStateException("IMPORT_AES_KEY_BASE64 inválida: deve ser Base64 de 32 bytes."); }
        }
    }
    private void enabled(){if(encryptionKey==null)throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE,"Importação desativada. Configure a chave criptográfica deste ambiente.");}
    public record ValidationPreview(UUID batchId, String status, int validRows,int invalidRows,int existingRecords,
                                    String sha256,List<CustomerCsvParser.Issue> errors,List<Map<String,Object>> preview) {}
    public record ImportResult(UUID batchId,String status,int inserted,int alreadyExisting,int rejected) {}
    public record BatchSummary(UUID id,String fileName,String status,int valid,int invalid,int inserted,int skipped,String createdAt,String importedAt) {}

    @Transactional
    public ValidationPreview upload(byte[] csv,String fileName,boolean legalAttestation,String operator) {
        enabled();
        if(!legalAttestation) throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"Confirme a autorização da empresa para migrar os dados.");
        if(fileName==null || !fileName.toLowerCase(Locale.ROOT).endsWith(".csv"))throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"Envie um arquivo .csv UTF-8.");
        CustomerCsvParser.Parsed parsed;
        try{parsed=CustomerCsvParser.parse(csv);}catch(IllegalArgumentException e){throw new ResponseStatusException(HttpStatus.BAD_REQUEST,e.getMessage());}
        Boolean exists=db.query("SELECT 1 FROM tenant_customer_import_batches WHERE sha256=? LIMIT 1",(org.springframework.jdbc.core.ResultSetExtractor<Boolean>)rs->rs.next(),parsed.sha256());
        if(Boolean.TRUE.equals(exists))throw new ResponseStatusException(HttpStatus.CONFLICT,"Este arquivo já foi enviado anteriormente. Consulte o histórico de importação.");
        byte[] iv=new byte[12];random.nextBytes(iv);UUID id=UUID.randomUUID();
        byte[] cipher=encrypt(csv,iv,id);
        String filename=fileName.replaceAll("[^\\p{L}\\p{N} ._\\-]","_");
        filename=filename.substring(0,Math.min(160,filename.length()));
        int valid=parsed.rows().size(),bad=parsed.errors().size();
        String status=bad>0?"INVALID":"READY";
        db.update("""
          INSERT INTO tenant_customer_import_batches
           (id, original_filename,sha256,encrypted_original,iv,status,total_rows,valid_rows,invalid_rows,legal_attestation,uploaded_by)
          VALUES (?,?,?,?,?,?,?,?,?,TRUE,?)
          """,id,filename,parsed.sha256(),cipher,iv,status,valid+bad,valid,bad,operator);
        Set<String> existingDocuments=existingDocuments(parsed.rows());
        Set<String> existingPlates=existingPlates(parsed.rows());
        int existing=0;List<Map<String,Object>> preview=new ArrayList<>();
        for (CustomerCsvParser.CustomerRow row:parsed.rows()) {
            boolean found=existingDocuments.contains(row.document());if(found)existing++;
            if(preview.size()<12)preview.add(Map.of("line",row.sourceLine(),"name",row.name(),"document",maskDocument(row.document()),
                    "action",found?"JÁ CADASTRADO — IGNORAR":
                    (row.hasVehicle()?(existingPlates.contains(row.plate()) && !row.plate().isBlank()?"NOVO · VEÍCULO JÁ EXISTE":"NOVO + VEÍCULO"):"NOVO")));
        }
        return new ValidationPreview(id,status,valid,bad,existing,parsed.sha256(),parsed.errors().stream().limit(50).toList(),preview);
    }
    private Set<String> existingDocuments(List<CustomerCsvParser.CustomerRow> rows) {
        Set<String> docs=new HashSet<>();
        for(int start=0;start<rows.size();start+=250){
            List<CustomerCsvParser.CustomerRow> block=rows.subList(start,Math.min(start+250,rows.size()));
            String sql="SELECT document FROM associates WHERE document IN ("+String.join(",",Collections.nCopies(block.size(),"?"))+")";
            Object[] args=block.stream().map(CustomerCsvParser.CustomerRow::document).toArray();
            docs.addAll(db.query(sql,(rs,n)->rs.getString(1),args));
        }
        return docs;
    }
    private Set<String> existingPlates(List<CustomerCsvParser.CustomerRow> rows) {
        Set<String> plates=new HashSet<>();
        List<String> requested=rows.stream().map(CustomerCsvParser.CustomerRow::plate).filter(p->!p.isBlank()).distinct().toList();
        for(int start=0;start<requested.size();start+=250){
            List<String> block=requested.subList(start,Math.min(start+250,requested.size()));
            String sql="SELECT UPPER(REPLACE(plate,'-','')) FROM commercial_vehicles WHERE UPPER(REPLACE(plate,'-','')) IN ("
                +String.join(",",Collections.nCopies(block.size(),"?"))+")";
            plates.addAll(db.query(sql,(rs,n)->rs.getString(1),block.toArray()));
        }
        return plates;
    }
    @Transactional
    public ImportResult confirm(UUID id,boolean approved,String operator){
        enabled();
        if(!approved)throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"Confirme que revisou a prévia e deseja importar somente registros novos.");
        List<Stored> entries=db.query("""
          SELECT status,encrypted_original,iv,sha256,imported_count,skipped_count
            FROM tenant_customer_import_batches WHERE id=? FOR UPDATE
          """,(rs,n)->new Stored(rs.getString(1),rs.getBytes(2),rs.getBytes(3),rs.getString(4),rs.getInt(5),rs.getInt(6)),id);
        if(entries.isEmpty())throw new ResponseStatusException(HttpStatus.NOT_FOUND,"Importação não encontrada neste ambiente.");
        Stored stored=entries.get(0);
        if(stored.status.equals("IMPORTED"))return new ImportResult(id,"IMPORTED",stored.inserted,stored.skipped,0);
        if(!stored.status.equals("READY"))throw new ResponseStatusException(HttpStatus.CONFLICT,"Corrija as linhas inválidas e envie um novo CSV.");
        byte[] csv=decrypt(stored.payload,stored.iv,id);
        CustomerCsvParser.Parsed parsed=CustomerCsvParser.parse(csv);
        if(!MessageDigest.isEqual(java.util.HexFormat.of().parseHex(parsed.sha256()),java.util.HexFormat.of().parseHex(stored.sha256)) || !parsed.errors().isEmpty())
            throw new ResponseStatusException(HttpStatus.CONFLICT,"Integridade do arquivo não confirmada. Importação cancelada.");
        int inserted=0,skipped=0;
        Set<String> existing=existingDocuments(parsed.rows());
        Set<String> existingVehiclePlates=existingPlates(parsed.rows());
        for (CustomerCsvParser.CustomerRow r:parsed.rows()) {
            if(existing.contains(r.document())){skipped++;continue;}
            UUID associate=UUID.randomUUID();
            List<UUID> insertedIds=db.query("""
                INSERT INTO associates (id,name,normalized_name,document,whatsapp,email,active,created_at,updated_at,
                                        birth_date,secondary_phone,postal_code,address_line,address_number,address_complement,neighborhood,city,state)
                VALUES (?,?,?,?,?,?,TRUE,NOW(),NOW(),?,?,?,?,?,?,?,?,?,?)
                ON CONFLICT DO NOTHING RETURNING id
                """,(rs,n)->(UUID)rs.getObject(1),associate,r.name(),Consultant.normalize(r.name()),r.document(),nil(r.phone()),nil(r.email()),
                r.birthDate().isEmpty()?null:LocalDate.parse(r.birthDate()),nil(r.phone2()),nil(r.zip()),nil(r.address()),nil(r.number()),nil(r.complement()),nil(r.neighborhood()),nil(r.city()),nil(r.state()));
            if(insertedIds.isEmpty()){skipped++;continue;}
            db.update("INSERT INTO tenant_customer_import_links(batch_id,source_line,associate_id,source_external_code) VALUES (?,?,?,?)",id,r.sourceLine(),associate,nil(r.externalId()));
            if (r.hasVehicle() && (r.plate().isBlank() || existingVehiclePlates.add(r.plate()))) {
                Integer modelYear=r.modelYear().isBlank()?null:Integer.valueOf(r.modelYear());
                Boolean zeroKm=r.vehicleZeroKm().isBlank()?null:Set.of("sim","true","1").contains(r.vehicleZeroKm());
                java.math.BigDecimal fipe=r.fipeValue().isBlank()?null:
                        new java.math.BigDecimal(CustomerCsvParser.normalizeMoney(r.fipeValue()));
                // DRAFT, sem cobrança ou plano: o ADM confere os dados FIPE antes de ativar.
                db.update("""
                    INSERT INTO commercial_vehicles
                      (id,associate_id,plate,model,created_at,updated_at,
                       zero_km,vehicle_type,brand,model_year,fipe_version,fipe_code,fipe_value)
                    VALUES (?,?,?,?,NOW(),NOW(),?,?,?,?,?,?,?)
                    """,UUID.randomUUID(),associate,nil(r.plate()),r.vehicleModel(),zeroKm,
                       nil(r.vehicleType()),nil(r.vehicleMake()),modelYear,
                       nil(r.fipeVersion()),nil(r.fipeCode()),fipe);
            }
            inserted++;
        }
        db.update("""
           UPDATE tenant_customer_import_batches SET status='IMPORTED',imported_count=?,skipped_count=?,imported_by=?,imported_at=NOW()
            WHERE id=?
           """,inserted,skipped,operator,id);
        return new ImportResult(id,"IMPORTED",inserted,skipped,0);
    }
    public record ImportedCustomer(UUID id,String name,String maskedDocument,String email,String phone,String sourceFile,String importedAt) {}
    public List<ImportedCustomer> importedCustomers() {
        enabled();return db.query("""
          SELECT a.id,a.name,a.document,a.email,a.whatsapp,b.original_filename,l.created_at
          FROM tenant_customer_import_links l
          JOIN associates a ON a.id=l.associate_id
          JOIN tenant_customer_import_batches b ON b.id=l.batch_id
          ORDER BY l.created_at DESC,a.name LIMIT 50
          """,(rs,n)->new ImportedCustomer((UUID)rs.getObject(1),rs.getString(2),maskDocument(rs.getString(3)),rs.getString(4),rs.getString(5),rs.getString(6),rs.getString(7)));
    }
    private static String nil(String value){return value==null||value.isBlank()?null:value;}
    public List<BatchSummary> history(){
        enabled();return db.query("""
          SELECT id,original_filename,status,valid_rows,invalid_rows,imported_count,skipped_count,created_at,imported_at
           FROM tenant_customer_import_batches ORDER BY created_at DESC LIMIT 50
           """,(rs,n)->new BatchSummary((UUID)rs.getObject(1),rs.getString(2),rs.getString(3),rs.getInt(4),rs.getInt(5),rs.getInt(6),rs.getInt(7),rs.getString(8),rs.getString(9)));
    }
    public byte[] original(UUID id,String operator){
        enabled();List<Stored> data=db.query("SELECT status,encrypted_original,iv,sha256,imported_count,skipped_count FROM tenant_customer_import_batches WHERE id=?",(rs,n)->new Stored(rs.getString(1),rs.getBytes(2),rs.getBytes(3),rs.getString(4),rs.getInt(5),rs.getInt(6)),id);
        if(data.isEmpty())throw new ResponseStatusException(HttpStatus.NOT_FOUND,"Arquivo não encontrado.");
        byte[] clear=decrypt(data.get(0).payload,data.get(0).iv,id);
        try{if(!MessageDigest.isEqual(java.util.HexFormat.of().parseHex(CustomerCsvParser.parse(clear).sha256()),java.util.HexFormat.of().parseHex(data.get(0).sha256)))
            throw new IllegalArgumentException("Checksum inválido");}catch(Exception e){throw new ResponseStatusException(HttpStatus.CONFLICT,"Integridade do arquivo não confirmada.");}
        db.update("INSERT INTO tenant_customer_import_access_audit(id,batch_id,operation,actor) VALUES (?,?,'ORIGINAL_DOWNLOAD',?)",UUID.randomUUID(),id,operator);
        return clear;
    }
    private byte[] encrypt(byte[] plain,byte[] iv,UUID batchId){try{
        Cipher c=Cipher.getInstance("AES/GCM/NoPadding");c.init(Cipher.ENCRYPT_MODE,encryptionKey,new GCMParameterSpec(128,iv));c.updateAAD(batchId.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8));return c.doFinal(plain);
    }catch(Exception e){throw new IllegalStateException("Falha ao cifrar arquivo da importação.");}}
    private byte[] decrypt(byte[] cipher,byte[] iv,UUID batchId){try{
        Cipher c=Cipher.getInstance("AES/GCM/NoPadding");c.init(Cipher.DECRYPT_MODE,encryptionKey,new GCMParameterSpec(128,iv));c.updateAAD(batchId.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8));return c.doFinal(cipher);
    }catch(Exception e){throw new ResponseStatusException(HttpStatus.CONFLICT,"Não foi possível verificar a integridade do arquivo armazenado.");}}
    public static String maskDocument(String value){if(value==null||value.length()<6)return "***";return "***"+value.substring(value.length()-4);}
    private record Stored(String status,byte[] payload,byte[] iv,String sha256,int inserted,int skipped){}
}
