package tw.hakuri.bot.integration.aivt.log;

import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import org.springframework.stereotype.Service;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.nio.file.attribute.BasicFileAttributes;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.LongSupplier;
import java.util.regex.Pattern;

/** Bounded incremental file tailing: child output cannot block on a slow or disconnected browser. */
@Service
public class AiLogService {
    private static final int READ_LIMIT = 256 * 1024, LINE_LIMIT = 16 * 1024, PAGE_SIZE = 200;
    private static final Pattern ANSI = Pattern.compile("\\x1B\\[[0-?]*[ -/]*[@-~]");
    private static final Pattern CONTROLS = Pattern.compile("[\\x00-\\x08\\x0B\\x0C\\x0E-\\x1F\\x7F]");
    private static final Map<String, String> FILES = Map.of("ollama", "ollama.log", "gpt-sovits", "gpt-sovits.log",
            "open-llm-vtuber", "open-llm-vtuber.log", "vtuber-electron", "vtuber-electron.log");
    private final Path directory;
    private final int capacity;
    private final LongSupplier clock;
    private final String instanceId = UUID.randomUUID().toString();
    private final Deque<AiLogEntry> cache = new ArrayDeque<>();
    private final Map<String, Tail> tails = new LinkedHashMap<>();
    private final ScheduledExecutorService worker = Executors.newSingleThreadScheduledExecutor(task -> {
        Thread thread = new Thread(task, "ai-log-tailer"); thread.setDaemon(true); return thread;
    });
    private long sequence;
    private static final class Tail {
        long offset, changedAt;
        Object fileKey;
        boolean initialized, skipPartial;
        String error;
        final ByteArrayOutputStream pending = new ByteArrayOutputStream();
    }
    public AiLogService() { this(Path.of("logs"), 5000, System::nanoTime); }
    AiLogService(Path directory, int capacity, LongSupplier clock) {
        this.directory = directory; this.capacity = capacity; this.clock = clock;
        FILES.keySet().stream().sorted().forEach(source -> tails.put(source, new Tail()));
    }
    @PostConstruct public void start() {
        worker.scheduleWithFixedDelay(this::poll, 0, 500, TimeUnit.MILLISECONDS);
    }
    synchronized void poll() {
        for (var entry : tails.entrySet()) {
            String source = entry.getKey(); Tail tail = entry.getValue();
            try {
                Path file = directory.resolve(FILES.get(source));
                if (!Files.isRegularFile(file)) continue;
                var attributes = Files.readAttributes(file, BasicFileAttributes.class);
                boolean replaced = tail.initialized && !Objects.equals(tail.fileKey, attributes.fileKey());
                if (replaced || attributes.size() < tail.offset) {
                    tail.offset = 0; tail.pending.reset(); tail.skipPartial = false;
                    append(source, "[日誌檔案已重建，從新檔案繼續讀取]");
                }
                if (!tail.initialized || replaced) {
                    tail.offset = Math.max(0, attributes.size() - READ_LIMIT);
                    tail.skipPartial = tail.offset > 0;
                    tail.initialized = true;
                }
                tail.fileKey = attributes.fileKey();
                int count = (int) Math.min(READ_LIMIT, Math.max(0, attributes.size() - tail.offset));
                if (count > 0) {
                    byte[] bytes = new byte[count];
                    try (var input = new RandomAccessFile(file.toFile(), "r")) {
                        input.seek(tail.offset); int read = input.read(bytes);
                        if (read > 0) { tail.offset += read; consume(source, tail, bytes, read); tail.changedAt = clock.getAsLong(); }
                    }
                } else if (tail.pending.size() > 0 && clock.getAsLong() - tail.changedAt >= TimeUnit.MILLISECONDS.toNanos(500)) {
                    flush(source, tail, true);
                }
                tail.error = null;
            } catch (IOException | SecurityException error) {
                if (!Objects.equals(tail.error, error.getMessage())) append(source, "[無法讀取日誌，請檢查檔案權限]");
                tail.error = error.getMessage();
            }
        }
    }
    private void consume(String source, Tail tail, byte[] bytes, int count) {
        for (int index = 0; index < count; index++) {
            int value = bytes[index] & 0xff;
            if (tail.skipPartial) { if (value == 10 || value == 13) tail.skipPartial = false; continue; }
            if (value == 10 || value == 13) flush(source, tail, false);
            else {
                tail.pending.write(value);
                if (tail.pending.size() >= LINE_LIMIT) flush(source, tail, true);
            }
        }
    }
    private void flush(String source, Tail tail, boolean partial) {
        byte[] bytes = tail.pending.toByteArray();
        int count = partial ? completeUtf8Prefix(bytes) : bytes.length;
        if (count == 0) return;
        append(source, new String(bytes, 0, count, StandardCharsets.UTF_8));
        tail.pending.reset(); tail.pending.write(bytes, count, bytes.length - count);
    }
    private static int completeUtf8Prefix(byte[] bytes) {
        int length = bytes.length;
        if (length == 0) return 0;
        int start = length - 1;
        while (start > 0 && (bytes[start] & 0xc0) == 0x80) start--;
        int lead = bytes[start] & 0xff;
        int expected = lead < 0x80 ? 1 : lead >= 0xf0 ? 4 : lead >= 0xe0 ? 3 : lead >= 0xc0 ? 2 : 1;
        return length - start < expected ? start : length;
    }
    private void append(String source, String text) {
        text = CONTROLS.matcher(ANSI.matcher(text).replaceAll("")).replaceAll("");
        if (text.isEmpty()) return;
        if (text.codePointCount(0, text.length()) > 4096) text = text.substring(0, text.offsetByCodePoints(0, 4096)) + " [已截短]";
        cache.addLast(new AiLogEntry(++sequence, Instant.now(), source, text));
        while (cache.size() > capacity) cache.removeFirst();
    }
    public synchronized AiLogSnapshot snapshot(String source, long after, String instance) {
        if (!source.equals("all") && !FILES.containsKey(source)) throw new IllegalArgumentException("不支援的 AI 日誌來源");
        if (after < 0) throw new IllegalArgumentException("日誌游標不可小於 0");
        boolean reset = after > sequence || (!instance.isEmpty() && !instance.equals(instanceId));
        long cursor = reset ? 0 : after;
        boolean truncated = !cache.isEmpty() && cursor < cache.getFirst().id() - 1;
        var scanned = cache.stream().filter(line -> line.id() > cursor).limit(PAGE_SIZE).toList();
        long next = scanned.isEmpty() ? sequence : scanned.getLast().id();
        var selected = scanned.stream().filter(line -> source.equals("all") || line.source().equals(source)).toList();
        return new AiLogSnapshot(instanceId, next, reset, truncated, next < sequence, capacity, selected);
    }
    @PreDestroy public void stop() { worker.shutdownNow(); }
}
