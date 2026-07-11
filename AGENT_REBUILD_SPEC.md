# Captions — Agent 从零重建规格书

> **用途**：给其他 agent 在不依赖本仓库源码的情况下，从零重建功能等价的 Android 应用。  
> **权威优先级**：本文描述「当前已实现行为」；`PLAN.md` 是产品愿景与未完成路线图。冲突时以本文「已实现」为准，以 `PLAN.md` 为扩展目标。  
> **版本快照**：`app.captions` `0.1.0`，minSdk 31 / targetSdk 35。

---

## 0. 一句话产品定义

**Captions** 是一款 Android 12+ 原生应用：用用户自备 API Key（BYOK），对麦克风或其他 App 播放音频做**实时转写 + 翻译**，并用**颜色（无文字标签）**区分多说话人。

无账号、无后端、无支付；音频直接发往第三方 STT/LLM 提供商。

---

## 1. 产品硬性约束（不可妥协）

| # | 约束 | 说明 |
|---|---|---|
| 1 | minSdk **31**（Android 12） | AudioPlaybackCapture 等能力依赖 |
| 2 | 说话人分离为**硬性要求** | UI **仅用颜色**区分，**永不**显示 "S1" / "说话人1" / 人名等文字标签 |
| 3 | 准确率优先于延迟与成本 | 默认模型选最高准确率档；`temperature=0` |
| 4 | BYOK | OpenRouter / Deepgram / ElevenLabs 三 Key，Keystore 加密存本地 |
| 5 | 双音频源 | 麦克风 **或** 其他 App 播放捕获（MediaProjection） |
| 6 | 单模块原生 Android | Kotlin + Jetpack Compose；无 Web / iOS / 服务端 |

---

## 2. 已实现 vs 规划（重建范围）

### 2.1 必须实现（当前代码已有）

- 三屏导航：Home / Settings / Live
- 三 API Key 加密存储 + 在线校验
- OpenRouter 模型列表拉取 + 推荐列表 + 自定义 model id
- 前台服务：麦克风 / MediaProjection 播放捕获
- STT 优先级：Deepgram 流式 → ElevenLabs 批量 → OpenRouter 多模态批量
- 能量 VAD 分块（批量路径）+ WAV 编码
- 跨块说话人 ID 重叠词对齐
- 同说话人短句合并（Coalescer）
- 实时字幕 UI：颜色气泡 + 原文/译文 + 音量条
- 翻译：OpenRouter chat completions，滚动双语上下文（最多 8 对）
- 单元测试 + Roborazzi 截图测试骨架

### 2.2 规划中、当前未实现（可选二期，见 PLAN.md）

- Room 会话持久化、历史列表、搜索
- TXT / SRT / JSON 导出
- 悬浮字幕窗（`SYSTEM_ALERT_WINDOW`）
- Silero VAD ONNX、ECAPA 说话人嵌入聚类
- AssemblyAI、Scribe Realtime 灰色预览
- 词表 UI、准确率档位、会话后精修 pass
- Retrofit、`tools/eval/` WER/BLEU
- CI/CD

**从零重建建议**：先完整复现 §2.1，再按 PLAN.md M6–M7 扩展。

---

## 3. 技术栈（精确版本）

| 层 | 技术 | 版本 / 备注 |
|---|---|---|
| 语言 | Kotlin | 2.1.0 |
| 构建 | AGP + Gradle Wrapper | AGP 8.7.3，Gradle 8.11.1 |
| DI | Hilt + KSP | Hilt 2.53.1，KSP 2.1.0-1.0.29 |
| UI | Compose + Material 3 | Compose BOM 2024.12.01 |
| 导航 | Navigation Compose | 2.8.5，字符串路由 |
| 异步 | Coroutines + Flow | StateFlow 单向数据流 |
| 持久化 | DataStore Preferences | 1.1.1（**无 Room**） |
| 密钥 | Android Keystore AES-GCM | alias `captions_api_keys` |
| 网络 | OkHttp 直调 | 4.12.0（**无 Retrofit**） |
| JSON | kotlinx.serialization | 1.7.3 |
| 测试 | JUnit4 + Turbine + MockWebServer + Robolectric + Roborazzi | 见 `libs.versions.toml` |
| JDK | 17 | `jvmTarget = 17` |
| SDK | compile/target 35，min 31 | `applicationId = app.captions` |

**不要引入**（当前实现未用）：Retrofit、Room、onnxruntime、Coil、Firebase、登录 SDK。

---

## 4. 工程骨架

```
Captions/                          # rootProject.name = "Captions"
├── settings.gradle.kts            # include(":app")；FAIL_ON_PROJECT_REPOS
├── build.gradle.kts               # 根插件 apply false
├── gradle.properties              # -Xmx4g，caching，configuration-cache，AndroidX
├── gradle/libs.versions.toml      # 版本目录（唯一依赖真相源）
├── gradle/wrapper/…
├── README.md
├── PLAN.md                        # 愿景与未完成路线图
└── app/
    ├── build.gradle.kts
    ├── proguard-rules.pro         # 保留 kotlinx.serialization
    └── src/
        ├── main/
        │   ├── AndroidManifest.xml
        │   ├── java/app/captions/…
        │   └── res/…
        └── test/java/app/captions/…
```

### 4.1 Manifest 权限与组件

**权限**：`INTERNET`、`RECORD_AUDIO`、`POST_NOTIFICATIONS`、`FOREGROUND_SERVICE`、`FOREGROUND_SERVICE_MICROPHONE`、`FOREGROUND_SERVICE_MEDIA_PROJECTION`。

**组件**：
- `CaptionsApp`（`@HiltAndroidApp`）
- `MainActivity`（exported，`singleTop`，LAUNCHER）
- `CaptionForegroundService`（exported=false，`foregroundServiceType="microphone|mediaProjection"`）

`allowBackup=false`。

### 4.2 Package 分层（必须按此划分）

```
app.captions/
├── CaptionsApp.kt
├── MainActivity.kt
├── di/AppModule.kt                 # DataStore、OkHttp、三家 baseUrl、KeyCrypto 绑定
├── audio/                          # 捕获、VAD、WAV
├── pipeline/                       # 会话编排、Provider 选择、说话人对齐、行合并
├── transcription/                  # 共享模型 + 三 Provider
│   ├── deepgram/
│   ├── elevenlabs/
│   └── openrouter/
├── translation/                    # OpenRouter 翻译
├── providers/                      # Key 校验、OpenRouter 模型目录
├── data/keys/                      # Keystore + DataStore
├── data/settings/                  # 模型偏好
├── service/                        # 前台服务
└── ui/{home,live,settings,caption,theme}/
```

---

## 5. 架构与数据流

```
MainActivity (NavHost: home | settings | live)
        │
        ▼
LiveCaptionScreen ──start/stop──► CaptionForegroundService
                                        │
                                        ▼
                              CaptionSessionController  (Singleton)
                     ┌──────────────┼──────────────┐
                     ▼              ▼              ▼
              MicrophoneCapture  PlaybackCapture  TranscriptionProviderSelector
                     │              │              │
                     └──── PCM16 ───┘              │
                            │                      ▼
                     EnergyVad (batch only)   Deepgram | ElevenLabs | OpenRouter
                            │                      │
                            ▼                      ▼
                       WavEncoder            SpeakerIdMapper
                                                   │
                                                   ▼
                                          CaptionLineCoalescer
                                                   │
                     OpenRouterTranslator ◄────────┤ (Final 行)
                                                   ▼
                                          StateFlow<LiveCaptionState>
                                                   │
                                                   ▼
                                          LiveCaptionViewModel → UI
```

**模式**：MVVM + 单向数据流；Service 与 ViewModel 共享同一个 `CaptionSessionController` Singleton。

---

## 6. 功能规格（按用户旅程）

### 6.1 Home

- 标题「Captions」，副标题「Live transcription & translation」
- 若三 Key 皆空：提示去 Settings；否则显示 Ready
- CTA：`Start live captions` → `live`；齿轮 → `settings`
- 就绪条件：`hasDeepgram || hasElevenLabs || hasOpenRouter`

### 6.2 Settings

**API Keys 区**（三个独立卡片/区块）：

| Provider | 用途 | 校验方式 | Auth |
|---|---|---|---|
| OpenRouter | 翻译必填路径；无专业 STT 时作转写回退 | `GET /api/v1/models` | `Authorization: Bearer` |
| Deepgram | 优先流式转写 | 探活（如 projects/keys 或 listen 相关） | `Authorization: Token` |
| ElevenLabs | 批量高准确率转写 | 探活 | `xi-api-key` |

行为：保存时 AES-GCM 加密写入 DataStore；可显示/隐藏；Verify 按钮显示 valid / invalid / network error。

**Models 区**：
- Translation model（默认 `google/gemini-3.1-pro-preview`）
- OpenRouter STT fallback model（同上默认）
- 推荐列表 + 从 OpenRouter catalog 合并 + **允许自定义** `provider/model-id` 输入

### 6.3 Live Captions

- 顶部：返回、状态文案、当前 Provider hint、音量 `LinearProgressIndicator`
- 源切换 Chip：`Microphone` / `App audio`
- App audio 说明：需一次性屏幕捕获授权；部分 DRM App 禁止；通话音频不可捕；授权后自动回到 Live
- Start：麦克风先请求 `RECORD_AUDIO`，再 `startForegroundService`；播放源走 `MediaProjectionManager.createScreenCaptureIntent()`
- Stop：发 `ACTION_STOP` 停服务
- 字幕列表：LazyColumn，每行左侧色条/气泡底色 = `SpeakerColors.colorFor(speaker)`；**无说话人文字**；原文 + 下方译文（有 OpenRouter Key 时）
- Partial 行半透明/未定稿；Final 入列表，最多保留约 200 行
- 空态文案强调「Speakers by color only」

### 6.4 前台服务行为

1. Android 14+：**先** `startForeground`（带正确 FGS type），**再** `getMediaProjection`
2. 通知：ongoing，Stop action，点开回 MainActivity
3. Playback 启动后调用 `bringCaptionsToFront`（`EXTRA_NAVIGATE_TO=live`）
4. `onDestroy` / STOP：`controller.stop()`

---

## 7. 音频层规格

| 项 | 值 |
|---|---|
| 采样率 | **16000** Hz |
| 声道 | mono |
| 编码 | PCM 16-bit little-endian |
| 帧长 | 默认 100 ms |
| 麦克风源 | `VOICE_RECOGNITION`（失败可文档化回退 `MIC`） |
| 播放捕获 usage | `USAGE_MEDIA` \| `USAGE_GAME` \| `USAGE_UNKNOWN` |

### 7.1 EnergyVadSegmenter（批量路径）

| 参数 | 默认值 |
|---|---|
| `targetMs` | 20_000 |
| `maxMs` | 45_000 |
| `silenceMsToCut` | 700 |
| `rmsThreshold` | 400.0 |
| `overlapMs` | 1_500 |

切分逻辑：有语音后，在静音边界切；达到 target 且有足够静音，或硬上限 max；纯静音不 emit；切出时保留尾部 overlap 到下一缓冲。

### 7.2 WavEncoder

PCM16 mono → 标准 WAV（44 字节头 + data），供 ElevenLabs / OpenRouter multipart 或 base64。

---

## 8. 转写 Provider 规格

### 8.1 选择优先级（`TranscriptionProviderSelector`）

1. Deepgram Key 非空 → `DEEPGRAM_STREAMING`
2. 否则 ElevenLabs Key 非空 → `ELEVENLABS_BATCH`
3. 否则 OpenRouter Key 非空 → `OPENROUTER_BATCH`
4. 全空 → 错误：「Add a Deepgram, ElevenLabs, or OpenRouter API key…」

### 8.2 共享领域模型

```kotlin
data class CaptionLine(
  id: String, speaker: Int, text: String,
  isFinal: Boolean, startedAtMs: Long, updatedAtMs: Long,
  translation: String? = null,
)

data class TranscriptionWord(text: String, speaker: Int, startSec: Double?, endSec: Double?)

sealed class TranscriptionEvent {
  Partial(text, speaker, words)
  Final(text, speaker, words)
  Error(message, cause?)
  Connected / Disconnected
}

interface StreamingTranscriptionSession { sendPcm16(frame); close() }
interface TranscriptionProvider { openStreamingSession(apiKey, onEvent) }
interface BatchTranscriptionProvider {
  transcribe(apiKey, wav, context: TranscriptionContext, model: String?): TranscriptionResult
}

data class TranscriptionContext(
  priorText: String = "",
  glossary: List<String> = emptyList(),
  speakerDescriptions: Map<Int, String> = emptyMap(),
)
```

`speaker` 仅为内部稳定 int，只用于颜色映射。

### 8.3 Deepgram Nova-3（流式）

- WebSocket：`wss`/`https` base `https://api.deepgram.com/` → `/v1/listen`
- Header：`Authorization: Token <key>`
- Query（**禁止**同时带 `diarize_model`，否则 400）：

```
model=nova-3
language=multi
encoding=linear16
sample_rate=16000
channels=1
interim_results=true
punctuate=true
smart_format=true
diarize=true
utterance_end_ms=2000
vad_events=true
```

- 发送：原始 PCM16 二进制帧
- 解析：interim → `Partial`；final/utterance → `Final`；提取词级 speaker

### 8.4 ElevenLabs Scribe v2（批量）

- `POST https://api.elevenlabs.io/v1/speech-to-text`
- Header：`xi-api-key`
- Multipart：`file=chunk.wav`，`model_id=scribe_v2`，`diarize=true`，`timestamps_granularity=word`，可选 `keyterms`
- 解析：词级 speaker → 按连续同 speaker 聚成 segments

### 8.5 OpenRouter 多模态（批量回退）

- `POST https://openrouter.ai/api/v1/chat/completions`
- Headers：`Authorization: Bearer`，`HTTP-Referer`，`X-Title: Captions`
- Body：`temperature=0`；`messages` 含 system（要求 JSON diarization）+ user content 含 `input_audio`（base64 WAV）
- System 注入：`priorText` 尾部、glossary、已知 `speaker_descriptions`
- 输出 JSON schema：

```json
{
  "segments": [
    {"speaker": 0, "lang": "en", "text": "...", "words": [{"text": "...", "speaker": 0}]}
  ],
  "speaker_descriptions": {"0": "brief voice note"}
}
```

- 默认模型：`google/gemini-3.1-pro-preview`

### 8.6 批量路径会话上下文

每块成功后：
- `priorText = (priorText + " " + chunkText).takeLast(1200)`
- 合并返回的 `speakerDescriptions`
- 经 `SpeakerIdMapper` 再 emit `Final`

---

## 9. 说话人与字幕合并

### 9.1 SpeakerIdMapper

- 维护 `localSpeaker → sessionSpeaker` 映射
- 新块：取上一块尾 12 词与本块头 12 词，规范化后相等则投票对齐 local→session
- 规范化：lowercase、trim、去常见标点
- `reset()` 在每次 `start()` 时调用

### 9.2 CaptionLineCoalescer

同 speaker 的连续 Final 合并，避免 Deepgram 短 utterance 刷屏：

| 参数 | 值 |
|---|---|
| `DEFAULT_GAP_MS` | 2800 |
| `MAX_MERGED_CHARS` | 280 |

规则：同 speaker；时间间隔 ≤ gap；合并后长度 ≤ max；短片段（≤4 词）总是可合；句末标点时 gap 减半。  
`join`：两端皆字母数字则插空格。合并后清空 translation 并重新翻译。

### 9.3 SpeakerColors

8 色色盲友好调色板（蓝/橙/绿/紫/青/粉/靛/lime）；`speaker % 8`；深色主题略提亮。UI **禁止**渲染 speaker 数字或标签。

---

## 10. 翻译规格

- 类：`OpenRouterTranslator`
- Endpoint：`POST /api/v1/chat/completions`
- 默认目标语言：`"Simplified Chinese"`（`DEFAULT_TARGET_LANGUAGE`）
- 默认模型：`google/gemini-3.1-pro-preview`
- System：精确字幕翻译；只返回译文；保留语气/专名/数字；附最近最多 6 对双语上下文
- 会话侧 `recentPairs` 最多 **8** 对（FIFO）
- 串行 Mutex，避免乱序写回
- 无 OpenRouter Key 时跳过翻译（转写仍可用）

推荐翻译模型列表（Settings 出厂）：

1. `google/gemini-3.1-pro-preview` — 最高准确率（默认）
2. `anthropic/claude-opus-4.7` — 语气/文学
3. `anthropic/claude-sonnet-4.6` — 均衡
4. `google/gemini-3-flash-preview` — 低成本

---

## 11. 数据与密钥

### 11.1 DataStore

文件：`captions_settings`

| Key | 内容 |
|---|---|
| `openrouter_api_key` | 密文 |
| `deepgram_api_key` | 密文 |
| `elevenlabs_api_key` | 密文 |
| `translation_model` | 明文 model id |
| `openrouter_stt_model` | 明文 model id |

### 11.2 KeyCrypto

- AndroidKeyStore，AES/GCM/NoPadding
- alias：`captions_api_keys`
- 存储格式：Base64(IV 12 bytes ‖ ciphertext+tag)

### 11.3 DI（AppModule）

- DataStore Singleton
- OkHttp：connect 15s，read 60s
- Named HttpUrl：`openRouterBaseUrl`、`deepgramBaseUrl`、`elevenLabsBaseUrl`
- `KeyCrypto` → `AndroidKeyCrypto`

---

## 12. UI 状态模型

```kotlin
enum class CaptureStatus { Idle, Connecting, Listening, Error }

data class LiveCaptionState(
  status: CaptureStatus = Idle,
  lines: List<CaptionLine> = emptyList(),
  partial: CaptionLine? = null,
  errorMessage: String? = null,
  level: Float = 0f,                 // 0..1 from PCM RMS/4000
  providerHint: String? = null,
  captureSource: CaptureSource = MICROPHONE,
  targetLanguage: String = "Simplified Chinese",
)
```

主题：Material 3 动态取色（`CaptionsTheme`）。文案集中在 `res/values/strings.xml`（英文 UI）。

---

## 13. 测试要求（重建验收）

至少覆盖以下单元测试主题（现有 18 个测试文件可作对照）：

| 区域 | 断言重点 |
|---|---|
| EnergyVadSegmenter | 静音丢弃、切块长度、overlap |
| WavEncoder | 头与 PCM 长度 |
| PlaybackCaptureConfig | usage 匹配 |
| ApiKeyRepository | 加解密存取 |
| ModelPreferencesRepository | 默认与自定义 model |
| KeyValidator | 200/401/网络错误 |
| TranscriptionProviderSelector | 优先级 |
| SpeakerIdMapper | 重叠对齐稳定性 |
| CaptionLineCoalescer | gap / 句末 / 长度 |
| Deepgram URL + message parser | query 与 Partial/Final |
| ElevenLabs parser + provider | MockWebServer multipart |
| OpenRouter audio parser | JSON segments |
| OpenRouterModelCatalog | audio modality 过滤 |
| OpenRouterTranslator | 只取译文、上下文 |
| Roborazzi | Home/Live 截图烟雾 |

构建命令：

```bash
./gradlew :app:assembleDebug
./gradlew :app:testDebugUnitTest
```

需要 JDK 17+ 与 Android SDK 35（`local.properties` → `sdk.dir`）。

---

## 14. 推荐重建顺序（里程碑）

按依赖从底向上，每步可测：

| 步 | 交付物 | 验收 |
|---|---|---|
| R0 | Gradle 单模块、Compose Hello、Hilt、Manifest 权限 | 安装启动 |
| R1 | DataStore + KeyCrypto + ApiKeyRepository + Settings Key UI + KeyValidator | 三 Key 保存/校验 |
| R2 | ModelPreferences + OpenRouter catalog + Settings Models | 可选模型与自定义 id |
| R3 | MicrophoneCapture + 前台服务麦克风 + 音量条 | 真机听到电平 |
| R4 | Transcription 模型 + Deepgram WebSocket | Live 出现着色字幕 |
| R5 | VAD + WavEncoder + ElevenLabs + OpenRouter STT + Selector | 拔 Deepgram Key 仍可转写 |
| R6 | SpeakerIdMapper + Coalescer + SpeakerColors UI | 多说话人稳定分色、无标签 |
| R7 | PlaybackCapture + MediaProjection 流程 + 回前台 | 捕获 YouTube 等并转写 |
| R8 | OpenRouterTranslator + 滚动上下文 | 双语行 |
| R9 | 单元测试与截图测试对齐 §13 | CI 本地绿 |
| R10（可选） | 按 PLAN.md 做 Room/导出/悬浮窗/Silero 等 | 产品完整版 |

---

## 15. 关键实现细节清单（易踩坑）

1. Deepgram：**不要**同时传 `diarize` 与 `diarize_model`
2. Android 14+：MediaProjection 前必须已是对应 type 的前台服务
3. Playback 授权 UI 会盖住 App：必须 `bringCaptionsToFront` / `EXTRA_NAVIGATE_TO=live`
4. 说话人 ID 仅会话内稳定；批量 Provider 每请求 local id 会重置 → 必须 overlap 对齐
5. 翻译与转写解耦：无 OpenRouter 仍可只转写
6. Release 开启 minify：ProGuard 保留 kotlinx.serialization
7. `allowBackup=false`，避免密钥备份泄露
8. OpenRouter 请求带 `HTTP-Referer` / `X-Title`（应用标识）
9. 列表 `takeLast(200)`，防止长会话 OOM
10. 批量路径 `priorText.takeLast(1200)`，控制 token

---

## 16. 外部服务速查

| 服务 | Base URL | 角色 |
|---|---|---|
| OpenRouter | `https://openrouter.ai/` | 翻译 + STT 回退 + `/api/v1/models` |
| Deepgram | `https://api.deepgram.com/` | Nova-3 流式 STT + diarize |
| ElevenLabs | `https://api.elevenlabs.io/` | Scribe v2 批量 STT + diarize |

用户自备 Key；应用不代理、不存储云端会话。

---

## 17. 非目标（当前版本明确不做）

- 用户账号 / OAuth / 云同步
- 应用内支付或 Key 代售
- iOS / Web / 桌面端
- 通话音频捕获
- 默认长期保存原始录音
- 在 UI 上显示任何说话人文字标签

---

## 18. 给重建 Agent 的工作协议

1. **先实现 §2.1 行为等价**，再考虑 PLAN.md 扩展。
2. **包名与 applicationId** 保持 `app.captions`（或文档声明变更）。
3. **每完成 R 步**跑对应单元测试；网络用 MockWebServer，勿在单测打真 API。
4. **UI 文案**可先用英文（与现 `strings.xml` 一致）；说话人颜色规则不可改。
5. 若某第三方 API 字段变更，保持接口抽象（`TranscriptionProvider` / `BatchTranscriptionProvider`），在实现类内适配。
6. 完整产品愿景、成本表、风险对策见同目录 `PLAN.md`；本文是「可运行现状」的重建蓝图。

---

*本文由现有仓库源码与 PLAN.md 对照生成，供跨 agent 无上下文重建使用。*
