package tw.hakuri.bot.integration.aivt;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.api.condition.*;
import java.nio.file.*;
import java.time.Duration;
import java.util.List;
import static org.assertj.core.api.Assertions.*;
import static org.awaitility.Awaitility.await;

@EnabledOnOs(OS.WINDOWS)
class WindowsProcessGroupTest {
    @TempDir Path directory;
    static Path powershell() { return Path.of(System.getenv("SystemRoot"), "System32/WindowsPowerShell/v1.0/powershell.exe"); }
    @Test void childReceivesUnbufferedUtf8PythonSettingsAndWritesOutputToLog() throws Exception {
        Path log = directory.resolve("environment.log");
        try (var group = new WindowsProcessGroup()) {
            var child = group.start(new AiServiceCommand("environment", List.of(powershell().toString(), "-NoProfile", "-NonInteractive", "-Command",
                    "Write-Output $env:PYTHONUNBUFFERED; Write-Output $env:PYTHONIOENCODING; Write-Output $env:PYTHONUTF8"), directory, 0, "environment.log"), log);
            await().atMost(Duration.ofSeconds(10)).until(() -> !child.alive());
            assertThat(child.exitCode()).isZero();
            assertThat(Files.readString(log).replace("\r", "")).isEqualTo("1\nutf-8\n1\n");
        }
    }
    @Test void startsHiddenChildAndClosesItsJob() throws Exception {
        try (var group = new WindowsProcessGroup()) {
            var child = group.start(new AiServiceCommand("test", List.of(powershell().toString(), "-NoProfile", "-NonInteractive", "-Command", "Start-Sleep -Seconds 60"), directory, 0, "test.log"), directory.resolve("test.log"));
            assertThat(child.alive()).isTrue(); long pid = child.pid();
            group.close();
            await().atMost(Duration.ofSeconds(3)).until(() -> ProcessHandle.of(pid).map(p -> !p.isAlive()).orElse(true));
            assertThat(child.alive()).isFalse();
        }
    }
    @Test void closesDescendantEvenWhenItsOriginalParentAlreadyExited() throws Exception {
        Path script = directory.resolve("parent with spaces.ps1"), pidFile = directory.resolve("child.pid");
        Files.writeString(script, "$child = Start-Process -FilePath '" + powershell() + "' -ArgumentList '-NoProfile','-NonInteractive','-Command','Start-Sleep -Seconds 60' -WindowStyle Hidden -PassThru\n$child.Id | Set-Content -LiteralPath '" + pidFile + "'\n");
        try (var group = new WindowsProcessGroup()) {
            var parent = group.start(new AiServiceCommand("parent", List.of(powershell().toString(), "-NoProfile", "-NonInteractive", "-ExecutionPolicy", "Bypass", "-File", script.toString()), directory, 0, "parent.log"), directory.resolve("parent.log"));
            await().atMost(Duration.ofSeconds(10)).until(() -> !parent.alive() && Files.exists(pidFile));
            long pid = Long.parseLong(Files.readString(pidFile).trim());
            assertThat(ProcessHandle.of(pid).orElseThrow().isAlive()).isTrue();
            group.close();
            await().atMost(Duration.ofSeconds(3)).until(() -> ProcessHandle.of(pid).map(p -> !p.isAlive()).orElse(true));
        }
    }
    @Test void operatingSystemKillsChildWhenOwningJvmHaltsWithoutShutdownHooks() throws Exception {
        String classpath = System.getProperty("surefire.test.class.path", System.getProperty("java.class.path"));
        Path javaExecutable = Path.of(System.getProperty("java.home"), "bin/java.exe");
        Process owner = new ProcessBuilder(javaExecutable.toString(), "--enable-native-access=ALL-UNNAMED", "-cp", classpath,
                JobOwnerProbe.class.getName(), directory.toString()).redirectErrorStream(true)
                .redirectOutput(directory.resolve("owner.log").toFile()).start();
        try {
            assertThat(owner.waitFor(10, java.util.concurrent.TimeUnit.SECONDS)).isTrue();
            assertThat(owner.exitValue()).withFailMessage(Files.readString(directory.resolve("owner.log"))).isZero();
            long pid = Long.parseLong(Files.readString(directory.resolve("halt-child.pid")));
            await().atMost(Duration.ofSeconds(3)).until(() -> ProcessHandle.of(pid).map(p -> !p.isAlive()).orElse(true));
        } finally { owner.destroyForcibly(); }
    }
}
