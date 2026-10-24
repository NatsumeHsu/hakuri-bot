package tw.hakuri.bot.observability.activity;

import com.fasterxml.jackson.databind.*;
import jakarta.annotation.PostConstruct;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Service;
import org.springframework.core.env.Environment;
import org.slf4j.LoggerFactory;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.time.Instant;
import java.util.*;

/** Human-readable input and workflow events, separate from technical process output. */
@Service
public class ActivityLogService {
    private final ObjectMapper json;
    private final Path file;
    private final int capacity;
    private final Deque<ActivityEntry> entries = new ArrayDeque<>();
    private final String instance = UUID.randomUUID().toString();
    private long sequence;
    private String currentStep = "等待輸入";
    private boolean storageWarning;
    @Autowired public ActivityLogService(ObjectMapper json, Environment environment) {
        this(json, Path.of(environment.getProperty("hakuri.activity.file", "logs/activity.jsonl")), 5000);
    }
    ActivityLogService(ObjectMapper json, Path file, int capacity) {
        this.json = json.copy().disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS); this.file = file; this.capacity = capacity;
    }
    @PostConstruct synchronized void restore() {
        for (Path source : List.of(backup(), file)) if (Files.isRegularFile(source)) {
            try (var reader = Files.newBufferedReader(source, StandardCharsets.UTF_8)) {
                String line;
                while ((line = reader.readLine()) != null) {
                    try {
                        var event = json.readValue(line, ActivityEntry.class);
                        if (event == null || event.id() <= 0 || event.source() == null || !Set.of("input", "voice", "step").contains(event.source()) || event.text() == null || event.receivedAt() == null) continue;
                        sequence = Math.max(sequence, event.id()); add(event);
                    } catch (IOException ignored) { /* An interrupted final write must not prevent startup. */ }
                }
            } catch (IOException error) { warnStorage(); }
        }
        step("主程式啟動中", "正在載入 Bot 與後台");
    }
    @EventListener(ApplicationReadyEvent.class) public void ready() { step("主程式就緒", "後台與 Bot 初始化完成"); }
    public void input(String name, String text) { record("input", name + "：" + text); }
    public void voice(String name, String text) { record("voice", name + "：" + text); }
    public synchronized void step(String stage, String detail) {
        currentStep = clean(stage); record("step", stage + (detail == null || detail.isBlank() ? "" : "｜" + detail));
    }
    private synchronized void record(String source, String text) {
        var event = new ActivityEntry(++sequence, Instant.now(), source, clean(text)); add(event);
        try {
            Files.createDirectories(file.toAbsolutePath().getParent());
            if (Files.exists(file) && Files.size(file) >= 2 * 1024 * 1024)
                Files.move(file, backup(), StandardCopyOption.REPLACE_EXISTING);
            String separator = "";
            if (Files.isRegularFile(file) && Files.size(file) > 0) try (var input = new RandomAccessFile(file.toFile(), "r")) {
                input.seek(input.length() - 1); if (input.read() != '\n') separator = "\n";
            }
            Files.writeString(file, separator + json.writeValueAsString(event) + "\n", StandardCharsets.UTF_8, StandardOpenOption.CREATE, StandardOpenOption.APPEND);
            storageWarning = false;
        } catch (IOException error) { warnStorage(); }
    }
    private void add(ActivityEntry event) { entries.addLast(event); while (entries.size() > capacity) entries.removeFirst(); }
    private Path backup() { return file.resolveSibling(file.getFileName() + ".1"); }
    private void warnStorage() {
        if (!storageWarning) LoggerFactory.getLogger(ActivityLogService.class).warn("流程紀錄無法寫入／載入檔案，目前仍保留記憶體暫存");
        storageWarning = true;
    }
    private static String clean(String text) {
        String result = text.replaceAll("[\\p{Cntrl}&&[^\\t]]", " ").trim();
        return result.codePointCount(0, result.length()) > 4000 ? result.substring(0, result.offsetByCodePoints(0, 4000)) + "…" : result;
    }
    public synchronized ActivitySnapshot snapshot(String source, long after, String previousInstance) {
        if (!Set.of("all", "input", "voice", "step").contains(source) || after < 0)
            throw new IllegalArgumentException("不支援的流程紀錄篩選或游標");
        boolean reset = after > sequence || (!previousInstance.isBlank() && !previousInstance.equals(instance));
        long cursor = reset ? 0 : after;
        var scanned = entries.stream().filter(event -> event.id() > cursor).limit(200).toList();
        long next = scanned.isEmpty() ? sequence : scanned.getLast().id();
        return new ActivitySnapshot(instance, next, reset, !entries.isEmpty() && cursor < entries.getFirst().id() - 1,
                next < sequence, capacity, currentStep, scanned.stream().filter(event -> source.equals("all") || source.equals(event.source())).toList());
    }
}
