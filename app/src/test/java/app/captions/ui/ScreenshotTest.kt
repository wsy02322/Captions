package app.captions.ui

import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onRoot
import app.captions.ui.home.HomeContent
import app.captions.ui.home.HomeUiState
import app.captions.ui.settings.KeyFieldState
import app.captions.ui.settings.KeyFieldStatus
import app.captions.ui.settings.SettingsContent
import app.captions.ui.settings.SettingsUiState
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
class ScreenshotTest {

    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun homeScreen_readyState() {
        composeRule.setContent {
            CaptionsTheme {
                HomeContent(
                    uiState = HomeUiState(
                        loaded = true,
                        hasOpenRouterKey = true,
                        hasElevenLabsKey = true,
                    ),
                    onOpenSettings = {},
                )
            }
        }
        composeRule.onRoot()
            .captureRoboImage("build/reports/screenshots/home_ready.png")
    }

    @Test
    fun homeScreen_setupNeeded() {
        composeRule.setContent {
            CaptionsTheme {
                HomeContent(
                    uiState = HomeUiState(loaded = true),
                    onOpenSettings = {},
                )
            }
        }
        composeRule.onRoot()
            .captureRoboImage("build/reports/screenshots/home_setup_needed.png")
    }

    @Test
    fun settingsScreen_validAndRejectedKeys() {
        composeRule.setContent {
            CaptionsTheme {
                SettingsContent(
                    uiState = SettingsUiState(
                        loaded = true,
                        openRouter = KeyFieldState(
                            text = "sk-or-v1-0123456789abcdef",
                            status = KeyFieldStatus.VALID,
                        ),
                        elevenLabs = KeyFieldState(
                            text = "xi-bad-key",
                            status = KeyFieldStatus.INVALID,
                        ),
                    ),
                    onKeyChanged = { _, _ -> },
                    onVerify = {},
                    onBack = {},
                )
            }
        }
        composeRule.onRoot()
            .captureRoboImage("build/reports/screenshots/settings_valid_and_rejected.png")
    }

    @Test
    fun settingsScreen_networkErrorState() {
        composeRule.setContent {
            CaptionsTheme {
                SettingsContent(
                    uiState = SettingsUiState(
                        loaded = true,
                        openRouter = KeyFieldState(
                            text = "sk-or-v1-0123456789abcdef",
                            status = KeyFieldStatus.NETWORK_ERROR,
                        ),
                        elevenLabs = KeyFieldState(
                            text = "",
                            status = KeyFieldStatus.IDLE,
                        ),
                    ),
                    onKeyChanged = { _, _ -> },
                    onVerify = {},
                    onBack = {},
                )
            }
        }
        composeRule.onRoot()
            .captureRoboImage("build/reports/screenshots/settings_network_error.png")
    }
}
