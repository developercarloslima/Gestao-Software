package br.com.nh.cotacao.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.*;

/** Adapter for the independent FIPE service. All catalog data and synchronization live there.
 * NH persists ONLY consulted price selections to retain immutable contract evidence and the
 * foreign key from inspection_pending_fipe_selection (migration V96). Never loads FIPE dumps. */
@Service
public class FipeDatasetCatalogService {
    private final JdbcTemplate db;
    private final ObjectMapper mapper;
    private final String base;
    private final String apiKey;
    private final HttpClient client=HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3)).build();

    public FipeDatasetCatalogService(JdbcTemplate db,ObjectMapper mapper,
            @Value("${fipe.api.base-url:http://fipe-api:8000}") String base,
            @Value("${fipe.api.key:}") String apiKey) {
        this.db=db;this.mapper=mapper;this.base=base.replaceAll("/+$","");this.apiKey=apiKey;
    }
    private static String q(Object v){return URLEncoder.encode(String.valueOf(v),StandardCharsets.UTF_8);}
    private JsonNode get(String route) {
        try {
            HttpRequest.Builder builder=HttpRequest.newBuilder(URI.create(base+route))
                .timeout(Duration.ofSeconds(12)).GET().header("Accept","application/json");
            if(!apiKey.isBlank())builder.header("X-API-Key",apiKey);
            HttpResponse<String> response=client.send(builder.build(),HttpResponse.BodyHandlers.ofString());
            if(response.statusCode()!=200)throw new IllegalStateException("API FIPE indisponível (HTTP "+response.statusCode()+")");
            return mapper.readTree(response.body());
        }catch(Exception ex){
            if(ex instanceof InterruptedException)Thread.currentThread().interrupt();
            throw new IllegalStateException("Não foi possível consultar a API FIPE independente: "+ex.getMessage(),ex);
        }
    }
    public boolean available(){try{return get("/health").path("base_carregada").asBoolean(false);}catch(Exception ex){return false;}}
    public Long activeImportId(){
        List<Long> rows=db.queryForList("SELECT id FROM fipe_dataset_imports WHERE status='COMPLETED' AND release_tag LIKE 'independent-api-%' ORDER BY reference_year DESC,reference_month DESC,id DESC LIMIT 1",Long.class);
        return rows.isEmpty()?null:rows.get(0);
    }
    public Map<String,Object> status(){return Map.of("available",available(),"source","api-fipe-independente");}

    // O NH faz a mediação da API privada: nenhuma chave FIPE é exposta ao navegador.
    private List<String> catalogNames(String endpoint, String field, String type, String brand, String search) {
        if (type != null && type.length() > 80 || brand != null && brand.length() > 120
                || search != null && search.length() > 120) throw new IllegalArgumentException("Filtro FIPE muito longo.");
        StringBuilder url = new StringBuilder(endpoint);
        List<String> args = new ArrayList<>();
        if (type != null && !type.isBlank()) args.add("tipo="+q(type));
        if (brand != null && !brand.isBlank()) args.add("marca="+q(brand));
        if (search != null && !search.isBlank()) args.add("busca="+q(search));
        // Consulta completa, em ordem alfabética, até o limite documentado da API FIPE.
        if (!endpoint.equals("/tipos")) args.add("limite="+(endpoint.equals("/marcas") ? 1000 : 2000));
        if (!args.isEmpty()) url.append('?').append(String.join("&", args));
        JsonNode data = get(url.toString());
        List<String> names = new ArrayList<>();
        for (JsonNode n : data.path(field)) if (n.isTextual()) names.add(n.asText());
        names.sort(java.text.Collator.getInstance(new Locale("pt", "BR")));
        return names;
    }
    public List<String> types(){return catalogNames("/tipos", "tipos", null, null, null);}
    public List<String> brands(String type, String search){return catalogNames("/marcas", "marcas", type, null, search);}
    public List<String> models(String type, String brand, String search){
        if (brand == null || brand.isBlank()) return List.of();
        return catalogNames("/modelos", "modelos", type, brand, search);
    }
    // Pesquisa exata por marca, filtrada por tipo/ano, sem depender de interpretar "Honda CG".
    public List<Map<String,Object>> versionsByBrand(String brand, String model, Integer year,
            Boolean zeroKm, String category, String type, int requestedLimit) {
        if (brand == null || brand.isBlank() || brand.length() > 120 || model == null
                || model.isBlank() || model.length() > 120 || year == null || year < 1950 || year > 2100)
            return List.of();
        if (type != null && type.length() > 80) throw new IllegalArgumentException("Tipo FIPE inválido.");
        int max = Math.min(Math.max(requestedLimit, 1), 1000);
        String url = "/consulta?marca="+q(brand)+"&modelo="+q(model)+"&ano="+(Boolean.TRUE.equals(zeroKm) ? 32000 : year)
                +"&limite="+max;
        if (type != null && !type.isBlank()) url += "&tipo="+q(type);
        if (zeroKm != null) url += "&zero_km="+zeroKm;
        return mapVehicleRows(get(url), year, zeroKm, category);
    }
    private List<Map<String,Object>> mapVehicleRows(JsonNode payload, Integer year,
            Boolean zeroKm, String category) {
        JsonNode reference=payload.path("referencia");
        int refYear=reference.path("ano").asInt(0),refMonth=reference.path("mes").asInt(0);
        if(refYear<1990 || refMonth<1 || refMonth>12)throw new IllegalStateException("Referência da API FIPE inválida.");
        List<Map<String,Object>> result=new ArrayList<>();
        for(JsonNode row:payload.path("veiculos")){
            int my=row.path("ano_modelo").asInt(0);boolean isZero=row.path("zero_km").asBoolean(false);
            String rawType=row.path("tipo_veiculo").asText("");
            String kind=canonicalVehicleType(rawType);
            if(my!=year && !(isZero && my==32000))continue;
            if(zeroKm != null && isZero != zeroKm.booleanValue())continue;
            if("FOUR_WHEELS_OR_MORE".equalsIgnoreCase(category) && kind.equalsIgnoreCase("moto"))continue;
            if("MOTORCYCLE".equalsIgnoreCase(category) && !kind.equalsIgnoreCase("moto"))continue;
            long cents=row.path("valor_centavos").asLong(0);
            if(cents<=0)continue;
            long id=cacheSelection(row,refYear,refMonth);
            Map<String,Object> item=new LinkedHashMap<>();
            item.put("id",id);item.put("vehicleType",kind);item.put("codeFipe",row.path("codigo_fipe").asText());
            item.put("model",row.path("nome_modelo").asText());item.put("brand",row.path("nome_marca").asText());
            item.put("fuel",row.path("nome_combustivel").asText());item.put("modelYear",my);item.put("zeroKm",isZero);
            item.put("valueCents",cents);item.put("referenceYear",refYear);item.put("referenceMonth",refMonth);
            result.add(item);
        }
        result.sort(Comparator.comparing(row -> (String)row.get("model"),String.CASE_INSENSITIVE_ORDER));
        return result;
    }
    public List<Map<String,Object>> versions(String model,Integer year,Boolean zeroKm,String category,int requestedLimit){
        if(year==null || year<1900 || year>2100 || model==null || model.isBlank() || model.length()>120)return List.of();
        String type="";
        if("MOTORCYCLE".equalsIgnoreCase(category))type="Motos";
        // Four-wheel category includes trucks as well; category is validated after the API response.
        String url="/buscar-modelos?modelo="+q(model)+"&ano="+year+"&limite="+Math.max(1,Math.min(requestedLimit,70));
        if(!type.isBlank())url+="&tipo="+q(type);
        if(zeroKm!=null)url+="&zero_km="+zeroKm;
        return mapVehicleRows(get(url),year,zeroKm,category);
    }
    private static String canonicalVehicleType(String raw) {
        String value=java.text.Normalizer.normalize(raw,java.text.Normalizer.Form.NFD)
            .replaceAll("\\p{M}","").toLowerCase(Locale.ROOT);
        if(value.startsWith("moto"))return "moto";
        if(value.startsWith("carr"))return "carro";
        if(value.startsWith("caminh"))return "caminhao";
        return value;
    }
    private synchronized long cacheSelection(JsonNode row,int year,int month){
        String tag=String.format("independent-api-%04d-%02d",year,month);
        db.update("""
            INSERT INTO fipe_dataset_imports(release_tag,reference_year,reference_month,status,source_url,finished_at)
            VALUES(?,?,?,'COMPLETED','internal:fipe-api',NOW())
            ON CONFLICT (release_tag) WHERE status='COMPLETED' DO NOTHING
            """,tag,year,month);
        Long importId=db.queryForObject("SELECT id FROM fipe_dataset_imports WHERE release_tag=? AND status='COMPLETED'",Long.class,tag);
        String type=canonicalVehicleType(row.path("tipo_veiculo").asText()),code=row.path("codigo_fipe").asText();
        int modelYear=row.path("ano_modelo").asInt();boolean zero=row.path("zero_km").asBoolean();
        String fuelCode=row.path("sigla_combustivel").asText("");
        db.update("""
            INSERT INTO fipe_dataset_prices(import_id,vehicle_type,fipe_code,model_name,brand_name,
                fuel_name,fuel_code,model_year,zero_km,value_cents)
            VALUES(?,?,?,?,?,?,?,?,?,?) ON CONFLICT (import_id,vehicle_type,fipe_code,model_year,zero_km,fuel_code) DO NOTHING
            """,importId,type,code,row.path("nome_modelo").asText(),row.path("nome_marca").asText(),
            row.path("nome_combustivel").asText(""),fuelCode,modelYear,zero,row.path("valor_centavos").asLong());
        return db.queryForObject("""
            SELECT id FROM fipe_dataset_prices WHERE import_id=? AND vehicle_type=? AND fipe_code=?
                AND model_year=? AND zero_km=? AND fuel_code=?
            """,Long.class,importId,type,code,modelYear,zero,fuelCode);
    }
    public List<Map<String,Object>> search(String q,String category,Integer year,String brand,int limit){
        if(year==null)return List.of();
        return versions((brand==null?"":brand+" ")+(q==null?"":q),year,null,category,limit);
    }
    public Map<String,Object> searchForQuotation(String query){
        return Map.of("source","api-fipe-independente","items",List.of());
    }
    public Map<String,Object> verifiedPrice(long priceId){
        List<Map<String,Object>> rows=db.queryForList("""
            SELECT p.id AS "id",p.fipe_code AS "codeFipe",p.model_name AS "model",p.brand_name AS "brand",
                p.model_year AS "modelYear",p.zero_km AS "zeroKm",p.vehicle_type AS "vehicleType",
                p.value_cents AS "valueCents",i.reference_month AS "referenceMonth",i.reference_year AS "referenceYear"
            FROM fipe_dataset_prices p JOIN fipe_dataset_imports i ON i.id=p.import_id
            WHERE p.id=? AND i.status='COMPLETED' AND i.release_tag LIKE 'independent-api-%'
            """,priceId);
        if(rows.isEmpty())throw new IllegalArgumentException("Versão FIPE indisponível. Consulte novamente.");
        Map<String,Object> item=rows.get(0);
        // A FIPE da tela precisa ser referência vigente; contratos já aceitos mantêm sua cópia histórica.
        JsonNode ref=get("/metadados").path("referencia_mais_recente");
        if(((Number)item.get("referenceMonth")).intValue()!=ref.path("mes").asInt() ||
           ((Number)item.get("referenceYear")).intValue()!=ref.path("ano").asInt())
            throw new IllegalArgumentException("Referência FIPE mudou. Selecione novamente a versão atual.");
        return item;
    }
    public Map<String,Object> historicalPrice(long priceId){
        List<Map<String,Object>> rows=db.queryForList("""
            SELECT p.id AS "id",p.fipe_code AS "codeFipe",p.model_name AS "model",p.brand_name AS "brand",
                p.model_year AS "modelYear",p.zero_km AS "zeroKm",p.vehicle_type AS "vehicleType",
                p.value_cents AS "valueCents",i.reference_month AS "referenceMonth",i.reference_year AS "referenceYear"
            FROM fipe_dataset_prices p JOIN fipe_dataset_imports i ON i.id=p.import_id WHERE p.id=? AND i.status='COMPLETED'
            """,priceId);
        if(rows.isEmpty())throw new IllegalArgumentException("Versão FIPE histórica indisponível.");
        return rows.get(0);
    }
}
