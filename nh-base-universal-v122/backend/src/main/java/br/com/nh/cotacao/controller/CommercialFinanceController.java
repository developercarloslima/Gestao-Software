package br.com.nh.cotacao.controller;

import br.com.nh.cotacao.dto.CommercialFinanceDtos.*;
import br.com.nh.cotacao.security.PortalPrincipal;
import br.com.nh.cotacao.security.PortalRole;
import br.com.nh.cotacao.service.CommercialFinanceService;
import br.com.nh.cotacao.service.PortalUserService;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/commercial")
public class CommercialFinanceController {
    private final CommercialFinanceService service;
    private final PortalUserService users;

    public CommercialFinanceController(CommercialFinanceService service, PortalUserService users) {
        this.service = service;
        this.users = users;
    }

    @GetMapping("/associates")
    public List<AssociateSummary> associates(@RequestParam(defaultValue="") String q) { return service.searchAssociates(q); }

    @PostMapping("/associates")
    public ResponseEntity<AssociateSummary> createAssociate(@Valid @RequestBody CreateAssociateRequest request) {
        return ResponseEntity.status(201).body(service.createAssociate(request));
    }

    @GetMapping("/associates/{id}")
    public AssociateDetail associate(@PathVariable UUID id) { return service.associate(id); }

    @GetMapping("/vehicles")
    public List<VehicleSummary> vehicles(Authentication auth) {
        PortalPrincipal p = principal(auth);
        UUID consultantId = linkedConsultant(p);
        if (p.role() == PortalRole.CONSULTANT && consultantId == null) return List.of();
        return service.vehiclesFor(consultantId, p.role());
    }

    @GetMapping("/vehicles/{id}")
    public VehicleDetail vehicle(@PathVariable UUID id, Authentication auth) {
        PortalPrincipal p = principal(auth);
        service.assertVehicleAccess(id, linkedConsultant(p), p.role());
        return service.vehicle(id);
    }

    @PostMapping("/vehicles/{id}/charges")
    public ResponseEntity<ChargeResponse> createCharge(@PathVariable UUID id, @Valid @RequestBody CreateChargeRequest request, Authentication auth) {
        PortalPrincipal p = principal(auth);
        service.assertVehicleAccess(id, linkedConsultant(p), p.role());
        return ResponseEntity.status(201).body(service.createCharge(id, request));
    }

    @PostMapping("/vehicles/{id}/finance/activate")
    public VehicleDetail activateFinance(@PathVariable UUID id, Authentication auth) {
        PortalPrincipal p = principal(auth);
        service.assertVehicleAccess(id, linkedConsultant(p), p.role());
        return service.retryFinancialActivation(id);
    }

    @PostMapping("/charges/{id}/refresh")
    public ChargeResponse refreshCharge(@PathVariable UUID id, Authentication auth) {
        PortalPrincipal p = principal(auth);
        return service.refreshCharge(id, linkedConsultant(p), p.role());
    }

    @GetMapping("/me/finance")
    public ConsultantFinanceSummary myFinance(Authentication auth) { return service.finance(requireConsultant(principal(auth))); }

    @GetMapping("/me/portfolio")
    public List<AssociateVehicleSummary> myPortfolio(Authentication auth) { return service.consultantPortfolio(requireConsultant(principal(auth))); }

    @PutMapping("/me/finance/pix")
    public ConsultantFinanceSummary configurePix(@Valid @RequestBody UpdatePixRequest request, Authentication auth) {
        return service.configurePix(requireConsultant(principal(auth)), request);
    }

    @PostMapping("/me/finance/withdrawals")
    public WithdrawalResponse requestWithdrawal(@Valid @RequestBody WithdrawalRequest request, Authentication auth) {
        return service.requestWithdrawal(requireConsultant(principal(auth)), request);
    }

    private UUID requireConsultant(PortalPrincipal p) {
        if (p.role() != PortalRole.CONSULTANT) throw new IllegalArgumentException("A área financeira pessoal é exclusiva do consultor.");
        return users.linkedConsultantId(p.username()).orElseThrow(() -> new IllegalArgumentException("Este usuário não está vinculado a um consultor."));
    }
    private UUID linkedConsultant(PortalPrincipal p) { return users.linkedConsultantId(p.username()).orElse(null); }
    private static PortalPrincipal principal(Authentication auth) { return (PortalPrincipal) auth.getPrincipal(); }
}
