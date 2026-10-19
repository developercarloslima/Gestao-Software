package br.com.nh.cotacao.service;

import br.com.nh.cotacao.entity.InspectionRequest;
import br.com.nh.cotacao.entity.Quotation;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import java.math.BigDecimal;
import java.util.Map;
import java.util.UUID;

@Service
public class InspectionFipeSelectionService {
    private final FipeDatasetCatalogService catalog;
    private final JdbcTemplate jdbc;
    public InspectionFipeSelectionService(FipeDatasetCatalogService catalog, JdbcTemplate jdbc) {
        this.catalog=catalog;this.jdbc=jdbc;
    }
    public Map<String,Object> verify(Long priceId, InspectionRequest inspection, Quotation quotation, BigDecimal requestedValue) {
        if (priceId==null) return null;
        Map<String,Object> item=catalog.verifiedPrice(priceId);
        if (quotation==null || !Number.class.isInstance(item.get("modelYear"))
                || (((Number)item.get("modelYear")).intValue()!=inspection.getModelYear()
                   && !(quotation.isZeroKm() && ((Number)item.get("modelYear")).intValue()==32000))
                || !Boolean.valueOf(String.valueOf(item.get("zeroKm"))).equals(quotation.isZeroKm()))
            throw new IllegalArgumentException("A versão FIPE não corresponde ao ano ou condição de 0 km da vistoria.");
        if (inspection.getVehicleType().name().equals("MOTORCYCLE") && !"moto".equals(item.get("vehicleType")))
            throw new IllegalArgumentException("A versão FIPE selecionada não é de motocicleta.");
        if (inspection.getVehicleType().name().equals("FOUR_WHEELS_OR_MORE") && "moto".equals(item.get("vehicleType")))
            throw new IllegalArgumentException("A categoria FIPE não corresponde à vistoria.");
        String search=java.text.Normalizer.normalize(inspection.getVehicleModel(),java.text.Normalizer.Form.NFD)
                .replaceAll("\\p{M}","").toUpperCase(java.util.Locale.ROOT);
        String result=java.text.Normalizer.normalize(item.get("brand")+" "+item.get("model"),java.text.Normalizer.Form.NFD)
                .replaceAll("\\p{M}","").toUpperCase(java.util.Locale.ROOT);
        java.util.Set<String> generic=java.util.Set.of("CARRO","MOTO","MODELO","VEICULO","ELETRICO","ELETRICA");
        var tokens=java.util.Arrays.stream(search.split("[^A-Z0-9]+"))
                .filter(word->word.length()>=2 && !generic.contains(word)).toList();
        boolean sameModel=tokens.stream().filter(word->word.length()>=3).anyMatch(result::contains);
        if (!sameModel && tokens.stream().noneMatch(word->word.length()>=3))
            sameModel=tokens.stream().anyMatch(result::contains);
        if (!sameModel) throw new IllegalArgumentException("O código FIPE não corresponde ao modelo informado na vistoria.");
        BigDecimal expected=BigDecimal.valueOf(((Number)item.get("valueCents")).longValue(),2);
        if (expected.compareTo(requestedValue)!=0) throw new IllegalArgumentException("O preço não corresponde à versão FIPE selecionada. Consulte novamente.");
        return item;
    }
    public void apply(Quotation quotation, Map<String,Object> selection) {
        if (selection==null) { quotation.updateFipeMetadata(null,null,null); return; }
        String reference=String.format("%02d/%d", ((Number)selection.get("referenceMonth")).intValue(), ((Number)selection.get("referenceYear")).intValue());
        quotation.updateFipeMetadata(String.valueOf(selection.get("codeFipe")), reference, "api-fipe-independente");
    }
    public void stage(UUID inspectionId, Long priceId) {
        if (priceId==null) { drop(inspectionId); return; }
        jdbc.update("""
           INSERT INTO inspection_pending_fipe_selection(inspection_id,price_id) VALUES (?,?)
           ON CONFLICT (inspection_id) DO UPDATE SET price_id=EXCLUDED.price_id, created_at=NOW()
           """,inspectionId,priceId);
    }
    public void drop(UUID inspectionId) { jdbc.update("DELETE FROM inspection_pending_fipe_selection WHERE inspection_id=?",inspectionId); }
    public boolean resolve(UUID inspectionId, Quotation quotation, BigDecimal committedFipe) {
        var pending=jdbc.queryForList("SELECT price_id FROM inspection_pending_fipe_selection WHERE inspection_id=?",inspectionId);
        if (pending.isEmpty()) return false;
        try {
            Map<String,Object> price=catalog.historicalPrice(((Number)pending.get(0).get("price_id")).longValue());
            BigDecimal expected=BigDecimal.valueOf(((Number)price.get("valueCents")).longValue(),2);
            if(expected.compareTo(committedFipe)==0) { apply(quotation,price); return true; }
            quotation.updateFipeMetadata(null,null,null);
        } catch (IllegalArgumentException ex) {
            // A referência mudou antes do aceite: preserva o contrato, não atribui código incorreto.
            quotation.updateFipeMetadata(null,null,null);
        } finally { drop(inspectionId); }
        return false;
    }
}
