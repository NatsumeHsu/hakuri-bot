package tw.hakuri.bot.integration.aivt.log;

import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.concurrent.atomic.AtomicLong;
import static org.assertj.core.api.Assertions.*;

class AiLogServiceTest {
    @TempDir Path directory;
    final AtomicLong time = new AtomicLong();
    AiLogService logs;
    @BeforeEach void setup() { logs = new AiLogService(directory, 5000, time::get); }
    @AfterEach void stop() { logs.stop(); }
    void write(String text) throws Exception { Files.writeString(directory.resolve("ollama.log"), text, StandardOpenOption.CREATE, StandardOpenOption.APPEND); }
    @Test void incrementallyReadsUtf8CrLfProgressAndStripsAnsiWithoutDuplicates() throws Exception {
        byte[] chinese = "白璃".getBytes(StandardCharsets.UTF_8);
        Files.write(directory.resolve("ollama.log"), new byte[] {chinese[0], chinese[1]});
        logs.poll(); time.set(1_000_000_000L); logs.poll();
        assertThat(logs.snapshot("all", 0, "").entries()).isEmpty();
        Files.write(directory.resolve("ollama.log"), java.util.Arrays.copyOfRange(chinese, 2, chinese.length), StandardOpenOption.APPEND);
        write("\r\n\u001b[32mready\u001b[0m\r50%\r100%\n"); logs.poll();
        var page = logs.snapshot("all", 0, "");
        assertThat(page.entries()).extracting(AiLogEntry::text).containsExactly("白璃", "ready", "50%", "100%");
        logs.poll(); assertThat(logs.snapshot("all", page.cursor(), page.instanceId()).entries()).isEmpty();
    }
    @Test void publishesOutputWithoutNewlineAfterItBecomesIdle() throws Exception {
        write("loading model"); logs.poll(); assertThat(logs.snapshot("all", 0, "").entries()).isEmpty();
        time.set(1_000_000_000L); logs.poll();
        assertThat(logs.snapshot("all", 0, "").entries()).extracting(AiLogEntry::text).containsExactly("loading model");
    }
    @Test void boundedCacheReportsGapsAndNewServerInstanceResetsCursor() throws Exception {
        logs.stop(); logs = new AiLogService(directory, 3, time::get);
        write("one\ntwo\nthree\nfour\nfive\n"); logs.poll();
        var page = logs.snapshot("all", 0, "");
        assertThat(page.truncated()).isTrue(); assertThat(page.cursor()).isEqualTo(5);
        assertThat(page.entries()).extracting(AiLogEntry::text).containsExactly("three", "four", "five");
        assertThat(logs.snapshot("all", 1, "old-instance").reset()).isTrue();
        assertThat(logs.snapshot("all", Long.MAX_VALUE, page.instanceId()).reset()).isTrue();
    }
    @Test void filteredPaginationAdvancesAcrossOtherServices() throws Exception {
        Files.writeString(directory.resolve("gpt-sovits.log"), "other\n".repeat(250));
        write("selected\n"); logs.poll();
        var first = logs.snapshot("ollama", 0, "");
        assertThat(first.entries()).isEmpty(); assertThat(first.hasMore()).isTrue();
        var second = logs.snapshot("ollama", first.cursor(), first.instanceId());
        assertThat(second.entries()).extracting(AiLogEntry::text).containsExactly("selected");
    }
    @Test void restoresRecentDiskHistoryAfterRestartAndHandlesTruncation() throws Exception {
        write("a previously longer output\n"); logs.poll(); logs.stop();
        logs = new AiLogService(directory, 5000, time::get); logs.poll();
        assertThat(logs.snapshot("all", 0, "").entries()).extracting(AiLogEntry::text).contains("a previously longer output");
        Files.writeString(directory.resolve("ollama.log"), "new\n"); logs.poll();
        assertThat(logs.snapshot("all", 0, "").entries().getLast().text()).isEqualTo("new");
    }
    @Test void startupReadsOnlyFileTailAndRejectsUnknownPaths() throws Exception {
        write("x".repeat(300000) + "\nlast line\n"); logs.poll();
        assertThat(logs.snapshot("all", 0, "").entries()).extracting(AiLogEntry::text).containsExactly("last line");
        assertThatThrownBy(() -> logs.snapshot("../.env", 0, "")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> logs.snapshot("all", -1, "")).isInstanceOf(IllegalArgumentException.class);
    }
}
