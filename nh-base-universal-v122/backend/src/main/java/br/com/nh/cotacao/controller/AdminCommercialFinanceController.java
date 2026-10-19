package br.com.nh.cotacao.controller;

import br.com.nh.cotacao.dto.CommercialFinanceDtos.*;
import br.com.nh.cotacao.service.CommercialFinanceService;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.*;
import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/admin/commercial")
public class AdminCommercialFinanceController {
    private final CommercialFinanceService service;
    public AdminCommercialFinanceController(CommercialFinanceService service){this.service=service;}

    @GetMapping("/consultants")
    public List<AdminConsultantFinanceRow> consultants(){return service.adminFinanceRows();}

    @GetMapping("/consultants/{id}")
    public ConsultantFinanceSummary consultant(@PathVariable UUID id){return service.finance(id);}

    @PutMapping("/consultants/{id}/commission")
    public ConsultantFinanceSummary commission(@PathVariable UUID id,@Valid @RequestBody UpdateCommissionRequest request){return service.configureCommission(id,request);}
}
