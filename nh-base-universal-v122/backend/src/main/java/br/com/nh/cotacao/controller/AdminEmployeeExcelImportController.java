package br.com.nh.cotacao.controller;

import br.com.nh.cotacao.service.EmployeeExcelImportService;
import br.com.nh.cotacao.security.PortalPrincipal;
import org.springframework.security.core.Authentication;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

@RestController
@RequestMapping("/api/admin/employee-excel")
public class AdminEmployeeExcelImportController {
    private final EmployeeExcelImportService service;
    public AdminEmployeeExcelImportController(EmployeeExcelImportService service){this.service=service;}

    @PostMapping(value="/preview",consumes=MediaType.MULTIPART_FORM_DATA_VALUE)
    public EmployeeExcelImportService.Preview preview(@RequestParam("file") MultipartFile file){
        return service.preview(file);
    }
    @PostMapping(value="/confirm",consumes=MediaType.MULTIPART_FORM_DATA_VALUE)
    public EmployeeExcelImportService.Imported confirm(@RequestParam("file") MultipartFile file,
            @RequestParam("confirmed") boolean confirmed,Authentication auth){
        if(!confirmed)throw new IllegalArgumentException("Confirme a criação das contas.");
        PortalPrincipal principal=(PortalPrincipal)auth.getPrincipal();
        return service.confirm(file,principal.username());
    }
}
