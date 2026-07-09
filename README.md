# Captions

Live transcription & translation for Android 12+, driven by your own API keys.

- **Transcription**: ElevenLabs Scribe v2 (default, acoustic speaker diarization) with OpenRouter multimodal fallback
- **Translation**: OpenRouter (Gemini 3.1 Pro default)
- **Speakers**: distinguished by color only — no text labels
- **Audio sources**: microphone, or other apps via AudioPlaybackCapture

See [PLAN.md](PLAN.md) for the full technical plan.

## Build

```bash
./gradlew :app:assembleDebug
./gradlew :app:testDebugUnitTest
```

Requires JDK 17+ and Android SDK 35 (`local.properties` with `sdk.dir`).
