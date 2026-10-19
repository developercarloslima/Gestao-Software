package br.com.nh.cotacao.entity;

import jakarta.persistence.*;

import java.time.OffsetDateTime;

@Entity
@Table(name = "app_settings")
public class AppSetting {
    @Id
    @Column(name = "setting_key", length = 80)
    private String key;

    @Column(name = "setting_value", length = 500)
    private String value;

    @Column(name = "updated_by", length = 160)
    private String updatedBy;

    @Column(name = "updated_at", nullable = false)
    private OffsetDateTime updatedAt;

    protected AppSetting() {}

    public static AppSetting create(String key, String value, String username) {
        AppSetting setting = new AppSetting();
        setting.key = key;
        setting.update(value, username);
        return setting;
    }

    public void update(String value, String username) {
        this.value = value == null ? "" : value.trim();
        this.updatedBy = username;
        this.updatedAt = OffsetDateTime.now();
    }

    public String getKey() { return key; }
    public String getValue() { return value; }
    public String getUpdatedBy() { return updatedBy; }
    public OffsetDateTime getUpdatedAt() { return updatedAt; }
}
