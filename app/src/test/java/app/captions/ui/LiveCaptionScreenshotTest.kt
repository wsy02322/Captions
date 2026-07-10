package app.captions.ui

import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onRoot
import app.captions.pipeline.CaptureStatus
import app.captions.pipeline.LiveCaptionState
import app.captions.transcription.CaptionLine
import app.captions.ui.live.LiveCaptionContent
import app.captions.ui.theme.CaptionsTheme
import com.github.takahirom.roborazzi.captureRoboImage
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w411dp-h891dp-420dpi")
class LiveCaptionScreenshotTest {

    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun liveCaption_coloredSpeakersNoLabels() {
        composeRule.setContent {
            CaptionsTheme {
                LiveCaptionContent(
                    state = LiveCaptionState(
                        status = CaptureStatus.Listening,
                        lines = listOf(
                            CaptionLine(
                                id = "1",
                                speaker = 0,
                                text = "Hello from speaker one",
                                isFinal = true,
                                startedAtMs = 1L,
                                translation = "来自一号说话人的你好",
                            ),
                            CaptionLine(
                                id = "2",
                                speaker = 1,
                                text = "And a reply from speaker two",
                                isFinal = true,
                                startedAtMs = 2L,
                                translation = "二号说话人的回复",
                            ),
                        ),
                        partial = CaptionLine("3", 0, "Continuing…", false, 3L),
                        level = 0.4f,
                        providerHint = "Deepgram Nova-3",
                    ),
                    captureSource = app.captions.audio.CaptureSource.PLAYBACK,
                    onBack = {},
                    onStart = {},
                    onStop = {},
                )
            }
        }
        composeRule.onRoot()
            .captureRoboImage("build/reports/screenshots/live_colored_speakers.png")
    }
}
