package tw.hakuri.bot.settings.entity;

import jakarta.persistence.*;

@Entity
@Table(name = "bot_settings")
public class SettingEntity {
    @Id @Column(name = "setting_key", length = 64, nullable = false)
    private String key;
    @Column(name = "setting_value", length = 2048, nullable = false)
    private String value;
    protected SettingEntity() { }
    public SettingEntity(String key, String value) { this.key = key; this.value = value; }
    public String getKey() { return key; }
    public String getValue() { return value; }
}
