/**
 * Nextcloud Android client application
 *
 * @author Chris Narkiewicz
 * Copyright (C) 2019 Chris Narkiewicz <hello@ezaquarii.com>
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the
 * GNU Affero General Public License for more details.
 *
 * You should have received a copy of the GNU Affero General Public License
 * along with this program. If not, see <http://www.gnu.org/licenses/>.
 */
package com.nextcloud.client.media

import android.accounts.Account
import android.content.Context
import android.media.AudioManager
import android.media.MediaPlayer
import android.os.PowerManager
import android.widget.MediaController
import com.nextcloud.client.media.PlayerStateMachine.Event
import com.nextcloud.client.media.PlayerStateMachine.State
import com.owncloud.android.R
import com.owncloud.android.datamodel.OCFile
import com.owncloud.android.lib.common.OwnCloudAccount
import com.owncloud.android.lib.common.OwnCloudClient
import com.owncloud.android.lib.common.OwnCloudClientManagerFactory
import com.owncloud.android.lib.common.utils.Log_OC

@Suppress("TooManyFunctions")
internal class Player(
    private val context: Context,
    private val listener: Listener? = null,
    audioManager: AudioManager,
    private val mediaPlayerCreator: () -> MediaPlayer = { MediaPlayer() }
) : MediaController.MediaPlayerControl {

    private companion object {
        const val DEFAULT_VOLUME = 1.0f
        const val DUCK_VOLUME = 0.1f
        const val MIN_DURATION_ALLOWING_SEEK = 3000
    }

    data class Error(val message: String)
    interface Listener {
        fun onStart()
        fun onStop()
        fun onError(error: Error)
    }

    private var fsm: PlayerStateMachine
    private var loadUrlTask: LoadUrlTask? = null

    private var file: OCFile? = null
    private var startPositionMs: Int = 0
    private var autoPlay = true
    private var account: Account? = null
    private var dataSource: String? = null
    private var lastError: Error? = null
    private var mediaPlayer: MediaPlayer? = null
    private val focusManager = AudioFocusManager(audioManager, this::onAudioFocusChange)

    private val delegate = object : PlayerStateMachine.Delegate {
        override val isDownloaded: Boolean get() = file?.isDown ?: false
        override val isAutoplayEnabled: Boolean get() = autoPlay

        override fun onStartDownloading() {
            trace("onStartDownloading()")
            if (file == null) {
                throw IllegalStateException("File not set.")
            }
            file?.let {
                val client = buildClient()
                val task = LoadUrlTask(client, it.remoteId, this@Player::onDownloaded)
                task.execute()
                loadUrlTask = task
            }
        }

        override fun onPrepare() {
            trace("onPrepare()")
            mediaPlayer = mediaPlayerCreator.invoke()
            mediaPlayer?.setOnErrorListener(this@Player::onMediaPlayerError)
            mediaPlayer?.setOnPreparedListener(this@Player::onMediaPlayerPrepared)
            mediaPlayer?.setOnCompletionListener(this@Player::onMediaPlayerCompleted)
            mediaPlayer?.setOnBufferingUpdateListener(this@Player::onMediaPlayerBufferingUpdate)
            mediaPlayer?.setWakeMode(context, PowerManager.PARTIAL_WAKE_LOCK)
            mediaPlayer?.setDataSource(dataSource)
            mediaPlayer?.setAudioStreamType(AudioManager.STREAM_MUSIC)
            mediaPlayer?.setVolume(DEFAULT_VOLUME, DEFAULT_VOLUME)
            mediaPlayer?.prepareAsync()
        }

        override fun onStopped() {
            trace("onStoppped()")
            mediaPlayer?.stop()
            mediaPlayer?.reset()
            mediaPlayer?.release()
            mediaPlayer = null

            file = null
            startPositionMs = 0
            account = null
            autoPlay = true
            dataSource = null
            loadUrlTask?.cancel(true)
            loadUrlTask = null
            listener?.onStop()
        }

        override fun onError() {
            trace("onError()")
            this.onStopped()
            lastError?.let {
                this@Player.listener?.onError(it)
            }
            if (lastError == null) {
                this@Player.listener?.onError(Error("Unknown"))
            }
        }

        override fun onStartPlayback() {
            trace("onStartPlayback()")
            mediaPlayer?.start()
        }

        override fun onPausePlayback() {
            trace("onPausePlayback()")
            mediaPlayer?.pause()
        }

        override fun onRequestFocus() {
            trace("onRequestFocus()")
            focusManager.requestFocus()
        }

        override fun onReleaseFocus() {
            trace("onReleaseFocus()")
            focusManager.releaseFocus()
        }

        override fun onAudioDuck(enabled: Boolean) {
            trace("onAudioDuck(): $enabled")
            if (enabled) {
                mediaPlayer?.setVolume(DUCK_VOLUME, DUCK_VOLUME)
            } else {
                mediaPlayer?.setVolume(DEFAULT_VOLUME, DEFAULT_VOLUME)
            }
        }
    }

    init {
        fsm = PlayerStateMachine(delegate)
    }

    fun play(file: OCFile, startPositionMs: Int, autoPlay: Boolean, account: Account) {
        this.file = file
        this.startPositionMs = startPositionMs
        this.autoPlay = autoPlay
        this.account = account
        if (file.isDown) {
            dataSource = file.storagePath
        }
        fsm.post(Event.PLAY)
    }

    fun stop() {
        fsm.post(Event.STOP)
    }

    private fun onMediaPlayerError(mp: MediaPlayer, what: Int, extra: Int): Boolean {
        lastError = Error(PlayerError.toString(context, what, extra))
        fsm.post(Event.ERROR)
        return true
    }

    private fun onMediaPlayerPrepared(mp: MediaPlayer) {
        trace("onMediaPlayerPrepared()")
        fsm.post(Event.PREPARED)
    }

    private fun onMediaPlayerCompleted(mp: MediaPlayer) {
        fsm.post(Event.STOP)
    }

    private fun onMediaPlayerBufferingUpdate(mp: MediaPlayer, percent: Int) {
        trace("onMediaPlayerBufferingUpdate(): $percent")
    }

    private fun onDownloaded(url: String?) {
        if (url != null) {
            dataSource = url
            fsm.post(Event.DOWNLOADED)
        } else {
            lastError = Error(context.getString(R.string.media_err_io))
            fsm.post(Event.ERROR)
        }
    }

    private fun onAudioFocusChange(focus: AudioFocus) {
        when (focus) {
            AudioFocus.FOCUS -> fsm.post(Event.FOCUS_GAIN)
            AudioFocus.DUCK -> fsm.post(Event.FOCUS_DUCK)
            AudioFocus.LOST -> fsm.post(Event.FOCUS_LOST)
        }
    }

    // this should be refactored into a proper, injectable factory
    private fun buildClient(): OwnCloudClient {
        val account = this.account
        if (account != null) {
            val ocAccount = OwnCloudAccount(account, context)
            return OwnCloudClientManagerFactory.getDefaultSingleton().getClientFor(ocAccount, context)
        } else {
            throw IllegalArgumentException("Account not set")
        }
    }

    private fun trace(fmt: String, vararg args: Any?) {
        Log_OC.v(javaClass.simpleName, fmt.format(args))
    }

    // region Media player controls

    override fun isPlaying(): Boolean {
        return fsm.isInState(State.PLAYING)
    }

    override fun canSeekForward(): Boolean {
        return duration > MIN_DURATION_ALLOWING_SEEK
    }

    override fun canSeekBackward(): Boolean {
        return duration > MIN_DURATION_ALLOWING_SEEK
    }

    override fun getDuration(): Int {
        val hasDuration = setOf(State.PLAYING, State.PAUSED)
            .find { fsm.isInState(it) } != null
        return if (hasDuration) {
            mediaPlayer?.duration ?: 0
        } else {
            0
        }
    }

    override fun pause() {
        fsm.post(Event.PAUSE)
    }

    override fun getBufferPercentage(): Int {
        return 0
    }

    override fun seekTo(pos: Int) {
        if (fsm.isInState(State.PLAYING)) {
            mediaPlayer?.seekTo(pos)
        }
    }

    override fun getCurrentPosition(): Int {
        return mediaPlayer?.currentPosition ?: 0
    }

    override fun start() {
        fsm.post(Event.START)
    }

    override fun getAudioSessionId(): Int {
        return 0
    }

    override fun canPause(): Boolean {
        return fsm.isInState(State.PLAYING)
    }

    // endregion
}
