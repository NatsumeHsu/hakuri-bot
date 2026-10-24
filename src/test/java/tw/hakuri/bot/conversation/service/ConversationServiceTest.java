package tw.hakuri.bot.conversation.service;

import tw.hakuri.bot.observability.activity.ActivityLogService;

import tw.hakuri.bot.audio.service.AudioService;
import tw.hakuri.bot.config.BotConfig;
import tw.hakuri.bot.integration.openllm.ProxyClient;

import com.fasterxml.jackson.databind.*;
import org.junit.jupiter.api.*;
import org.springframework.mock.env.MockEnvironment;
import java.nio.ByteBuffer;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.Consumer;
import static org.assertj.core.api.Assertions.*;
import static org.awaitility.Awaitility.await;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class ConversationServiceTest {
    ProxyClient proxy;
    AudioService audio;
    ConversationService service;
    ActivityLogService activity;
    AiReplyService replies;
    Consumer<JsonNode> incoming;
    Runnable opened, disconnected;
    final List<Map<String, String>> sent = new CopyOnWriteArrayList<>();
    final ObjectMapper json = new ObjectMapper();
    @SuppressWarnings("unchecked")
    @BeforeEach void setup() {
        proxy = mock(ProxyClient.class); audio = mock(AudioService.class);
        when(proxy.ready()).thenReturn(true);
        when(proxy.send(anyMap())).thenAnswer(call -> { sent.add(Map.copyOf(call.getArgument(0))); return CompletableFuture.completedFuture(null); });
        doAnswer(call -> { incoming = call.getArgument(0); opened = call.getArgument(1); disconnected = call.getArgument(2); return null; })
                .when(proxy).start(any(), any(), any());
        activity = mock(ActivityLogService.class);
        replies = new AiReplyService();
        service = new ConversationService(new BotConfig(new MockEnvironment().withProperty("OPENLLM_ENABLED", "true")), proxy, audio, activity, replies);
        service.start();
    }
    @AfterEach void cleanup() { service.stop(); }
    @Test void capturesActualAiTextEvenWithoutDiscordVoiceOrAudioPayload() {
        message("control", "conversation-chain-start");
        var first = json.createObjectNode().put("type", "audio");
        first.putNull("audio"); first.putObject("display_text").put("text", "AI 的第一段。").put("name", "白璃");
        incoming.accept(first); assertThat(replies.snapshot().text()).isEqualTo("AI 的第一段。");
        first.withObject("display_text").put("text", "第二段。"); incoming.accept(first);
        assertThat(replies.snapshot().text()).isEqualTo("AI 的第一段。第二段。");
        message("control", "conversation-chain-end"); assertThat(replies.snapshot().state()).isEqualTo("COMPLETE");
        message("control", "conversation-chain-start"); first.withObject("display_text").put("text", "下一輪回覆。"); incoming.accept(first);
        assertThat(replies.snapshot().text()).isEqualTo("下一輪回覆。"); verifyNoInteractions(audio);
    }
    @Test void ignoresInterruptedTextAndBackendThinkingPlaceholder() {
        message("full-text", "Thinking..."); assertThat(replies.snapshot().text()).isEmpty();
        service.interrupt(); message("interrupt-signal", ""); sendAudio();
        assertThat(replies.snapshot().text()).isEmpty(); assertThat(replies.snapshot().state()).isEqualTo("INTERRUPTED");
    }
    @Test void recordsInputAndWorkflowInProcessingOrder() {
        service.queue("觀眾", "你好", false);
        var order = inOrder(activity);
        order.verify(activity).input("觀眾", "你好");
        order.verify(activity).step("文字已排隊", "觀眾：等待處理");
        order.verify(activity).step("送往 AI", "等待 AI 回覆");
        message("control", "conversation-chain-start"); message("control", "conversation-chain-end");
        order.verify(activity).step("AI 思考中", "正在產生回覆");
        order.verify(activity).step("AI 回覆完成", "等待下一則輸入");
    }
    void message(String type, String text) {
        var value = json.createObjectNode().put("type", type).put("text", text); incoming.accept(value);
    }
    @Test void waitsForConversationEndBeforeSendingNextInput() {
        service.queue("A", "one", false); service.queue("B", "two", false);
        assertThat(sent).hasSize(1);
        message("backend-synth-complete", ""); assertThat(sent).hasSize(1);
        message("control", "conversation-chain-end");
        assertThat(sent).hasSize(2); assertThat(sent.get(1).get("text")).isEqualTo("B: two");
    }
    @Test void interruptionClearsQueueAndIgnoresOldAudioUntilNextStart() throws Exception {
        service.setVoiceReady(true); service.queue("A", "one", false); service.queue("B", "two", false);
        long before = service.epoch(); service.interrupt();
        assertThat(service.epoch()).isGreaterThan(before);
        assertThat(sent.getLast().get("type")).isEqualTo("interrupt-signal");
        message("interrupt-signal", "");
        sendAudio(); verifyNoInteractions(audio);
        service.queue("C", "three", false);
        assertThat(sent.getLast().get("text")).isEqualTo("C: three");
        message("control", "conversation-chain-start");
        when(audio.playback(any())).thenReturn(new byte[3840]); sendAudio();
        await().atMost(Duration.ofSeconds(2)).until(service::canProvide);
        assertThat(service.frame()).hasSize(3840);
        assertThat(sent.stream().noneMatch(item -> "B: two".equals(item.get("text")))).isTrue();
    }
    @Test void waitsForPlaybackAfterBackendEnds() throws Exception {
        when(audio.playback(any())).thenReturn(new byte[7680]);
        service.setVoiceReady(true); service.queue("A", "one", false);
        message("control", "conversation-chain-start"); sendAudio();
        await().atMost(Duration.ofSeconds(2)).until(service::canProvide);
        service.queue("B", "two", false); message("control", "conversation-chain-end");
        assertThat(sent).hasSize(1);
        service.frame(); assertThat(sent).hasSize(1); service.frame();
        await().atMost(Duration.ofSeconds(2)).untilAsserted(() -> assertThat(sent).hasSize(2));
    }
    @Test void leavingRejectsAnInFlightAudioDecode() throws Exception {
        var started = new CountDownLatch(1); var release = new CountDownLatch(1);
        when(audio.playback(any())).thenAnswer(call -> { started.countDown(); release.await(2, TimeUnit.SECONDS); return new byte[3840]; });
        service.setVoiceReady(true); sendAudio(); assertThat(started.await(2, TimeUnit.SECONDS)).isTrue();
        service.setVoiceReady(false); release.countDown();
        await().during(Duration.ofMillis(100)).atMost(Duration.ofSeconds(1)).untilAsserted(() -> assertThat(service.canProvide()).isFalse());
    }
    @Test void wakeWordAppliesOnlyToVoiceAndQueueIsBounded() {
        var limited = new ConversationService(new BotConfig(new MockEnvironment().withProperty("OPENLLM_ENABLED", "true").withProperty("REQUIRE_WAKE_WORD", "true")
                .withProperty("VOICE_MAX_QUEUE", "1")), proxy, audio, mock(ActivityLogService.class), new tw.hakuri.bot.conversation.service.AiReplyService());
        try {
            when(proxy.ready()).thenReturn(false);
            assertThat(limited.queue("A", "你好", true)).isFalse();
            assertThat(limited.queue("A", "你好", false)).isTrue();
            assertThat(limited.queue("B", "白璃你好", true)).isFalse();
            limited.clear(); assertThat(limited.queue("B", "白璃你好", true)).isTrue();
            assertThat(limited.isInterrupt("白璃，等一下！")).isTrue();
        } finally { limited.stop(); }
    }
    @Test void retriesFailedInputAfterReconnect() {
        doReturn(CompletableFuture.failedFuture(new IllegalStateException("offline"))).when(proxy).send(anyMap());
        service.queue("A", "retry me", false);
        disconnected.run();
        doAnswer(call -> { sent.add(Map.copyOf(call.getArgument(0))); return CompletableFuture.completedFuture(null); }).when(proxy).send(anyMap());
        opened.run();
        assertThat(sent).hasSize(1); assertThat(sent.getFirst().get("text")).isEqualTo("A: retry me");
    }
    @Test void disabledOpenLlmDoesNotConnectOrQueueOrInterrupt() {
        var disabledProxy = mock(ProxyClient.class);
        var disabledAudio = mock(AudioService.class);
        var disabled = new ConversationService(new BotConfig(new MockEnvironment().withProperty("OPENLLM_ENABLED", "false")),
                disabledProxy, disabledAudio, mock(ActivityLogService.class), new tw.hakuri.bot.conversation.service.AiReplyService());
        try {
            disabled.start();
            assertThat(disabled.queue("Discord", "hello", false)).isFalse();
            assertThat(disabled.queue("Voice", "hello", true)).isFalse();
            assertThat(disabled.queue("Twitch", "hello", false)).isFalse();
            disabled.interrupt();
            assertThat(disabled.status(0)).contains("OPENLLM_ENABLED=false");
            verifyNoInteractions(disabledProxy, disabledAudio);
        } finally { disabled.stop(); }
    }
    void sendAudio() {
        var node = json.createObjectNode().put("type", "audio").put("audio", Base64.getEncoder().encodeToString(AudioService.wav(new byte[2])));
        node.putObject("display_text").put("text", "這是一段用來測試回音的語音"); incoming.accept(node);
    }
}
