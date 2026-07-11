# Captions

Android 12+ 实时转写与翻译。准确率优先，功能与显示极简。

- **转写**：Deepgram 或 ElevenLabs（二选一）；默认 model name，可自行输入
- **翻译**：仅 OpenRouter；默认 model name，可自行输入
- **显示**：彩色文字区分说话人（无文字标签）；可暂停/继续屏幕滚动
- **音频**：麦克风，或系统/其他 App 播放捕获（选择与授权流程必须 robust）

详见 [PLAN.md](PLAN.md)。

## Build

```bash
./gradlew :app:assembleDebug
./gradlew :app:testDebugUnitTest
```

需要 JDK 17+ 与 Android SDK 35（`local.properties` 中配置 `sdk.dir`）。
