package tw.hakuri.bot.admin.service;

import tw.hakuri.bot.conversation.service.ConversationService;
import tw.hakuri.bot.integration.discord.DiscordBot;
import tw.hakuri.bot.integration.aivt.AivtLauncher;
import tw.hakuri.bot.integration.twitch.TwitchClient;
import org.springframework.stereotype.Service;
import java.time.Instant;
import java.util.Map;
import java.util.NoSuchElementException;

@Service
public class AdminService {
    private final DiscordBot discord;
    private final ConversationService conversation;
    private final AdminSettings settings;
    private final AdminCredentialService credentials;
    private final AivtLauncher aivt;
    private final AiActivationService aiActivation;
    private final TwitchClient twitch;

    public AdminService(DiscordBot discord, ConversationService conversation, AdminSettings settings, AdminCredentialService credentials, AivtLauncher aivt, AiActivationService aiActivation, TwitchClient twitch) {
        this.discord = discord; this.conversation = conversation; this.settings = settings; this.credentials = credentials;
        this.aivt = aivt;
        this.aiActivation = aiActivation;
        this.twitch = twitch;
    }
    public Map<String, Object> status() {
        return Map.of("discord", discord.snapshot(), "conversation", conversation.snapshot(), "aivt", aivt.status(), "twitch", twitch.snapshot(), "time", Instant.now().toString());
    }
    public Map<String, Object> settings() { return settings.view(); }
    public Map<String, Object> saveSettings(Map<String, String> values) { return settings.save(values); }
    public Map<String, String> changePassword(String password) {
        credentials.changePassword(password);
        return Map.of("message", "管理員密碼已更新，下次登入請使用新密碼");
    }
    public Map<String, String> send(String text) {
        if (text == null || text.isBlank() || text.length() > 2000) throw new IllegalArgumentException("請輸入 1–2000 字的訊息");
        if (!conversation.queue("管理員", text, false)) throw new IllegalStateException("Open-LLM 已停用或佇列已滿");
        return Map.of("message", "已加入對話佇列");
    }
    public Map<String, String> action(String action) {
        if (action.equals("start-aivt")) return aiActivation.start();
        switch (action) {
            case "clear" -> conversation.clear();
            case "interrupt" -> conversation.interrupt();
            case "leave" -> discord.leave();
            default -> throw new NoSuchElementException("操作不存在");
        }
        return Map.of("message", "操作完成");
    }
}
