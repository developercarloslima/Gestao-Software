package br.com.gestaosoftware.control.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.server.ResponseStatusException;

import java.io.IOException;
import java.util.*;

@Service
public class CompanySiteEditorService {
    private static final Set<String> FIELDS=Set.of(
        "headerSubtitle","assistanceText","assistancePhone","navigationLinks",
        "heroEyebrow","heroTitle","heroDescription","heroImageUrl","heroButtonText","heroButtonUrl",
        "benefitsEyebrow","benefitsTitle","benefitsItems",
        "stepsEyebrow","stepsTitle","stepsItems",
        "appTitle","appDescription","appImageUrl","iosUrl","androidUrl",
        "contactEyebrow","contactTitle","contactDescription","contactPhone","contactWhatsapp","contactEmail","contactFormRecipient",
        "footerDescription"
    );
    private static final Set<String> FLAGS=Set.of(
        "showHero","showBenefits","showSteps","showApp","showContact","showQuoteButton","showContactForm"
    );
    private final JdbcTemplate jdbc;
    private final ObjectMapper mapper;
    public CompanySiteEditorService(JdbcTemplate jdbc,ObjectMapper mapper){this.jdbc=jdbc;this.mapper=mapper;}

    private boolean hasCompany(UUID id){
        return Boolean.TRUE.equals(jdbc.queryForObject(
            "SELECT EXISTS(SELECT 1 FROM platform_companies WHERE id=?)",Boolean.class,id));
    }
    private boolean siteEnabled(UUID id){
        return Boolean.TRUE.equals(jdbc.queryForObject("""
            SELECT EXISTS(
               SELECT 1 FROM platform_company_modules
                WHERE company_id=? AND module_code='SITE_PRINCIPAL' AND enabled=TRUE
            )""",Boolean.class,id));
    }
    private void requireCompany(UUID id){
        if(!hasCompany(id))throw new ResponseStatusException(HttpStatus.NOT_FOUND,"Empresa não encontrada.");
    }

    @Transactional(readOnly=true)
    public Map<String,Object> get(UUID companyId){
        requireCompany(companyId);
        List<String> rows=jdbc.query(
            "SELECT config_json FROM platform_company_site_settings WHERE company_id=?",
            (rs,i)->rs.getString(1),companyId);
        if(rows.isEmpty())return Map.of();
        try {
            return mapper.readValue(rows.get(0),new TypeReference<Map<String,Object>>(){});
        }catch(Exception e){throw new IllegalStateException("Conteúdo do site inválido.",e);}
    }

    @Transactional
    public Map<String,Object> save(UUID companyId,Map<String,Object> submitted){
        requireCompany(companyId);
        if(!siteEnabled(companyId))throw new ResponseStatusException(
            HttpStatus.CONFLICT,"Ative o módulo Site Principal para editar suas seções.");
        if(submitted==null)throw new IllegalArgumentException("Informe as seções do site.");
        Map<String,Object> data=new LinkedHashMap<>();
        for(var entry:submitted.entrySet()){
            String key=entry.getKey();
            Object value=entry.getValue();
            if(FLAGS.contains(key)){
                if(!(value instanceof Boolean))throw new IllegalArgumentException("Visibilidade inválida: "+key);
                data.put(key,value);
            }else if(FIELDS.contains(key)){
                if(!(value instanceof String str))throw new IllegalArgumentException("Texto inválido: "+key);
                int max=Set.of("benefitsItems","stepsItems","navigationLinks").contains(key)?6000:1200;
                if(str.length()>max)throw new IllegalArgumentException("Campo muito longo: "+key);
                if(Set.of("heroImageUrl","appImageUrl").contains(key)&&!str.isBlank()
                    && !(str.startsWith("https://")||str.startsWith("/api/public/platform/site-assets/")))
                    throw new IllegalArgumentException("Imagem deve usar HTTPS ou arquivo do acervo do site.");
                data.put(key,str.trim());
            }else throw new IllegalArgumentException("Campo desconhecido: "+key);
        }
        try{
            String json=mapper.writeValueAsString(data);
            if(json.length()>16000)throw new IllegalArgumentException("Conteúdo do site excedeu o limite.");
            jdbc.update("""
                INSERT INTO platform_company_site_settings(company_id,config_json,updated_at)
                VALUES(?,?,NOW())
                ON CONFLICT(company_id) DO UPDATE SET config_json=EXCLUDED.config_json,updated_at=NOW()
                """,companyId,json);
        }catch(com.fasterxml.jackson.core.JsonProcessingException e){
            throw new IllegalArgumentException("Não foi possível validar o conteúdo do site.",e);
        }
        return data;
    }

    public record SiteAsset(String contentType,byte[] content){}

    @Transactional
    public Map<String,Object> upload(UUID companyId,MultipartFile file) throws IOException {
        requireCompany(companyId);
        if(!siteEnabled(companyId))throw new ResponseStatusException(HttpStatus.CONFLICT,"Módulo Site Principal não habilitado.");
        if(file==null||file.isEmpty()||file.getSize()>5*1024*1024L)
            throw new IllegalArgumentException("Envie imagem de até 5 MB.");
        String mime=String.valueOf(file.getContentType()).toLowerCase(Locale.ROOT);
        byte[] data=file.getBytes();
        boolean png=data.length>=8&&(data[0]&255)==137&&(data[1]&255)==80&&(data[2]&255)==78&&(data[3]&255)==71;
        boolean jpg=data.length>=3&&(data[0]&255)==255&&(data[1]&255)==216&&(data[2]&255)==255;
        boolean webp=data.length>=12&&new String(data,0,4,java.nio.charset.StandardCharsets.US_ASCII).equals("RIFF")
            &&new String(data,8,4,java.nio.charset.StandardCharsets.US_ASCII).equals("WEBP");
        if(!((png&&mime.equals("image/png"))||(jpg&&mime.equals("image/jpeg"))||(webp&&mime.equals("image/webp"))))
            throw new IllegalArgumentException("Formato inválido. Use PNG, JPEG ou WebP.");
        UUID asset=UUID.randomUUID();
        String filename=String.valueOf(file.getOriginalFilename()).replaceAll("[\\r\\n\\x00-\\x1f]","_");
        if(filename.length()>180)filename=filename.substring(0,180);
        jdbc.update("INSERT INTO platform_company_site_assets(id,company_id,original_name,content_type,content_bytes) VALUES(?,?,?,?,?)",
            asset,companyId,filename,mime,data);
        return Map.of("url","/api/public/platform/site-assets/"+asset,"assetId",asset.toString());
    }
    @Transactional(readOnly=true)
    public SiteAsset publicAsset(UUID assetId){
        List<SiteAsset> rows=jdbc.query("""
            SELECT a.content_type,a.content_bytes FROM platform_company_site_assets a
              JOIN platform_companies c ON c.id=a.company_id
             WHERE a.id=? AND c.status IN ('ACTIVE','TRIAL')
               AND EXISTS(SELECT 1 FROM platform_company_modules m
                  WHERE m.company_id=a.company_id AND m.module_code='SITE_PRINCIPAL' AND m.enabled=TRUE)
            """,(rs,i)->new SiteAsset(rs.getString(1),rs.getBytes(2)),assetId);
        if(rows.isEmpty())throw new ResponseStatusException(HttpStatus.NOT_FOUND,"Imagem indisponível.");
        return rows.get(0);
    }
}
