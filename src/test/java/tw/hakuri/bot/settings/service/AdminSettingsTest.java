package tw.hakuri.bot.settings.service;

import tw.hakuri.bot.admin.service.AdminSettings;
import tw.hakuri.bot.admin.service.AdminCredentialService;
import tw.hakuri.bot.config.BotConfig;
import tw.hakuri.bot.settings.repository.SettingRepository;

import org.junit.jupiter.api.*;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.context.annotation.Import;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.mock.env.MockEnvironment;
import java.util.*;
import static org.assertj.core.api.Assertions.*;

@DataJpaTest(properties = {"spring.datasource.url=jdbc:sqlite:file:settings-test?mode=memory&cache=shared", "spring.jpa.show-sql=false"})
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import(SettingsStore.class)
class AdminSettingsTest {
    @Autowired SettingsStore store;
    @Autowired SettingRepository repository;
    AdminSettings settings;
    @BeforeEach void setup() {
        settings = new AdminSettings(new BotConfig(new MockEnvironment().withProperty("DISCORD_TOKEN", "secret-token")), store);
    }
    @Test void persistsPendingSettingsAndLoadsThemForNextStartup() {
        var view = settings.save(Map.of("OPENLLM_ENABLED", "true", "DISCORD_VOICE_ENABLED", "true", "VOICE_MAX_QUEUE", "30"));
        assertThat(view.get("restartRequired")).isEqualTo(true);
        assertThat(settings.effective().get("OPENLLM_ENABLED")).isEqualTo("false");
        assertThat(repository.findById("VOICE_MAX_QUEUE").orElseThrow().getValue()).isEqualTo("30");
        var restarted = new BotConfig(store.resolvedEnvironment());
        assertThat(restarted.openllmEnabled()).isTrue(); assertThat(restarted.maxQueue()).isEqualTo(30);
        assertThat(restarted.discordVoiceEnabled()).isTrue();
        assertThat(repository.findById("DISCORD_TOKEN")).isEmpty();
        assertThat(new AdminSettings(restarted, store).view().get("restartRequired")).isEqualTo(false);
    }
    @Test void obsStylePersistsAndAppliesWithoutRestart() {
        var values = Map.of("OBS_FONT_FAMILY", "Noto Sans TC", "OBS_FONT_SIZE", "60", "OBS_FONT_COLOR", "#12ABEF");
        var view = settings.save(values);
        assertThat(view.get("restartRequired")).isEqualTo(false);
        assertThat(new tw.hakuri.bot.admin.service.ObsSettingsService(store).style()).containsAllEntriesOf(values);
        for (var invalid : List.of(Map.of("OBS_FONT_SIZE", "161"), Map.of("OBS_FONT_SIZE", "42.5"),
                Map.of("OBS_FONT_COLOR", "red"), Map.of("OBS_FONT_FAMILY", "bad; font"))) {
            assertThatThrownBy(() -> settings.save(invalid)).isInstanceOf(IllegalArgumentException.class);
            assertThat(new tw.hakuri.bot.admin.service.ObsSettingsService(store).style()).containsAllEntriesOf(values);
        }
    }
    @Test void invalidChangesLeaveDatabaseIntact() {
        settings.save(Map.of("VOICE_MAX_QUEUE", "30"));
        var before = store.read();
        for (var invalid : List.of(Map.of("VOICE_MAX_QUEUE", "0"), Map.of("OPENLLM_HTTP", "file:///secret"),
                Map.of("BOT_ENABLED", "maybe"), Map.of("DISCORD_TOKEN", "secret"), Map.of("WAKE_WORD", "bad"),
                Map.of("FFMPEG_PATH", "new-path"), Map.of("TWITCH_CHANNEL", "changed"))) {
            assertThatThrownBy(() -> settings.save(invalid)).isInstanceOf(IllegalArgumentException.class);
            assertThat(store.read()).isEqualTo(before);
        }
    }
    @Test void passwordIsHashedAndExcludedFromSettingsApi() {
        new AdminCredentialService(repository).changePassword("a-long-test-password");
        String hash = repository.findById("ADMIN_PASSWORD_HASH").orElseThrow().getValue();
        assertThat(hash).startsWith("{bcrypt}").doesNotContain("a-long-test-password");
        assertThat(new org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder()
                .matches("a-long-test-password", hash.substring(8))).isTrue();
        assertThat(store.read()).doesNotContainKey("ADMIN_PASSWORD_HASH");
    }
    @Test void importsLegacyOnlyOnceAndPreservesAlreadySavedDatabaseValues() {
        store.save(Map.of("OPENLLM_ENABLED", "false"));
        var legacy = new Properties();
        legacy.setProperty("OPENLLM_ENABLED", "true"); legacy.setProperty("VOICE_MAX_QUEUE", "42");
        legacy.setProperty("DISCORD_TOKEN", "secret-token"); legacy.setProperty("ADMIN_PASSWORD", "old-password");
        store.importLegacy(legacy);
        assertThat(store.read()).containsEntry("OPENLLM_ENABLED", "false").containsEntry("VOICE_MAX_QUEUE", "42");
        assertThat(repository.findById("DISCORD_TOKEN")).isEmpty();
        assertThat(repository.findById("ADMIN_PASSWORD")).isEmpty();
        assertThat(repository.findById("ADMIN_PASSWORD_HASH")).isPresent();
        legacy.setProperty("TWITCH_ENABLED", "true"); store.importLegacy(legacy);
        assertThat(repository.findById("TWITCH_ENABLED")).isEmpty();
    }
    @Test void onlyFourEnvironmentKeysAreUsedByRuntimeConfig() {
        var source = new MockEnvironment().withProperty("BOT_ENABLED", "false")
                .withProperty("OPENLLM_HTTP", "http://ignored:9999")
                .withProperty("DISCORD_TOKEN", "test-token").withProperty("FFMPEG_PATH", "test-ffmpeg")
                .withProperty("TWITCH_CHANNEL", "test_channel").withProperty("WAKE_WORD", "測試");
        var config = new BotConfig(new SettingsStore(repository, source).resolvedEnvironment());
        assertThat(config.enabled()).isTrue(); assertThat(config.http()).isEqualTo("http://127.0.0.1:12393");
        assertThat(config.token()).isEqualTo("test-token"); assertThat(config.ffmpeg()).isEqualTo("test-ffmpeg");
        assertThat(config.twitchChannel()).isEqualTo("test_channel"); assertThat(config.wakeWord()).isEqualTo("測試");
    }
}
