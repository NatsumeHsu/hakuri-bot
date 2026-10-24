package tw.hakuri.bot.admin.service;

import tw.hakuri.bot.audio.service.AudioService;
import tw.hakuri.bot.config.BotConfig;
import tw.hakuri.bot.conversation.service.ConversationService;
import tw.hakuri.bot.integration.aivt.AivtLauncher;
import tw.hakuri.bot.settings.service.SettingsStore;
import tw.hakuri.bot.observability.activity.ActivityLogService;
import org.springframework.stereotype.Service;
import java.util.Map;

@Service
public class AiActivationService {
    private final AivtLauncher launcher;
    private final AudioService audio;
    private final BotConfig config;
    private final SettingsStore settings;
    private final ConversationService conversation;
    private final ActivityLogService activity;
    public AiActivationService(AivtLauncher launcher, AudioService audio, BotConfig config, SettingsStore settings, ConversationService conversation, ActivityLogService activity) {
        this.launcher = launcher; this.audio = audio; this.config = config; this.settings = settings; this.conversation = conversation;
        this.activity = activity;
    }
    public synchronized Map<String, String> start() {
        try { audio.checkFfmpeg(); }
        catch (Exception error) { throw new IllegalStateException("無法啟用 AI：FFmpeg 驗證失敗，請檢查 FFMPEG_PATH"); }
        launcher.start(() -> {
            var selected = settings.read();
            config.configureDiscordVoice(Boolean.parseBoolean(selected.getOrDefault("DISCORD_VOICE_ENABLED", "" + config.discordVoiceEnabled())));
            config.configureOpenLlm(Boolean.parseBoolean(selected.getOrDefault("OPENLLM_ENABLED", "" + config.openllmConfigured())));
            if (config.openllmEnabled()) {
                conversation.start();
                activity.step("依設定連線 AI", config.discordVoiceEnabled() ? "Discord 語音已啟用，可使用 !join" : "Discord 語音保持停用");
            } else {
                activity.step("AI 服務已啟動", "Open-LLM 連線設定為停用，尚未連線");
            }
        });
        return Map.of("message", "AI 服務正在啟動，全部就緒後將依服務連線設定決定是否連線及啟用 Discord 語音");
    }
}
