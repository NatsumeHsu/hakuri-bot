package tw.hakuri.bot.integration.discord;

import tw.hakuri.bot.audio.service.AudioService;
import tw.hakuri.bot.config.BotConfig;
import tw.hakuri.bot.conversation.service.ConversationService;
import tw.hakuri.bot.integration.twitch.TwitchClient;
import tw.hakuri.bot.observability.activity.ActivityLogService;

import club.minnced.discord.jdave.interop.JDaveSessionFactory;
import jakarta.annotation.PreDestroy;
import net.dv8tion.jda.api.*;
import net.dv8tion.jda.api.audio.*;
import net.dv8tion.jda.api.entities.User;
import net.dv8tion.jda.api.events.message.MessageReceivedEvent;
import net.dv8tion.jda.api.hooks.ListenerAdapter;
import net.dv8tion.jda.api.managers.AudioManager;
import net.dv8tion.jda.api.requests.GatewayIntent;
import net.dv8tion.jda.api.utils.cache.CacheFlag;
import org.slf4j.*;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;
import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;
import java.util.*;
import java.util.concurrent.*;

@Component
public class DiscordBot extends ListenerAdapter implements ApplicationRunner {
    private static final Logger log = LoggerFactory.getLogger(DiscordBot.class);
    private final BotConfig config;
    private final ConversationService conversation;
    private final AudioService audio;
    private final TwitchClient twitch;
    private final ActivityLogService activity;
    private final ScheduledExecutorService timer = Executors.newSingleThreadScheduledExecutor();
    private final ExecutorService asrWorkers = Executors.newVirtualThreadPerTaskExecutor();
    private final Semaphore asrSlots = new Semaphore(3);
    private final Map<String, Recording> recordings = new HashMap<>();
    private final Map<String, Long> cooldowns = new HashMap<>();
    private JDA jda;
    private AudioManager manager;
    private long generation;
    private boolean stopped;

    private static class Recording {
        final User user;
        final String name;
        final long started = System.nanoTime(), generation, epoch;
        final ByteArrayOutputStream pcm = new ByteArrayOutputStream();
        long last = started;
        boolean overlap;
        Recording(User user, String name, long generation, long epoch) {
            this.user = user; this.name = name; this.generation = generation; this.epoch = epoch;
        }
    }
    public DiscordBot(BotConfig config, ConversationService conversation, AudioService audio, TwitchClient twitch, ActivityLogService activity) {
        this.config = config; this.conversation = conversation; this.audio = audio; this.twitch = twitch; this.activity = activity;
    }
    @Override public void run(ApplicationArguments args) throws Exception {
        config.disableOpenLlm(); // AI is activated only after the managed services are ready.
        if (!config.enabled()) { log.info("BOT_ENABLED=false，外部連線已停用"); return; }
        if (config.token().isBlank()) throw new IllegalArgumentException("請在 .env 設定 DISCORD_TOKEN");
        twitch.start();
        jda = JDABuilder.createDefault(config.token(), GatewayIntent.GUILD_MESSAGES, GatewayIntent.MESSAGE_CONTENT,
                        GatewayIntent.GUILD_VOICE_STATES)
                .enableCache(CacheFlag.VOICE_STATE)
                .setAudioModuleConfig(new AudioModuleConfig().withDaveSessionFactory(new JDaveSessionFactory()))
                .addEventListeners(this).build();
        jda.awaitReady();
        timer.scheduleAtFixedRate(this::flushSilence, 50, 50, TimeUnit.MILLISECONDS);
        log.info("Discord 已登入: {}", jda.getSelfUser().getName());
        activity.step("Discord 已連線", "可以接收文字與語音指令");
    }
    @Override public void onMessageReceived(MessageReceivedEvent event) {
        if (!event.isFromGuild() || event.getAuthor().isBot()) return;
        String text = event.getMessage().getContentRaw().trim();
        if (!config.openllmEnabled() && (text.equals("!stop") || text.startsWith("!say "))) {
            if (text.startsWith("!say ")) activity.input(event.getAuthor().getName(), text.substring(5));
            activity.step("AI 功能未啟用", "請先從後台啟動 AI");
            reply(event, "AI 尚未就緒或 Open-LLM 連線未啟用，請在後台儲存連線設定並啟動 AI 服務");
            return;
        }
        try {
            switch (text) {
                case "!join" -> {
                    if (!config.discordVoiceEnabled()) { reply(event, "Discord 語音已停用，請在後台服務連線設定啟用後再啟動 AI"); return; }
                    if (!config.openllmEnabled()) { reply(event, "AI 尚未就緒或 Open-LLM 連線已停用，請先從後台啟動 AI 並確認設定"); return; }
                    var member = event.getMember();
                    var channel = member == null || member.getVoiceState() == null ? null : member.getVoiceState().getChannel();
                    if (channel == null) { reply(event, "請先加入語音頻道"); return; }
                    synchronized (this) {
                        leave();
                        manager = event.getGuild().getAudioManager();
                        long currentGeneration = generation;
                        manager.setSelfDeafened(false); manager.setSelfMuted(false);
                        manager.setReceivingHandler(new AudioReceiveHandler() {
                            @Override public boolean canReceiveUser() { return config.discordVoiceEnabled() && config.openllmEnabled(); }
                            @Override public void handleUserAudio(UserAudio input) { receive(input, currentGeneration); }
                        });
                        manager.setSendingHandler(new AudioSendHandler() {
                            @Override public boolean canProvide() { return config.discordVoiceEnabled() && config.openllmEnabled() && conversation.canProvide(); }
                            @Override public ByteBuffer provide20MsAudio() {
                                byte[] frame = conversation.frame(); return frame == null ? null : ByteBuffer.wrap(frame);
                            }
                            @Override public boolean isOpus() { return false; }
                        });
                        manager.openAudioConnection(channel);
                    }
                    reply(event, "正在加入語音頻道，連線完成後開始接收語音");
                }
                case "!leave" -> { leave(); reply(event, "已離開語音頻道"); }
                case "!stop" -> { conversation.interrupt(); reply(event, "已中斷目前對話與語音"); }
                case "!clear" -> { conversation.clear(); reply(event, "已清除等待中的文字訊息"); }
                case "!status" -> reply(event, conversation.status(3 - asrSlots.availablePermits()));
                default -> {
                    if (text.startsWith("!say ")) {
                        String name = event.getMember() == null ? event.getAuthor().getName() : event.getMember().getEffectiveName();
                        if (!conversation.queue(name, text.substring(5), false)) reply(event, "訊息為空或佇列已滿");
                    }
                }
            }
        } catch (Exception e) { log.warn("Discord 指令失敗: {}", e.getMessage()); reply(event, "指令執行失敗，請查看程式日誌"); }
    }
    private void reply(MessageReceivedEvent event, String text) {
        event.getMessage().reply(text).queue(ignored -> { }, error -> log.warn("Discord 回覆失敗: {}", error.getMessage()));
    }
    private synchronized void receive(UserAudio input, long currentGeneration) {
        if (!config.discordVoiceEnabled() || !config.openllmEnabled() || stopped || currentGeneration != generation || input.getUser().isBot() || manager == null) return;
        String id = input.getUser().getId();
        long now = System.nanoTime();
        if (cooldowns.getOrDefault(id, 0L) > now) return;
        Recording recording = recordings.get(id);
        if (recording == null) {
            if (recordings.size() >= config.maxQueue()) return;
            var member = manager.getGuild().getMember(input.getUser());
            String name = member == null ? input.getUser().getName() : member.getEffectiveName();
            recording = new Recording(input.getUser(), name, generation, conversation.epoch());
            recordings.put(id, recording);
            activity.step("收到語音", name + "：正在錄音");
        }
        recording.last = now;
        recording.overlap |= conversation.playing();
        byte[] pcm = input.getAudioData(1.0); // JDA: 48 kHz stereo, signed 16-bit big endian.
        if (recording.pcm.size() + pcm.length > 192000 * config.maxSeconds() || now - recording.started >= config.maxSeconds() * 1e9) {
            activity.step("略過錄音", recording.name + "：語音超過長度上限");
            recordings.remove(id); cooldowns.put(id, now + TimeUnit.MILLISECONDS.toNanos(config.cooldownMs())); return;
        }
        recording.pcm.writeBytes(pcm);
    }
    private synchronized void flushSilence() {
        if (stopped) return;
        try {
            conversation.setVoiceReady(config.discordVoiceEnabled() && config.openllmEnabled() && manager != null && manager.isConnected());
            if (!config.discordVoiceEnabled() || !config.openllmEnabled()) { recordings.clear(); cooldowns.clear(); return; }
            long now = System.nanoTime();
            cooldowns.values().removeIf(until -> until <= now);
            var iterator = recordings.entrySet().iterator();
            while (iterator.hasNext()) {
                var entry = iterator.next();
                Recording recording = entry.getValue();
                boolean tooLong = now - recording.started >= config.maxSeconds() * 1e9;
                if (!tooLong && now - recording.last < TimeUnit.MILLISECONDS.toNanos(config.silenceMs())) continue;
                iterator.remove();
                cooldowns.put(entry.getKey(), now + TimeUnit.MILLISECONDS.toNanos(config.cooldownMs()));
                if (tooLong || recording.pcm.size() < 192000 * config.minSeconds()) {
                    activity.step("略過錄音", recording.name + (tooLong ? "：語音過長" : "：語音過短")); continue;
                }
                if (!asrSlots.tryAcquire()) { activity.step("語音辨識忙碌", recording.name + "：目前辨識工作已滿"); continue; }
                asrWorkers.submit(() -> transcribe(recording));
            }
        } catch (Exception e) { log.warn("語音分段失敗: {}", e.getMessage()); }
    }
    private void transcribe(Recording recording) {
        try {
            synchronized (this) {
                if (stopped || !config.discordVoiceEnabled() || !config.openllmEnabled() || recording.generation != generation) return;
            }
            String text = audio.transcribe(recording.pcm.toByteArray(), recording.name);
            synchronized (this) {
                if (stopped || !config.discordVoiceEnabled() || !config.openllmEnabled() || recording.generation != generation || recording.epoch != conversation.epoch()) { activity.step("略過舊語音", "語音功能已停用、對話中斷或已離開頻道"); return; }
                if (text.isBlank()) return;
                if (conversation.echo(text, recording.overlap)) { activity.step("略過回音", "辨識文字與最近播放的回覆相同"); return; }
                if (conversation.isInterrupt(text)) conversation.interrupt();
                else conversation.queue(recording.name, text, true);
            }
        } catch (Exception e) { log.warn("ASR 失敗: {}", e.getMessage()); }
        finally { asrSlots.release(); }
    }
    public synchronized void leave() {
        generation++; recordings.clear(); cooldowns.clear();
        conversation.setVoiceReady(false);
        if (manager != null) { manager.closeAudioConnection(); manager.setReceivingHandler(null); manager.setSendingHandler(null); manager = null; }
    }
    public synchronized Map<String, Object> snapshot() {
        return Map.of("enabled", config.enabled(), "connected", jda != null && jda.getStatus() == JDA.Status.CONNECTED,
                "voiceEnabled", config.discordVoiceEnabled(),
                "name", jda == null ? "尚未登入" : jda.getSelfUser().getName(),
                "pendingAsr", 3 - asrSlots.availablePermits(), "recordings", recordings.size());
    }
    @PreDestroy public synchronized void stop() {
        stopped = true; leave(); timer.shutdownNow(); asrWorkers.shutdownNow();
        if (jda != null) jda.shutdownNow();
    }
}
