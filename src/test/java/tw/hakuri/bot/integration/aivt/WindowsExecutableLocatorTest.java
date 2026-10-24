package tw.hakuri.bot.integration.aivt;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.*;
import java.util.Map;
import static org.assertj.core.api.Assertions.*;

class WindowsExecutableLocatorTest {
    @Test void explicitMissingExecutableDoesNotSilentlyUseAnotherInstallation() {
        assertThatThrownBy(() -> WindowsExecutableLocator.configured("OLLAMA_PATH", home.resolve("missing.exe").toString(), "ollama.exe"))
                .isInstanceOf(java.io.IOException.class).hasMessageContaining("OLLAMA_PATH", "不存在");
    }
    @TempDir Path home;
    Path executable(String relative) throws Exception {
        Path file = home.resolve(relative); Files.createDirectories(file.getParent()); return Files.createFile(file).toAbsolutePath();
    }
    @Test void resolvesMixedCasePathKeyAndQuotedDirectories() throws Exception {
        Path binary = executable("custom tools/ollama.exe");
        assertThat(WindowsExecutableLocator.find("ollama.exe", Map.of("Path", ";\"" + binary.getParent() + "\";"), home)).isEqualTo(binary.toString());
    }
    @Test void findsOllamaWithoutPathAndPrefersExplicitPathWhenAvailable() throws Exception {
        Path installed = executable("AppData/Local/Programs/Ollama/ollama.exe");
        assertThat(WindowsExecutableLocator.find("ollama.exe", Map.of(), home)).isEqualTo(installed.toString());
        Path custom = executable("custom/ollama.exe");
        assertThat(WindowsExecutableLocator.find("ollama.exe", Map.of("PATH", custom.getParent().toString()), home)).isEqualTo(custom.toString());
    }
    @Test void findsUvInWinGetLinksWithoutPath() throws Exception {
        Path binary = executable("AppData/Local/Microsoft/WinGet/Links/uv.exe");
        assertThat(WindowsExecutableLocator.find("uv.exe", Map.of(), home)).isEqualTo(binary.toString());
    }
    @Test void missingExecutableReportsActionableError() {
        assertThatThrownBy(() -> WindowsExecutableLocator.find("ollama.exe", Map.of(), home))
                .isInstanceOf(java.io.IOException.class).hasMessageContaining("ollama.exe", "VS Code");
    }
}
