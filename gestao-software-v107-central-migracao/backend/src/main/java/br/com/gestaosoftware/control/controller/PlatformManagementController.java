package br.com.gestaosoftware.control.controller;

import br.com.gestaosoftware.control.service.PlatformManagementService;
import br.com.gestaosoftware.control.security.PlatformPrincipal;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.math.BigDecimal;
import java.time.YearMonth;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

@RestController
@RequestMapping("/api/platform")
public class PlatformManagementController {
    private final PlatformManagementService service;

    public PlatformManagementController(PlatformManagementService service) {
        this.service = service;
    }

    private static final Set<String> PRICING_CALCULATOR_OWNERS = Set.of("carlos.lima", "pedro.henrique");

    @GetMapping("/modules")
    public List<Map<String, Object>> modules() { return service.modules(); }

    @GetMapping("/pricing-calculator")
    public Map<String, Object> pricingCalculator(Authentication authentication) {
        PlatformPrincipal principal = (PlatformPrincipal) authentication.getPrincipal();
        String username = principal.username() == null ? "" : principal.username().trim().toLowerCase();
        if (!PRICING_CALCULATOR_OWNERS.contains(username)) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Calculadora comercial restrita aos sócios autorizados.");
        }
        return Map.of(
                "plans", List.of(
                        Map.of("code", "START", "name", "Start", "maxUsers", 50, "maxSales", 1500, "listPrice", 1690, "awsCost", 350, "capacity", "Até 50 usuários e 1.500 novas vendas/mês."),
                        Map.of("code", "BUSINESS", "name", "Business", "maxUsers", 250, "maxSales", 7500, "listPrice", 3900, "awsCost", 900, "capacity", "Até 250 usuários e 7.500 novas vendas/mês."),
                        Map.of("code", "PRO", "name", "Pro", "maxUsers", 1000, "maxSales", 30000, "listPrice", 8900, "awsCost", 2500, "capacity", "Até 1.000 usuários e 30.000 novas vendas/mês."),
                        Map.of("code", "SCALE", "name", "Scale", "maxUsers", 5000, "maxSales", 150000, "listPrice", 29900, "awsCost", 10000, "capacity", "Até 5.000 usuários e 150.000 novas vendas/mês."),
                        Map.of("code", "ENTERPRISE", "name", "Enterprise", "maxUsers", 20000, "maxSales", 600000, "listPrice", 99900, "awsCost", 35000, "capacity", "Até 20.000 usuários e 600.000 novas vendas/mês."),
                        Map.of("code", "DEDICATED", "name", "Dedicated Cloud", "maxUsers", 100000, "maxSales", 3000000, "listPrice", 299000, "awsCost", 135000, "capacity", "Até 100.000 usuários e 3 milhões de novas vendas/mês."),
                        Map.of("code", "HYBRID", "name", "Hybrid Enterprise", "maxUsers", 10000000, "maxSales", 100000000, "listPrice", 449000, "awsCost", 170000, "capacity", "Acima de 100.000 usuários ou 3 milhões de vendas/mês · dimensionamento personalizado.")
                ),
                "terms", Map.of(
                        "MONTHLY", Map.of("discount", 0.0, "months", 1, "label", "mensal"),
                        "SEMIANNUAL", Map.of("discount", 0.08, "months", 6, "label", "semestral"),
                        "ANNUAL", Map.of("discount", 0.15, "months", 12, "label", "anual")
                ),
                "moduleBaseFactor", 0.40,
                "moduleIncrement", 0.15,
                "defaultAsaasPaymentFee", 2.00,
                "defaultAsaasWithdrawalFee", 2.00,
                "asaasFeeMode", "PASSTHROUGH"
        );
    }

    @GetMapping("/dashboard")
    public Map<String, Object> dashboard(
            @RequestParam(required = false) @DateTimeFormat(pattern = "yyyy-MM") YearMonth month
    ) { return service.dashboard(month); }

    @GetMapping("/companies")
    public List<Map<String, Object>> companies() { return service.listCompanies(); }

    @GetMapping("/companies/{id}")
    public Map<String, Object> company(@PathVariable UUID id) { return service.getCompany(id); }

    @PostMapping("/companies")
    public Map<String, Object> createCompany(@RequestBody PlatformManagementService.CompanyInput input) {
        return service.createCompany(input);
    }

    @PutMapping("/companies/{id}")
    public Map<String, Object> updateCompany(@PathVariable UUID id, @RequestBody PlatformManagementService.CompanyInput input) {
        return service.updateCompany(id, input);
    }

    @PostMapping("/companies/{id}/deactivate")
    public Map<String, Object> deactivateCompany(@PathVariable UUID id) {
        return service.deactivateCompany(id);
    }

    @DeleteMapping("/companies/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void deleteCompany(@PathVariable UUID id) {
        service.deleteCompany(id);
    }

    @PostMapping(value="/companies/{id}/files/{purpose}", consumes=MediaType.MULTIPART_FORM_DATA_VALUE)
    public Map<String, Object> uploadCompanyFile(@PathVariable UUID id, @PathVariable String purpose, @RequestPart("file") MultipartFile file) throws Exception {
        return service.saveCompanyFile(id, purpose, file.getOriginalFilename(), file.getContentType(), file.getBytes());
    }

    @GetMapping("/files/{fileId}")
    public ResponseEntity<byte[]> companyFile(@PathVariable UUID fileId) {
        PlatformManagementService.FilePayload file = service.getCompanyFile(fileId, false);
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, "inline; filename=\"" + file.originalName().replace("\"", "") + "\"")
                .contentType(MediaType.parseMediaType(file.contentType()))
                .contentLength(file.fileSize())
                .body(file.content());
    }

    @PostMapping("/companies/{companyId}/contracts/{contractId}/sign-management")
    public Map<String, Object> signManagementContract(@PathVariable UUID companyId, @PathVariable UUID contractId, Authentication authentication) {
        PlatformPrincipal principal = (PlatformPrincipal) authentication.getPrincipal();
        return service.signManagementContract(companyId, contractId, principal.username(), principal.username());
    }

    @PostMapping("/companies/{id}/invoices")
    public Map<String, Object> generateInvoice(@PathVariable UUID id, @RequestBody PlatformManagementService.InvoiceInput input) {
        return service.generateInvoice(id, input);
    }

    @PatchMapping("/invoices/{id}")
    public Map<String, Object> updateInvoice(@PathVariable UUID id, @RequestBody PlatformManagementService.InvoiceUpdate input) {
        return service.updateInvoice(id, input);
    }

    @GetMapping("/expenses")
    public List<Map<String, Object>> expenses(
            @RequestParam(required = false) @DateTimeFormat(pattern = "yyyy-MM") YearMonth month
    ) { return service.listExpenses(month); }

    @PostMapping("/expenses")
    public Map<String, Object> createExpense(@RequestBody PlatformManagementService.ExpenseInput input) {
        return service.saveExpense(null, input);
    }

    @PutMapping("/expenses/{id}")
    public Map<String, Object> updateExpense(@PathVariable UUID id, @RequestBody PlatformManagementService.ExpenseInput input) {
        return service.saveExpense(id, input);
    }

    @DeleteMapping("/expenses/{id}")
    public void deleteExpense(@PathVariable UUID id) { service.deleteExpense(id); }

    @GetMapping("/partners")
    public List<Map<String, Object>> partners() { return service.partners(); }

    @PutMapping("/partners/shares")
    public List<Map<String, Object>> partnerShares(@RequestBody PartnerSharesRequest request) {
        return service.updatePartnerShares(request.pedroPercent(), request.carlosPercent());
    }

    @GetMapping("/vps")
    public List<Map<String, Object>> vps() { return service.listVps(); }

    @PostMapping("/vps")
    public Map<String, Object> createVps(@RequestBody PlatformManagementService.VpsInput input) {
        return service.saveVps(null, input);
    }

    @PutMapping("/vps/{id}")
    public Map<String, Object> updateVps(@PathVariable UUID id, @RequestBody PlatformManagementService.VpsInput input) {
        return service.saveVps(id, input);
    }

    @PutMapping("/companies/{id}/hosting")
    public Map<String, Object> saveHosting(@PathVariable UUID id, @RequestBody PlatformManagementService.HostingInput input) {
        return service.saveHosting(id, input);
    }

    public record PartnerSharesRequest(BigDecimal pedroPercent, BigDecimal carlosPercent) {}
}
