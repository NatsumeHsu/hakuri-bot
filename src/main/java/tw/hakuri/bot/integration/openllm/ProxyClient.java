package tw.hakuri.bot.integration.openllm;

import tw.hakuri.bot.config.BotConfig;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import java.net.URI;
import java.net.http.*;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;

@Component
public class ProxyClient {
    private static final Logger log = LoggerFactory.getLogger(ProxyClient.class);
    private final BotConfig config;
    private final ObjectMapper json = new ObjectMapper();
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();
    private final ScheduledExecutorService timer = Executors.newSingleThreadScheduledExecutor();
    private volatile WebSocket socket;
    private volatile boolean stopped;
    private final AtomicBoolean retryScheduled = new AtomicBoolean();
    private volatile Consumer<WebSocket> connectionFailed;
    private Consumer<JsonNode> messages;
    private Runnable opened, closed;
    public ProxyClient(BotConfig config) { this.config = config; }
    public void start(Consumer<JsonNode> messages, Runnable opened, Runnable closed) {
        if (!config.openllmEnabled()) return;
        this.messages = messages; this.opened = opened; this.closed = closed; connect();
    }
    public boolean ready() { return socket != null && !socket.isOutputClosed(); }
    public CompletableFuture<WebSocket> send(Map<String, String> data) {
        WebSocket current = socket;
        if (current == null) return CompletableFuture.failedFuture(new IllegalStateException("Proxy 未連線"));
        try {
            return current.sendText(json.writeValueAsString(data), true).whenComplete((ws, error) -> {
                if (error != null && socket == current) {
                    Consumer<WebSocket> failed = connectionFailed;
                    // Requeue the input callback before reconnecting; no immediate retry loop.
                    timer.execute(() -> { current.abort(); if (failed != null) failed.accept(current); });
                }
            });
        }
        catch (Exception e) { return CompletableFuture.failedFuture(e); }
    }
    private void connect() {
        if (stopped) return;
        http.newWebSocketBuilder().connectTimeout(Duration.ofSeconds(10)).buildAsync(URI.create(config.ws()), new WebSocket.Listener() {
            private final StringBuilder text = new StringBuilder();
            private boolean disconnected;
            @Override public void onOpen(WebSocket ws) {
                if (stopped) { ws.abort(); return; }
                connectionFailed = this::disconnect;
                socket = ws; ws.request(1); log.info("Proxy 已連線"); opened.run();
            }
            @Override public CompletionStage<?> onText(WebSocket ws, CharSequence part, boolean last) {
                text.append(part);
                if (text.length() > 32 * 1024 * 1024) { ws.abort(); disconnect(ws); return null; }
                if (last) {
                    try { messages.accept(json.readTree(text.toString())); }
                    catch (Exception e) { log.warn("Proxy 訊息處理失敗: {}", e.getMessage()); }
                    text.setLength(0);
                }
                ws.request(1); return null;
            }
            @Override public CompletionStage<?> onClose(WebSocket ws, int status, String reason) { disconnect(ws); return null; }
            @Override public void onError(WebSocket ws, Throwable error) { disconnect(ws); }
            private synchronized void disconnect(WebSocket ws) {
                if (disconnected) return;
                disconnected = true;
                if (socket == ws) socket = null;
                closed.run(); retry();
            }
        }).whenComplete((ws, error) -> { if (error != null) retry(); });
    }
    private void retry() {
        if (!stopped && retryScheduled.compareAndSet(false, true)) {
            log.warn("Proxy 連線中斷，3 秒後重試");
            try { timer.schedule(() -> { retryScheduled.set(false); connect(); }, 3, TimeUnit.SECONDS); }
            catch (RejectedExecutionException ignored) { }
        }
    }
    @PreDestroy public void stop() { stopped = true; timer.shutdownNow(); if (socket != null) socket.abort(); }
}
