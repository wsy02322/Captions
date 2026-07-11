# Captions

Android 12+ 实时转写与翻译，由用户自备 API Key 驱动。

**核心原则**：转写与翻译准确率优先；功能与 UI 极简；音频输入选择可靠。

| 能力 | Provider |
| --- | --- |
| **转写**（二选一） | Deepgram（默认 `nova-3`，流式）或 ElevenLabs（默认 `scribe_v2`，批量） |
| **翻译** | OpenRouter（默认 `google/gemini-3.1-pro-preview`） |
| **说话人** | 仅用颜色区分，无文字标签 |
| **音频** | 麦克风，或应用播放音频（AudioPlaybackCapture） |

转写与翻译 model 均有默认值，可在设置中自定义 model name。

详见 [PLAN.md](PLAN.md)。

## Build

```bash
./gradlew :app:assembleDebug
./gradlew :app:testDebugUnitTest
```

Requires JDK 17+ and Android SDK 35 (`local.properties` with `sdk.dir`).
