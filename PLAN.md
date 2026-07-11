# Captions — 实时转写与翻译：重构方案

## 0. 设计原则（不可妥协）

| # | 原则 | 含义 |
| --- | --- | --- |
| **P1** | **准确率绝对优先** | 转写与翻译的正确性高于延迟、成本、功能数量、UI 华丽度。任何冲突时，其他全部让位。 |
| **P2** | **功能极简** | 只做：捕获音频 → 转写 → 翻译 → 显示。不做历史库、导出、悬浮窗、词表 UI、准确率档位等。 |
| **P3** | **显示极简** | 屏幕上几乎只有彩色文本（说话人用颜色区分，无 "S1" 等标签）+ 滚动暂停/继续。 |
| **P4** | **模型可配** | 转写：Deepgram **或** ElevenLabs（二选一）；翻译：仅 OpenRouter。均提供默认 model name，用户可自由输入其他 model name。 |
| **P5** | **音频源选择必须 robust** | 麦克风 / 系统播放捕获（其他 App）的授权、前台服务、失败回退、无声检测，必须可靠可恢复。 |

---

## 1. 目标与非目标

### 1.1 目标

- Android 12+（minSdk 31），Kotlin + Jetpack Compose。
- 实时：音频 → 转写（含说话人分离）→ 翻译 → 彩色字幕流。
- 音频源：麦克风 **或** 其他应用/系统播放音频（AudioPlaybackCapture）。
- 说话人：仅用文字颜色区分，无文字标签。
- 滚动：默认跟随最新行；用户可暂停/继续自动滚动。

### 1.2 非目标（明确不做 / 延后）

- OpenRouter 作为转写 Provider（不再作为回退路径；转写只走 Deepgram / ElevenLabs）。
- AssemblyAI、Speechmatics 等第三方 STT。
- 会话历史、Room 持久化、TXT/SRT/JSON 导出。
- 悬浮字幕窗、词表/术语表 UI、精修 pass、准确率档位切换。
- 说话人长按换色 / 合并（若误分，以 Provider 准确率为准，不靠 UI 补救）。

> 以上若与准确率冲突，一律砍掉或延后，不为「功能完整」牺牲管线质量。

---

## 2. 总体架构

```
┌─ ForegroundService（microphone | mediaProjection）────────────────┐
│  AudioCaptureEngine（麦克风 或 PlaybackCapture → PCM16 mono 16kHz） │
│    └→ VAD 分段（静音边界，15–30s，1–2s 重叠）                      │
│         └→ TranscriptionProvider（用户选定：Deepgram 或 ElevenLabs）│
│              └→ OpenRouterTranslator（仅翻译）                     │
│                   └→ StateFlow → 极简 Compose 字幕页               │
└───────────────────────────────────────────────────────────────────┘
```

- **MVVM + 单向数据流**；Hilt；Coroutines/Flow。
- Package：`audio/` · `pipeline/` · `transcription/` · `translation/` · `data/`（Key + 模型偏好）· `ui/`（首页启动 + 实时页 + 设置）· `service/`。

---

## 3. 音频捕获（P5：必须 robust）

### 3.1 两种源

| 源 | 实现 | 权限 / 类型 |
| --- | --- | --- |
| **麦克风** | `AudioRecord` + `VOICE_RECOGNITION`（回退 `MIC`），16 kHz / mono / PCM16 | `RECORD_AUDIO`、`FOREGROUND_SERVICE_MICROPHONE` |
| **系统/其他 App 播放** | `MediaProjection` + `AudioPlaybackCaptureConfiguration`（匹配 `USAGE_MEDIA` / `GAME` / `UNKNOWN`） | `FOREGROUND_SERVICE_MEDIA_PROJECTION`；Android 14+ **先** `startForeground` **再** `getMediaProjection` |

### 3.2 选择流程（robust 清单）

启动前用户明确二选一：**麦克风** / **系统音频（其他 App）**。流程必须覆盖：

1. **前置检查**
   - 转写 Key：所选 Provider（Deepgram 或 ElevenLabs）已填且校验通过。
   - 翻译 Key：OpenRouter 已填且校验通过。
   - 缺 Key 时禁止开始，设置页给出明确提示。
2. **麦克风路径**
   - 已授权 → 直接起 FGS；未授权 → 请求 `RECORD_AUDIO`，拒绝则停留并说明原因，不静默失败。
3. **系统音频路径（关键路径）**
   - 启动 FGS（`mediaProjection` 类型）→ 再弹出系统 `createScreenCaptureIntent()`。
   - 用户取消授权 → 停止 FGS、回到选择态，提示「未授权屏幕/音频捕获」。
   - 授权成功 → `getMediaProjection` → 绑定 `AudioPlaybackCapture` → 开始读 PCM。
   - OEM 差异：授权后用 `bringCaptionsToFront` 把字幕页拉回前台（已有实践保留）。
4. **运行中健康检查**
   - 连续 N 秒（建议 5–8s）能量接近静音 → 提示「未检测到播放音频」：可能目标 App 禁止捕获（`ALLOW_CAPTURE_BY_NONE`）、音量过低、或选错源；提供一键切麦克风 / 重试授权。
   - `AudioRecord` read 错误 / MediaProjection 回调 `onStop` → 停止会话、通知用户、可一键重开。
5. **停止**
   - UI「停止」与通知栏 Stop 同一路径：停捕获 → 关队列 → `stopSelf`，无僵尸服务。
6. **已知平台限制（启动前短文案说明一次即可）**
   - 部分流媒体/DRM App 禁止播放捕获；通话音频不可捕获；捕获的是混音后的播放流。

### 3.3 分段（服务准确率）

- VAD 在静音边界切分（能量阈值起步；可后续换 Silero ONNX）。
- 目标块 15–30 s，硬上限 45 s；相邻块 1–2 s 重叠，合并时去重对齐。
- 纯静音丢弃，避免空转写污染上下文。

---

## 4. 转写与翻译（P1 + P4）

### 4.1 Provider 边界（收紧）

| 阶段 | 允许的后端 | 说明 |
| --- | --- | --- |
| **转写** | **Deepgram** 或 **ElevenLabs**（用户二选一） | 必须声学 diarization；不再使用 OpenRouter 做 STT |
| **翻译** | **仅 OpenRouter** | 文本 LLM；不混用其他网关 |

启动条件：`所选转写 Provider 的 Key` + `OpenRouter Key` 均可用。未选 Provider 或 Key 缺失 → 不可开始。

### 4.2 默认 model name + 自由输入

设置页对每个环节提供：

1. **默认值**（出厂写入 DataStore；空输入 = 恢复默认）。
2. **可编辑文本框**：用户粘贴任意官方 model name / model id。
3. （可选）若干推荐 chip，点击填入文本框；**不以下拉锁死可选集合**。

| 环节 | 默认 model name | 用户可改 |
| --- | --- | --- |
| Deepgram 转写 | `nova-3`（multilingual + `diarize=true`；勿与 `diarize_model` 同用） | 是，如其他 Deepgram model id |
| ElevenLabs 转写 | `scribe_v2`（`diarize=true`） | 是，如后续 Scribe 新版 id |
| OpenRouter 翻译 | `google/gemini-3.1-pro-preview` | 是，任意 OpenRouter 文本模型 id |

- Deepgram / ElevenLabs 各自只在对应 Provider 被选中时生效。
- 翻译模型仅影响 OpenRouter `chat/completions`；与转写解耦。
- 校验：保存时 trim；非法空串回退默认；可选对 OpenRouter 做一次轻量探活（不阻塞输入）。

### 4.3 转写实现要点（准确率）

**Deepgram（流式优先）**

- Live WebSocket：`model=nova-3`，`diarize=true`，多语言/`multi`；词级 speaker → 稳定会话内颜色映射。
- 流式结果边到边着色；最终 interim→final 合并，避免闪烁。

**ElevenLabs（分块批量）**

- `POST /v1/speech-to-text`，`model_id` 取用户配置（默认 `scribe_v2`），`diarize=true`。
- 跨块 speaker ID：重叠区词对齐为主；设备端嵌入聚类为增强（准确率需要时再上，不为此堆 UI）。

**共同**

- `temperature`/采样类参数取最稳（若 API 支持）。
- 失败块：指数退避重试；仍失败则保留错误状态，不丢后续队列顺序。
- **禁止**为「更快」默认关掉 diarization。

### 4.4 翻译实现要点（准确率）

- OpenRouter `chat/completions`，模型 = 用户配置（默认 Gemini 3.1 Pro）。
- 每段请求携带：目标语言、最近若干段原文（滚动上下文）、说话人 ID（语气一致）。
- `temperature=0`；结构化输出；失败重试。
- 不做「快速档」默认降级；用户若自填更小模型，是其主动选择。

### 4.5 API Key

- OpenRouter（翻译必填）+ Deepgram 和/或 ElevenLabs（按所选转写 Provider 必填）。
- Android Keystore 加密后存 DataStore；校验探活后显示可用状态。

---

## 5. 说话人与显示（P2 + P3）

- Provider 输出整数 speaker ID → 映射到固定调色板颜色（色盲友好 8–10 色）。
- **UI 只改文字颜色（或左侧细色条 + 同色文字）**；不显示 "说话人 1"、人名、chip、气泡卡片堆叠。
- 一行结构建议：`[原文]` 换行 `[译文]`，同色；或原文/译文紧邻，仍同说话人同色。
- **自动滚动**：新行到达滚到底；**暂停滚动**后用户可上翻回看；**继续滚动**恢复跟随。
- 顶部仅保留：捕获状态（极短）+ 开始/停止 + 滚动暂停/继续。无音量表、无统计条、无次要营销信息。

---

## 6. 准确率策略（总纲）

一切工程决策服从下列顺序：

1. **选对模型与参数**：默认即高准确率模型；diarization 常开；用户自定义 model 时仍传齐 diarize/多语言等准确率相关参数。
2. **好的音频进模型**：正确的捕获源、稳定的 PCM、VAD 不截词、重叠对齐。
3. **好的上下文进翻译**：滚动前文、说话人一致、低温确定性输出。
4. **少做旁路功能**：任何新功能若增加管线复杂度或抢占调试时间，默认拒绝，除非直接提升准确率或音频源可靠性。

评估：保留/补充针对 Deepgram 与 ElevenLabs 解析、重叠合并、滚动状态的单元测试；真机用多说话人中英混合音频做人工抽检。

---

## 7. UI 结构（极简）

| 页 | 内容 |
| --- | --- |
| **首页** | 开始实时字幕、进入设置；无历史列表。 |
| **实时字幕页** | 彩色文本流 + 源选择（麦克风 / 系统音频）+ 开始/停止 + 滚动暂停/继续。 |
| **设置页** | 三个 Key；转写 Provider 单选（Deepgram / ElevenLabs）；三个 model 文本框（Deepgram / ElevenLabs / OpenRouter 翻译）带默认值与「恢复默认」。 |

Material 3 即可；不为视觉加卡片墙、统计条、悬浮窗。

---

## 8. 数据与隐私

- 运行时状态以内存 + StateFlow 为主；不做会话历史库（非目标）。
- 原始音频默认不落盘；失败重试可用短时临时块，用完即删。
- 隐私提示：音频发往 Deepgram 或 ElevenLabs；文本发往 OpenRouter 及其上游。

---

## 9. 权限

`INTERNET`、`RECORD_AUDIO`、`POST_NOTIFICATIONS`、`FOREGROUND_SERVICE`、`FOREGROUND_SERVICE_MICROPHONE`、`FOREGROUND_SERVICE_MEDIA_PROJECTION`。  
`foregroundServiceType="mediaProjection|microphone"`。不做 `SYSTEM_ALERT_WINDOW`。

---

## 10. 技术栈

Kotlin 2.x · Jetpack Compose + Material 3 · Hilt · DataStore · OkHttp + kotlinx.serialization · minSdk 31 / targetSdk 35 · JUnit + MockWebServer。

---

## 11. 里程碑（按原则重排）

| 里程碑 | 内容 | 验收 |
| --- | --- | --- |
| **R0 计划对齐** | 本文档；砍掉 OpenRouter STT / 历史 / 悬浮窗等非目标 | 团队按本文实现与评审 |
| **R1 设置与模型** | Provider 二选一；Deepgram / ElevenLabs / 翻译 model 默认值 + 自由输入；Key 校验 | 改 model name 后请求打到对应用户字符串 |
| **R2 转写准确率** | Deepgram 流式 diar + ElevenLabs 批量 diar；颜色映射；去掉 OpenRouter 转写路径 | 多说话人音频颜色稳定、文本准确 |
| **R3 翻译准确率** | 仅 OpenRouter；滚动上下文；低温；默认高准确率模型 | 译文术语/代词一致 |
| **R4 音频源 robust** | §3.2 全流程：授权顺序、取消、无声提示、Projection 断开恢复、切源 | 真机：YouTube 等可捕获；取消/无声/断开均可恢复且无僵尸 FGS |
| **R5 极简 UI** | 彩色文本 + 滚动暂停/继续；去掉多余控件与非目标页 | 首屏只有必要控件；滚动行为正确 |

每个里程碑附：相关单测 + 真机/模拟器手测清单（麦克风虚拟输入；播放捕获用机内播放测试音）。

---

## 12. 风险与对策

| 风险 | 对策 |
| --- | --- |
| 目标 App 禁止播放捕获 | 无声检测 + 明确提示改麦克风外放或换源 |
| MediaProjection / OEM 生命周期怪异 | FGS 先于 projection；`onStop` 停会话；拉回前台 |
| 用户填错 model name | 请求失败时展示 API 原文错误；一键恢复默认 |
| 跨块 speaker 漂移（ElevenLabs） | 重叠对齐；不以复杂 UI 合并代替 |
| 成本 | 不设「廉价默认」；静音丢弃即可。准确率优先于省钱 |

---

## 13. 相对旧方案的变更摘要

| 旧方案 | 新方案 |
| --- | --- |
| 转写：Deepgram / ElevenLabs / OpenRouter 回退 | 转写：**仅** Deepgram **或** ElevenLabs |
| 模型：推荐列表 + 档位 | **默认 model name + 用户自由输入** |
| UI：会话列表、导出、悬浮窗、词表… | **彩色文本 + 滚动暂停/继续** |
| 准确率与成本并重长文 | **准确率绝对优先**；成本/功能让位 |
| 音频源有实现但说明分散 | **独立 robust 流程**（§3.2）作为一等需求 |
