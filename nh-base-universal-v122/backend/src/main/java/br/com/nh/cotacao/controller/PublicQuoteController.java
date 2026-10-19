package br.com.nh.cotacao.controller;

import br.com.nh.cotacao.dto.QuoteDtos.*;
import br.com.nh.cotacao.service.PricingService;
import br.com.nh.cotacao.service.QuoteService;
import br.com.nh.cotacao.repository.VehicleCategoryRepository;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/public/quotes")
public class PublicQuoteController {
    private final QuoteService quoteService;
    private final PricingService pricingService;
    private final VehicleCategoryRepository categoryRepository;

    public PublicQuoteController(
            QuoteService quoteService,
            PricingService pricingService,
            VehicleCategoryRepository categoryRepository
    ) {
        this.quoteService = quoteService;
        this.pricingService = pricingService;
        this.categoryRepository = categoryRepository;
    }

    public record PublicCategoryResponse(String code, String name) {}

    @GetMapping("/categories")
    public List<PublicCategoryResponse> categories() {
        return categoryRepository.findAllByActiveTrueOrderByNameAsc().stream()
                .map(item -> new PublicCategoryResponse(item.getCode(), item.getName()))
                .toList();
    }

    @GetMapping("/promotional-motorcycle-prices")
    public List<PricingService.PromotionalMotorcyclePriceView> promotionalMotorcyclePrices() {
        return pricingService.promotionalMotorcyclePrices();
    }

    @PostMapping("/options")
    public OptionsResponse options(@Valid @RequestBody OptionsRequest request) {
        return quoteService.options(request);
    }

    @PostMapping
    public ResponseEntity<QuoteResponse> create(@Valid @RequestBody CreatePublicQuoteRequest request) {
        return ResponseEntity.status(201).body(quoteService.createPublic(request));
    }

    @GetMapping("/{id}")
    public QuoteResponse get(@PathVariable UUID id) {
        return quoteService.getPublic(id);
    }

    @PostMapping("/{id}/decision")
    public DecisionResponse decide(@PathVariable UUID id, @Valid @RequestBody DecisionRequest request) {
        return quoteService.decidePublic(id, request);
    }
}
