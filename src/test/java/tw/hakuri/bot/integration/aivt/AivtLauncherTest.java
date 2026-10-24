package tw.hakuri.bot.integration.aivt;

import tw.hakuri.bot.observability.activity.ActivityLogService;
import tw.hakuri.bot.config.AiServicePaths;
import org.springframework.mock.env.MockEnvironment;
import java.nio.file.Files;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.io.IOException;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.atomic.*;
import static org.assertj.core.api.Assertions.*;
import static org.awaitility.Awaitility.await;
import static org.mockito.Mockito.*;

class AivtLauncherTest {
    @TempDir Path directory;
    @Test void commandPlanUsesEnvironmentPathsAndKeepsConfiguredWorkingDirectories() throws Exception {
        Path sovits = Files.createDirectories(directory.resolve("sovits work"));
        Path vtuber = Files.createDirectories(directory.resolve("vtuber work"));
        Path binaries = Files.createDirectories(directory.resolve("custom binaries"));
        Path python = Files.createFile(binaries.resolve("python.exe"));
        Path api = Files.createFile(binaries.resolve("custom api.py"));
        Path server = Files.createFile(binaries.resolve("custom server.py"));
        Path electron = Files.createFile(binaries.resolve("test-electron.exe"));
        Path ollama = Files.createFile(binaries.resolve("test-ollama.exe"));
        Path uv = Files.createFile(binaries.resolve("test-uv.exe"));
        var paths = new AiServicePaths(new MockEnvironment().withProperty("AI_ROOT", directory.toString())
                .withProperty("GPT_SOVITS_DIR", sovits.toString()).withProperty("GPT_SOVITS_PYTHON_PATH", python.toString())
                .withProperty("GPT_SOVITS_API_PATH", api.toString()).withProperty("OPENLLM_DIR", vtuber.toString())
                .withProperty("OPENLLM_SERVER_PATH", server.toString()).withProperty("VTUBER_ELECTRON_PATH", electron.toString())
                .withProperty("OLLAMA_PATH", ollama.toString()).withProperty("UV_PATH", uv.toString()));
        var plan = AivtLauncher.localCommands(paths);
        assertThat(plan.get(0).command()).containsExactly(ollama.toString(), "serve");
        assertThat(plan.get(1).command()).containsExactly(python.toString(), api.toString(), "-a", "127.0.0.1", "-p", "9880");
        assertThat(plan.get(1).directory()).isEqualTo(sovits);
        assertThat(plan.get(2).command()).containsExactly(uv.toString(), "run", server.toString());
        assertThat(plan.get(2).directory()).isEqualTo(vtuber);
        assertThat(plan.get(3).command()).containsExactly(electron.toString());
    }
    AiServiceCommand command(int port) { return new AiServiceCommand("test AI", List.of("unused.exe"), directory, port, "test.log"); }
    @Test void ownsServicesAndClosesWholeGroupWhenBotStops() throws Exception {
        var group = mock(AiProcessGroup.class); var child = mock(AiProcessGroup.Child.class);
        when(child.alive()).thenReturn(true); when(child.pid()).thenReturn(123L);
        when(group.start(any(), any())).thenReturn(child);
        var launcher = new AivtLauncher(true, directory, () -> List.of(command(0)), () -> group, port -> false, Duration.ofSeconds(1), mock(ActivityLogService.class));
        try {
            Runnable ready = mock(Runnable.class);
            launcher.start(ready);
            await().atMost(Duration.ofSeconds(2)).untilAsserted(() -> assertThat(launcher.status()).containsEntry("state", "RUNNING"));
            verify(ready).run();
            assertThatThrownBy(launcher::start).isInstanceOf(IllegalStateException.class).hasMessageContaining("重複");
        } finally { launcher.stop(); }
        verify(group).close(); assertThat(launcher.status()).containsEntry("running", false);
    }
    @Test void occupiedPortsAreNotAdoptedOrTerminated() {
        var created = new AtomicBoolean();
        var launcher = new AivtLauncher(true, directory, () -> List.of(command(12393)), () -> { created.set(true); throw new AssertionError(); }, port -> true, Duration.ofSeconds(1), mock(ActivityLogService.class));
        try { assertThatThrownBy(launcher::start).isInstanceOf(IllegalStateException.class).hasMessageContaining("已被占用"); }
        finally { launcher.stop(); }
        assertThat(created).isFalse();
    }
    @Test void partialStartupFailureClosesEveryOwnedChild() throws Exception {
        var group = mock(AiProcessGroup.class);
        when(group.start(any(), any())).thenThrow(new IOException("failed"));
        var launcher = new AivtLauncher(true, directory, () -> List.of(command(0)), () -> group, port -> false, Duration.ofSeconds(1), mock(ActivityLogService.class));
        try {
            launcher.start();
            await().atMost(Duration.ofSeconds(2)).untilAsserted(() -> assertThat(launcher.status()).containsEntry("state", "FAILED"));
            verify(group).close();
        } finally { launcher.stop(); }
    }
    @Test void stopsGroupWhileWaitingForServiceReadiness() throws Exception {
        var group = mock(AiProcessGroup.class); var child = mock(AiProcessGroup.Child.class); when(child.alive()).thenReturn(true);
        when(group.start(any(), any())).thenReturn(child);
        var launcher = new AivtLauncher(true, directory, () -> List.of(command(12393)), () -> group, port -> false, Duration.ofSeconds(10), mock(ActivityLogService.class));
        launcher.start();
        await().atMost(Duration.ofSeconds(2)).untilAsserted(() -> verify(group).start(any(), any()));
        launcher.stop(); verify(group).close(); assertThat(launcher.status()).containsEntry("state", "STOPPED");
    }
    @Test void refusesUnsupportedOperatingSystems() {
        var launcher = new AivtLauncher(false, directory, List::of, () -> { throw new AssertionError(); }, port -> false, Duration.ZERO, mock(ActivityLogService.class));
        try { assertThatThrownBy(launcher::start).isInstanceOf(IllegalStateException.class).hasMessageContaining("Windows"); }
        finally { launcher.stop(); }
    }
    @Test void readinessTimeoutClosesTheWholeGroup() throws Exception {
        var group = mock(AiProcessGroup.class); var child = mock(AiProcessGroup.Child.class); when(child.alive()).thenReturn(true);
        when(group.start(any(), any())).thenReturn(child);
        var launcher = new AivtLauncher(true, directory, () -> List.of(command(12393)), () -> group, port -> false, Duration.ZERO, mock(ActivityLogService.class));
        Runnable ready = mock(Runnable.class);
        try {
            launcher.start(ready);
            await().atMost(Duration.ofSeconds(2)).untilAsserted(() -> assertThat(launcher.status()).containsEntry("state", "FAILED"));
            assertThat(launcher.status().get("detail").toString()).contains("逾時"); verify(group).close();
            verifyNoInteractions(ready);
        } finally { launcher.stop(); }
    }
}
