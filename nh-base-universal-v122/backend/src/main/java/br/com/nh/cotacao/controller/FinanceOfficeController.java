package br.com.nh.cotacao.controller;

import br.com.nh.cotacao.security.PortalPrincipal;
import br.com.nh.cotacao.service.FinanceOfficeService;
import org.springframework.http.*;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.*;

@RestController
@RequestMapping("/api/finance-office")
public class FinanceOfficeController {
    private final FinanceOfficeService service;
    public FinanceOfficeController(FinanceOfficeService service){this.service=service;}

    @GetMapping("/dashboard") public Map<String,Object> dashboard(@RequestParam(defaultValue="month") String range){return service.dashboard(range);}
    @GetMapping("/ledger") public List<Map<String,Object>> ledger(@RequestParam(required=false)String type,@RequestParam(required=false)String status,@RequestParam(required=false)LocalDate from,@RequestParam(required=false)LocalDate to){return service.ledger(type,status,from,to);}
    @PostMapping("/ledger") public ResponseEntity<Map<String,Object>> createLedger(@RequestBody Map<String,Object>r,Authentication a){return ResponseEntity.status(201).body(service.createLedger(r,user(a)));}
    @PatchMapping("/ledger/{id}/status") public Map<String,Object> ledgerStatus(@PathVariable UUID id,@RequestBody Map<String,Object>r){return service.setLedgerStatus(id,String.valueOf(r.get("status")),r.get("effectiveDate")==null?null:LocalDate.parse(String.valueOf(r.get("effectiveDate"))));}
    @PatchMapping("/ledger/{id}/reconcile") public Map<String,Object> reconcile(@PathVariable UUID id,@RequestBody Map<String,Object>r){return service.reconcile(id,r.get("note")==null?null:String.valueOf(r.get("note")));}

    @GetMapping("/documents") public List<Map<String,Object>> docs(@RequestParam(required=false)String type,@RequestParam(required=false)UUID consultantId){return service.documents(type,consultantId);}
    @PostMapping(value="/documents",consumes=MediaType.MULTIPART_FORM_DATA_VALUE) public ResponseEntity<Map<String,Object>> upload(@RequestParam String type,@RequestParam(required=false)UUID consultantId,@RequestParam(required=false)UUID collaboratorId,@RequestParam(required=false)String collaboratorName,@RequestParam(required=false)String collaboratorReference,@RequestParam(required=false)UUID withdrawalId,@RequestParam(required=false)LocalDate referenceMonth,@RequestParam(required=false)String title,@RequestParam(required=false)String notes,@RequestPart("file") MultipartFile file,Authentication a){return ResponseEntity.status(201).body(service.uploadDocument(type,consultantId,collaboratorId,collaboratorName,collaboratorReference,withdrawalId,referenceMonth,title,notes,file,user(a)));}
    @GetMapping("/documents/{id}/file") public ResponseEntity<byte[]> file(@PathVariable UUID id){var f=service.file(id);return ResponseEntity.ok().contentType(MediaType.parseMediaType(f.contentType())).header(HttpHeaders.CONTENT_DISPOSITION,"attachment; filename=\""+f.fileName().replace("\"","")+"\"").body(f.bytes());}

    @GetMapping("/withdrawals") public List<Map<String,Object>> withdrawals(){return service.withdrawalQueue();}
    @PostMapping("/withdrawals/{id}/process") public Map<String,Object> processWithdrawal(@PathVariable UUID id,@RequestBody(required=false) Map<String,Object>r){return service.processWithdrawal(id,r==null||r.get("note")==null?null:String.valueOf(r.get("note")));}
    @PatchMapping("/withdrawals/{id}") public Map<String,Object> withdrawal(@PathVariable UUID id,@RequestBody Map<String,Object>r){return service.updateWithdrawal(id,String.valueOf(r.get("status")),r.get("note")==null?null:String.valueOf(r.get("note")));}
    @GetMapping("/consultants") public List<Map<String,Object>> consultants(){return service.consultantFinancials();}
    @GetMapping("/commission-rules") public List<Map<String,Object>> rules(){return service.commissionRules();}
    @GetMapping("/career-levels") public List<Map<String,Object>> careerLevels(){return service.careerLevels();}
    @GetMapping("/base-prizes") public List<Map<String,Object>> basePrizes(){return service.basePrizes();}
    @PatchMapping("/commission-rules/{key}") public void rule(@PathVariable String key,@RequestBody Map<String,Object>r,Authentication a){service.updateCommissionRule(key,new BigDecimal(String.valueOf(r.get("value"))),!Boolean.FALSE.equals(r.get("enabled")),user(a));}
    @PutMapping("/consultants/{id}/referrer") public void referrer(@PathVariable UUID id,@RequestBody Map<String,Object>r){Object v=r.get("referrerId");service.setReferrer(id,v==null||String.valueOf(v).isBlank()?null:UUID.fromString(String.valueOf(v)));}

    @GetMapping("/suppliers") public List<Map<String,Object>> suppliers(){return service.suppliers();}
    @PostMapping("/suppliers") public ResponseEntity<Map<String,Object>> supplier(@RequestBody Map<String,Object>r){return ResponseEntity.status(201).body(service.createSupplier(r));}
    @GetMapping("/cards") public List<Map<String,Object>> cards(){return service.cards();}
    @PostMapping("/cards") public ResponseEntity<Map<String,Object>> card(@RequestBody Map<String,Object>r){return ResponseEntity.status(201).body(service.createCard(r));}
    @GetMapping("/budgets") public List<Map<String,Object>> budgets(){return service.budgets();}
    @PostMapping("/budgets") public ResponseEntity<Map<String,Object>> budget(@RequestBody Map<String,Object>r,Authentication a){return ResponseEntity.status(201).body(service.createBudget(r,user(a)));}
    @GetMapping("/settings") public Map<String,Object> settings(){return service.settings();}
    @PatchMapping("/settings/{key}") public Map<String,Object> setting(@PathVariable String key,@RequestBody Map<String,Object>r,Authentication a){return service.updateSetting(key,new BigDecimal(String.valueOf(r.get("value"))),user(a));}

    private static String user(Authentication a){return ((PortalPrincipal)a.getPrincipal()).username();}
}
