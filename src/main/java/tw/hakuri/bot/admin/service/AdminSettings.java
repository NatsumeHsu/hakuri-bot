package tw.hakuri.bot.admin.service;

import tw.hakuri.bot.config.SettingsKeys;

import tw.hakuri.bot.config.BotConfig;
import tw.hakuri.bot.settings.service.SettingsStore;

import org.springframework.core.env.*;
import org.springframework.stereotype.Service;
import java.net.URI;
import java.util.*;

@Service
public class AdminSettings {
    static final Set<String> KEYS = SettingsKeys.CONFIG_KEYS;
    private final BotConfig config;
    private final SettingsStore store;
    public AdminSettings(BotConfig config, SettingsStore store) { this.config = config; this.store = store; }
    public Map<String, String> effective() {
        var result = new TreeMap<String, String>();
        result.put("BOT_ENABLED", "" + config.enabled()); result.put("OPENLLM_ENABLED", "" + config.openllmConfigured());
        result.put("DISCORD_VOICE_ENABLED", "" + config.discordVoiceEnabled());
        result.put("OPENLLM_HTTP", config.http()); result.put("OPENLLM_WS", config.ws()); result.put("FFMPEG_PATH", config.ffmpeg());
        result.put("TWITCH_ENABLED", "" + config.twitchEnabled()); result.put("TWITCH_CHANNEL", config.twitchChannel());
        result.put("VOICE_SILENCE_MS", "" + config.silenceMs()); result.put("VOICE_MAX_SECONDS", "" + config.maxSeconds());
        result.put("VOICE_MIN_SECONDS", "" + config.minSeconds()); result.put("VOICE_MAX_QUEUE", "" + config.maxQueue());
        result.put("VOICE_COOLDOWN_MS", "" + config.cooldownMs()); result.put("REQUIRE_WAKE_WORD", "" + config.requireWakeWord());
        result.put("WAKE_WORD", config.wakeWord()); result.putAll(new ObsSettingsService(store).style()); return result;
    }
    public synchronized Map<String, Object> view() {
        var effective = effective(); var next = new TreeMap<>(effective); next.putAll(store.read());
        return Map.of("effective", effective, "saved", next, "restartRequired", !next.equals(effective),
                "environmentOverrides", List.of("FFMPEG_PATH", "TWITCH_CHANNEL", "WAKE_WORD"));
    }
    public synchronized Map<String, Object> save(Map<String, String> updates) {
        if (updates.isEmpty() || !KEYS.containsAll(updates.keySet())) throw new IllegalArgumentException("只能修改 SQLite 管理的欄位；環境設定請修改 .env");
        for (var entry : updates.entrySet()) {
            String value = entry.getValue();
            if (value == null || value.length() > 2048 || value.contains("\n") || value.contains("\r")) throw new IllegalArgumentException("設定格式錯誤");
            if ((entry.getKey().endsWith("_ENABLED") || entry.getKey().equals("REQUIRE_WAKE_WORD")) && !Set.of("true", "false").contains(value))
                throw new IllegalArgumentException("功能開關必須為 true 或 false");
        }
        var saved = store.read(); saved.putAll(updates);
        var next = new TreeMap<>(effective()); next.putAll(saved);
        var validation = new StandardEnvironment();
        validation.getPropertySources().addFirst(new MapPropertySource("admin", new HashMap<>(next)));
        new BotConfig(validation); ObsSettingsService.validate(next);
        validateUri(next.get("OPENLLM_HTTP"), Set.of("http", "https"));
        validateUri(next.get("OPENLLM_WS"), Set.of("ws", "wss"));
        store.save(updates);
        return view();
    }
    private void validateUri(String value, Set<String> schemes) {
        URI uri = URI.create(value);
        if (!schemes.contains(uri.getScheme()) || uri.getHost() == null || uri.getUserInfo() != null)
            throw new IllegalArgumentException("服務網址格式錯誤");
    }
}
