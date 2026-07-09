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
                            CaptionLine("1", 0, "Hello from speaker one", true, 1L),
                            CaptionLine("2", 1, "And a reply from speaker two", true, 2L),
                        ),
                        partial = CaptionLine("3", 0, "Continuing…", false, 3L),
                        level = 0.4f,
                    ),
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
