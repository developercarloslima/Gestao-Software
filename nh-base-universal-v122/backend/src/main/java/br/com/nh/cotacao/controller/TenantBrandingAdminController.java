package br.com.nh.cotacao.controller;

import br.com.nh.cotacao.platform.GestaoTenantConfigService;
import org.springframework.web.bind.annotation.*;
import java.util.Map;

@RestController
@RequestMapping("/api/admin/branding")
public class TenantBrandingAdminController {
    private final GestaoTenantConfigService config;

    public TenantBrandingAdminController(GestaoTenantConfigService config) {
        this.config = config;
    }

    @GetMapping
    public Map<String,Object> current() {
        return config.config();
    }

    @PutMapping
    public Map<String,Object> save(@RequestBody Map<String,Object> fields) {
        return config.saveBranding(fields);
    }
}
