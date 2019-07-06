package com.nextcloud.client.media

import android.media.AudioManager
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class AudioFocusTest {

    @Test
    fun `invalid values result in null`() {
        val focus = AudioFocus.fromPlatformFocus(-10000)
        assertNull(focus)
    }

    @Test
    fun `audio focus values are converted`() {
        val validValues = listOf(
            AudioManager.AUDIOFOCUS_GAIN,
            AudioManager.AUDIOFOCUS_GAIN_TRANSIENT,
            AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK,
            AudioManager.AUDIOFOCUS_LOSS,
            AudioManager.AUDIOFOCUS_LOSS_TRANSIENT,
            AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK
        )
        validValues.forEach {
            val focus = AudioFocus.fromPlatformFocus(-it)
            assertNotNull(focus)
        }
    }
}
