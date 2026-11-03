package br.com.nh.cotacao.controller;

import br.com.nh.cotacao.service.ReceivablesService;
import br.com.nh.cotacao.service.ReceivablesService.*;
import org.springframework.web.bind.annotation.*;
import java.util.UUID;

/** ADM e Financeiro compartilham a mesma consulta, sob permissões distintas no SecurityConfig. */
@RestController
@RequestMapping({"/api/admin/commercial/receivables", "/api/finance-office/receivables"})
public class ReceivablesController {
    private final ReceivablesService service;
    public ReceivablesController(ReceivablesService service) { this.service = service; }

    @GetMapping
    public VehiclePage list(@RequestParam(defaultValue = "") String q,
                            @RequestParam(defaultValue = "0") int page,
                            @RequestParam(defaultValue = "30") int size) {
        return service.list(q, page, size);
    }

    @GetMapping("/vehicles/{vehicleId}")
    public VehicleBills bills(@PathVariable UUID vehicleId) {
        return service.bills(vehicleId);
    }

    @PostMapping("/vehicles/{vehicleId}/sync")
    public VehicleSync synchronizeVehicle(@PathVariable UUID vehicleId) {
        return service.synchronizeVehicle(vehicleId);
    }

    @PostMapping("/charges/{chargeId}/refresh")
    public VehicleBills refresh(@PathVariable UUID chargeId) {
        return service.refreshAndList(chargeId);
    }

    @PostMapping("/charges/{chargeId}/second-copy")
    public SecondCopyResult secondCopy(@PathVariable UUID chargeId,
                                       @RequestBody(required = false) SecondCopyRequest request) {
        return service.secondCopy(chargeId, request);
    }
}
