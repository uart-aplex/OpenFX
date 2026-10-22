package com.openfx.mobile.ui.media

import com.openfx.core.media.OpenFxDataSourceFactory
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.common.util.UnstableApi
import androidx.annotation.OptIn
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject
import androidx.media3.common.AudioAttributes
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSessionService

/**
 * 背景音樂播放 Service — 使用 Media3 + MediaSession
 * 讓音樂在背景繼續播放，並支援通知列控制
 *
 * UI 端透過 [MusicPlayerManager] 的 MediaController 連接到這裡。
 * 通知列播放控制由 Media3 MediaSessionService 自動提供。
 */
@OptIn(UnstableApi::class)
@AndroidEntryPoint
class PlaybackService : MediaSessionService() {

    /** 讓背景音樂也能播放 smb:// 上的檔案 */
    @Inject
    lateinit var dataSourceFactory: OpenFxDataSourceFactory

    private var mediaSession: MediaSession? = null

    override fun onCreate() {
        super.onCreate()
        val player = ExoPlayer.Builder(this)
            .setMediaSourceFactory(DefaultMediaSourceFactory(dataSourceFactory))
            // handleAudioFocus = true：其他 App 搶音訊焦點時自動暫停/降音量
            .setAudioAttributes(AudioAttributes.DEFAULT, /* handleAudioFocus= */ true)
            // 拔掉耳機時自動暫停
            .setHandleAudioBecomingNoisy(true)
            .build()
        mediaSession = MediaSession.Builder(this, player).build()
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaSession? {
        return mediaSession
    }

    override fun onDestroy() {
        mediaSession?.run {
            player.release()
            release()
        }
        mediaSession = null
        super.onDestroy()
    }
}
