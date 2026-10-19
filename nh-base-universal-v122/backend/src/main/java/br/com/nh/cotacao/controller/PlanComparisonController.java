package br.com.nh.cotacao.controller;

import br.com.nh.cotacao.dto.QuoteDtos.OptionsRequest;
import br.com.nh.cotacao.dto.QuoteDtos.OptionsResponse;
import br.com.nh.cotacao.entity.MotorcycleOrigin;
import br.com.nh.cotacao.entity.PricingRegion;
import br.com.nh.cotacao.entity.Region;
import br.com.nh.cotacao.security.PortalPrincipal;
import br.com.nh.cotacao.service.*;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

@RestController
public class PlanComparisonController {
    private final PlanComparisonTokenService tokenService;
    private final PortalUserService portalUserService;
    private final ConsultantService consultantService;
    private final QuoteService quoteService;
    private final CommunicationSettingsService communicationSettings;

    public PlanComparisonController(
            PlanComparisonTokenService tokenService,
            PortalUserService portalUserService,
            ConsultantService consultantService,
            QuoteService quoteService,
            CommunicationSettingsService communicationSettings
    ) {
        this.tokenService = tokenService;
        this.portalUserService = portalUserService;
        this.consultantService = consultantService;
        this.quoteService = quoteService;
        this.communicationSettings = communicationSettings;
    }

    @PostMapping("/api/plan-comparisons")
    public CreateComparisonResponse create(
            @Valid @RequestBody CreateComparisonRequest request,
            Authentication authentication
    ) {
        PortalPrincipal principal = (PortalPrincipal) authentication.getPrincipal();
        portalUserService.assertConsultantAccess(principal.username(), principal.role(), request.consultantId());
        var consultant = consultantService.findActive(request.consultantId());

        // Gera um snapshot da comparação. Assim, o link continua exibindo exatamente
        // os valores/coberturas que o consultor enviou, mesmo que o catálogo seja editado depois.
        OptionsResponse options = quoteService.options(request.toOptionsRequest());

        int discount = request.discountPercent() == null ? 0 : request.discountPercent();
        if (discount < 0 || discount > 100) {
            throw new IllegalArgumentException("O desconto deve ficar entre 0% e 100%.");
        }
        if (discount > 0) {
            boolean availableInAtLeastOnePlan = options.plans().stream()
                    .flatMap(plan -> plan.discounts().stream())
                    .anyMatch(option -> option.percent() == discount && option.available());
            if (!availableInAtLeastOnePlan) {
                throw new IllegalArgumentException(
                        "O desconto de " + discount + "% não está disponível para os planos/veículo desta comparação."
                );
            }
        }

        if (consultant.getWhatsapp() == null || consultant.getWhatsapp().isBlank()) {
            throw new IllegalArgumentException(
                    "Cadastre o WhatsApp deste consultor na aba Colaboradores antes de enviar a comparação."
            );
        }

        var payload = new PlanComparisonTokenService.Payload(
                consultant.getId(), consultant.getName(), consultant.getWhatsapp(), clean(request.customerName()), clean(request.model()),
                clean(request.plate()), discount, request.auctionOrChassisRemarked(), Boolean.TRUE.equals(request.auctionOrChassisRemarked()) ? 70 : 100, options, 0
        );
        String token = tokenService.issue(payload);
        return new CreateComparisonResponse("/comparacao/?token=" + token);
    }

    @GetMapping("/api/public/plan-comparisons/{token}")
    public PublicComparisonResponse getPublic(@PathVariable String token) {
        var payload = tokenService.verify(token)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.GONE, "Este link de comparação expirou ou é inválido."));
        OptionsResponse options = payload.options();
        return new PublicComparisonResponse(
                payload.customerName(), payload.consultantName(), payload.model(), payload.plate(), options.fipeValue(),
                payload.discountPercent() == null ? 0 : payload.discountPercent(),
                payload.auctionOrChassisRemarked(), payload.indemnityFipePercent() == null ? 100 : payload.indemnityFipePercent(),
                Instant.ofEpochSecond(payload.expiresAtEpochSecond()),
                payload.consultantWhatsapp() == null || payload.consultantWhatsapp().isBlank()
                        ? communicationSettings.teamWhatsapp()
                        : payload.consultantWhatsapp(),
                options
        );
    }

    public record CreateComparisonRequest(
            @NotNull UUID consultantId,
            @NotBlank @Size(max = 120) String customerName,
            @NotBlank @Size(max = 120) String model,
            @Size(max = 10) String plate,
            @NotBlank String categoryCode,
            Region region,
            PricingRegion pricingRegion,
            MotorcycleOrigin motorcycleOrigin,
            @NotNull @DecimalMin("0.01") BigDecimal fipeValue,
            @NotNull @Min(1950) @Max(2100) Integer manufactureYear,
            Boolean auctionOrChassisRemarked,
            Boolean motorcycle,
            @Min(1) @Max(2500) Integer motorcycleCc,
            @Size(max = 40) String promoMotorcycleTier,
            @Min(0) @Max(100) Integer discountPercent
    ) {
        OptionsRequest toOptionsRequest() {
            return new OptionsRequest(
                    categoryCode, region, pricingRegion, motorcycleOrigin, fipeValue, motorcycle, motorcycleCc, promoMotorcycleTier, manufactureYear
            );
        }
    }

    public record CreateComparisonResponse(String url) {}

    public record PublicComparisonResponse(
            String customerName,
            String consultantName,
            String model,
            String plate,
            BigDecimal fipeValue,
            Integer discountPercent,
            Boolean auctionOrChassisRemarked,
            Integer indemnityFipePercent,
            Instant expiresAt,
            String returnWhatsapp,
            OptionsResponse options
    ) {}

    private static String clean(String value) {
        if (value == null) return null;
        String result = value.trim().replaceAll("\\s+", " ");
        return result.isBlank() ? null : result;
    }

}
