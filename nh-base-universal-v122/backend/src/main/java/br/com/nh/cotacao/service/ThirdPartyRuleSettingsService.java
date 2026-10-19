package br.com.nh.cotacao.service;

import br.com.nh.cotacao.dto.AdminDtos.ThirdPartyRuleSettingsResponse;
import br.com.nh.cotacao.dto.AdminDtos.UpdateThirdPartyRuleRequest;
import br.com.nh.cotacao.dto.AdminDtos.UpdateThirdPartyRulesRequest;
import br.com.nh.cotacao.entity.AppSetting;
import br.com.nh.cotacao.entity.CatalogChangeAudit;
import br.com.nh.cotacao.repository.AppSettingRepository;
import br.com.nh.cotacao.repository.CatalogChangeAuditRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

@Service
public class ThirdPartyRuleSettingsService {
    private static final String KEY_PREFIX = "THIRD_PARTY_RULE_";
    private static final Set<String> SUPPORTED_PLAN_CODES = Set.of(
            "CAR_ECONOMICO", "UTILITY_ECONOMICO", "CAR_COMPLETO", "UTILITY_COMPLETO"
    );

    private final AppSettingRepository repository;
    private final CatalogChangeAuditRepository auditRepository;

    public ThirdPartyRuleSettingsService(
            AppSettingRepository repository,
            CatalogChangeAuditRepository auditRepository
    ) {
        this.repository = repository;
        this.auditRepository = auditRepository;
    }

    @Transactional(readOnly = true)
    public List<ThirdPartyRuleSettingsResponse> getAll() {
        return List.of(
                get("CAR_ECONOMICO"),
                get("UTILITY_ECONOMICO"),
                get("CAR_COMPLETO"),
                get("UTILITY_COMPLETO")
        );
    }

    @Transactional(readOnly = true)
    public ThirdPartyRuleSettingsResponse get(String planCode) {
        String normalized = normalizePlanCode(planCode);
        RuleValues defaults = defaultValues(normalized);
        AppSetting setting = repository.findById(key(normalized)).orElse(null);
        if ("true".equalsIgnoreCase(System.getenv("GS_TENANT_MODE")) && setting == null) {
            RuleValues unset = new RuleValues(BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO);
            return new ThirdPartyRuleSettingsResponse(normalized, planName(normalized), unset.fipeThreshold(),
                    unset.belowNormalAmount(), unset.belowDiscountedAmount(), unset.aboveNormalAmount(),
                    unset.aboveDiscountedAmount(), "PENDING_CONFIGURATION", null);
        }
        RuleValues values = setting == null ? defaults : parse(setting.getValue(), defaults);
        return new ThirdPartyRuleSettingsResponse(
                normalized,
                planName(normalized),
                values.fipeThreshold(),
                values.belowNormalAmount(),
                values.belowDiscountedAmount(),
                values.aboveNormalAmount(),
                values.aboveDiscountedAmount(),
                setting == null ? "SYSTEM" : setting.getUpdatedBy(),
                setting == null ? null : setting.getUpdatedAt()
        );
    }

    @Transactional(readOnly = true)
    public BigDecimal limitFor(String planCode, BigDecimal fipeValue, Integer discountPercent) {
        if (planCode == null || fipeValue == null) return null;
        String normalized = planCode.trim().toUpperCase(Locale.ROOT);
        if (!SUPPORTED_PLAN_CODES.contains(normalized)) return null;
        ThirdPartyRuleSettingsResponse rule = get(normalized);
        if ("PENDING_CONFIGURATION".equals(rule.updatedBy()))
            throw new IllegalStateException("O ADM desta empresa precisa configurar os limites de terceiros deste plano antes de cotar.");
        boolean above = fipeValue.compareTo(rule.fipeThreshold()) >= 0;
        boolean discounted = discountPercent != null && discountPercent > 0;
        if (above) {
            return discounted ? rule.aboveDiscountedAmount() : rule.aboveNormalAmount();
        }
        return discounted ? rule.belowDiscountedAmount() : rule.belowNormalAmount();
    }

    @Transactional
    public List<ThirdPartyRuleSettingsResponse> update(UpdateThirdPartyRulesRequest request, String username) {
        Map<String, UpdateThirdPartyRuleRequest> unique = new LinkedHashMap<>();
        for (UpdateThirdPartyRuleRequest item : request.rules()) {
            String planCode = normalizePlanCode(item.planCode());
            if (unique.putIfAbsent(planCode, item) != null) {
                throw new IllegalArgumentException("O mesmo plano foi informado mais de uma vez na regra de terceiros.");
            }
        }
        if (unique.isEmpty()) {
            throw new IllegalArgumentException("Informe pelo menos uma regra de terceiros para salvar.");
        }

        List<ThirdPartyRuleSettingsResponse> updated = new ArrayList<>();
        for (Map.Entry<String, UpdateThirdPartyRuleRequest> entry : unique.entrySet()) {
            String planCode = entry.getKey();
            UpdateThirdPartyRuleRequest item = entry.getValue();
            validate(item);

            ThirdPartyRuleSettingsResponse old = get(planCode);
            String newValue = serialize(new RuleValues(
                    item.fipeThreshold(),
                    item.belowNormalAmount(),
                    item.belowDiscountedAmount(),
                    item.aboveNormalAmount(),
                    item.aboveDiscountedAmount()
            ));
            AppSetting setting = repository.findById(key(planCode))
                    .orElseGet(() -> AppSetting.create(key(planCode), newValue, username));
            setting.update(newValue, username);
            repository.save(setting);

            ThirdPartyRuleSettingsResponse next = get(planCode);
            auditRepository.save(CatalogChangeAudit.createText(
                    "THIRD_PARTY_RULE", null, planCode,
                    "Regra de danos a terceiros alterada — " + planName(planCode),
                    summary(old), summary(next), username
            ));
            updated.add(next);
        }
        return updated;
    }

    public boolean supports(String planCode) {
        if (planCode == null) return false;
        return SUPPORTED_PLAN_CODES.contains(planCode.trim().toUpperCase(Locale.ROOT));
    }

    private String normalizePlanCode(String planCode) {
        if (planCode == null || planCode.isBlank()) {
            throw new IllegalArgumentException("Plano da regra de terceiros não informado.");
        }
        String normalized = planCode.trim().toUpperCase(Locale.ROOT);
        if (!SUPPORTED_PLAN_CODES.contains(normalized)) {
            throw new IllegalArgumentException("Este plano não usa a regra-base administrável de danos a terceiros.");
        }
        return normalized;
    }

    private void validate(UpdateThirdPartyRuleRequest item) {
        if (item.fipeThreshold() == null || item.fipeThreshold().signum() < 0) {
            throw new IllegalArgumentException("Informe uma FIPE de corte válida para a regra de terceiros.");
        }
        if (item.belowNormalAmount() == null || item.belowNormalAmount().signum() < 0
                || item.belowDiscountedAmount() == null || item.belowDiscountedAmount().signum() < 0
                || item.aboveNormalAmount() == null || item.aboveNormalAmount().signum() < 0
                || item.aboveDiscountedAmount() == null || item.aboveDiscountedAmount().signum() < 0) {
            throw new IllegalArgumentException("Os limites de terceiros não podem ficar vazios nem ser negativos.");
        }
    }

    private String key(String planCode) {
        return KEY_PREFIX + planCode;
    }

    private RuleValues defaultValues(String planCode) {
        BigDecimal threshold = new BigDecimal("51000.00");
        if ("CAR_ECONOMICO".equals(planCode) || "UTILITY_ECONOMICO".equals(planCode)) {
            BigDecimal amount = new BigDecimal("35000.00");
            return new RuleValues(threshold, amount, amount, amount, amount);
        }
        return new RuleValues(
                threshold,
                new BigDecimal("50000.00"),
                new BigDecimal("50000.00"),
                new BigDecimal("100000.00"),
                new BigDecimal("50000.00")
        );
    }

    private RuleValues parse(String raw, RuleValues fallback) {
        if (raw == null || raw.isBlank()) return fallback;
        try {
            String[] parts = raw.split("\\|", -1);
            if (parts.length != 5) return fallback;
            return new RuleValues(
                    new BigDecimal(parts[0]),
                    new BigDecimal(parts[1]),
                    new BigDecimal(parts[2]),
                    new BigDecimal(parts[3]),
                    new BigDecimal(parts[4])
            );
        } catch (RuntimeException ignored) {
            return fallback;
        }
    }

    private String serialize(RuleValues values) {
        return values.fipeThreshold().toPlainString() + "|"
                + values.belowNormalAmount().toPlainString() + "|"
                + values.belowDiscountedAmount().toPlainString() + "|"
                + values.aboveNormalAmount().toPlainString() + "|"
                + values.aboveDiscountedAmount().toPlainString();
    }

    private String summary(ThirdPartyRuleSettingsResponse rule) {
        return "corteFIPE=" + rule.fipeThreshold()
                + "; abaixoSemDesconto=" + rule.belowNormalAmount()
                + "; abaixoComDesconto=" + rule.belowDiscountedAmount()
                + "; acimaSemDesconto=" + rule.aboveNormalAmount()
                + "; acimaComDesconto=" + rule.aboveDiscountedAmount();
    }

    private String planName(String code) {
        return switch (code) {
            case "CAR_ECONOMICO" -> "Carro Nacional — Econômico";
            case "UTILITY_ECONOMICO" -> "Utilitário — Econômico";
            case "CAR_COMPLETO" -> "Carro Nacional — Completo";
            case "UTILITY_COMPLETO" -> "Utilitário — Completo";
            default -> code;
        };
    }

    private record RuleValues(
            BigDecimal fipeThreshold,
            BigDecimal belowNormalAmount,
            BigDecimal belowDiscountedAmount,
            BigDecimal aboveNormalAmount,
            BigDecimal aboveDiscountedAmount
    ) {}
}
