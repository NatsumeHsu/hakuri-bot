package tw.hakuri.bot.observability.activity;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.*;
import static org.assertj.core.api.Assertions.*;

class ActivityLogServiceTest {
    @TempDir Path directory;
    ActivityLogService service(int capacity) {
        return new ActivityLogService(new ObjectMapper().findAndRegisterModules(), directory.resolve("activity.jsonl"), capacity);
    }
    @Test void recordsTextTranscriptsAndCurrentStepWithFilters() {
        var log = service(5000);
        log.input("觀眾", "你好\n測試"); log.voice("語音使用者", "白璃今天好嗎"); log.step("送往 AI", "等待回覆");
        var all = log.snapshot("all", 0, "");
        assertThat(all.entries()).extracting(ActivityEntry::source).containsExactly("input", "voice", "step");
        assertThat(all.currentStep()).isEqualTo("送往 AI");
        assertThat(log.snapshot("voice", 0, "").entries()).extracting(ActivityEntry::text).containsExactly("語音使用者：白璃今天好嗎");
        assertThat(all.entries().getFirst().text()).doesNotContain("\n");
        assertThat(log.snapshot("all", all.cursor(), all.instanceId()).entries()).isEmpty();
    }
    @Test void restoresInputsAndVoiceHistoryAfterRestart() {
        var log = service(5000); log.input("A", "文字內容"); log.voice("B", "辨識文字");
        var restarted = service(5000); restarted.restore();
        assertThat(restarted.snapshot("voice", 0, "").entries()).extracting(ActivityEntry::text).containsExactly("B：辨識文字");
        assertThat(restarted.snapshot("input", 0, "").entries()).extracting(ActivityEntry::text).containsExactly("A：文字內容");
        assertThat(restarted.snapshot("all", 1, log.snapshot("all", 0, "").instanceId()).reset()).isTrue();
    }
    @Test void retentionLimitAndCursorResetRemainBounded() {
        var log = service(2); log.input("A", "one"); log.input("A", "two"); log.voice("B", "three");
        var all = log.snapshot("all", 0, "");
        assertThat(all.entries()).hasSize(2); assertThat(all.truncated()).isTrue();
        assertThat(log.snapshot("all", Long.MAX_VALUE, "").reset()).isTrue();
        assertThatThrownBy(() -> log.snapshot("ollama", 0, "")).isInstanceOf(IllegalArgumentException.class);
    }
    @Test void interruptedFileWriteDoesNotLoseLaterRecords() throws Exception {
        Files.writeString(directory.resolve("activity.jsonl"), "null\n{broken");
        var log = service(5000); log.restore(); log.voice("A", "後續辨識內容");
        var restarted = service(5000); restarted.restore();
        assertThat(restarted.snapshot("voice", 0, "").entries()).extracting(ActivityEntry::text).containsExactly("A：後續辨識內容");
    }
    @Test void diskFailureStillRetainsInMemoryHistory() throws Exception {
        Path blocker = Files.writeString(directory.resolve("blocked"), "not a directory");
        var log = new ActivityLogService(new ObjectMapper().findAndRegisterModules(), blocker.resolve("activity.jsonl"), 2);
        log.voice("A", "可保留的辨識文字");
        assertThat(log.snapshot("voice", 0, "").entries()).hasSize(1);
    }
    @Test void rotatesDiskCacheWithoutLosingNewestInput() throws Exception {
        Files.writeString(directory.resolve("activity.jsonl"), "x".repeat(2 * 1024 * 1024));
        var log = service(5000); log.input("A", "最新文字");
        assertThat(directory.resolve("activity.jsonl.1")).exists();
        assertThat(Files.size(directory.resolve("activity.jsonl"))).isLessThan(1000);
        var restarted = service(5000); restarted.restore();
        assertThat(restarted.snapshot("input", 0, "").entries()).extracting(ActivityEntry::text).containsExactly("A：最新文字");
    }
}
