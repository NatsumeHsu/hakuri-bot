package tw.hakuri.bot.integration.twitch;

import tw.hakuri.bot.config.BotConfig;
import tw.hakuri.bot.conversation.service.ConversationService;
import tw.hakuri.bot.observability.activity.ActivityLogService;
import jakarta.annotation.PreDestroy;
import org.slf4j.*;
import org.springframework.stereotype.Component;
import java.net.URI;
import java.net.http.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;

/** Anonymous, read-only IRC: one command per WebSocket frame, JOIN only after login. */
@Component
public class TwitchClient {
    private static final Logger log = LoggerFactory.getLogger(TwitchClient.class);
    private final BotConfig config;
    private final ConversationService conversation;
    private final ActivityLogService activity;
    private final ScheduledExecutorService timer = Executors.newSingleThreadScheduledExecutor();
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();
    private final AtomicBoolean retryScheduled = new AtomicBoolean();
    private final AtomicLong received = new AtomicLong(), queued = new AtomicLong();
    private volatile WebSocket socket;
    private volatile Session session;
    private volatile boolean stopped;
    private boolean started;
    private volatile String state = "STOPPED", lastError = "";

    public TwitchClient(BotConfig config, ConversationService conversation, ActivityLogService activity) {
        this.config = config; this.conversation = conversation; this.activity = activity;
    }
    public synchronized void start() {
        if (started || stopped) return;
        if (!config.twitchEnabled()) { state = "DISABLED"; activity.step("Twitch 未啟用", "請在後台設定啟用後重新啟動"); return; }
        started = true; connect();
    }
    public Map<String, Object> snapshot() {
        return Map.of("enabled", config.twitchEnabled(), "channel", config.twitchChannel(), "state", state,
                "joined", state.equals("JOINED"), "received", received.get(), "queued", queued.get(), "lastError", lastError);
    }
    private void connect() {
        if (stopped) return;
        state = "CONNECTING";
        Session listener = newSession();
        http.newWebSocketBuilder().connectTimeout(Duration.ofSeconds(10))
                .buildAsync(URI.create("wss://irc-ws.chat.twitch.tv:443"), listener)
                .whenComplete((ws, error) -> { if (error != null) listener.fail("Twitch 連線失敗：" + error.getClass().getSimpleName()); });
    }
    Session newSession() { Session listener = new Session(); session = listener; return listener; }
    final class Session implements WebSocket.Listener {
        private final StringBuilder buffer = new StringBuilder();
        private final String nickname = "justinfan" + ThreadLocalRandom.current().nextInt(10000, 99999);
        private CompletableFuture<WebSocket> outbound = CompletableFuture.completedFuture(null);
        private WebSocket ws;
        private volatile boolean retried;
        private boolean authenticated, joined;
        private ScheduledFuture<?> handshakeTimeout;

        @Override public void onOpen(WebSocket opened) {
            if (stopped || session != this) { opened.abort(); return; }
            ws = opened; socket = opened; state = "AUTHENTICATING";
            activity.step("Twitch 登入中", "正在登入並加入 #" + config.twitchChannel());
            handshakeTimeout = timer.schedule(() -> fail("Twitch 登入或加入頻道逾時"), 20, TimeUnit.SECONDS);
            send("CAP REQ :twitch.tv/tags twitch.tv/commands twitch.tv/membership");
            send("NICK " + nickname);
            opened.request(1);
        }
        private synchronized void send(String command) {
            if (retried || stopped || session != this) return;
            outbound = outbound.thenCompose(ignored -> ws.sendText(command + "\r\n", true));
            outbound.whenComplete((sent, error) -> { if (error != null) fail("Twitch 指令傳送失敗"); });
        }
        @Override public CompletionStage<?> onText(WebSocket current, CharSequence text, boolean last) {
            if (stopped || session != this || retried) return null;
            buffer.append(text);
            if (buffer.length() > 1024 * 1024) { fail("Twitch 訊息超過大小限制"); return null; }
            int end;
            while ((end = buffer.indexOf("\r\n")) >= 0) {
                String line = buffer.substring(0, end); buffer.delete(0, end + 2); handle(line);
            }
            current.request(1); return null;
        }
        private void handle(String raw) {
            if (raw.startsWith("PING ")) { send("PONG " + raw.substring(5)); return; }
            String line = raw.startsWith("@") && raw.contains(" ") ? raw.substring(raw.indexOf(' ') + 1) : raw;
            String[] parts = line.split(" ", 4);
            String command = parts.length > 1 ? parts[1] : "";
            if (command.equals("RECONNECT")) { fail("Twitch 要求重新連線"); return; }
            if (command.equals("001") && !authenticated) {
                authenticated = true; state = "JOINING"; send("JOIN #" + config.twitchChannel()); return;
            }
            if (command.equals("CAP") && line.contains(" NAK ")) { fail("Twitch 拒絕登入握手指令"); return; }
            if (command.equals("NOTICE")) {
                String message = line.contains(" :") ? line.substring(line.indexOf(" :") + 2) : "聊天室通知";
                activity.step("Twitch 通知", message);
                if (message.contains("authentication failed") || message.contains("Improperly formatted auth")) fail(message);
                return;
            }
            boolean ownJoin = command.equals("JOIN") && line.startsWith(":" + nickname + "!");
            if (authenticated && !joined && (ownJoin || command.equals("ROOMSTATE"))
                    && parts.length > 2 && parts[2].equals("#" + config.twitchChannel())) {
                joined = true; state = "JOINED"; lastError = "";
                if (handshakeTimeout != null) handshakeTimeout.cancel(false);
                activity.step("Twitch 已加入聊天室", "#" + config.twitchChannel() + " 的訊息將送往 AI");
                log.info("Twitch 已加入聊天室 #{}", config.twitchChannel());
            }
            if (joined) accept(raw);
        }
        @Override public CompletionStage<?> onClose(WebSocket current, int code, String reason) { fail("Twitch 連線已關閉（" + code + "）"); return null; }
        @Override public void onError(WebSocket current, Throwable error) { fail("Twitch 連線錯誤：" + error.getClass().getSimpleName()); }
        synchronized void fail(String reason) {
            if (retried || stopped || session != this) return;
            retried = true; if (handshakeTimeout != null) handshakeTimeout.cancel(false);
            lastError = reason; state = "RECONNECTING";
            if (ws != null) ws.abort(); if (socket == ws) socket = null;
            activity.step("Twitch 重新連線", reason); log.warn("{}，3 秒後重試", reason);
            reconnect();
        }
    }
    void accept(String line) {
        String tags = "";
        if (line.startsWith("@")) {
            int space = line.indexOf(' '); if (space < 0) return;
            tags = line.substring(1, space); line = line.substring(space + 1);
        }
        int command = line.indexOf(" PRIVMSG #" + config.twitchChannel() + " :");
        int bang = line.indexOf('!');
        if (!line.startsWith(":") || command < 0 || bang < 0 || bang > command) return;
        String name = line.substring(1, bang);
        for (String tag : tags.split(";")) if (tag.startsWith("display-name=") && tag.length() > 13) name = unescape(tag.substring(13));
        String text = line.substring(line.indexOf(" :", command) + 2);
        received.incrementAndGet();
        activity.step("收到 Twitch 訊息", "#" + config.twitchChannel() + "｜" + name);
        if (conversation.queue(name, text, false)) queued.incrementAndGet();
    }
    static String unescape(String input) {
        StringBuilder output = new StringBuilder();
        for (int i = 0; i < input.length(); i++) {
            char ch = input.charAt(i);
            if (ch == '\\' && ++i < input.length()) {
                ch = switch (input.charAt(i)) { case 's' -> ' '; case ':' -> ';'; case 'r' -> '\r'; case 'n' -> '\n'; default -> input.charAt(i); };
            } else if (ch == '\\') break;
            output.append(ch);
        }
        return output.toString();
    }
    private void reconnect() {
        if (!stopped && retryScheduled.compareAndSet(false, true)) {
            try { timer.schedule(() -> { retryScheduled.set(false); connect(); }, 3, TimeUnit.SECONDS); }
            catch (RejectedExecutionException ignored) { }
        }
    }
    @PreDestroy public void stop() { stopped = true; state = "STOPPED"; timer.shutdownNow(); if (socket != null) socket.abort(); }
}
