package app.captions.audio

import android.media.AudioAttributes
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class PlaybackCaptureConfigTest {
    @Test
    fun `matched usages cover media game and unknown`() {
        assertArrayEquals(
            intArrayOf(
                AudioAttributes.USAGE_MEDIA,
                AudioAttributes.USAGE_GAME,
                AudioAttributes.USAGE_UNKNOWN,
            ),
            PlaybackCaptureConfig.MATCHED_USAGES,
        )
        assertTrue(PlaybackCaptureConfig.MATCHED_USAGES.none { it == AudioAttributes.USAGE_VOICE_COMMUNICATION })
    }
}
