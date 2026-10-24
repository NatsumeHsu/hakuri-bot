package tw.hakuri.bot.audio.service;

import tw.hakuri.bot.config.BotConfig;
import tw.hakuri.bot.observability.activity.ActivityLogService;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Service;
import java.io.*;
import java.net.URI;
import java.net.ConnectException;
import java.net.http.*;
import java.nio.*;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.UUID;
import java.util.concurrent.*;

@Service
public class AudioService {
    private final BotConfig config;
    private final ActivityLogService activity;
    private final ObjectMapper json = new ObjectMapper();
    // Uvicorn's HTTP/1 server may parse h2c upgrades without the multipart body (HTTP 422).
    private final HttpClient http = HttpClient.newBuilder().version(HttpClient.Version.HTTP_1_1)
            .connectTimeout(Duration.ofSeconds(10)).build();
    public AudioService(BotConfig config, ActivityLogService activity) { this.config = config; this.activity = activity; }

    public void checkFfmpeg() throws Exception { convert(new byte[3840], "s16be", "48000", "2", "s16le", "16000", "1"); }
    public byte[] playback(byte[] wav) throws Exception {
        return convert(wav, null, null, null, "s16be", "48000", "2");
    }
    public String transcribe(byte[] pcm) throws Exception {
        return transcribe(pcm, "Discord 語音");
    }
    public String transcribe(byte[] pcm, String speaker) throws Exception {
        activity.step("語音辨識中", speaker + "：正在轉換為文字");
        try {
            String text = transcribeRequest(pcm);
            if (text.isEmpty()) activity.step("未辨識到文字", speaker + "：請再說一次");
            else { activity.voice(speaker, text); activity.step("語音辨識完成", speaker + "：準備處理辨識文字"); }
            return text;
        } catch (Exception error) {
            activity.step(error instanceof AudioConversionException ? "語音處理失敗" : "語音辨識失敗", speaker + "：" + failureReason(error));
            if (error instanceof InterruptedException) Thread.currentThread().interrupt();
            throw error;
        }
    }
    private String transcribeRequest(byte[] pcm) throws Exception {
        byte[] wav;
        try { wav = wav(convert(pcm, "s16be", "48000", "2", "s16le", "16000", "1")); }
        catch (InterruptedException error) { throw error; }
        catch (Exception error) { throw new AudioConversionException(error); }
        String boundary = "hakuri-" + UUID.randomUUID();
        var body = new ByteArrayOutputStream();
        body.write(("--" + boundary + "\r\nContent-Disposition: form-data; name=\"file\"; filename=\"discord.wav\"\r\nContent-Type: audio/wav\r\n\r\n").getBytes(StandardCharsets.UTF_8));
        body.write(wav);
        body.write(("\r\n--" + boundary + "--\r\n").getBytes(StandardCharsets.UTF_8));
        var response = http.send(HttpRequest.newBuilder(URI.create(config.http() + "/asr")).version(HttpClient.Version.HTTP_1_1)
                .timeout(Duration.ofSeconds(30)).header("Content-Type", "multipart/form-data; boundary=" + boundary)
                .POST(HttpRequest.BodyPublishers.ofByteArray(body.toByteArray())).build(), HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() / 100 != 2) throw new AsrHttpException(response.statusCode(), response.body());
        var result = json.readTree(response.body());
        if (result == null || !result.path("text").isTextual()) throw new IOException("ASR 回應缺少 text 辨識文字欄位");
        return result.path("text").asText().trim();
    }
    private static String failureReason(Exception error) {
        if (error instanceof AudioConversionException) return "FFmpeg 音訊轉換失敗，請檢查 FFMPEG_PATH";
        if (error instanceof ConnectException) return "無法連線到 Open-LLM-VTuber，請先從後台啟動 AI";
        if (error instanceof HttpTimeoutException) return "Open-LLM-VTuber 語音辨識逾時，請稍後重試";
        if (error instanceof AsrHttpException http) return switch (http.status) {
            case 400, 422 -> "語音上傳格式未被接受（HTTP " + http.status + "）";
            case 404 -> "Open-LLM-VTuber 網址未提供 /asr，請確認服務網址";
            case 500, 503 -> "Open-LLM-VTuber 內建辨識引擎失敗（HTTP " + http.status + "），請查看 AI 日誌";
            default -> "Open-LLM-VTuber 語音辨識請求失敗（HTTP " + http.status + "）";
        };
        if (error instanceof InterruptedException) return "辨識工作已取消";
        return "語音辨識回應異常，請查看主程式日誌";
    }
    private static final class AudioConversionException extends IOException {
        AudioConversionException(Exception cause) { super("FFmpeg 音訊轉換失敗", cause); }
    }
    private static final class AsrHttpException extends IOException {
        final int status;
        AsrHttpException(int status, String response) {
            super("ASR HTTP " + status + ": " + response.substring(0, Math.min(1000, response.length())));
            this.status = status;
        }
    }
    public static byte[] wav(byte[] pcm) {
        var buffer = ByteBuffer.allocate(44 + pcm.length).order(ByteOrder.LITTLE_ENDIAN);
        buffer.put("RIFF".getBytes(StandardCharsets.US_ASCII)).putInt(36 + pcm.length)
                .put("WAVEfmt ".getBytes(StandardCharsets.US_ASCII)).putInt(16).putShort((short) 1)
                .putShort((short) 1).putInt(16000).putInt(32000).putShort((short) 2).putShort((short) 16)
                .put("data".getBytes(StandardCharsets.US_ASCII)).putInt(pcm.length).put(pcm);
        return buffer.array();
    }
    private byte[] convert(byte[] input, String inFormat, String inRate, String inChannels,
                           String outFormat, String outRate, String outChannels) throws Exception {
        var args = new java.util.ArrayList<>(java.util.List.of(config.ffmpeg(), "-hide_banner", "-loglevel", "error"));
        if (inFormat != null) args.addAll(java.util.List.of("-f", inFormat, "-ar", inRate, "-ac", inChannels));
        args.addAll(java.util.List.of("-i", "pipe:0", "-f", outFormat, "-ar", outRate, "-ac", outChannels, "pipe:1"));
        Process process = new ProcessBuilder(args).start();
        try (var workers = Executors.newVirtualThreadPerTaskExecutor()) {
            var output = workers.submit(() -> process.getInputStream().readAllBytes());
            var errors = workers.submit(() -> process.getErrorStream().readAllBytes());
            var writer = workers.submit(() -> { try (var stream = process.getOutputStream()) { stream.write(input); } return null; });
            try {
                if (!process.waitFor(30, TimeUnit.SECONDS)) throw new IOException("FFmpeg 處理逾時");
                if (process.exitValue() != 0) throw new IOException("FFmpeg: " + new String(errors.get(), StandardCharsets.UTF_8));
                writer.get();
                return output.get();
            } finally { process.destroyForcibly(); }
        }
    }
}
