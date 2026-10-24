package tw.hakuri.bot.config;

import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;
import java.nio.file.Path;

/** Machine-specific service locations belong to .env, not database settings. */
@Component
public class AiServicePaths {
    private final Path root, sovitsDirectory, sovitsPython, sovitsApi, openllmDirectory, openllmServer, electron;
    private final String ollama, uv;
    public AiServicePaths(Environment env) {
        root = path(env, "AI_ROOT", Path.of("D:\\AI"));
        sovitsDirectory = path(env, "GPT_SOVITS_DIR", root.resolve("GPT-SoVITS"));
        sovitsPython = path(env, "GPT_SOVITS_PYTHON_PATH", sovitsDirectory.resolve("runtime/python.exe"));
        sovitsApi = path(env, "GPT_SOVITS_API_PATH", sovitsDirectory.resolve("api_v2.py"));
        openllmDirectory = path(env, "OPENLLM_DIR", root.resolve("Open-LLM-VTuber"));
        openllmServer = path(env, "OPENLLM_SERVER_PATH", openllmDirectory.resolve("run_server.py"));
        electron = path(env, "VTUBER_ELECTRON_PATH", Path.of(System.getProperty("user.home"),
                "AppData/Local/Programs/open-llm-vtuber/open-llm-vtuber-electron.exe"));
        ollama = env.getProperty("OLLAMA_PATH", "").trim();
        uv = env.getProperty("UV_PATH", "").trim();
    }
    private static Path path(Environment env, String key, Path fallback) {
        String value = env.getProperty(key, "").trim();
        try { return (value.isEmpty() ? fallback : Path.of(value)).toAbsolutePath().normalize(); }
        catch (java.nio.file.InvalidPathException error) { throw new IllegalArgumentException(key + " 路徑格式無效", error); }
    }
    public Path root() { return root; }
    public Path sovitsDirectory() { return sovitsDirectory; }
    public Path sovitsPython() { return sovitsPython; }
    public Path sovitsApi() { return sovitsApi; }
    public Path openllmDirectory() { return openllmDirectory; }
    public Path openllmServer() { return openllmServer; }
    public Path electron() { return electron; }
    public String ollama() { return ollama; }
    public String uv() { return uv; }
}
