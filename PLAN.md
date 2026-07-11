# Captions — 实时转写与翻译 Android 应用：技术方案（重构版）

## 0. 四条核心原则（一切决策的排序依据）

| 优先级 | 原则 | 含义 |
| --- | --- | --- |
| **1** | **转写与翻译准确率最高** | 模型选型、分块策略、上下文注入、重试与降级，均以准确率为准；成本、延迟、电量、动画、历史记录等一律让位 |
| **2** | **功能与显示极简** | 只做「听 → 转写 → 翻译 → 看」；字幕区纯文本、无气泡装饰；说话人**仅用颜色区分，不显示任何文字标签**；提供**暂停/恢复自动滚动**以便回看 |
| **3** | **Provider 边界清晰** | **转写**：Deepgram **或** ElevenLabs（用户二选一，各有默认 model name，可自定义）；**翻译**：仅 OpenRouter（默认 model name，可自定义）。转写**不再**走 OpenRouter 多模态回退 |
| **4** | **音频输入选择必须 robust** | 麦克风 vs 应用/系统播放捕获的选择、授权、前台服务与 MediaProjection 生命周期须可靠，失败可恢复、状态可感知（见 §5） |

> **不在本版范围**（除非直接提升准确率）：会话历史、导出、悬浮窗、词表 UI、AssemblyAI、成本仪表盘、Room 持久化、说话人合并手势、精修 pass UI 等——见 §11「明确不做」。

---

## 1. 目标与范围

| 项 | 方案 |
| --- | --- |
| 平台 | Android 12+（minSdk 31），Kotlin + Jetpack Compose + Material 3 |
| 核心能力 | 实时转写 + 逐句翻译，双语极简文本流 |
| API Key | Deepgram **或** ElevenLabs（转写）+ OpenRouter（翻译）；Keystore 加密 + DataStore |
| 说话人 | 声学 diarization；UI 仅颜色，无 "S1"/人名等标签 |
| 音频源 | 麦克风；其他应用播放音频（AudioPlaybackCapture + MediaProjection） |

---

## 2. 总体架构

```
┌─ CaptionForegroundService ─────────────────────────────────────────┐
│  AudioCapture（Mic 或 Playback → PCM16 mono 16 kHz）                │
│    ├─ Deepgram 路径：PCM 流式 WebSocket（nova-3, diarize）          │
│    └─ ElevenLabs 路径：VAD 分块 → WAV → POST speech-to-text       │
│         └─ SpeakerIdMapper（跨块说话人 ID 对齐）                    │
│              └─ OpenRouterTranslator（逐句翻译，滚动上下文）        │
│                   └─ StateFlow → Compose 极简字幕页                 │
└───────────────────────────────────────────────────────────────────┘
```

- **模式**：MVVM + 单向数据流；Hilt；Coroutines/Flow。
- **持久化**：仅 DataStore（Key、模型名、转写 Provider 选择）；**无** Room；会话结束即丢（准确率优先，不分散到存储层）。
- **Package 分层**（单 module）：
  - `audio/`：捕获、VAD、WAV 编码
  - `transcription/`：Deepgram、ElevenLabs 实现
  - `translation/`：OpenRouter 翻译
  - `pipeline/`：会话编排、说话人对齐、Provider 选择
  - `providers/`：Key 校验、OpenRouter 模型列表
  - `data/`：Keystore、DataStore
  - `ui/`：首页、实时字幕页、设置页
  - `service/`：前台服务、MediaProjection 生命周期

---

## 3. 转写（Deepgram 或 ElevenLabs）

用户须在设置中**显式选择**转写 Provider（二选一）；须填写对应 API Key 方可开始会话。无 Key 时引导去设置，**不**自动回退到其他 Provider。

### 3.1 Deepgram（流式，低延迟着色）

- WebSocket `wss://api.deepgram.com/v1/listen`
- **默认 model**：`nova-3`
- 参数：`language=multi`，`diarize=true`，`interim_results=true`，`punctuate=true`
- 用户可在设置输入**自定义 model name**（覆盖默认）
- 优势：边说边出 partial/final，说话人颜色实时更新
- 适用：有 Deepgram 额度、或需要最低转写延迟的场景

### 3.2 ElevenLabs（批量，准确率优先）

- `POST /v1/speech-to-text`
- **默认 model**：`scribe_v2`，`diarize=true`
- 用户可在设置输入**自定义 model id**
- 音频经 VAD 分块（15–30 s，1–2 s 重叠）→ WAV → 批量请求
- 跨块说话人 ID 通过重叠区词对齐（`SpeakerIdMapper`）保持稳定
- 适用：追求转写 WER 最优、可接受数秒块延迟的场景

### 3.3 共同要求

- 输出结构：`CaptionLine`（speaker ID、text、isFinal、可选 translation）
- `temperature=0`（ElevenLabs 侧按 API 默认）；解析失败重试（最多 4 次，指数退避）
- **淘汰**：OpenRouter 多模态转写、Whisper 系无 diarization 端点——不符合「必须声学 diarization」的硬性要求

### 3.4 默认 model 一览

| 用途 | Provider | 默认 model name | 自定义 |
| --- | --- | --- | --- |
| 转写 | Deepgram | `nova-3` | 设置页文本框 |
| 转写 | ElevenLabs | `scribe_v2` | 设置页文本框 |
| 翻译 | OpenRouter | `google/gemini-3.1-pro-preview` | 设置页推荐列表 + 自定义 |

---

## 4. 翻译（仅 OpenRouter）

- `POST /api/v1/chat/completions`，纯文本 LLM
- **默认 model**：`google/gemini-3.1-pro-preview`（准确率优先；用户可选用推荐列表或自填 model id）
- 每句 final 转写触发异步翻译；携带最近 N 对原文+译文作滚动上下文（默认 N=6–8），保证代词/术语一致
- `temperature=0`；OpenRouter Key **必填**方可显示译文（仅有转写 Key 时可只看原文）
- 模型列表：`GET /api/v1/models` 拉取并合并推荐项；启动时可校验默认 model 仍在售

**准确率相关（优先实现）**

1. 滚动上下文（已实现基础版，可加大 N 若 token 预算允许）
2. 系统提示词固定目标语言（当前默认简体中文；后续可加设置项，但不优先于准确率调优）
3. 失败重试与解析容错

**暂不实现**：术语表 UI、会话后整段精修 pass、流式译文 token 显示——除非实测能显著提升 BLEU/COMET。

---

## 5. 音频输入（必须 robust）

音频源选择是会话成败的前置条件，实现与 UX 要求如下。

### 5.1 选择与状态机

```
空闲 → 用户选 Mic / 应用音频 → 点「开始」
  ├─ Mic：检查 RECORD_AUDIO → 授权/拒绝分支 → 启动 FGS → 捕获
  └─ 应用音频：启动 FGS → MediaProjection 授权 → getMediaProjection → PlaybackCapture
监听中：禁止切换音频源；仅可「停止」
错误：展示可读原因 + 「重试」；回到空闲，保留用户上次音频源选择
```

- UI：`FilterChip` 二选一（麦克风 / 应用音频），**仅在未监听时可切换**
- 选择持久化到 DataStore，下次打开恢复

### 5.2 麦克风路径

- `AudioRecord`，`VOICE_RECOGNITION`（回退 `MIC`），16 kHz / mono / PCM16
- 权限：未授权时 `RequestPermission`；拒绝后 inline 说明 + 跳转系统设置入口
- 前台服务类型：`FOREGROUND_SERVICE_MICROPHONE`（API 34+）

### 5.3 应用/系统播放捕获路径

- **顺序（Android 14+ 强制）**：先 `startForeground` → 再 `MediaProjectionManager.createScreenCaptureIntent()` → 用户确认 → `getMediaProjection`
- `AudioPlaybackCaptureConfiguration`：`USAGE_MEDIA`、`USAGE_GAME`、`USAGE_UNKNOWN`
- 授权取消：不清除 Chip 选择，提示「需要屏幕捕获权限才能听应用音频」
- OEM 兼容：授权回调内 `bringCaptionsToFront()`，避免部分机型授权后不回应用
- 前台服务类型：`FOREGROUND_SERVICE_MEDIA_PROJECTION`（可与 microphone 组合声明）
- **无声检测**：启动后 3–5 s 内若 RMS 持续低于阈值，提示可能原因（目标应用禁止捕获、未播放、音量静音）并建议改用麦克风

### 5.4 生命周期与恢复

- `MediaProjection` 仅在一次会话内持有；停止服务即 `release()`
- 服务 `START_STICKY`；进程被杀后用户需重新点「开始」（不静默恢复投影）
- 捕获线程异常 → `CaptureStatus.Error` + 通知栏可点回应用
- 采样率：设备输出重采样到 16 kHz 再送 STT

### 5.5 用户须知（简短 inline 文案）

- 部分应用（DRM / `ALLOW_CAPTURE_BY_NONE`）无法捕获 → 建议外放 + 麦克风
- 通话音频（`USAGE_VOICE_COMMUNICATION`）不可捕获
- 捕获的是系统混音，无法指定单个 App（除非该 App 独占播放）

---

## 6. 准确率策略（最高优先级，§0 原则 1 展开）

一切工程取舍以转写 WER / 翻译质量为验收标准。

1. **模型**：默认即最高档——Deepgram `nova-3` 或 ElevenLabs `scribe_v2` + OpenRouter `gemini-3.1-pro-preview`；不提供「快速/省钱」档位开关，避免误触降级
2. **分块**：ElevenLabs 路径用 VAD 在静音边界切分，块长 15–30 s，重叠 1–2 s，避免截断单词；纯静音丢弃
3. **说话人稳定**：块间重叠区词级对齐；准确率不足时再考虑 ECAPA 嵌入聚类（远期）
4. **上下文**：翻译滚动上下文；ElevenLabs 转写可在提示/后处理链路透传前文（若 API 支持）
5. **确定性**：`temperature=0`，结构化解析，失败重试
6. **评估**：`tools/eval/` 保留小型多语多说话人样本 + WER/BLEU 脚本，管线变更必跑回归

**为准确率可接受的代价**：更高 API 费用、更大块延迟（ElevenLabs）、更重上下文 token、不做本地历史缓存。

---

## 7. 说话人与颜色

- 声学 diarization 为硬性要求；无 diarization 的模型不接入
- 每个 speaker ID → 调色板固定色（深浅主题各一套，8–10 色，色盲友好）
- UI：**仅**左侧色条 + 文字色/浅底色区分说话人；**禁止**显示说话人编号、姓名、图标
- 译文用次要文字色，不单独着色（避免喧宾夺主）

---

## 8. UI / UX（极简）

### 8.1 实时字幕页（核心屏）

| 元素 | 要求 |
| --- | --- |
| 字幕行 | 原文 + 译文（若有）；无气泡圆角装饰可减至色条+纯文本 |
| 说话人 | 仅颜色（§7） |
| 滚动 | 新句默认滚到底；**暂停滚动 / 恢复滚动** _toggle（暂停时用户可手动翻看，新内容仍追加但不自动跳） |
| 控制 | 开始 / 停止；音频源 Chip；极简状态一行（连接中/监听中/错误） |
| 音量 | 细线性电平条（辅助判断「是否有声」） |
| 不做 | 会话列表、导出、悬浮窗、词表、说话人合并、主题切换 beyond 系统深浅色 |

### 8.2 设置页

- API Key：Deepgram、ElevenLabs、OpenRouter（校验按钮）
- 转写 Provider：单选 Deepgram / ElevenLabs
- 转写 model：显示当前 Provider 的默认名 + 自定义输入框
- 翻译 model：推荐列表 + 自定义输入框
- 不做：准确率档位、源语言选择（先用自动检测 + 固定中文译文）、词表管理

### 8.3 首页

- 进入实时字幕；若 Key 不齐，简短说明缺什么
- 无 onboarding 多步向导（robust 音频选择在字幕页完成即可）

---

## 9. 权限与清单

`INTERNET`、`RECORD_AUDIO`、`POST_NOTIFICATIONS`、`FOREGROUND_SERVICE`、`FOREGROUND_SERVICE_MICROPHONE`、`FOREGROUND_SERVICE_MEDIA_PROJECTION`。

前台服务：`foregroundServiceType="mediaProjection|microphone"`。

**不申请** `SYSTEM_ALERT_WINDOW`（无悬浮窗）。

---

## 10. 技术栈（与仓库一致）

Kotlin 2.x · Jetpack Compose + Material 3 · Hilt · DataStore · OkHttp + kotlinx.serialization · minSdk 31 · JUnit5 + MockWebServer + Compose UI 测试。

**当前未用、本版不引入**：Room、Retrofit、onnxruntime（VAD 暂用能量阈值 `EnergyVadSegmenter`）。

---

## 11. 明确不做（本版）

- OpenRouter 转写 / Whisper 回退
- 会话持久化、历史、导出（TXT/SRT/JSON）
- 悬浮字幕 overlay
- AssemblyAI、Speechmatics 等第三 STT
- 词表 UI、精修 pass UI、成本统计
- 说话人长按合并/换色（非准确率刚需）
- 多目标语言选择器（默认简体中文译文直至有准确率回归数据支撑扩展）

---

## 12. 里程碑

| 里程碑 | 内容 | 验收 | 状态 |
| --- | --- | --- | --- |
| **M1** 骨架 | 工程、Hilt、导航、Key 加密与校验 | 三 Key 可校验 | ✅ |
| **M2** 转写核心 | Mic 捕获、Deepgram 流式 + ElevenLabs 批量、说话人颜色、Provider 选择 | 真机着色转写 | ✅ 部分（缺显式 Provider 选择、自定义 model） |
| **M3** 播放捕获 | MediaProjection + PlaybackCapture + FGS 生命周期 | YouTube 等可转写 | ✅ |
| **M4** 准确率加固 | 跨块说话人对齐、VAD 调参、翻译上下文、重试 | 多说话人稳定色差；块边界少截词 | 🔶 进行中 |
| **M5** 极简 UX | 纯文本字幕、暂停/恢复滚动、设置页 model 自定义、去掉 OpenRouter STT | 符合 §0 四条原则 | 🔲 |
| **M6** 音频 robust | §5 状态机、无声检测、错误恢复、选择持久化 | 弱网/OEM/拒绝授权可预期恢复 | 🔲 |
| **M7** 评估 | `tools/eval/` WER/BLEU 基线 | 改版可量化对比 | 🔲 |

---

## 13. 风险与对策

| 风险 | 对策 |
| --- | --- |
| 目标应用禁止播放捕获 | 无声检测 + inline 说明；引导麦克风外放 |
| MediaProjection 授权后不回应用 | 授权回调 `bringCaptionsToFront()`；通知栏点回 |
| Android 14+ FGS 时序 | 先 `startForeground` 再取 projection（已实现） |
| ElevenLabs 块延迟 | 接受（准确率优先）；Deepgram 作低延迟备选 |
| 跨块说话人漂移 | 重叠词对齐；远期嵌入聚类 |
| API 限流/5xx | 指数退避重试；状态栏错误可重试 |
| 用户误选无 Key 的 Provider | 开始前校验；设置页引导 |

---

## 附录 A：Provider 选型参考（非决策依据）

成本与竞品对比仅作备案；**产品决策以 §0 原则 1（准确率）为准**，不以表格最低价驱动。

- ElevenLabs Scribe v2：批量 WER 第一梯队，diarization 含价，99 语
- Deepgram Nova-3：流式 diarization，多语言含中文，适合有额度时
- 翻译：OpenRouter `gemini-3.1-pro-preview` 在 OpenMark/WMT 类基准表现领先
