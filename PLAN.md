# Captions — 实时转写与翻译 Android 应用：技术方案

## 1. 目标与需求

| 需求 | 方案要点 |
| --- | --- |
| Android 12+（minSdk 31） | Kotlin + Jetpack Compose，targetSdk 最新版 |
| 转写 + 翻译 | 两阶段管线：音频 → 转写 → 翻译；转写支持多 Provider（Deepgram / ElevenLabs / OpenRouter），翻译走 OpenRouter |
| 多 API Key 驱动 | OpenRouter Key（必填）+ Deepgram Key（有免费额度时优先）+ ElevenLabs Key（产品出厂推荐）；均 Keystore 加密存储 |
| 多语言 | 自动语种检测或手动指定源语言；目标语言任选；支持混合语言音频 |
| 多说话人（硬性要求） | 说话人分离为必选能力；UI **仅用颜色区分说话人，不显示任何文字标签**；内部用稳定 ID 跟踪并映射到固定颜色 |
| 准确率最高优先 | 见 §6「准确率策略」——模型选择、上下文注入、重叠分块、精修 pass 均围绕此设计 |
| 音频源：其他应用 / 麦克风 | AudioPlaybackCapture（MediaProjection）+ AudioRecord 麦克风 |

## 2. 总体架构

```
┌─ ForegroundService（mediaProjection|microphone 类型）─────────────┐
│  AudioCaptureEngine（麦克风 或 播放捕获，PCM16 mono 16kHz）        │
│    └→ 环形缓冲 → VAD 分段器（静音边界，15–30s，1–2s 重叠）         │
│         └→ TranscriptionQueue（WAV/流式 → TranscriptionProvider：  │
│              Deepgram Nova-3 / ElevenLabs Scribe / OpenRouter 回退）│
│              └→ TranslationQueue（OpenRouter：纠错 + 翻译）        │
│                   └→ Room 持久化 → StateFlow → Compose UI         │
└───────────────────────────────────────────────────────────────────┘
```

- **架构模式**：MVVM + 单向数据流；Hilt 依赖注入；Coroutines/Flow。
- **模块划分**（单 module 起步，按 package 分层）：
  - `audio/`：捕获引擎、VAD、WAV 编码、说话人嵌入
  - `pipeline/`：分段器、转写队列、翻译队列、跨块说话人对齐、重试策略
  - `providers/`：`TranscriptionProvider` 接口 + ElevenLabs/OpenRouter 实现、翻译客户端、模型列表拉取
  - `data/`：Room（会话、片段、说话人）、DataStore（设置）、Keystore（双 API Key）
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

## 4. 服务集成（ElevenLabs 转写 + OpenRouter 翻译/回退）

应用采用**多 Key 架构**（OpenRouter 必填；专业 STT Key 按优先级选用）：
- **OpenRouter Key（必填）**：驱动全部翻译、词表纠错 pass，以及无专业 STT Key 时的转写回退 Provider。
- **Deepgram Key（本开发者优先）**：已有 $200 免费额度时作为个人默认转写 Provider——Nova-3 支持流式 diarization + 多语言（含中文），额度内边际成本为 0。
- **ElevenLabs Key（产品出厂推荐）**：无 Deepgram 额度 / 追求最高准确率时用 Scribe v2（批量 diarization 含价，AA-WER 第一梯队）。

### 4.1 转写 Provider 抽象

`TranscriptionProvider` 接口：输入 VAD 分块音频 + 上下文（词表、前文、已知说话人信息），输出 `{"segments":[{"speaker":1,"lang":"zh","text":"…","words":[…]}]}`。`speaker` 为整数 ID，仅用于内部跟踪与颜色映射，永不显示给用户。

**Provider A：ElevenLabs Scribe v2（默认，需 ElevenLabs Key）**
- `POST /v1/speech-to-text`，`model_id=scribe_v2`，`diarize=true`，multipart 上传 WAV 分块；返回词级时间戳 + 每词说话人标签 + 语种。
- 词表通过 `keyterms` 参数注入（等价于 AssemblyAI 的 keyterm prompting）。
- **跨块说话人 ID 对齐**（Scribe 的 speaker ID 仅在单次请求内稳定）：
  1. 相邻块 1–2 s 重叠区做词级对齐，重叠词的说话人建立块间 ID 映射；
  2. 辅以设备端说话人嵌入（ECAPA-TDNN ONNX，约 20 MB）：对每块每说话人取平均嵌入，跨块余弦聚类，得到全会话稳定 ID；
  3. 兜底：翻译模型基于对话语义校验可疑的说话人切换。
- **即时预览（可选，M7）**：Scribe v2 Realtime WebSocket（`scribe_v2_realtime`，~150 ms 延迟）输出未定色的灰色预览字幕；Realtime 端点不支持 diarization，待批量分块结果返回后替换为着色定稿。

**Provider B：OpenRouter 多模态 LLM（回退，仅 OpenRouter Key）**
- `POST /api/v1/chat/completions` + `input_audio` 内容块（base64 WAV），系统提示词注入前文上下文、词表、说话人分离指令，输出同一结构化 JSON，`temperature=0`。
- 跨块说话人一致性：提示词携带「已知说话人声音描述」（由模型在上一块生成）。
- 模型列表通过 `GET /api/v1/models` 按 `input_modalities` 含 `audio` 过滤，动态可选。
- OpenRouter 专用 STT 端点（`/api/v1/audio/transcriptions`，Whisper 系）不做 diarization，无法满足硬性要求，仅作默认隐藏的高级降级选项。

### 4.2 默认模型推荐（2026-07 实测在售）

**转写**

| 档位 | Provider / 模型 | 价格 | 推荐理由 |
| --- | --- | --- | --- |
| **个人开发默认（有 $200 额度时）** | Deepgram `nova-3` multilingual（`diarize=true`） | 额度内 $0；耗尽后 ~$0.55–$0.75/h | 流式+批量均支持 diarization；含中文；额度约 267–366 h |
| **产品出厂默认 / 最高准确率** | ElevenLabs `scribe_v2`（diarize=true） | ~$0.22/h（+keyterms $0.05） | AA-WER 第一梯队；diarization 含价；99 语言 |
| 低延迟着色可选 | AssemblyAI `u3-rt-pro` + stream diar | $0.57/h | 真流式说话人标签，边说边着色 |
| 回退默认（无专业 STT Key） | OpenRouter `google/gemini-3.1-pro-preview` | $2/$12 per M tokens | 多模态 diarization 最强谱系；单 Key 可用 |
| 回退·均衡 | OpenRouter `google/gemini-3.5-flash` | $1.5/$9 | 延迟低、便宜 |
| 回退·低成本 | OpenRouter `google/gemini-3-flash-preview` | $0.5/$3 | 实时性最好 |

**翻译（纯文本 LLM，均走 OpenRouter）**

| 档位 | 模型 | 价格（输入/输出 per M tokens） | 推荐理由 |
| --- | --- | --- | --- |
| **最高准确率（默认）** | `google/gemini-3.1-pro-preview` | $2 / $12 | OpenMark 2026-03 翻译基准第一，前代蝉联 WMT25 人评 16 语对中 14 项冠军；价格仅为 Opus/GPT-5.5 的 40% |
| 语气/文学敏感备选 | `anthropic/claude-opus-4.7` | $5 / $25 | 专业译员盲评认可度最高的谱系，西/阿等语对领先，适合演讲、影视等语气敏感内容 |
| 均衡 | `anthropic/claude-sonnet-4.6` | $3 / $15 | COMET 高分、流畅度佳、成本适中 |
| 低成本 | `google/gemini-3-flash-preview` | $0.5 / $3 | 高吞吐实时翻译，质量/成本比最好 |

- 默认组合即**混合管线**：Scribe v2 出高准确率转写 + 声学 diarization，Gemini 3.1 Pro 做词表纠错 + 翻译，兼得两者优势。
- 回退模式下转写与翻译同用 `google/gemini-3.1-pro-preview`，共享语言先验，术语一致性更好。
- 以上均为动态推荐列表的「出厂默认」；应用每次启动校验模型仍在售（OpenRouter 拉 `/models`，ElevenLabs 调用探活），下架时自动回退到同档位次选并提示用户。

### 4.3 翻译请求设计
- `POST /api/v1/chat/completions`，模型按 §4.2 档位可配置。
- 每次请求携带：目标语言、最近 N 段原文+译文（滚动上下文，保证代词/术语一致）、会话术语表（用户可编辑）、说话人 ID（保证同一说话人称谓/语气一致）。
- 逐段流式翻译显示；会话结束后可选「精修 pass」：整段上下文重译，覆盖初译。

### 4.4 客户端工程
- OkHttp + Retrofit + kotlinx.serialization；指数退避重试（429/5xx/网络错误，最多 4 次）；请求队列串行保序，转写与翻译两条队列并行。
- API Key：Android Keystore AES 加密后存 DataStore；所有请求头 `Authorization: Bearer <key>`，附 `HTTP-Referer`/`X-Title` 标识应用。
- 失败片段落盘保留原始音频（临时目录），支持手动重试，避免丢内容。

### 4.5 转写模型对比与成本重评（本 use case，2026-07）

**硬性筛选**：必须声学级 diarization（说话人仅用颜色区分）、多语言、准确率优先。OpenRouter 专用 STT 端点（Whisper / GPT-4o Transcribe / Chirp-3 / Qwen3-ASR 等）**全部因无 diarization 直接淘汰**，不参与默认选型。

**计费口径**：官方价（ElevenLabs / AssemblyAI / Deepgram 官网；OpenRouter `/models` API 实测）。本应用默认走 VAD 分块（15–30s，1–2s 重叠）→ 批量路径按 **×1.075** 计入重叠开销；流式路径按会话时长计（无重叠）。词表按「开启 keyterms」计（本应用有词表功能）。

#### 合格模型：有效成本（$/音频小时）

| 模型 | 基价 | diarization | keyterms | 合计 | 有效（含重叠） | 模式 | 对本场景 |
| --- | --- | --- | --- | --- | --- | --- | --- |
| **ElevenLabs Scribe v2** | $0.22 | **含** | +$0.05 | $0.27 | **$0.29** | 分块批量 | **默认首选**：准确率第一梯队 + diar 含价 + 99 语 |
| ElevenLabs Scribe v2（关词表） | $0.22 | 含 | — | $0.22 | $0.24 | 分块批量 | 无词表时更便宜 |
| AssemblyAI U3.5 Pro async | $0.21 | +$0.02 | +$0.05 | $0.28 | **$0.30** | 分块批量 | 与 Scribe 几乎同价；词表上限更高（1000） |
| AssemblyAI U3.5 Pro async + 实验 diar | $0.21 | +$0.065 | +$0.05 | $0.325 | $0.35 | 分块批量 | 多人/难音频时用 |
| Speechmatics Pro（公开价约） | ~$0.24 | **含** | — | ~$0.24 | ~$0.24 | 批量/流式 | **ZH-EN code-switch 最强**；价目不透明 |
| OpenRouter Gemini 3.5 Flash（LLM 音频） | ~$0.35 | 语义级 | 提示词 | ~$0.35 | ~$0.38 | 分块批量 | 单 Key 回退·均衡 |
| OpenRouter Gemini 3.1 Pro（LLM 音频） | ~$0.47 | 语义级 | 提示词 | ~$0.47 | ~$0.51 | 分块批量 | 单 Key 回退·最高准确率 |
| **AssemblyAI U3.5 Pro Realtime + 流式 diar** | $0.45 | +$0.12 | 含 | **$0.57** | $0.57 | **真流式** | **唯一「边说边着色」路径**；约 2× 批量价 |
| Deepgram Nova-3 multi + diar + kt（预录） | $0.35 | +$0.12 | +$0.08 | $0.55 | $0.59 | 分块批量 | 贵近 2×；多语言弱于前三 |
| Deepgram Nova-3 multi + diar + kt（流式） | $0.55 | +$0.12 | +$0.08 | $0.75 | $0.75 | 流式 | 延迟最低，但贵且偏英语 |
| ElevenLabs Scribe v2 Realtime | $0.39 | **无** | — | — | — | 流式 | **单独使用不合格**；仅作灰色预览字幕 |

#### 月成本粗算（仅转写，含词表）

| 用量 | Scribe v2 | AssemblyAI async | AssemblyAI 流式+diar | Gemini 3.1 Pro 回退 | Deepgram multi 流式 |
| --- | --- | --- | --- | --- | --- |
| 20 h/月（轻度） | **~$6** | ~$6 | ~$11 | ~$10 | ~$15 |
| 60 h/月（中度） | **~$17** | ~$18 | ~$34 | ~$30 | ~$45 |

翻译另计 OpenRouter 文本模型（默认 Gemini 3.1 Pro，按 token；典型字幕会话远低于转写成本）。

#### 能力对照（本场景权重）

| 维度（权重） | Scribe v2 | AssemblyAI U3.5 | Speechmatics | Deepgram Nova-3 | Gemini LLM |
| --- | --- | --- | --- | --- | --- |
| 转写准确率（高） | ★★★★★ | ★★★★☆ | ★★★★☆ | ★★★★☆（英强） | ★★★★☆ |
| 声学 diarization（硬性） | ★★★★★（含价，≤48） | ★★★★★（流式也有） | ★★★★★（含价） | ★★★★☆（+$0.12/h） | ★★☆☆☆（语义） |
| 多语言 / code-switch（高） | ★★★★★（99） | ★★★★★（99+） | ★★★★★（ZH-EN 包） | ★★★☆☆（~45） | ★★★★☆ |
| 真流式着色字幕（中） | ✗（Realtime 无 diar） | ★★★★★ | ★★★★☆ | ★★★★☆ | ✗ |
| 词表注入（中） | ★★★★（+$0.05） | ★★★★★（流式含） | ★★★☆☆ | ★★★★（+$0.08） | ★★★★★（提示词） |
| 有效成本（中） | ★★★★★ | ★★★★★ | ★★★★★ | ★★☆☆☆ | ★★★☆☆ |
| 单 Key 体验（低） | 需第二 Key | 需第二 Key | 需第二 Key | 需第二 Key | ★★★★★ |

#### 重评结论

**产品默认（无免费额度的一般用户）**
1. **默认仍为 ElevenLabs Scribe v2 分块批量**（~$0.29/h 含词表）：在「必须 diarization + 多语言 + 准确率」约束下，付费性价比最优；Realtime **不能**单独做默认（无 diar）。
2. **AssemblyAI** 为付费并列候选：async ≈ Scribe；流式+diar（$0.57/h）适合「边说边着色」。
3. **Deepgram** 付费价偏高（diar+词表后 $0.55–$0.75/h），一般不作为出厂默认。
4. **OpenRouter LLM 音频** 继续作无专业 STT Key 时的回退；Whisper 等无 diar 端点维持隐藏降级。

**本仓库开发者特例：已有 $200 Deepgram 免费额度 → 个人默认改用 Deepgram**
- 额度可支撑（含 diar + keyterms）：批量多语言约 **366 h**（~18 个月 @20h/月，或 ~6 个月 @60h/月）；流式多语言约 **267 h**。
- Deepgram 对本场景**能力合格**：Nova-3 支持流式 diarization（`diarize=true`；勿与 `diarize_model` 同用）、多语言含中文（`zh`）与 `multi` code-switch、keyterm prompting。
- 相对 Scribe：付费更贵、多语言/diar 通常略弱；但在额度耗尽前**边际成本为 $0**，对个人开发/自用是理性选择。
- 额度耗尽后自动回退到 Scribe（若已填 ElevenLabs Key）或 OpenRouter LLM。

**Provider 落地优先级（据此调整）**
1. **M2**：`TranscriptionProvider` + **Deepgram Nova-3**（开发者有额度时的默认；设置页可选）——优先流式+diar，实现真正的边说边着色
2. **M2/M4**：ElevenLabs Scribe v2（产品出厂默认 / 额度耗尽后首选）
3. **M4**：OpenRouter 多模态回退
4. **M7**：AssemblyAI 流式 diar 可选；Scribe Realtime 灰色预览

## 5. 多说话人与多语言

- **说话人分离（必选）**：默认由 ElevenLabs Scribe v2 做声学级 diarization（词级说话人标签），跨块 ID 通过重叠区词对齐 + 设备端说话人嵌入聚类保持稳定（见 §4.1）；回退路径由多模态模型按声纹/语气分离，提示词携带「已知说话人声音描述」（由模型在上一块生成）保持跨块一致。
- **说话人呈现：仅用颜色，无文字标签**：
  - 每个说话人 ID 映射到调色板中的一种固定颜色（气泡底色/左侧色条+文字色），整个会话及导出物中保持稳定；
  - UI 上不显示 "S1"、"说话人 1"、人名等任何文字标签；
  - 调色板取自 Material 3 色板中对比度充足、色盲友好的 8–10 色序列（如蓝/橙/绿/紫/青/粉…），深浅色主题各一套；超过色板容量时按色相间隔生成新色；
  - 用户可长按某说话人的气泡换色，或将两个误分的说话人「合并」（合并 = 颜色统一，仍无标签）；
  - 导出：SRT/TXT 导出用颜色名或 `<font color>` 标记（SRT 支持）；JSON 导出保留内部 ID 与颜色值。
- **多语言**：默认自动检测（模型逐段标注 `lang`）；用户可固定源语言以提升准确率（作为提示词参数传入）。混合语言音频逐段标注、逐段翻译。
- 降级 STT 路径（Whisper 系）不支持说话人分离，启用前 UI 明确警告所有文本将变为单色。

## 6. 准确率策略（最高优先级）

1. **模型档位**：默认「最高准确率」= ElevenLabs Scribe v2 转写（声学 diarization）+ `google/gemini-3.1-pro-preview` 词表纠错与翻译 + 会话后精修（见 §4.2）；无 ElevenLabs Key 时回退 Gemini 3.1 Pro 全链路；另提供「均衡」「快速」档，用户明确选择才降级。
2. **上下文注入**：回退路径每块转写提示词携带上一块尾部转写文本 + 会话主题摘要；Scribe 路径由翻译/纠错 pass 的 LLM 持有全部上下文，显著减少专名/术语错误。
3. **用户词表**：会话可配置领域词汇/人名（Scribe `keyterms` 参数 / 回退路径提示词注入 + 翻译术语表复用）。
4. **VAD 静音边界 + 重叠分块**：避免截词；重叠区文本对齐去重。
5. **确定性输出**：`temperature=0`，结构化 JSON schema 输出，失败时自动重试并放宽解析。
6. **二次精修**：会话结束后可一键用整段上下文重跑翻译（和可选的转写校对），修正早期缺乏上下文造成的误差。
7. **评估**：`tools/eval/` 放置 WER/BLEU 评估脚本 + 小型多语言多说话人测试音频集，回归验证管线改动。

## 7. UI / UX

- **会话列表页**：历史会话（标题、时长、语言对、来源图标），支持搜索、导出（TXT/SRT/JSON）、删除。
- **实时字幕页**：气泡流（原文 + 译文成对显示），说话人**仅以颜色区分**（气泡底色/色条，无任何文字标签，见 §5），自动滚动+手动回看，顶部显示捕获状态与音量表，暂停/恢复/结束控制。
- **悬浮字幕窗**（可选，`SYSTEM_ALERT_WINDOW`）：看视频时叠加双语字幕条，可拖动、调透明度。
- **设置页**：双 API Key（OpenRouter 必填，校验调 `/models`；ElevenLabs 推荐填写，校验调探活接口，未填时显示"转写将回退到 OpenRouter 多模态"提示）、转写 Provider 与模型选择（动态列表）、翻译模型选择、准确率档位、源/目标语言、词表管理。
- **新手引导**：首次使用引导填两个 Key（说明各自作用与可只填 OpenRouter 的取舍）、授权麦克风/投影，说明播放捕获的系统限制。
- Material 3 动态取色，深色模式。

## 8. 数据与隐私

- Room：`Session`、`Segment`（原文/译文/说话人/语言/时间戳/状态）、`SpeakerProfile`、`GlossaryTerm`。
- 原始音频默认不长期保留（仅失败重试的临时块），可在设置中开启「保留录音」。
- 隐私声明：音频会发送至 ElevenLabs 和/或 OpenRouter 及其上游模型提供商；应用内明确提示。

## 9. 权限与清单

`INTERNET`、`RECORD_AUDIO`、`POST_NOTIFICATIONS`、`FOREGROUND_SERVICE`、`FOREGROUND_SERVICE_MICROPHONE`、`FOREGROUND_SERVICE_MEDIA_PROJECTION`、可选 `SYSTEM_ALERT_WINDOW`。前台服务 `foregroundServiceType="mediaProjection|microphone"`。

## 10. 技术栈

Kotlin 2.x · Jetpack Compose + Material 3 · Hilt · Room · DataStore · OkHttp/Retrofit + kotlinx.serialization · onnxruntime-android（Silero VAD + ECAPA-TDNN 说话人嵌入）· minSdk 31 / targetSdk 35 · Gradle Version Catalog · JUnit5 + Turbine + MockWebServer 单测，Compose UI 测试。

## 11. 里程碑

| 里程碑 | 内容 | 验收 |
| --- | --- | --- |
| M1 项目骨架 | Gradle 工程、Hilt、导航、设置页、多 API Key（OpenRouter + ElevenLabs + Deepgram）加密存储与校验 | Key 分别校验通过并返回可用状态 |
| M2 麦克风转写 | AudioRecord + VAD/流式 + `TranscriptionProvider` + **Deepgram Nova-3（优先，含流式 diar）** + ElevenLabs Scribe v2 实现 + 实时字幕页（颜色区分说话人） | 真机/模拟器实时看到按说话人着色的转写文本；Deepgram 额度可跑通端到端 |
| M3 播放捕获 | MediaProjection + AudioPlaybackCapture + 前台服务完整生命周期 | 捕获其他应用（如 YouTube）音频并转写 |
| M4 说话人与回退路径 | 跨块说话人 ID 对齐（重叠词对齐 + 嵌入聚类）、颜色映射、OpenRouter 多模态回退 Provider（含说话人描述传递） | 多说话人音频中不同说话人以稳定且不同的颜色呈现（无文字标签）；拔掉 ElevenLabs Key 后回退路径可用 |
| M5 翻译管线 | 逐段翻译、滚动上下文、术语表、词表纠错 pass、精修 pass | 双语实时显示，译文术语一致 |
| M6 存储与导出 | Room 会话持久化、历史页、TXT/SRT/JSON 导出 | 重启后会话可回看、可导出 |
| M7 打磨 | 悬浮字幕窗、AssemblyAI 流式 diar 可选 Provider（边说边着色）、Scribe Realtime 灰色预览、准确率档位、词表 UI、错误恢复、评估脚本 | WER/BLEU 回归基线建立；流式着色档位可用 |

每个里程碑：单元测试（分段器、重叠合并、队列重试、API 解析用 MockWebServer）+ 模拟器手动验证（麦克风用虚拟音频输入，播放捕获用模拟器内播放测试音频）。

## 12. 风险与对策

| 风险 | 对策 |
| --- | --- |
| 目标应用禁止播放捕获 / DRM | 启动时检测无声帧并提示用户改用麦克风外放方案 |
| ElevenLabs / OpenRouter 限流或故障 | 指数退避 + 失败块落盘手动重试 + Provider/模型一键切换（Scribe 故障可临时切 OpenRouter 回退路径） |
| Scribe 跨块说话人 ID 不连续 | 重叠区词对齐 + 设备端嵌入聚类（§4.1）；UI 支持手动合并说话人 |
| 长会话内存/电量 | 分块即弃 PCM、Room 分页加载、WakeLock 仅限捕获期间 |
| 回退路径说话人跨块漂移 | 说话人描述随上下文传递；UI 支持手动合并说话人 |
| API 成本 | 静音丢弃、快速档位、实时显示本会话累计用量 |
