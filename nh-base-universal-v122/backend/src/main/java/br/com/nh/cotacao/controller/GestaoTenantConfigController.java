package br.com.nh.cotacao.controller;

import br.com.nh.cotacao.platform.GestaoTenantConfigService;
import org.springframework.web.bind.annotation.*;
import java.util.Map;

@RestController
@RequestMapping("/api/public/tenant-config")
public class GestaoTenantConfigController {
    private final GestaoTenantConfigService service;
    public GestaoTenantConfigController(GestaoTenantConfigService service){this.service=service;}
    @GetMapping public Map<String,Object> config(){return service.config();}
}
