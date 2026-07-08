# Captions — 实时转写与翻译 Android 应用：技术方案

## 1. 目标与需求

| 需求 | 方案要点 |
| --- | --- |
| Android 12+（minSdk 31） | Kotlin + Jetpack Compose，targetSdk 最新版 |
| 转写 + 翻译 | 两阶段管线：音频 → 转写 → 翻译，均通过 OpenRouter |
| OpenRouter API Key 驱动 | 用户在设置中填入 Key，Keystore 加密存储；模型可自由选择 |
| 多语言 | 自动语种检测或手动指定源语言；目标语言任选；支持混合语言音频 |
| 多说话人 | 多模态 LLM 输出带说话人标签的结构化转写（S1/S2…），跨分块保持一致 |
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

### 4.1 两条转写路径（用户可在设置中切换，默认"高精度"）

| 路径 | 端点 | 模型示例 | 特点 |
| --- | --- | --- | --- |
| **高精度（默认）** | `POST /api/v1/chat/completions` + `input_audio` 内容块 | `google/gemini-2.5-pro`、`google/gemini-2.5-flash` | 支持系统提示词：注入前文上下文、领域词表、说话人分离指令；输出结构化 JSON |
| 快速/低成本 | `POST /api/v1/audio/transcriptions` | `openai/whisper-large-v3`、`openai/gpt-4o-transcribe`、Groq Whisper | 专用 STT，快且便宜，但无说话人标签、无上下文注入 |

- 音频以 base64 WAV 上传（PCM16 封 WAV 头即可，无需额外编码库）。
- 模型列表通过 `GET /api/v1/models` 拉取，按 `input_modalities` 含 `audio` / `output_modalities` 含 `transcription` 过滤，供设置页动态选择。
- 高精度路径的结构化输出 schema：`{"segments":[{"speaker":"S1","lang":"zh","text":"…"}]}`，`temperature=0`。

### 4.2 翻译
- `POST /api/v1/chat/completions`，默认强文本模型（如 `anthropic/claude-sonnet-4` / `google/gemini-2.5-pro`，可配置）。
- 每次请求携带：目标语言、最近 N 段原文+译文（滚动上下文，保证代词/术语一致）、会话术语表（用户可编辑）。
- 逐段流式翻译显示；会话结束后可选「精修 pass」：整段上下文重译，覆盖初译。

### 4.3 客户端工程
- OkHttp + Retrofit + kotlinx.serialization；指数退避重试（429/5xx/网络错误，最多 4 次）；请求队列串行保序，转写与翻译两条队列并行。
- API Key：Android Keystore AES 加密后存 DataStore；所有请求头 `Authorization: Bearer <key>`，附 `HTTP-Referer`/`X-Title` 标识应用。
- 失败片段落盘保留原始音频（临时目录），支持手动重试，避免丢内容。

## 5. 多说话人与多语言

- **说话人分离**：高精度路径由模型完成 diarization——系统提示词要求按声纹/语气区分说话人并稳定编号；每个新分块的提示词携带「已知说话人描述」（S1: 男声、低沉、说中文…由模型自己在上一块生成）以保持跨块一致。用户可在 UI 中给 S1/S2 重命名。
- **多语言**：默认自动检测（模型逐段标注 `lang`）；用户可固定源语言以提升准确率（作为提示词/`language` 参数传入）。混合语言音频逐段标注、逐段翻译。
- 快速路径（Whisper 系）不支持说话人标签，UI 中降级为无说话人视图。

## 6. 准确率策略（最高优先级）

1. **模型档位**：默认「最高准确率」= Gemini 2.5 Pro 转写 + 强模型翻译 + 会话后精修；另提供「均衡」「快速」档，用户明确选择才降级。
2. **上下文注入**：每块转写提示词携带上一块尾部转写文本 + 会话主题摘要，显著减少专名/术语错误。
3. **用户词表**：会话可配置领域词汇/人名（提示词注入 + 翻译术语表复用）。
4. **VAD 静音边界 + 重叠分块**：避免截词；重叠区文本对齐去重。
5. **确定性输出**：`temperature=0`，结构化 JSON schema 输出，失败时自动重试并放宽解析。
6. **二次精修**：会话结束后可一键用整段上下文重跑翻译（和可选的转写校对），修正早期缺乏上下文造成的误差。
7. **评估**：`tools/eval/` 放置 WER/BLEU 评估脚本 + 小型多语言多说话人测试音频集，回归验证管线改动。

## 7. UI / UX

- **会话列表页**：历史会话（标题、时长、语言对、来源图标），支持搜索、导出（TXT/SRT/JSON）、删除。
- **实时字幕页**：双栏或气泡流（原文 + 译文），说话人以颜色/头像区分，自动滚动+手动回看，顶部显示捕获状态与音量表，暂停/恢复/结束控制。
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
| M2 麦克风转写 | AudioRecord + VAD 分段 + STT 端点 + 实时字幕页 | 真机/模拟器实时看到转写文本 |
| M3 播放捕获 | MediaProjection + AudioPlaybackCapture + 前台服务完整生命周期 | 捕获其他应用（如 YouTube）音频并转写 |
| M4 高精度转写 | chat/completions 多模态路径、说话人分离、上下文注入、结构化解析 | 多说话人音频输出稳定的 S1/S2 标签 |
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
