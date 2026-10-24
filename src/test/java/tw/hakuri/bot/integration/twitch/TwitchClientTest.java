package tw.hakuri.bot.integration.twitch;

import tw.hakuri.bot.config.BotConfig;
import tw.hakuri.bot.conversation.service.ConversationService;
import tw.hakuri.bot.audio.service.AudioService;
import tw.hakuri.bot.integration.openllm.ProxyClient;
import tw.hakuri.bot.observability.activity.ActivityLogService;

import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import java.net.http.WebSocket;
import java.util.*;
import java.util.concurrent.CompletableFuture;

class TwitchClientTest {
    @Test void parsesDisplayNameAndPreservesMessageColons() {
        var conversation = mock(ConversationService.class);
        var twitch = new TwitchClient(new BotConfig(new MockEnvironment()), conversation, mock(ActivityLogService.class));
        try {
            twitch.accept("@display-name=白璃\\s觀眾;id=123 :viewer!viewer@viewer.tmi.twitch.tv PRIVMSG #natsume0304 :你好 : 測試");
            verify(conversation).queue("白璃 觀眾", "你好 : 測試", false);
            twitch.accept(":server NOTICE #natsume0304 :ignored");
            twitch.accept(":viewer!viewer@host PRIVMSG #other :ignored");
            verifyNoMoreInteractions(conversation);
            assertThat(TwitchClient.unescape("one\\:two\\\\three")).isEqualTo("one;two\\three");
        } finally { twitch.stop(); }
    }
    @Test void sendsOneCommandPerFrameJoinsAfterLoginAndForwardsActualPrivmsgToProxy() {
        var config = new BotConfig(new MockEnvironment().withProperty("TWITCH_ENABLED", "true").withProperty("OPENLLM_ENABLED", "true"));
        var proxy = mock(ProxyClient.class); var activity = mock(ActivityLogService.class);
        when(proxy.ready()).thenReturn(true);
        when(proxy.send(anyMap())).thenReturn(CompletableFuture.completedFuture(null));
        var conversation = new ConversationService(config, proxy, mock(AudioService.class), activity, new tw.hakuri.bot.conversation.service.AiReplyService());
        var twitch = new TwitchClient(config, conversation, activity);
        var ws = mock(WebSocket.class);
        var frames = new ArrayList<String>();
        when(ws.sendText(any(), eq(true))).thenAnswer(call -> { frames.add(call.getArgument(0).toString()); return CompletableFuture.completedFuture(ws); });
        try {
            var session = twitch.newSession(); session.onOpen(ws);
            assertThat(frames).hasSize(2);
            assertThat(frames.get(0)).isEqualTo("CAP REQ :twitch.tv/tags twitch.tv/commands twitch.tv/membership\r\n");
            assertThat(frames.get(1)).startsWith("NICK justinfan").endsWith("\r\n");
            session.onText(ws, ":tmi.twitch.tv 001 justinfan :Welcome, GLHF!\r\n", true);
            assertThat(frames.getLast()).isEqualTo("JOIN #natsume0304\r\n");
            assertThat(twitch.snapshot()).containsEntry("joined", false);
            session.onText(ws, "@room-id=1016415181 :tmi.twitch.tv ROOMSTATE #natsume0304\r\n", true);
            assertThat(twitch.snapshot()).containsEntry("joined", true);
            session.onText(ws, "@display-name=觀眾A :viewer!viewer@host PRIVMSG #natsume0304 :白璃你好\r", false);
            verify(proxy, never()).send(anyMap());
            session.onText(ws, "\n", true);
            verify(proxy).send(Map.of("type", "text-input", "text", "觀眾A: 白璃你好"));
            assertThat(twitch.snapshot()).containsEntry("received", 1L).containsEntry("queued", 1L);
            verify(activity).input("觀眾A", "白璃你好");
        } finally { twitch.stop(); conversation.stop(); }
    }
    @Test void serializesPendingSendsAndRespondsToPingWithoutCombiningFrames() {
        var config = new BotConfig(new MockEnvironment());
        var twitch = new TwitchClient(config, mock(ConversationService.class), mock(ActivityLogService.class));
        var ws = mock(WebSocket.class); var first = new CompletableFuture<WebSocket>();
        when(ws.sendText(any(), eq(true))).thenReturn(first, CompletableFuture.completedFuture(ws));
        try {
            var session = twitch.newSession(); session.onOpen(ws);
            verify(ws, times(1)).sendText(any(), eq(true));
            first.complete(ws);
            verify(ws, times(2)).sendText(any(), eq(true));
            session.onText(ws, "PING :tmi.twitch.tv\r\n", true);
            verify(ws).sendText("PONG :tmi.twitch.tv\r\n", true);
        } finally { twitch.stop(); }
    }
    @Test void capabilityRejectionReportsFailureRatherThanSuccessfulJoin() {
        var activity = mock(ActivityLogService.class);
        var twitch = new TwitchClient(new BotConfig(new MockEnvironment()), mock(ConversationService.class), activity);
        var ws = mock(WebSocket.class); when(ws.sendText(any(), eq(true))).thenReturn(CompletableFuture.completedFuture(ws));
        try {
            var session = twitch.newSession(); session.onOpen(ws);
            session.onText(ws, ":tmi.twitch.tv CAP * NAK :NICK bad JOIN #natsume0304\r\n", true);
            assertThat(twitch.snapshot()).containsEntry("joined", false).containsEntry("state", "RECONNECTING");
            verify(ws).abort();
        } finally { twitch.stop(); }
    }
}
