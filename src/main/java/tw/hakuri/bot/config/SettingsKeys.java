package tw.hakuri.bot.config;

import java.util.Set;

/** Separates environment-owned secrets and identifiers from database-managed settings. */
public final class SettingsKeys {
    public static final Set<String> ENV_KEYS = Set.of("FFMPEG_PATH", "DISCORD_TOKEN", "TWITCH_CHANNEL", "WAKE_WORD",
            "AI_ROOT", "OLLAMA_PATH", "GPT_SOVITS_DIR", "GPT_SOVITS_PYTHON_PATH", "GPT_SOVITS_API_PATH",
            "OPENLLM_DIR", "OPENLLM_SERVER_PATH", "UV_PATH", "VTUBER_ELECTRON_PATH", "SERVER_ADDRESS", "SERVER_PORT");
    public static final Set<String> CONFIG_KEYS = Set.of("BOT_ENABLED", "DISCORD_VOICE_ENABLED", "OPENLLM_ENABLED", "OPENLLM_HTTP", "OPENLLM_WS",
            "TWITCH_ENABLED", "VOICE_SILENCE_MS", "VOICE_MAX_SECONDS", "VOICE_MIN_SECONDS", "VOICE_MAX_QUEUE",
            "VOICE_COOLDOWN_MS", "REQUIRE_WAKE_WORD", "OBS_FONT_FAMILY", "OBS_FONT_SIZE", "OBS_FONT_COLOR");
    private SettingsKeys() { }
}
