package br.com.nh.cotacao.controller;

import br.com.nh.cotacao.security.PortalPrincipal;
import br.com.nh.cotacao.security.PortalRole;
import br.com.nh.cotacao.service.FinanceOfficeService;
import br.com.nh.cotacao.service.PortalUserService;
import org.springframework.http.*;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.time.LocalDate;
import java.util.*;

@RestController
@RequestMapping("/api/commercial/me/documents")
public class ConsultantFinanceDocumentsController {
    private final FinanceOfficeService finance;
    private final PortalUserService users;
    public ConsultantFinanceDocumentsController(FinanceOfficeService finance, PortalUserService users){this.finance=finance;this.users=users;}

    @GetMapping
    public List<Map<String,Object>> list(Authentication auth){UUID id=consultant(auth);List<Map<String,Object>> result=new ArrayList<>();result.addAll(finance.documents("CONSULTANT_INVOICE",id));result.addAll(finance.documents("CONSULTANT_PAYMENT_RECEIPT",id));result.sort((a,b)->String.valueOf(b.get("createdAt")).compareTo(String.valueOf(a.get("createdAt"))));return result;}

    @PostMapping(value="/invoice",consumes=MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<Map<String,Object>> invoice(@RequestParam(required=false) LocalDate referenceMonth,@RequestParam(required=false)String notes,@RequestPart("file") MultipartFile file,Authentication auth){UUID id=consultant(auth);return ResponseEntity.status(201).body(finance.uploadDocument("CONSULTANT_INVOICE",id,null,referenceMonth,"Nota fiscal do consultor",notes,file,user(auth)));}

    @GetMapping("/{documentId}/file")
    public ResponseEntity<byte[]> file(@PathVariable UUID documentId,Authentication auth){UUID id=consultant(auth);var f=finance.fileForConsultant(documentId,id);return ResponseEntity.ok().contentType(MediaType.parseMediaType(f.contentType())).header(HttpHeaders.CONTENT_DISPOSITION,"attachment; filename=\""+f.fileName().replace("\"","")+"\"").body(f.bytes());}

    private UUID consultant(Authentication auth){PortalPrincipal p=(PortalPrincipal)auth.getPrincipal();if(p.role()!=PortalRole.CONSULTANT)throw new IllegalArgumentException("Área exclusiva do consultor.");return users.linkedConsultantId(p.username()).orElseThrow(()->new IllegalArgumentException("Usuário sem vínculo com consultor."));}
    private String user(Authentication auth){return ((PortalPrincipal)auth.getPrincipal()).username();}
}
