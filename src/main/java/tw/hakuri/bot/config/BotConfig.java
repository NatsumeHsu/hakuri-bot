package tw.hakuri.bot.config;

import org.springframework.core.env.Environment;

public class BotConfig {
    private final String token, http, ws, ffmpeg, twitchChannel, wakeWord;
    private final boolean enabled, twitchEnabled, requireWakeWord;
    private volatile boolean openllmEnabled;
    private volatile boolean openllmConfigured;
    private volatile boolean discordVoiceEnabled;
    private final int silenceMs, maxQueue, cooldownMs;
    private final double maxSeconds, minSeconds;

    public String token() { return token; }
    public String http() { return http; }
    public String ws() { return ws; }
    public String ffmpeg() { return ffmpeg; }
    public String twitchChannel() { return twitchChannel; }
    public String wakeWord() { return wakeWord; }
    public boolean enabled() { return enabled; }
    public boolean openllmEnabled() { return openllmEnabled; }
    public boolean openllmConfigured() { return openllmConfigured; }
    public void configureOpenLlm(boolean enabled) { openllmConfigured = enabled; openllmEnabled = enabled; }
    public boolean discordVoiceEnabled() { return discordVoiceEnabled; }
    public void configureDiscordVoice(boolean enabled) { discordVoiceEnabled = enabled; }
    /** Manual AIVT activation can enable AI immediately; all other settings remain startup-only. */
    public void enableOpenLlm() { openllmEnabled = true; }
    public void disableOpenLlm() { openllmEnabled = false; }
    public boolean twitchEnabled() { return twitchEnabled; }
    public boolean requireWakeWord() { return requireWakeWord; }
    public int silenceMs() { return silenceMs; }
    public int maxQueue() { return maxQueue; }
    public int cooldownMs() { return cooldownMs; }
    public double maxSeconds() { return maxSeconds; }
    public double minSeconds() { return minSeconds; }

    public BotConfig(Environment env) {
        token = env.getProperty("DISCORD_TOKEN", "");
        enabled = env.getProperty("BOT_ENABLED", Boolean.class, true);
        openllmEnabled = env.getProperty("OPENLLM_ENABLED", Boolean.class, false);
        openllmConfigured = openllmEnabled;
        discordVoiceEnabled = env.getProperty("DISCORD_VOICE_ENABLED", Boolean.class, false);
        http = env.getProperty("OPENLLM_HTTP", "http://127.0.0.1:12393").replaceAll("/+$", "");
        ws = env.getProperty("OPENLLM_WS", "ws://127.0.0.1:12393/proxy-ws");
        ffmpeg = env.getProperty("FFMPEG_PATH", "ffmpeg");
        twitchEnabled = env.getProperty("TWITCH_ENABLED", Boolean.class, false);
        twitchChannel = env.getProperty("TWITCH_CHANNEL", "natsume0304").replaceFirst("^#", "").toLowerCase();
        wakeWord = env.getProperty("WAKE_WORD", "白璃");
        requireWakeWord = env.getProperty("REQUIRE_WAKE_WORD", Boolean.class, false);
        silenceMs = env.getProperty("VOICE_SILENCE_MS", Integer.class, 1000);
        maxQueue = env.getProperty("VOICE_MAX_QUEUE", Integer.class, 20);
        cooldownMs = env.getProperty("VOICE_COOLDOWN_MS", Integer.class, 700);
        maxSeconds = env.getProperty("VOICE_MAX_SECONDS", Double.class, 12.0);
        minSeconds = env.getProperty("VOICE_MIN_SECONDS", Double.class, 0.5);
        if (silenceMs <= 0 || maxQueue <= 0 || cooldownMs < 0 || !Double.isFinite(maxSeconds)
                || !Double.isFinite(minSeconds) || minSeconds < 0 || maxSeconds <= minSeconds || maxSeconds > 300)
            throw new IllegalArgumentException("語音設定無效：請檢查錄音時間、靜音時間、佇列及冷卻時間");
        if (requireWakeWord && wakeWord.isBlank()) throw new IllegalArgumentException("WAKE_WORD 不可為空");
        if (twitchEnabled && !twitchChannel.matches("[a-z0-9_]+"))
            throw new IllegalArgumentException("TWITCH_CHANNEL 格式錯誤");
    }
}
