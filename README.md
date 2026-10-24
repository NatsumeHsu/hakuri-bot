# 白璃 Hakuri Bot

以 Java 25、Spring Boot 與 JDA 建立的 Discord／Twitch AI Bot，將文字與 Discord 語音轉送到 Open-LLM-VTuber，並提供本機管理後台。

## 功能

- **Discord 對話**：接收文字指令、錄音分段、語音轉文字、播放 AI 語音回覆，以及中斷對話。
- **Twitch 聊天轉送**：匿名唯讀加入指定聊天室，將觀眾名稱與原文依序送往 AI。
- **AI 服務管理**：從後台啟動 Ollama、GPT-SoVITS、Open-LLM-VTuber 與 Electron，由 Bot 管理子程序生命週期。
- **管理後台**：登入保護、服務狀態、設定管理、對話控制、密碼修改與聊天室轉送計數。
- **簡易流程紀錄**：顯示輸入文字、語音辨識結果與處理步驟，保留近期紀錄。
- **AI 最新回覆**：自動更新 AI 實際回覆，同輪文字累加，新一輪取代舊內容。

## 執行需求

| 項目 | 說明 |
| --- | --- |
| 作業系統 | 完整的本機 AI 子程序管理功能使用 Windows Job Object |
| Java | **JDK 25**，`JAVA_HOME` 或 PATH 應指向正確版本 |
| 建置工具 | 專案內附 Maven Wrapper，不必另裝 Maven；首次使用需連網下載依賴 |
| 音訊工具 | FFmpeg，供 Discord 錄音與回覆音訊轉碼 |
| Discord | Bot Token、Message Content Intent，以及頻道存取與語音權限 |
| AI 服務 | 已安裝並設定好的 Ollama、GPT-SoVITS、Open-LLM-VTuber、uv 與 Electron |

專案不包含 AI 服務本體、模型權重或角色資源。LLM、ASR、TTS 與模型路徑由各 AI 服務自身的設定檔管理，例如 Open-LLM-VTuber 的 `conf.yaml`。

## 快速開始

### 1. 準備環境設定

複製範例設定；已有 `.env` 時直接編輯原檔：

```powershell
Copy-Item example.env .env
```

填入 Discord Token、FFmpeg 路徑與 Twitch 頻道，並確認 AI 服務路徑。不要提交 `.env`。

### 2. 啟動

雙擊 `start.bat`，或執行：

```powershell
.\start.ps1
```

啟動腳本使用 Maven Wrapper 啟動 Spring Boot。首次啟動需下載依賴。也可以從 IDE 執行 `HakuriBotApplication`。

### 3. 登入後台

開啟 `http://127.0.0.1:8080/`。新資料庫預設帳號與密碼均為 `admin`；登入後可修改密碼。既有密碼不會自動重設。密碼以 BCrypt 雜湊存入 SQLite。

### 4. 啟動 AI

先在「服務連線」選擇需要啟用的功能並儲存，再按「啟動 AI 服務」。Bot 依序啟動 Ollama、GPT-SoVITS、Open-LLM-VTuber、Electron；後端就緒後，依儲存的 Open-LLM 與 Discord 語音設定決定是否連線。

啟動 Bot 不會自動啟動 AI。Open-LLM、Discord 語音與 Twitch 預設關閉，Discord Bot 預設開啟。其餘設定修改後通常需重新啟動 Bot；OBS 字幕樣式儲存後即時生效。

## 環境設定

完整範例見 `example.env`，`.env` 管理以下欄位：

| 變數 | 用途 |
| --- | --- |
| `FFMPEG_PATH` | FFmpeg 執行檔 |
| `DISCORD_TOKEN` | Discord Bot Token |
| `TWITCH_CHANNEL` | Twitch 聊天室頻道 |
| `WAKE_WORD` | 語音喚醒詞 |
| `AI_ROOT` | AI 服務根目錄 |
| `OLLAMA_PATH` | Ollama 執行檔；留空時嘗試自動尋找 |
| `GPT_SOVITS_DIR` | GPT-SoVITS 工作目錄 |
| `GPT_SOVITS_PYTHON_PATH` | GPT-SoVITS Python 執行檔 |
| `GPT_SOVITS_API_PATH` | GPT-SoVITS API 程式 |
| `OPENLLM_DIR` | Open-LLM-VTuber 工作目錄 |
| `OPENLLM_SERVER_PATH` | Open-LLM-VTuber 伺服器程式 |
| `UV_PATH` | uv 執行檔；留空時嘗試自動尋找 |
| `VTUBER_ELECTRON_PATH` | VTuber Electron 執行檔 |
| `SERVER_ADDRESS` | 後台監聽位址，預設 `127.0.0.1` |
| `SERVER_PORT` | 後台連接埠，預設 `8080` |

其他服務開關、連線網址與語音參數由後台管理，存於 `hakuri.db` 的 `bot_settings` 表，透過 Spring Data JPA／Hibernate ORM 存取。FFmpeg、Twitch 頻道、喚醒詞在後台僅供檢視，請修改 `.env`。

Open-LLM HTTP 預設為 `http://127.0.0.1:12393`。語音辨識使用 Open-LLM-VTuber 的 `/asr` 端點與其 ASR 設定，不需另外啟動獨立 ASR 服務。

## Discord 與 Twitch

Discord Bot 需要 Message Content Intent 與適當的文字／語音頻道權限。啟用 Discord 語音及 Open-LLM 後，使用以下指令：

| 指令 | 用途 |
| --- | --- |
| `!join` | 加入使用者所在語音頻道 |
| `!leave` | 離開語音頻道 |
| `!say 文字` | 傳送文字給 AI |
| `!stop` | 中斷目前對話 |
| `!clear` | 清除等待佇列 |
| `!status` | 查詢狀態 |

Discord 錄音經 FFmpeg 轉為 16 kHz 單聲道 WAV 後送往 ASR；辨識文字經喚醒詞與佇列規則送往 AI。Bot 同時管理一個語音頻道。

Twitch 使用匿名唯讀連線，不會向聊天室發言。啟用後，聊天室原文連同顯示名稱送入 AI 佇列。後台顯示加入狀態、收到筆數與送入 AI 筆數；AI 未連線時無法處理對話。

## AI 子程序與紀錄

AI 服務以受管理的子程序啟動，Windows Job Object 會在 Bot 結束時清理所屬程序。既有程序佔用服務連接埠時會回報衝突；請先確認其他方式啟動的 AI 服務。

後台簡易紀錄顯示輸入文字、語音轉文字與目前流程步驟。活動紀錄存於 `logs/activity.jsonl`，單檔約 2 MiB 後輪替至 `.1`，記憶體保留最近 5,000 筆。原始 AI 子程序輸出另存於 `logs/`，供故障排查。

AI 最新回覆顯示實際輸出文字，同一輪逐句累加，新一輪清除舊內容。重新啟動 Bot 後，最新回覆重新累積。

## OBS 字幕擷取

OBS 新增「瀏覽器」來源，網址固定使用：

```text
http://127.0.0.1:8080/obs.html
```

建議寬度 `1920`、高度 `300`。頁面為透明背景，字幕置中靠下，會自動更新 AI 回覆。

在後台「系統設定 → OBS 字幕」調整字型、字體大小與文字顏色。設定存入 SQLite，儲存後自動套用，不需重新啟動或修改 OBS 網址。字型需安裝於執行 OBS 的電腦。預設為 Microsoft JhengHei、42 px、白色；大小範圍為 16–160 px。

若修改伺服器位址或連接埠，需同步修改 OBS 來源網址。OBS 在其他電腦執行時，請使用可連線的 Bot 主機位址。

字幕頁、最新回覆與字幕樣式為匿名唯讀；其他管理操作需登入。伺服器若對其他電腦開放，可連線者也能讀取目前回覆。

## API

管理 API 需登入；修改操作另需帶入 `/api/csrf` 回傳的 CSRF 標頭。

| 方法 | 路徑 | 用途 |
| --- | --- | --- |
| GET | `/api/csrf` | 取得 CSRF Token，匿名可讀 |
| GET | `/api/status` | Bot、語音與服務狀態 |
| GET / PUT | `/api/settings` | 讀取／儲存設定 |
| POST | `/api/messages` | 傳送文字 |
| POST | `/api/actions/{action}` | 控制對話與啟動服務 |
| PUT | `/api/password` | 修改管理員密碼 |
| GET | `/api/activity` | 簡易活動紀錄 |
| GET | `/api/ai/logs` | AI 原始紀錄 |
| GET | `/api/ai/reply` | 最新 AI 回覆，匿名唯讀 |
| GET | `/api/ai/reply/style` | OBS 字幕樣式，匿名唯讀 |

## 專案分層

```text
src/main/java/tw/hakuri/bot/
  admin/           後台控制器、安全性、設定服務
  audio/           音訊轉碼與 ASR
  config/          設定與 AI 路徑
  conversation/    對話佇列與 AI 回覆
  integration/     Discord、Twitch、Open-LLM、AI 子程序
  observability/   活動紀錄
  settings/        SQLite entity、repository、service
src/main/resources/static/  後台與 OBS 字幕頁
```

## 建置與測試

```powershell
.\mvnw.cmd -B verify
```

音訊測試可指定可用的 FFmpeg：

```powershell
.\mvnw.cmd -B '-Dffmpeg.test.path=D:\ffmpeg\bin\ffmpeg.exe' verify
```

套件輸出於 `target/`。測試不會啟動完整 AI 模型服務。

## 常見問題

- **找不到 FFmpeg**：確認 `.env` 的 `FFMPEG_PATH` 指向存在的執行檔。
- **找不到 Ollama／uv**：在 `.env` 明確填入對應執行檔的完整路徑。
- **Discord 沒有辨識文字**：確認已啟動 AI、啟用 Open-LLM 與 Discord 語音，並確認 Open-LLM 的 ASR 模型可正常使用；查看活動紀錄中的 HTTP 或連線錯誤。
- **Twitch 沒有訊息**：確認頻道名稱、Twitch 開關與聊天室加入狀態，再檢查收到／送出計數。
- **OBS 沒有字幕**：確認網址與連接埠、Bot 是否有實際 AI 回覆，以及瀏覽器來源是否啟用。
- **設定沒有立即生效**：OBS 樣式即時生效；AI 連線選項於啟動 AI 就緒後套用，其他執行設定需重啟 Bot。


