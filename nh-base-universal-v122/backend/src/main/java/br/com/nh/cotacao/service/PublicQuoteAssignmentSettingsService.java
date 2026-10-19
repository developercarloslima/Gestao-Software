package br.com.nh.cotacao.service;

import br.com.nh.cotacao.dto.AdminDtos.PublicQuoteAssignmentSettingsResponse;
import br.com.nh.cotacao.entity.AppSetting;
import br.com.nh.cotacao.entity.CatalogChangeAudit;
import br.com.nh.cotacao.repository.AppSettingRepository;
import br.com.nh.cotacao.repository.CatalogChangeAuditRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class PublicQuoteAssignmentSettingsService {
    public static final String KEY = "PUBLIC_QUOTE_ASSIGN_LAST_CONSULTANT";

    private final AppSettingRepository repository;
    private final CatalogChangeAuditRepository auditRepository;

    public PublicQuoteAssignmentSettingsService(
            AppSettingRepository repository,
            CatalogChangeAuditRepository auditRepository
    ) {
        this.repository = repository;
        this.auditRepository = auditRepository;
    }

    @Transactional(readOnly = true)
    public boolean assignToLastLoggedConsultant() {
        return repository.findById(KEY)
                .map(AppSetting::getValue)
                .map(String::trim)
                .filter(value -> !value.isBlank())
                .map(Boolean::parseBoolean)
                .orElse(true);
    }

    @Transactional(readOnly = true)
    public PublicQuoteAssignmentSettingsResponse get() {
        AppSetting setting = repository.findById(KEY).orElse(null);
        return new PublicQuoteAssignmentSettingsResponse(
                assignToLastLoggedConsultant(),
                setting == null ? "SYSTEM" : setting.getUpdatedBy(),
                setting == null ? null : setting.getUpdatedAt()
        );
    }

    @Transactional
    public PublicQuoteAssignmentSettingsResponse update(boolean enabled, String username) {
        boolean old = assignToLastLoggedConsultant();
        AppSetting setting = repository.findById(KEY)
                .orElseGet(() -> AppSetting.create(KEY, Boolean.toString(enabled), username));
        setting.update(Boolean.toString(enabled), username);
        repository.save(setting);

        auditRepository.save(CatalogChangeAudit.createText(
                "PUBLIC_QUOTE_ASSIGNMENT", null, KEY,
                "Distribuição automática das cotações do site alterada",
                "ativo=" + old, "ativo=" + enabled, username
        ));
        return get();
    }
}
