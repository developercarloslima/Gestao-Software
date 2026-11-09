package br.com.nh.cotacao.controller;

import br.com.nh.cotacao.dto.CommercialFinanceDtos.*;
import br.com.nh.cotacao.service.CommercialFinanceService;
import br.com.nh.cotacao.service.CommercialSalesBiService;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.*;
import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/admin/commercial")
public class AdminCommercialFinanceController {
    private final CommercialFinanceService service;
    private final CommercialSalesBiService salesBi;
    public AdminCommercialFinanceController(CommercialFinanceService service, CommercialSalesBiService salesBi){
        this.service=service;this.salesBi=salesBi;
    }

    @GetMapping("/sales-bi")
    public CommercialSalesBiService.Report salesBi(
            @RequestParam(required=false) java.time.LocalDate from,
            @RequestParam(required=false) java.time.LocalDate to){
        return salesBi.report(from,to);
    }

    @GetMapping("/consultants")
    public List<AdminConsultantFinanceRow> consultants(){return service.adminFinanceRows();}

    @GetMapping("/associates")
    public List<CommercialFinanceService.AdminAssociateRow> associates(){return service.adminAssociates();}

    @GetMapping("/consultants/{id}")
    public ConsultantFinanceSummary consultant(@PathVariable UUID id){return service.finance(id);}

    @PutMapping("/consultants/{id}/commission")
    public ConsultantFinanceSummary commission(@PathVariable UUID id,@Valid @RequestBody UpdateCommissionRequest request){return service.configureCommission(id,request);}
}
