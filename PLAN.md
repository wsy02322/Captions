# Captions — 实时转写与翻译 Android 应用：技术方案

## 1. 目标与需求

| 需求 | 方案要点 |
| --- | --- |
| Android 12+（minSdk 31） | Kotlin + Jetpack Compose，targetSdk 最新版 |
| 转写 + 翻译 | 两阶段管线：音频 → 转写 → 翻译，均通过 OpenRouter |
| OpenRouter API Key 驱动 | 用户在设置中填入 Key，Keystore 加密存储；模型可自由选择 |
| 多语言 | 自动语种检测或手动指定源语言；目标语言任选；支持混合语言音频 |
| 多说话人（硬性要求） | 说话人分离为必选能力；UI **仅用颜色区分说话人，不显示任何文字标签**；内部用稳定 ID 跟踪并映射到固定颜色 |
| 准确率最高优先 | 见 §6「准确率策略」——模型选择、上下文注入、重叠分块、精修 pass 均围绕此设计 |
| 音频源：其他应用 / 麦克风 | AudioPlaybackCapture（MediaProjection）+ AudioRecord 麦克风 |

## 2. 总体架构

```
┌─ ForegroundService（mediaProjection|microphone 类型）─────────────┐
│  AudioCaptureEngine（麦克风 或 播放捕获，PCM16 mono 16kHz）        │
│    └→ 环形缓冲 → VAD 分段器（静音边界，15–30s，1–2s 重叠）         │
│         └→ TranscriptionQueue（WAV 编码 → base64 → OpenRouter）    │
│              └→ TranslationQueue（滚动上下文 + 术语表）            │
│                   └→ Room 持久化 → StateFlow → Compose UI         │
└───────────────────────────────────────────────────────────────────┘
```

- **架构模式**：MVVM + 单向数据流；Hilt 依赖注入；Coroutines/Flow。
- **模块划分**（单 module 起步，按 package 分层）：
  - `audio/`：捕获引擎、VAD、WAV 编码
  - `pipeline/`：分段器、转写队列、翻译队列、重试策略
  - `openrouter/`：Retrofit/OkHttp 客户端、两种端点封装、模型列表拉取
  - `data/`：Room（会话、片段、说话人）、DataStore（设置）、Keystore（API Key）
  - `ui/`：会话列表、实时字幕页、设置页、悬浮字幕窗
  - `service/`：前台服务、通知、MediaProjection 生命周期

## 3. 音频捕获

### 3.1 麦克风
- `AudioRecord`，`MediaRecorder.AudioSource.VOICE_RECOGNITION`（回退 `MIC`），16 kHz / mono / PCM16。
- 权限：`RECORD_AUDIO`、`FOREGROUND_SERVICE_MICROPHONE`（API 34+ 必须声明前台服务类型）。

### 3.2 其他应用音频（AudioPlaybackCapture）
- `MediaProjectionManager` 请求授权（每次会话需用户确认，Android 14+ 还需先启动前台服务再 `getMediaProjection`）。
- `AudioPlaybackCaptureConfiguration` 匹配 usage：`USAGE_MEDIA`、`USAGE_GAME`、`USAGE_UNKNOWN`。
- 已知限制（需在 UI 中向用户说明）：
  - 目标应用可通过 `setAllowedCapturePolicy(ALLOW_CAPTURE_BY_NONE)` 禁止捕获（部分流媒体/DRM 应用如此）；
  - 通话音频（`USAGE_VOICE_COMMUNICATION`）不可捕获；
  - 捕获的是系统混音后的播放流，采样率按设备输出重采样到 16 kHz。
- 权限：`FOREGROUND_SERVICE_MEDIA_PROJECTION`。

### 3.3 分段（对准确率至关重要）
- 本地 VAD（Silero VAD ONNX，onnxruntime-android；回退能量阈值 VAD）在静音边界切分，避免切断单词。
- 目标块长 15–30 s；硬上限 45 s（防止长时间无静音）；相邻块保留 1–2 s 重叠，合并时按重叠文本去重对齐。
- 纯静音段直接丢弃，节省 API 成本。

## 4. OpenRouter 集成

### 4.1 转写路径

**主路径（唯一默认）**：`POST /api/v1/chat/completions` + `input_audio` 内容块，使用支持音频输入的多模态模型。这是满足「必须区分说话人」的唯一路径——系统提示词注入前文上下文、领域词表、说话人分离指令，输出结构化 JSON。

专用 STT 端点（`/api/v1/audio/transcriptions`，Whisper/GPT-4o Transcribe/Qwen3-ASR 等）**不做说话人分离，无法满足硬性要求**，仅作为设置页中的高级降级选项（默认隐藏，启用时明确警告将失去说话人区分）。

- 音频以 base64 WAV 上传（PCM16 封 WAV 头即可，无需额外编码库）。
- 模型列表通过 `GET /api/v1/models` 拉取，按 `input_modalities` 含 `audio` 过滤，供设置页动态选择。
- 结构化输出 schema：`{"segments":[{"speaker":1,"lang":"zh","text":"…"}]}`，`temperature=0`。`speaker` 为整数 ID，仅用于内部跟踪与颜色映射，永不显示给用户。

### 4.2 默认模型推荐（2026-07 经 OpenRouter `/models` API 实测在售）

**转写（音频多模态，必须支持说话人分离）**

| 档位 | 模型 | 价格（输入/输出 per M tokens） | 推荐理由 |
| --- | --- | --- | --- |
| **最高准确率（默认）** | `google/gemini-3.1-pro-preview` | $2 / $12 | Gemini 系为当前 diarization 准确率最强的多模态模型；继承 Gemini 2.5 Pro 在 WMT25、ASR 评测的领先谱系；1M 上下文利于长会话上下文注入 |
| 均衡 | `google/gemini-3.5-flash` | $1.5 / $9 | 同系 Flash 档，延迟低、便宜，diarization 能力保留 |
| 低成本 | `google/gemini-3-flash-preview` | $0.5 / $3 | 实时性最好，准确率可接受 |
| 备选（非 Google 系） | `openai/gpt-audio` | $2.5 / — | 语音理解强，但 diarization 稳定性弱于 Gemini，作为故障切换备选 |

**翻译（纯文本 LLM）**

| 档位 | 模型 | 价格（输入/输出 per M tokens） | 推荐理由 |
| --- | --- | --- | --- |
| **最高准确率（默认）** | `google/gemini-3.1-pro-preview` | $2 / $12 | OpenMark 2026-03 翻译基准第一，前代蝉联 WMT25 人评 16 语对中 14 项冠军；价格仅为 Opus/GPT-5.5 的 40% |
| 语气/文学敏感备选 | `anthropic/claude-opus-4.7` | $5 / $25 | 专业译员盲评认可度最高的谱系，西/阿等语对领先，适合演讲、影视等语气敏感内容 |
| 均衡 | `anthropic/claude-sonnet-4.6` | $3 / $15 | COMET 高分、流畅度佳、成本适中 |
| 低成本 | `google/gemini-3-flash-preview` | $0.5 / $3 | 高吞吐实时翻译，质量/成本比最好 |

- 转写与翻译默认同用 `google/gemini-3.1-pro-preview` 还有一个工程红利：转写段落与翻译共享同一模型的语言先验，术语一致性更好。
- 以上均为动态推荐列表的「出厂默认」；应用每次启动拉取 `/models` 校验模型仍在售，下架时自动回退到同档位次选并提示用户。

### 4.3 翻译请求设计
- `POST /api/v1/chat/completions`，模型按 §4.2 档位可配置。
- 每次请求携带：目标语言、最近 N 段原文+译文（滚动上下文，保证代词/术语一致）、会话术语表（用户可编辑）、说话人 ID（保证同一说话人称谓/语气一致）。
- 逐段流式翻译显示；会话结束后可选「精修 pass」：整段上下文重译，覆盖初译。

### 4.4 客户端工程
- OkHttp + Retrofit + kotlinx.serialization；指数退避重试（429/5xx/网络错误，最多 4 次）；请求队列串行保序，转写与翻译两条队列并行。
- API Key：Android Keystore AES 加密后存 DataStore；所有请求头 `Authorization: Bearer <key>`，附 `HTTP-Referer`/`X-Title` 标识应用。
- 失败片段落盘保留原始音频（临时目录），支持手动重试，避免丢内容。

## 5. 多说话人与多语言

- **说话人分离（必选）**：由多模态模型完成 diarization——系统提示词要求按声纹/语气区分说话人并输出稳定整数 ID；每个新分块的提示词携带「已知说话人声音描述」（说话人 1: 男声、低沉、说中文…由模型自己在上一块生成）以保持跨块一致。
- **说话人呈现：仅用颜色，无文字标签**：
  - 每个说话人 ID 映射到调色板中的一种固定颜色（气泡底色/左侧色条+文字色），整个会话及导出物中保持稳定；
  - UI 上不显示 "S1"、"说话人 1"、人名等任何文字标签；
  - 调色板取自 Material 3 色板中对比度充足、色盲友好的 8–10 色序列（如蓝/橙/绿/紫/青/粉…），深浅色主题各一套；超过色板容量时按色相间隔生成新色；
  - 用户可长按某说话人的气泡换色，或将两个误分的说话人「合并」（合并 = 颜色统一，仍无标签）；
  - 导出：SRT/TXT 导出用颜色名或 `<font color>` 标记（SRT 支持）；JSON 导出保留内部 ID 与颜色值。
- **多语言**：默认自动检测（模型逐段标注 `lang`）；用户可固定源语言以提升准确率（作为提示词参数传入）。混合语言音频逐段标注、逐段翻译。
- 降级 STT 路径（Whisper 系）不支持说话人分离，启用前 UI 明确警告所有文本将变为单色。

## 6. 准确率策略（最高优先级）

1. **模型档位**：默认「最高准确率」= `google/gemini-3.1-pro-preview` 转写 + 同模型翻译 + 会话后精修（见 §4.2）；另提供「均衡」「快速」档，用户明确选择才降级。
2. **上下文注入**：每块转写提示词携带上一块尾部转写文本 + 会话主题摘要，显著减少专名/术语错误。
3. **用户词表**：会话可配置领域词汇/人名（提示词注入 + 翻译术语表复用）。
4. **VAD 静音边界 + 重叠分块**：避免截词；重叠区文本对齐去重。
5. **确定性输出**：`temperature=0`，结构化 JSON schema 输出，失败时自动重试并放宽解析。
6. **二次精修**：会话结束后可一键用整段上下文重跑翻译（和可选的转写校对），修正早期缺乏上下文造成的误差。
7. **评估**：`tools/eval/` 放置 WER/BLEU 评估脚本 + 小型多语言多说话人测试音频集，回归验证管线改动。

## 7. UI / UX

- **会话列表页**：历史会话（标题、时长、语言对、来源图标），支持搜索、导出（TXT/SRT/JSON）、删除。
- **实时字幕页**：气泡流（原文 + 译文成对显示），说话人**仅以颜色区分**（气泡底色/色条，无任何文字标签，见 §5），自动滚动+手动回看，顶部显示捕获状态与音量表，暂停/恢复/结束控制。
- **悬浮字幕窗**（可选，`SYSTEM_ALERT_WINDOW`）：看视频时叠加双语字幕条，可拖动、调透明度。
- **设置页**：API Key（校验按钮：调 `/models` 验证）、转写/翻译模型选择（动态列表）、准确率档位、源/目标语言、词表管理。
- **新手引导**：首次使用引导填 Key、授权麦克风/投影，说明播放捕获的系统限制。
- Material 3 动态取色，深色模式。

## 8. 数据与隐私

- Room：`Session`、`Segment`（原文/译文/说话人/语言/时间戳/状态）、`SpeakerProfile`、`GlossaryTerm`。
- 原始音频默认不长期保留（仅失败重试的临时块），可在设置中开启「保留录音」。
- 隐私声明：音频会发送至 OpenRouter 及其上游模型提供商；应用内明确提示。

## 9. 权限与清单

`INTERNET`、`RECORD_AUDIO`、`POST_NOTIFICATIONS`、`FOREGROUND_SERVICE`、`FOREGROUND_SERVICE_MICROPHONE`、`FOREGROUND_SERVICE_MEDIA_PROJECTION`、可选 `SYSTEM_ALERT_WINDOW`。前台服务 `foregroundServiceType="mediaProjection|microphone"`。

## 10. 技术栈

Kotlin 2.x · Jetpack Compose + Material 3 · Hilt · Room · DataStore · OkHttp/Retrofit + kotlinx.serialization · onnxruntime-android（Silero VAD）· minSdk 31 / targetSdk 35 · Gradle Version Catalog · JUnit5 + Turbine + MockWebServer 单测，Compose UI 测试。

## 11. 里程碑

| 里程碑 | 内容 | 验收 |
| --- | --- | --- |
| M1 项目骨架 | Gradle 工程、Hilt、导航、设置页、API Key 加密存储与校验 | Key 校验通过 `/models` 返回模型列表 |
| M2 麦克风转写 | AudioRecord + VAD 分段 + chat/completions 音频转写（默认模型）+ 实时字幕页 | 真机/模拟器实时看到转写文本 |
| M3 播放捕获 | MediaProjection + AudioPlaybackCapture + 前台服务完整生命周期 | 捕获其他应用（如 YouTube）音频并转写 |
| M4 高精度转写 | chat/completions 多模态路径、说话人分离、颜色映射、上下文注入、结构化解析 | 多说话人音频中不同说话人以稳定且不同的颜色呈现（无文字标签） |
| M5 翻译管线 | 逐段翻译、滚动上下文、术语表、精修 pass | 双语实时显示，译文术语一致 |
| M6 存储与导出 | Room 会话持久化、历史页、TXT/SRT/JSON 导出 | 重启后会话可回看、可导出 |
| M7 打磨 | 悬浮字幕窗、准确率档位、词表 UI、错误恢复、评估脚本 | WER/BLEU 回归基线建立 |

每个里程碑：单元测试（分段器、重叠合并、队列重试、API 解析用 MockWebServer）+ 模拟器手动验证（麦克风用虚拟音频输入，播放捕获用模拟器内播放测试音频）。

## 12. 风险与对策

| 风险 | 对策 |
| --- | --- |
| 目标应用禁止播放捕获 / DRM | 启动时检测无声帧并提示用户改用麦克风外放方案 |
| OpenRouter 限流 / 上游模型故障 | 指数退避 + 失败块落盘手动重试 + 备选模型一键切换 |
| 长会话内存/电量 | 分块即弃 PCM、Room 分页加载、WakeLock 仅限捕获期间 |
| 说话人跨块漂移 | 说话人描述随上下文传递；UI 支持手动合并说话人 |
| API 成本 | 静音丢弃、快速档位、实时显示本会话累计用量（STT 端点返回 usage.cost） |
