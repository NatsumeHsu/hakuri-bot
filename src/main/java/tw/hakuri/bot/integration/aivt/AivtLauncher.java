package tw.hakuri.bot.integration.aivt;

import jakarta.annotation.PreDestroy;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.beans.factory.annotation.Autowired;
import tw.hakuri.bot.observability.activity.ActivityLogService;
import tw.hakuri.bot.config.AiServicePaths;
import java.io.IOException;
import java.net.*;
import java.nio.file.*;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;

/** Owns the entire AI stack rather than detached batch windows. */
@Service
public class AivtLauncher {
    @FunctionalInterface interface GroupFactory { AiProcessGroup create() throws IOException; }
    @FunctionalInterface interface Commands { List<AiServiceCommand> get() throws IOException; }
    @FunctionalInterface interface PortProbe { boolean open(int port); }
    private final boolean windows;
    private final Path logs;
    private final Commands commands;
    private final GroupFactory groups;
    private final PortProbe ports;
    private final Duration readyTimeout;
    private final ActivityLogService activity;
    private final ScheduledExecutorService worker = Executors.newSingleThreadScheduledExecutor();
    private final Map<AiServiceCommand, AiProcessGroup.Child> children = new LinkedHashMap<>();
    private AiProcessGroup group;
    private Future<?> startup;
    private ScheduledFuture<?> monitoring;
    private String state = "STOPPED", detail = "尚未啟動";
    private boolean stopping;

    @Autowired public AivtLauncher(ActivityLogService activity, AiServicePaths paths) {
        this(System.getProperty("os.name", "").toLowerCase(Locale.ROOT).startsWith("windows"), Path.of("logs"),
                () -> localCommands(paths), WindowsProcessGroup::new, AivtLauncher::portOpen, Duration.ofMinutes(5), activity);
    }
    AivtLauncher(boolean windows, Path logs, Commands commands, GroupFactory groups, PortProbe ports, Duration readyTimeout, ActivityLogService activity) {
        this.windows = windows; this.logs = logs; this.commands = commands; this.groups = groups; this.ports = ports; this.readyTimeout = readyTimeout;
        this.activity = activity;
    }
    static List<AiServiceCommand> localCommands(AiServicePaths paths) throws IOException {
        Path root = paths.root(), python = paths.sovitsPython(), api = paths.sovitsApi();
        Path server = paths.openllmServer(), electron = paths.electron();
        for (Path directory : List.of(root, paths.sovitsDirectory(), paths.openllmDirectory()))
            if (!Files.isDirectory(directory)) throw new IOException("找不到 AI 工作目錄：" + directory);
        for (Path file : List.of(python, api, server, electron))
            if (!Files.isRegularFile(file)) throw new IOException("找不到 " + file);
        // Electron is single-instance; don't forward into a GUI owned by another launcher.
        try (var processes = ProcessHandle.allProcesses()) {
            if (processes.anyMatch(process -> process.info().command().map(path -> path.equalsIgnoreCase(electron.toString())).orElse(false)))
                throw new IOException("VTuber Electron 已在執行，請先關閉後再由 Bot 啟動");
        }
        return List.of(
                new AiServiceCommand("Ollama", List.of(WindowsExecutableLocator.configured("OLLAMA_PATH", paths.ollama(), "ollama.exe"), "serve"), root, 11434, "ollama.log"),
                new AiServiceCommand("GPT-SoVITS", List.of(python.toString(), api.toString(), "-a", "127.0.0.1", "-p", "9880"), paths.sovitsDirectory(), 9880, "gpt-sovits.log"),
                new AiServiceCommand("Open-LLM-VTuber", List.of(WindowsExecutableLocator.configured("UV_PATH", paths.uv(), "uv.exe"), "run", server.toString()), paths.openllmDirectory(), 12393, "open-llm-vtuber.log"),
                new AiServiceCommand("VTuber Electron", List.of(electron.toString()), electron.getParent(), 0, "vtuber-electron.log"));
    }
    private static boolean portOpen(int port) {
        try (Socket socket = new Socket()) { socket.connect(new InetSocketAddress("127.0.0.1", port), 250); return true; }
        catch (IOException error) { return false; }
    }
    public synchronized Map<String, Object> status() {
        var result = new LinkedHashMap<String, Object>();
        result.put("supported", windows); result.put("state", state); result.put("detail", detail);
        result.put("running", state.equals("STARTING") || state.equals("RUNNING"));
        result.put("processes", children.entrySet().stream().map(entry -> Map.of(
                "name", entry.getKey().name(), "pid", entry.getValue().pid(), "running", entry.getValue().alive())).toList());
        return result;
    }
    public synchronized Map<String, String> start() {
        return start(() -> { });
    }
    public synchronized Map<String, String> start(Runnable onReady) {
        if (!windows) throw new IllegalStateException("AI 子程序管理僅支援 Windows");
        if (stopping) throw new IllegalStateException("Bot 正在停止");
        if (group != null) throw new IllegalStateException("AI 服務已啟動或正在啟動，請勿重複啟動");
        try {
            List<AiServiceCommand> plan = commands.get();
            for (var service : plan) if (service.port() > 0 && ports.open(service.port()))
                throw new IOException(service.name() + " 連接埠 " + service.port() + " 已被占用，請先關閉原服務再由 Bot 啟動");
            Files.createDirectories(logs);
            group = groups.create(); children.clear(); state = "STARTING"; detail = "準備啟動 AI 子程序";
            activity.step("AI 啟動中", "準備啟動 Ollama、語音合成與 VTuber");
            AiProcessGroup owned = group;
            startup = worker.submit(() -> boot(owned, plan, onReady));
            return Map.of("message", "AI 子程序正在依序啟動，Bot 停止時會自動關閉整組 AI 服務");
        } catch (IOException error) { activity.step("AI 啟動失敗", error.getMessage()); throw new IllegalStateException(error.getMessage()); }
    }
    private void boot(AiProcessGroup owned, List<AiServiceCommand> plan, Runnable onReady) {
        try {
            for (var service : plan) {
                AiProcessGroup.Child child;
                synchronized (this) {
                    if (stopping || group != owned) return;
                    detail = "正在啟動 " + service.name();
                    activity.step("啟動 AI 服務", service.name());
                    child = owned.start(service, logs.resolve(service.logName())); children.put(service, child);
                }
                long deadline = System.nanoTime() + readyTimeout.toNanos();
                while (service.port() > 0 && !ports.open(service.port())) {
                    if (!child.alive()) throw new IOException(service.name() + " 提前結束，結束碼=" + child.exitCode());
                    if (System.nanoTime() > deadline) throw new IOException(service.name() + " 啟動逾時");
                    Thread.sleep(250);
                }
                if (Thread.currentThread().isInterrupted()) throw new InterruptedException();
                activity.step("服務已就緒", service.name());
            }
            synchronized (this) {
                if (stopping || group != owned) return;
                state = "RUNNING"; detail = "AI 服務已啟動，所有子程序由 Bot 管理";
                activity.step("AI 服務已就緒", "可以開始對話");
                monitoring = worker.scheduleWithFixedDelay(() -> monitor(owned), 1, 1, TimeUnit.SECONDS);
                for (var child : children.values()) if (!child.alive()) throw new IOException("AI 子程序已結束，取消連線");
                onReady.run();
            }
        } catch (InterruptedException error) { Thread.currentThread().interrupt(); }
        catch (Exception error) { fail(owned, error.getMessage()); }
    }
    private synchronized void monitor(AiProcessGroup owned) {
        if (stopping || group != owned) return;
        for (var entry : children.entrySet()) if (!entry.getValue().alive()) {
            fail(owned, entry.getKey().name() + " 已結束，已關閉其餘 AI 子程序"); return;
        }
    }
    private synchronized void fail(AiProcessGroup owned, String message) {
        if (group != owned) return;
        if (monitoring != null) monitoring.cancel(false);
        owned.close(); group = null; state = "FAILED"; detail = message;
        activity.step("AI 服務停止", message);
        LoggerFactory.getLogger(AivtLauncher.class).warn("AI 子程序啟動／執行失敗：{}", message);
    }
    @PreDestroy public synchronized void stop() {
        stopping = true;
        if (startup != null) startup.cancel(true);
        if (monitoring != null) monitoring.cancel(false);
        worker.shutdownNow();
        if (group != null) { group.close(); group = null; activity.step("AI 服務已關閉", "Bot 停止，已清理子程序"); }
        state = "STOPPED"; detail = "Bot 已停止，受管 AI 子程序已關閉";
    }
}
