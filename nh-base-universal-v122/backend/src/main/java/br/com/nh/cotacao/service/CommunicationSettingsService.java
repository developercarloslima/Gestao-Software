package br.com.nh.cotacao.service;

import br.com.nh.cotacao.dto.AdminDtos.CommunicationSettingsResponse;
import br.com.nh.cotacao.dto.AdminDtos.UpdateCommunicationSettingsRequest;
import br.com.nh.cotacao.entity.AppSetting;
import br.com.nh.cotacao.entity.CatalogChangeAudit;
import br.com.nh.cotacao.repository.AppSettingRepository;
import br.com.nh.cotacao.repository.CatalogChangeAuditRepository;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.OffsetDateTime;
import java.util.Comparator;

@Service
public class CommunicationSettingsService {
    public static final String EMAIL_KEY = "COTATION_TEAM_EMAIL";
    public static final String WHATSAPP_KEY = "COTATION_TEAM_WHATSAPP";

    private final AppSettingRepository repository;
    private final CatalogChangeAuditRepository auditRepository;
    private final String defaultEmail;
    private final String defaultWhatsapp;

    public CommunicationSettingsService(
            AppSettingRepository repository,
            CatalogChangeAuditRepository auditRepository,
            @Value("${app.team-email:}") String defaultEmail,
            @Value("${app.team-whatsapp-number:}") String defaultWhatsapp
    ) {
        this.repository = repository;
        this.auditRepository = auditRepository;
        this.defaultEmail = cleanEmail(defaultEmail);
        this.defaultWhatsapp = cleanPhone(defaultWhatsapp);
    }

    @Transactional(readOnly = true)
    public CommunicationSettingsResponse get() {
        AppSetting email = repository.findById(EMAIL_KEY).orElse(null);
        AppSetting whatsapp = repository.findById(WHATSAPP_KEY).orElse(null);
        String resolvedEmail = valueOrDefault(email, defaultEmail);
        String resolvedWhatsapp = valueOrDefault(whatsapp, defaultWhatsapp);
        AppSetting latest = latest(email, whatsapp);
        return new CommunicationSettingsResponse(
                resolvedEmail,
                resolvedWhatsapp,
                latest == null ? "SYSTEM" : latest.getUpdatedBy(),
                latest == null ? null : latest.getUpdatedAt()
        );
    }

    @Transactional
    public CommunicationSettingsResponse update(UpdateCommunicationSettingsRequest request, String username) {
        String newEmail = cleanEmail(request.teamEmail());
        String newWhatsapp = cleanPhone(request.teamWhatsapp());
        String oldEmail = teamEmail();
        String oldWhatsapp = teamWhatsapp();

        save(EMAIL_KEY, newEmail, username);
        save(WHATSAPP_KEY, newWhatsapp, username);

        auditRepository.save(CatalogChangeAudit.createText(
                "COMMUNICATION", null, "COMMUNICATION_SETTINGS", "Destinos de envio alterados",
                "e-mail=" + visible(oldEmail) + "; whatsapp=" + visible(oldWhatsapp),
                "e-mail=" + visible(newEmail) + "; whatsapp=" + visible(newWhatsapp),
                username
        ));
        return get();
    }

    @Transactional(readOnly = true)
    public String teamEmail() {
        AppSetting setting = repository.findById(EMAIL_KEY).orElse(null);
        return valueOrDefault(setting, defaultEmail);
    }

    @Transactional(readOnly = true)
    public String teamWhatsapp() {
        AppSetting setting = repository.findById(WHATSAPP_KEY).orElse(null);
        return valueOrDefault(setting, defaultWhatsapp);
    }

    private void save(String key, String value, String username) {
        AppSetting setting = repository.findById(key)
                .orElseGet(() -> AppSetting.create(key, value, username));
        setting.update(value, username);
        repository.save(setting);
    }

    private String valueOrDefault(AppSetting setting, String fallback) {
        if (setting == null || setting.getValue() == null || setting.getValue().isBlank()) return fallback;
        return setting.getValue().trim();
    }

    private AppSetting latest(AppSetting first, AppSetting second) {
        return java.util.stream.Stream.of(first, second)
                .filter(item -> item != null && item.getUpdatedAt() != null)
                .max(Comparator.comparing(AppSetting::getUpdatedAt))
                .orElse(null);
    }

    private static String cleanEmail(String value) {
        return value == null ? "" : value.trim().toLowerCase();
    }

    private static String cleanPhone(String value) {
        return value == null ? "" : value.replaceAll("\\D", "");
    }

    private static String visible(String value) {
        return value == null || value.isBlank() ? "não configurado" : value;
    }
}
