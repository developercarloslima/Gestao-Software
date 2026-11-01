package br.com.nh.cotacao.controller;

import br.com.nh.cotacao.dto.CommercialFinanceDtos.*;
import br.com.nh.cotacao.security.PortalPrincipal;
import br.com.nh.cotacao.service.CommercialFinanceService;
import br.com.nh.cotacao.service.PortalUserService;
import br.com.nh.cotacao.service.SecondCopyService;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

import java.util.UUID;

@RestController
@RequestMapping("/api/associate-portal")
public class AssociatePortalController {
    private final CommercialFinanceService finance;
    private final PortalUserService users;
    private final SecondCopyService secondCopy;

    public AssociatePortalController(CommercialFinanceService finance, PortalUserService users, SecondCopyService secondCopy) {
        this.finance = finance;
        this.users = users;
        this.secondCopy = secondCopy;
    }

    @GetMapping("/second-copy")
    public SecondCopyService.SecondCopyResponse secondCopy(Authentication auth) {
        return secondCopy.list(associateId(auth));
    }

    @GetMapping("/me")
    public AssociatePortalProfile me(Authentication auth) { return finance.associatePortal(associateId(auth)); }

    @PutMapping("/me")
    public AssociatePortalProfile update(@Valid @RequestBody UpdateAssociateSelfRequest request, Authentication auth) {
        return finance.updateAssociateSelf(associateId(auth), request);
    }

    @GetMapping("/vehicles/{vehicleId}")
    public AssociateVehicleDetail vehicle(@PathVariable UUID vehicleId, Authentication auth) {
        return finance.associateVehicle(associateId(auth), vehicleId);
    }

    @PostMapping("/vehicles/{vehicleId}/charges")
    public ResponseEntity<ChargeResponse> generate(@PathVariable UUID vehicleId, @Valid @RequestBody SelfServiceChargeRequest request, Authentication auth) {
        return ResponseEntity.status(201).body(finance.createAssociateSelfServiceCharge(associateId(auth), vehicleId, request));
    }

    @PostMapping("/charges/{chargeId}/refresh")
    public ChargeResponse refresh(@PathVariable UUID chargeId, Authentication auth) {
        return finance.refreshAssociateCharge(associateId(auth), chargeId);
    }

    private UUID associateId(Authentication auth) {
        PortalPrincipal principal = (PortalPrincipal) auth.getPrincipal();
        return users.linkedAssociateId(principal.username())
                .orElseThrow(() -> new IllegalArgumentException("Usuário não vinculado a um associado."));
    }
}
