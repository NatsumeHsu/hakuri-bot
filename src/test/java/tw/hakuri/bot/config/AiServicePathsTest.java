package tw.hakuri.bot.config;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.mock.env.MockEnvironment;
import java.nio.file.Path;
import static org.assertj.core.api.Assertions.*;

class AiServicePathsTest {
    @TempDir Path directory;
    @Test void derivesDefaultLocationsFromConfiguredRoot() {
        var paths = new AiServicePaths(new MockEnvironment().withProperty("AI_ROOT", directory.toString()));
        assertThat(paths.sovitsDirectory()).isEqualTo(directory.resolve("GPT-SoVITS"));
        assertThat(paths.sovitsPython()).isEqualTo(directory.resolve("GPT-SoVITS/runtime/python.exe"));
        assertThat(paths.openllmServer()).isEqualTo(directory.resolve("Open-LLM-VTuber/run_server.py"));
        assertThat(paths.ollama()).isEmpty(); assertThat(paths.uv()).isEmpty();
    }
    @Test void supportsIndependentExecutableAndScriptOverrides() {
        var paths = new AiServicePaths(new MockEnvironment().withProperty("AI_ROOT", directory.toString())
                .withProperty("GPT_SOVITS_DIR", directory.resolve("custom sovits").toString())
                .withProperty("GPT_SOVITS_PYTHON_PATH", directory.resolve("python.exe").toString())
                .withProperty("OPENLLM_SERVER_PATH", directory.resolve("custom server.py").toString())
                .withProperty("VTUBER_ELECTRON_PATH", directory.resolve("electron.exe").toString())
                .withProperty("OLLAMA_PATH", directory.resolve("ollama.exe").toString())
                .withProperty("UV_PATH", directory.resolve("uv.exe").toString()));
        assertThat(paths.sovitsApi()).isEqualTo(directory.resolve("custom sovits/api_v2.py"));
        assertThat(paths.sovitsPython()).isEqualTo(directory.resolve("python.exe"));
        assertThat(paths.openllmServer()).isEqualTo(directory.resolve("custom server.py"));
        assertThat(paths.electron()).isEqualTo(directory.resolve("electron.exe"));
        assertThat(paths.ollama()).isEqualTo(directory.resolve("ollama.exe").toString());
        assertThat(paths.uv()).isEqualTo(directory.resolve("uv.exe").toString());
    }
}
