package tw.hakuri.bot.settings.service;

import tw.hakuri.bot.settings.entity.SettingEntity;
import tw.hakuri.bot.settings.repository.SettingRepository;

import io.github.cdimascio.dotenv.Dotenv;
import static tw.hakuri.bot.config.SettingsKeys.*;
import org.springframework.core.env.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import java.io.*;
import java.nio.file.*;
import java.util.*;

@Service
public class SettingsStore {
    private final SettingRepository repository;
    private final Environment environment;
    public SettingsStore(SettingRepository repository, Environment environment) { this.repository = repository; this.environment = environment; }
    @Transactional(readOnly = true)
    public Map<String, String> read() {
        var result = new TreeMap<String, String>();
        repository.findAll().forEach(row -> { if (CONFIG_KEYS.contains(row.getKey())) result.put(row.getKey(), row.getValue()); });
        return result;
    }
    @Transactional
    public void save(Map<String, String> updates) {
        if (!CONFIG_KEYS.containsAll(updates.keySet())) throw new IllegalArgumentException("只能修改資料庫管理的設定");
        repository.saveAllAndFlush(updates.entrySet().stream().map(e -> new SettingEntity(e.getKey(), e.getValue())).toList());
    }
    @Transactional
    public void migrateLegacy() throws IOException {
        if (!environment.getProperty("hakuri.settings.migrate-legacy", Boolean.class, true)
                || repository.existsById("LEGACY_IMPORTED")) return;
        var legacy = new Properties();
        Dotenv.configure().ignoreIfMissing().load().entries().forEach(entry -> {
            if (CONFIG_KEYS.contains(entry.getKey()) || entry.getKey().equals("ADMIN_PASSWORD")) legacy.setProperty(entry.getKey(), entry.getValue());
        });
        Path file = Path.of("admin-settings.properties");
        if (Files.exists(file)) try (var input = Files.newInputStream(file)) { legacy.load(input); }
        importLegacy(legacy);
    }
    void importLegacy(Properties legacy) {
        if (repository.existsById("LEGACY_IMPORTED")) return;
        for (String key : CONFIG_KEYS) if (legacy.containsKey(key) && !repository.existsById(key))
            repository.save(new SettingEntity(key, legacy.getProperty(key)));
        String password = legacy.getProperty("ADMIN_PASSWORD", "");
        if (!password.isBlank() && !repository.existsById("ADMIN_PASSWORD_HASH"))
            repository.save(new SettingEntity("ADMIN_PASSWORD_HASH", "{bcrypt}" + new BCryptPasswordEncoder().encode(password)));
        repository.save(new SettingEntity("LEGACY_IMPORTED", "true"));
    }
    public Environment resolvedEnvironment() {
        var resolved = new StandardEnvironment();
        resolved.getPropertySources().remove(StandardEnvironment.SYSTEM_PROPERTIES_PROPERTY_SOURCE_NAME);
        resolved.getPropertySources().remove(StandardEnvironment.SYSTEM_ENVIRONMENT_PROPERTY_SOURCE_NAME);
        var values = new HashMap<String, Object>(read());
        // Explicit application/test properties may override the persisted startup settings.
        if (environment instanceof ConfigurableEnvironment configurable) {
            for (PropertySource<?> source : configurable.getPropertySources()) {
                if (source.getName().equals("commandLineArgs") || source.getName().equals("Inlined Test Properties"))
                    for (String key : CONFIG_KEYS) if (source.containsProperty(key)) values.put(key, source.getProperty(key));
            }
        }
        resolved.getPropertySources().addFirst(new MapPropertySource("sqlite-settings", values));
        resolved.getPropertySources().addLast(new PropertySource<Environment>("environment-defaults", environment) {
            @Override public Object getProperty(String name) {
                if (ENV_KEYS.contains(name) || name.equals("hakuri.settings.migrate-legacy")) return source.getProperty(name);
                return null;
            }
        });
        return resolved;
    }
}
