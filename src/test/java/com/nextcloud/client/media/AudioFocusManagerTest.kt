package com.nextcloud.client.media

import android.media.AudioFocusRequest
import android.media.AudioManager
import com.nhaarman.mockitokotlin2.any
import com.nhaarman.mockitokotlin2.argThat
import com.nhaarman.mockitokotlin2.mock
import com.nhaarman.mockitokotlin2.verify
import com.nhaarman.mockitokotlin2.whenever
import org.junit.Before
import org.junit.Test
import org.mockito.ArgumentMatcher

class AudioFocusManagerTest {

    private val audioManager = mock<AudioManager>()
    private val callback = mock<(AudioFocus)->Unit>()
    private lateinit var audioFocusManager: AudioFocusManager

    val audioRequestMatcher = object : ArgumentMatcher<AudioFocusRequest> {
        override fun matches(argument: AudioFocusRequest?): Boolean = true
    }

    @Before
    fun setUp() {
        audioFocusManager = AudioFocusManager(audioManager, callback)
        whenever(audioManager.requestAudioFocus(any(), any(), any()))
            .thenReturn(AudioManager.AUDIOFOCUS_REQUEST_GRANTED)
        whenever(audioManager.abandonAudioFocusRequest(argThat(audioRequestMatcher)))
            .thenReturn(AudioManager.AUDIOFOCUS_REQUEST_GRANTED)
        whenever(audioManager.abandonAudioFocusRequest(any()))
            .thenReturn(AudioManager.AUDIOFOCUS_REQUEST_GRANTED)
    }

    @Test
    fun `acquiring focus triggers callback immediately`() {
        audioFocusManager.requestFocus()
        verify(callback).invoke(AudioFocus.FOCUS)
    }

    @Test
    fun `failing to acquire focus triggers callback immediately`() {
        whenever(audioManager.requestAudioFocus(any(), any(), any()))
            .thenReturn(AudioManager.AUDIOFOCUS_REQUEST_FAILED)
        audioFocusManager.requestFocus()
        verify(callback).invoke(AudioFocus.LOST)
    }

    @Test
    fun `releasing focus triggers callback immediately`() {
        audioFocusManager.releaseFocus()
        verify(callback).invoke(AudioFocus.LOST)
    }
}
