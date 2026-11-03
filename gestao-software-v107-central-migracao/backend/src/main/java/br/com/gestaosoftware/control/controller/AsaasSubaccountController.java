package br.com.gestaosoftware.control.controller;

import br.com.gestaosoftware.control.security.PlatformPrincipal;
import br.com.gestaosoftware.control.service.AsaasSubaccountService;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

@RestController
@RequestMapping("/api/platform/companies/{companyId}/asaas-subaccount")
public class AsaasSubaccountController {
    private static final Set<String> OWNERS = Set.of("carlos.lima", "pedro.henrique");
    private final AsaasSubaccountService service;

    public AsaasSubaccountController(AsaasSubaccountService service) {
        this.service = service;
    }

    private String owner(Authentication authentication) {
        if (authentication == null || !(authentication.getPrincipal() instanceof PlatformPrincipal principal))
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Apenas os sócios podem configurar subcontas.");
        String username = principal.username() == null ? "" : principal.username().trim().toLowerCase(Locale.ROOT);
        if (!OWNERS.contains(username))
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Apenas os sócios podem configurar subcontas.");
        return username;
    }

    @GetMapping
    public Map<String, Object> status(@PathVariable UUID companyId, Authentication authentication) {
        owner(authentication);
        return service.status(companyId);
    }

    @PostMapping
    public Map<String, Object> create(@PathVariable UUID companyId,
                                      @RequestBody AsaasSubaccountService.Registration input,
                                      Authentication authentication) {
        return service.create(companyId, input, owner(authentication));
    }
}
