package tw.hakuri.bot.config;

import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;
import static org.assertj.core.api.Assertions.*;

class BotConfigTest {
    @Test void openLlmIsDisabledByDefaultAndCanBeEnabled() {
        assertThat(new BotConfig(new MockEnvironment()).openllmEnabled()).isFalse();
        assertThat(new BotConfig(new MockEnvironment().withProperty("OPENLLM_ENABLED", "true")).openllmEnabled()).isTrue();
        assertThat(new BotConfig(new MockEnvironment()).discordVoiceEnabled()).isFalse();
        assertThat(new BotConfig(new MockEnvironment().withProperty("DISCORD_VOICE_ENABLED", "true")).discordVoiceEnabled()).isTrue();
    }
    @Test void respectsExternalServiceSettings() {
        var config = new BotConfig(new MockEnvironment().withProperty("OPENLLM_HTTP", "http://localhost:9000/")
                .withProperty("OPENLLM_WS", "ws://localhost:9000/proxy-ws").withProperty("FFMPEG_PATH", "custom-ffmpeg"));
        assertThat(config.http()).isEqualTo("http://localhost:9000");
        assertThat(config.ws()).isEqualTo("ws://localhost:9000/proxy-ws");
        assertThat(config.ffmpeg()).isEqualTo("custom-ffmpeg");
    }
    @Test void rejectsInvalidRecordingBounds() {
        assertThatThrownBy(() -> new BotConfig(new MockEnvironment().withProperty("VOICE_MAX_SECONDS", "0")))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new BotConfig(new MockEnvironment().withProperty("VOICE_MAX_SECONDS", "NaN")))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
