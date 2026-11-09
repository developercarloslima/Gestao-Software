package br.com.nh.cotacao.controller;

import br.com.nh.cotacao.security.PortalPrincipal;
import br.com.nh.cotacao.service.SalesTeamAnalyticsService;
import br.com.nh.cotacao.service.SalesTeamService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

/** Todas as operações exigem ADMIN pela regra /api/admin/**. */
@RestController
@RequestMapping("/api/admin/sales-teams")
public class AdminSalesTeamController {
    public record TeamInput(@NotBlank @Size(min=3,max=120) String name,@NotNull List<@NotBlank String> keys) {}
    public record TeamUpdate(@Size(min=3,max=120) String name,Boolean active) {}
    public record TeamEdit(@NotBlank @Size(min=3,max=120) String name,@NotNull List<@NotBlank String> keys) {}
    public record MembersInput(@NotNull List<@NotBlank String> keys) {}

    private final SalesTeamService teams;
    private final SalesTeamAnalyticsService analytics;

    public AdminSalesTeamController(SalesTeamService teams,SalesTeamAnalyticsService analytics) {
        this.teams=teams;
        this.analytics=analytics;
    }

    @GetMapping
    public List<SalesTeamService.Team> list() { return teams.list(); }

    @GetMapping("/candidates")
    public List<SalesTeamService.Candidate> candidates() { return teams.availableCandidates(); }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public SalesTeamService.Team create(@Valid @RequestBody TeamInput request,Authentication authentication) {
        return teams.createWithMembers(request.name(),request.keys(),username(authentication));
    }

    @PatchMapping("/{id}")
    public SalesTeamService.Team update(@PathVariable UUID id,@Valid @RequestBody TeamUpdate request,
                                        Authentication authentication) {
        return teams.update(id,request.name(),request.active(),username(authentication));
    }

    @PutMapping("/{id}")
    public SalesTeamService.Team edit(@PathVariable UUID id,@Valid @RequestBody TeamEdit request,
                                      Authentication authentication) {
        return teams.editWithMembers(id,request.name(),request.keys(),username(authentication));
    }

    @DeleteMapping("/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void delete(@PathVariable UUID id,Authentication authentication) {
        teams.delete(id,username(authentication));
    }

    @GetMapping("/{id}/members")
    public List<SalesTeamService.Candidate> members(@PathVariable UUID id) { return teams.candidates(id); }

    @PutMapping("/{id}/members")
    public List<SalesTeamService.Candidate> members(@PathVariable UUID id,
                   @Valid @RequestBody MembersInput request,Authentication authentication) {
        return teams.replaceMembers(id,request.keys(),username(authentication));
    }

    @GetMapping("/analytics")
    public SalesTeamAnalyticsService.Report analytics(
            @RequestParam(required=false) LocalDate from,@RequestParam(required=false) LocalDate to,
            @RequestParam(required=false) UUID teamId,@RequestParam(required=false) UUID consultantId) {
        return analytics.report(from,to,teamId,consultantId);
    }

    @GetMapping(value="/analytics/export/{dataset}.csv",produces="text/csv;charset=UTF-8")
    public ResponseEntity<byte[]> export(@PathVariable String dataset,
            @RequestParam(required=false) LocalDate from,@RequestParam(required=false) LocalDate to,
            @RequestParam(required=false) UUID teamId,@RequestParam(required=false) UUID consultantId) {
        byte[] csv=analytics.csv(from,to,teamId,consultantId,dataset);
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION,"attachment; filename=nh-bi-"+dataset+".csv")
                .contentType(new MediaType("text","csv",java.nio.charset.StandardCharsets.UTF_8))
                .body(csv);
    }

    private String username(Authentication auth) {
        return ((PortalPrincipal)auth.getPrincipal()).username();
    }
}
