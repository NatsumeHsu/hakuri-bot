package tw.hakuri.bot.audio.service;

import tw.hakuri.bot.observability.activity.ActivityLogService;

import tw.hakuri.bot.config.BotConfig;

import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Assumptions;
import org.springframework.mock.env.MockEnvironment;
import java.net.InetSocketAddress;
import java.nio.*;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicReference;
import static org.assertj.core.api.Assertions.*;

class AudioServiceTest {
    @Test void serverErrorsKeepHttpDetailsAndShowSpecificWorkflowFailure() throws Exception {
        String executable = System.getProperty("ffmpeg.test.path");
        Assumptions.assumeTrue(executable != null);
        var code = new java.util.concurrent.atomic.AtomicInteger(422);
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/asr", exchange -> {
            exchange.getRequestBody().readAllBytes();
            byte[] body = "{\"detail\":\"file field required\"}".getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(code.get(), body.length); exchange.getResponseBody().write(body); exchange.close();
        });
        server.start();
        try {
            for (int status : new int[]{422, 404, 500}) {
                code.set(status);
                var activity = org.mockito.Mockito.mock(ActivityLogService.class);
                var audio = new AudioService(new BotConfig(new MockEnvironment().withProperty("FFMPEG_PATH", executable)
                        .withProperty("OPENLLM_HTTP", "http://127.0.0.1:" + server.getAddress().getPort())), activity);
                assertThatThrownBy(() -> audio.transcribe(new byte[96000], "A"))
                        .isInstanceOf(java.io.IOException.class).hasMessageContaining("ASR HTTP " + status, "file field required");
                org.mockito.Mockito.verify(activity).step(org.mockito.ArgumentMatchers.eq("語音辨識失敗"),
                        org.mockito.ArgumentMatchers.contains(status == 404 ? "/asr" : "HTTP " + status));
                org.mockito.Mockito.verify(activity, org.mockito.Mockito.never()).voice(org.mockito.ArgumentMatchers.anyString(), org.mockito.ArgumentMatchers.anyString());
            }
        } finally { server.stop(0); }
    }
    @Test void unavailableVtuberServerIsReportedAsConnectionFailure() throws Exception {
        String executable = System.getProperty("ffmpeg.test.path");
        Assumptions.assumeTrue(executable != null);
        int port;
        try (var reservation = new java.net.ServerSocket(0, 1, java.net.InetAddress.getLoopbackAddress())) { port = reservation.getLocalPort(); }
        var activity = org.mockito.Mockito.mock(ActivityLogService.class);
        var audio = new AudioService(new BotConfig(new MockEnvironment().withProperty("FFMPEG_PATH", executable)
                .withProperty("OPENLLM_HTTP", "http://127.0.0.1:" + port)), activity);
        assertThatThrownBy(() -> audio.transcribe(new byte[96000], "A")).isInstanceOf(java.io.IOException.class);
        org.mockito.Mockito.verify(activity).step("語音辨識失敗", "A：無法連線到 Open-LLM-VTuber，請先從後台啟動 AI");
    }
    @Test void writesCorrectMono16kWavHeader() {
        byte[] pcm = {1, 2, 3, 4}; byte[] wav = AudioService.wav(pcm);
        var buffer = ByteBuffer.wrap(wav).order(ByteOrder.LITTLE_ENDIAN);
        assertThat(wav).hasSize(48);
        assertThat(new String(wav, 0, 4, StandardCharsets.US_ASCII)).isEqualTo("RIFF");
        assertThat(buffer.getInt(4)).isEqualTo(40);
        assertThat(buffer.getShort(22)).isEqualTo((short) 1);
        assertThat(buffer.getInt(24)).isEqualTo(16000);
        assertThat(buffer.getInt(40)).isEqualTo(4);
        assertThat(java.util.Arrays.copyOfRange(wav, 44, wav.length)).isEqualTo(pcm);
    }
    @Test void realFfmpegAndMultipartAsrRoundTrip() throws Exception {
        String executable = System.getProperty("ffmpeg.test.path");
        Assumptions.assumeTrue(executable != null, "使用 -Dffmpeg.test.path=... 啟用實際 FFmpeg 測試");
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        var request = new AtomicReference<byte[]>();
        var upgrade = new AtomicReference<String>();
        server.createContext("/asr", exchange -> {
            upgrade.set(exchange.getRequestHeaders().getFirst("Upgrade"));
            request.set(exchange.getRequestBody().readAllBytes());
            byte[] body = "{\"text\":\" 白璃你好 \"}".getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, body.length); exchange.getResponseBody().write(body); exchange.close();
        });
        server.start();
        try {
            var activity = org.mockito.Mockito.mock(ActivityLogService.class);
            var audio = new AudioService(new BotConfig(new MockEnvironment().withProperty("FFMPEG_PATH", executable)
                    .withProperty("OPENLLM_HTTP", "http://127.0.0.1:" + server.getAddress().getPort())), activity);
            audio.checkFfmpeg();
            byte[] input = new byte[192000];
            var samples = ByteBuffer.wrap(input).order(ByteOrder.BIG_ENDIAN);
            while (samples.hasRemaining()) samples.putShort((short) 0x1234);
            assertThat(audio.transcribe(input, "測試使用者")).isEqualTo("白璃你好");
            assertThat(upgrade.get()).isNull();
            org.mockito.Mockito.verify(activity).voice("測試使用者", "白璃你好");
            org.mockito.Mockito.verify(activity).step("語音辨識中", "測試使用者：正在轉換為文字");
            byte[] multipart = request.get();
            String raw = new String(multipart, StandardCharsets.ISO_8859_1);
            assertThat(raw).contains("name=\"file\"; filename=\"discord.wav\"");
            int offset = raw.indexOf("RIFF");
            var wav = ByteBuffer.wrap(multipart).order(ByteOrder.LITTLE_ENDIAN);
            assertThat(wav.getInt(offset + 24)).isEqualTo(16000);
            assertThat(wav.getInt(offset + 40)).isEqualTo(32000);
            // Resampling/downmixing may round the sample by one or two units.
            assertThat((int) wav.getShort(offset + 44 + 1000)).isBetween(0x1232, 0x1236);
            assertThat(audio.playback(AudioService.wav(new byte[32000]))).hasSize(192000);
        } finally { server.stop(0); }
    }
    @Test void failedRecognitionReportsStepWithoutInventingTranscript() {
        var activity = org.mockito.Mockito.mock(ActivityLogService.class);
        var audio = new AudioService(new BotConfig(new MockEnvironment().withProperty("FFMPEG_PATH", "missing-ffmpeg-for-test")), activity);
        assertThatThrownBy(() -> audio.transcribe(new byte[4], "A")).isInstanceOf(Exception.class);
        org.mockito.Mockito.verify(activity).step("語音處理失敗", "A：FFmpeg 音訊轉換失敗，請檢查 FFMPEG_PATH");
        org.mockito.Mockito.verify(activity, org.mockito.Mockito.never()).voice(org.mockito.ArgumentMatchers.anyString(), org.mockito.ArgumentMatchers.anyString());
    }
}
