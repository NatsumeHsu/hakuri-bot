package tw.hakuri.bot.integration.aivt;

import java.io.IOException;
import java.nio.file.*;
import java.util.*;

/** Resolves binaries even when an IDE inherited an old PATH or a differently cased Path key. */
final class WindowsExecutableLocator {
    private WindowsExecutableLocator() { }
    static String find(String name) throws IOException {
        return find(name, System.getenv(), Path.of(System.getProperty("user.home")));
    }
    static String configured(String setting, String value, String fallbackName) throws IOException {
        if (value.isBlank()) return find(fallbackName);
        Path file;
        try { file = Path.of(value).toAbsolutePath().normalize(); }
        catch (InvalidPathException error) { throw new IOException(setting + " 路徑格式無效", error); }
        if (!Files.isRegularFile(file)) throw new IOException(setting + " 指定的執行檔不存在：" + file);
        return file.toString();
    }
    static String find(String name, Map<String, String> environment, Path home) throws IOException {
        var directories = new LinkedHashSet<Path>();
        for (String entry : value(environment, "PATH").split(";")) {
            String directory = entry.trim().replace("\"", "");
            if (directory.isEmpty()) continue;
            try { directories.add(Path.of(directory)); } catch (InvalidPathException ignored) { }
        }
        String local = value(environment, "LOCALAPPDATA");
        Path localAppData = local.isBlank() ? home.resolve("AppData/Local") : Path.of(local);
        if (name.equalsIgnoreCase("ollama.exe")) {
            directories.add(localAppData.resolve("Programs/Ollama"));
            directories.add(localAppData.resolve("Ollama"));
            String programFiles = value(environment, "ProgramFiles");
            if (!programFiles.isBlank()) directories.add(Path.of(programFiles).resolve("Ollama"));
        }
        if (name.equalsIgnoreCase("uv.exe")) {
            directories.add(localAppData.resolve("Microsoft/WinGet/Links"));
            directories.add(home.resolve(".local/bin"));
        }
        for (Path directory : directories) {
            Path file = directory.resolve(name);
            if (Files.isRegularFile(file)) return file.toAbsolutePath().normalize().toString();
        }
        throw new IOException("找不到 " + name + "；已檢查 PATH 與安裝目錄，請確認已安裝，或重啟 VS Code 以更新 PATH");
    }
    private static String value(Map<String, String> environment, String key) {
        return environment.entrySet().stream().filter(entry -> entry.getKey().equalsIgnoreCase(key))
                .map(Map.Entry::getValue).findFirst().orElse("");
    }
}
