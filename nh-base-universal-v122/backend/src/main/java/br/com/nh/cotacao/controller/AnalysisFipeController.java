package br.com.nh.cotacao.controller;

import br.com.nh.cotacao.service.FipeDatasetCatalogService;
import org.springframework.web.bind.annotation.*;
import java.util.*;

@RestController
@RequestMapping("/api/analysis/fipe")
public class AnalysisFipeController {
    private final FipeDatasetCatalogService catalog;
    public AnalysisFipeController(FipeDatasetCatalogService catalog) { this.catalog=catalog; }
    @GetMapping("/status")
    public Map<String,Boolean> status() {
        return Map.of("available", catalog.available());
    }
    @GetMapping("/types")
    public List<String> types() { return catalog.types(); }
    @GetMapping("/brands")
    public List<String> brands(@RequestParam(required=false) String type,
            @RequestParam(required=false) String search) {return catalog.brands(type,search);}
    @GetMapping("/models")
    public List<String> models(@RequestParam String brand,
            @RequestParam(required=false) String type,
            @RequestParam(required=false) String search) {return catalog.models(type,brand,search);}
    @GetMapping("/versions")
    public List<Map<String,Object>> versions(@RequestParam String model, @RequestParam Integer year,
            @RequestParam(required=false) Boolean zeroKm, @RequestParam(defaultValue="") String category,
            @RequestParam(required=false) String brand, @RequestParam(required=false) String type) {
        return brand == null || brand.isBlank() ? catalog.versions(model,year,zeroKm,category,70)
                : catalog.versionsByBrand(brand,model,year,zeroKm,category,type,1000);
    }
}
