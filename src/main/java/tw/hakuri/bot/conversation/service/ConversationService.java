package tw.hakuri.bot.conversation.service;

import tw.hakuri.bot.audio.service.AudioService;
import tw.hakuri.bot.config.BotConfig;
import tw.hakuri.bot.integration.openllm.ProxyClient;
import tw.hakuri.bot.observability.activity.ActivityLogService;

import com.fasterxml.jackson.databind.JsonNode;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import java.util.*;
import java.util.concurrent.*;

@Service
public class ConversationService {
    private static final Logger log = LoggerFactory.getLogger(ConversationService.class);
    private final BotConfig config;
    private final ProxyClient proxy;
    private final AudioService audio;
    private final ActivityLogService activity;
    private final AiReplyService replies;
    private final Deque<String> inputs = new ArrayDeque<>();
    private final Deque<Clip> clips = new ArrayDeque<>();
    private final Deque<Speech> recent = new ArrayDeque<>();
    private final ScheduledExecutorService timer = Executors.newSingleThreadScheduledExecutor();
    private final ExecutorService decoder = Executors.newSingleThreadExecutor();
    private boolean active, interrupt, discard, stopped, voiceReady, decoding;
    private boolean proxyStarted;
    private byte[] pcm;
    private int position;
    private long epoch, voiceEpoch;
    private ScheduledFuture<?> resume;
    record Clip(byte[] wav, String text) { }
    record Speech(String text, long time) { }
    public ConversationService(BotConfig config, ProxyClient proxy, AudioService audio, ActivityLogService activity, AiReplyService replies) {
        this.config = config; this.proxy = proxy; this.audio = audio; this.activity = activity;
        this.replies = replies;
    }
    public synchronized void start() {
        if (stopped || proxyStarted) return;
        if (!config.openllmEnabled()) { log.info("OPENLLM_ENABLED=false，Open-LLM 功能已停用"); return; }
        proxyStarted = true;
        proxy.start(this::message, this::opened, this::disconnected);
    }
    public synchronized long epoch() { return epoch; }
    public synchronized boolean playing() { return pcm != null || decoding || !clips.isEmpty(); }
    public synchronized boolean queue(String name, String text, boolean voice) {
        text = text.trim();
        if (text.isEmpty()) return false;
        if (!voice) activity.input(name, text);
        if (!config.openllmEnabled()) { activity.step("未送出", "AI 功能尚未啟用"); return false; }
        if (stopped || inputs.size() >= config.maxQueue()) { activity.step("未送出", "Bot 已停止或等待佇列已滿"); return false; }
        if (voice && config.requireWakeWord() && !text.contains(config.wakeWord())) { activity.step("略過語音", "辨識文字未包含喚醒詞"); return false; }
        activity.step("文字已排隊", name + (proxy.ready() ? "：等待處理" : "：等待 AI 連線"));
        inputs.add(name + ": " + text); next(); return true;
    }
    private synchronized void opened() { activity.step("AI 已連線", "可以處理文字與語音"); if (interrupt) signalInterrupt(); next(); }
    private synchronized void disconnected() {
        if (active) replies.error();
        active = false; if (resume != null) resume.cancel(false);
        activity.step("AI 連線中斷", "正在重新連線");
    }
    private void next() {
        if (stopped || interrupt || active || playing() || !proxy.ready() || inputs.isEmpty()) return;
        String input = inputs.remove(); active = true;
        replies.begin();
        activity.step("送往 AI", "等待 AI 回覆");
        long generation = epoch;
        proxy.send(Map.of("type", "text-input", "text", input)).whenComplete((ws, error) -> {
            synchronized (this) {
                if (generation != epoch || stopped) return;
                if (error != null) { inputs.addFirst(input); active = false; replies.error(); activity.step("傳送失敗", "文字已放回等待佇列"); }
            }
        });
    }
    private synchronized void message(JsonNode data) {
        switch (data.path("type").asText()) {
            case "audio" -> {
                if (interrupt || discard) return;
                replies.append(data.path("display_text").path("text").asText(""), data.path("display_text").path("name").asText(""));
                if (!voiceReady || clips.size() >= config.maxQueue()) return;
                try {
                    byte[] wav = Base64.getDecoder().decode(data.path("audio").asText());
                    if (wav.length < 44 || wav[0] != 'R' || wav[1] != 'I' || wav[2] != 'F' || wav[3] != 'F'
                            || wav[8] != 'W' || wav[9] != 'A' || wav[10] != 'V' || wav[11] != 'E') return;
                    clips.add(new Clip(wav, data.path("display_text").path("text").asText(""))); playNext();
                } catch (IllegalArgumentException e) { log.warn("略過無效的音訊資料"); }
            }
            case "interrupt-signal" -> { if (interrupt) finishInterrupt(); }
            case "control" -> {
                switch (data.path("text").asText()) {
                    case "conversation-chain-start" -> { if (!interrupt) { if (!active) replies.begin(); discard = false; active = true; activity.step("AI 思考中", "正在產生回覆"); } }
                    case "conversation-chain-end" -> {
                        if (interrupt) finishInterrupt();
                        else if (!discard) { active = false; replies.complete(); activity.step("AI 回覆完成", playing() ? "等待語音播放完成" : "等待下一則輸入"); next(); }
                    }
                }
            }
            case "error" -> { log.warn("Proxy 回報錯誤: {}", data.path("message").asText()); replies.error(); activity.step("AI 處理失敗", "請重試或查看服務狀態"); active = false; next(); }
            default -> { }
        }
    }
    public synchronized void interrupt() {
        if (!config.openllmEnabled()) return;
        activity.step("中斷對話", "清除等待文字與待播放語音");
        replies.interrupt();
        epoch++; interrupt = true; discard = true; active = false;
        inputs.clear(); clearPlayback();
        if (resume != null) resume.cancel(false);
        if (proxy.ready()) signalInterrupt();
    }
    private void signalInterrupt() {
        long generation = epoch;
        proxy.send(Map.of("type", "interrupt-signal")).whenComplete((ws, error) -> {
            synchronized (this) {
                if (stopped || generation != epoch || !interrupt || error != null) return;
                if (resume != null) resume.cancel(false);
                resume = timer.schedule(() -> { synchronized (this) {
                    if (generation == epoch && proxy.ready()) finishInterrupt();
                } }, 1500, TimeUnit.MILLISECONDS);
            }
        });
    }
    private void finishInterrupt() { if (resume != null) resume.cancel(false); interrupt = false; active = false; next(); }
    public synchronized void setVoiceReady(boolean ready) {
        if (voiceReady != ready) activity.step(ready ? "語音頻道已連線" : "已離開語音頻道", ready ? "可以接收語音與播放回覆" : "已停止錄音與播放");
        voiceReady = ready; if (!ready) clearPlayback(); else playNext(); next();
    }
    private void clearPlayback() { voiceEpoch++; clips.clear(); pcm = null; position = 0; decoding = false; }
    private void playNext() {
        if (stopped || interrupt || !voiceReady || decoding || pcm != null) return;
        Clip clip = clips.poll();
        if (clip == null) { next(); return; }
        decoding = true; long generation = voiceEpoch;
        decoder.submit(() -> {
            byte[] converted = null;
            try { converted = audio.playback(clip.wav()); }
            catch (Exception e) { log.warn("播放轉碼失敗: {}", e.getMessage()); activity.step("播放失敗", "語音轉換失敗，將略過這段回覆"); }
            synchronized (this) {
                if (stopped || generation != voiceEpoch) return;
                decoding = false;
                if (converted != null && converted.length > 0) {
                    pcm = converted; position = 0;
                    activity.step("播放回覆", "正在播放 AI 語音");
                    recent.add(new Speech(normalize(clip.text()), System.currentTimeMillis()));
                    while (recent.size() > config.maxQueue()) recent.remove();
                } else playNext();
            }
        });
    }
    public synchronized boolean canProvide() { return pcm != null; }
    public synchronized byte[] frame() {
        if (pcm == null) return null;
        byte[] frame = new byte[3840];
        int count = Math.min(frame.length, pcm.length - position);
        System.arraycopy(pcm, position, frame, 0, count); position += count;
        if (position >= pcm.length) {
            pcm = null;
            // Let the final 20 ms frame finish before advancing the conversation.
            long generation = voiceEpoch;
            decoding = true;
            timer.schedule(() -> { synchronized (this) {
                if (generation == voiceEpoch && !stopped) { decoding = false; activity.step("語音播放完成", "處理下一段回覆或等待輸入"); playNext(); }
            } }, 20, TimeUnit.MILLISECONDS);
        }
        return frame;
    }
    public synchronized boolean echo(String text, boolean overlap) {
        String normalized = normalize(text);
        return overlap && normalized.length() >= 8 && recent.stream().anyMatch(item ->
                System.currentTimeMillis() - item.time() < 30000 && item.text().equals(normalized));
    }
    static String normalize(String text) { return text.replaceAll("[\\p{P}\\p{Z}\\s]", ""); }
    public boolean isInterrupt(String text) {
        String value = normalize(text);
        return List.of("停", "閉嘴", "等一下", "先不要說話").stream().anyMatch(suffix -> value.contains(config.wakeWord() + suffix));
    }
    public synchronized void clear() { inputs.clear(); activity.step("已清空佇列", "等待新的輸入"); }
    public synchronized Map<String, Object> snapshot() {
        return Map.of("openllmEnabled", config.openllmEnabled(), "proxyConnected", config.openllmEnabled() && proxy.ready(),
                "voiceConnected", voiceReady, "conversationActive", active, "playing", playing(),
                "inputCount", inputs.size(), "audioCount", clips.size(), "queue", List.copyOf(inputs), "interruptPending", interrupt);
    }
    public synchronized String status(int pendingAsr) {
        if (!config.openllmEnabled()) return "Open-LLM: 已停用 (OPENLLM_ENABLED=false)\n語音頻道: " + voiceReady;
        return "Proxy: " + proxy.ready() + "\n語音頻道: " + voiceReady + "\nASR 處理中: " + pendingAsr
                + "\n文字等待: " + inputs.size() + "\n音訊等待: " + clips.size() + "\n對話中: " + active + "\n播放中: " + playing();
    }
    @PreDestroy public synchronized void stop() { stopped = true; clearPlayback(); timer.shutdownNow(); decoder.shutdownNow(); }
}
