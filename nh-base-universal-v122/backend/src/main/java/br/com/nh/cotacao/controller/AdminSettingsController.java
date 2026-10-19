package br.com.nh.cotacao.controller;

import br.com.nh.cotacao.dto.AdminDtos.CommunicationSettingsResponse;
import br.com.nh.cotacao.dto.AdminDtos.UpdateCommunicationSettingsRequest;
import br.com.nh.cotacao.dto.AdminDtos.PublicQuoteAssignmentSettingsResponse;
import br.com.nh.cotacao.dto.AdminDtos.UpdatePublicQuoteAssignmentSettingsRequest;
import br.com.nh.cotacao.dto.AdminDtos.ThirdPartyRuleSettingsResponse;
import br.com.nh.cotacao.dto.AdminDtos.UpdateThirdPartyRulesRequest;
import br.com.nh.cotacao.security.PortalPrincipal;
import br.com.nh.cotacao.service.CommunicationSettingsService;
import br.com.nh.cotacao.service.PublicQuoteAssignmentSettingsService;
import br.com.nh.cotacao.service.ThirdPartyRuleSettingsService;
import jakarta.validation.Valid;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/admin/settings")
public class AdminSettingsController {
    private final CommunicationSettingsService service;
    private final PublicQuoteAssignmentSettingsService publicQuoteAssignmentSettings;
    private final ThirdPartyRuleSettingsService thirdPartyRuleSettings;

    public AdminSettingsController(
            CommunicationSettingsService service,
            PublicQuoteAssignmentSettingsService publicQuoteAssignmentSettings,
            ThirdPartyRuleSettingsService thirdPartyRuleSettings
    ) {
        this.service = service;
        this.publicQuoteAssignmentSettings = publicQuoteAssignmentSettings;
        this.thirdPartyRuleSettings = thirdPartyRuleSettings;
    }

    @GetMapping("/communications")
    public CommunicationSettingsResponse get() { return service.get(); }

    @PutMapping("/communications")
    public CommunicationSettingsResponse update(
            @Valid @RequestBody UpdateCommunicationSettingsRequest request,
            Authentication auth
    ) {
        return service.update(request, ((PortalPrincipal) auth.getPrincipal()).username());
    }

    @GetMapping("/public-quote-assignment")
    public PublicQuoteAssignmentSettingsResponse publicQuoteAssignment() {
        return publicQuoteAssignmentSettings.get();
    }

    @PutMapping("/public-quote-assignment")
    public PublicQuoteAssignmentSettingsResponse updatePublicQuoteAssignment(
            @Valid @RequestBody UpdatePublicQuoteAssignmentSettingsRequest request,
            Authentication auth
    ) {
        return publicQuoteAssignmentSettings.update(
                request.enabled(),
                ((PortalPrincipal) auth.getPrincipal()).username()
        );
    }

    @GetMapping("/third-party-rules")
    public List<ThirdPartyRuleSettingsResponse> thirdPartyRules() {
        return thirdPartyRuleSettings.getAll();
    }

    @PutMapping("/third-party-rules")
    public List<ThirdPartyRuleSettingsResponse> updateThirdPartyRules(
            @Valid @RequestBody UpdateThirdPartyRulesRequest request,
            Authentication auth
    ) {
        return thirdPartyRuleSettings.update(
                request,
                ((PortalPrincipal) auth.getPrincipal()).username()
        );
    }
}
