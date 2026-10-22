package com.openfx.mobile.ui.media

import com.openfx.core.media.MediaUris
import com.openfx.core.media.OpenFxDataSourceFactory
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject
import androidx.compose.ui.res.stringResource
import com.openfx.mobile.R
import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.annotation.OptIn
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.media3.common.AudioAttributes
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.PlayerView
import java.io.File

/**
 * 內建影片播放器 — ExoPlayer + PlayerView
 *
 * PlayerView 內建控制列 (播放/暫停/拖曳/全螢幕手勢)，
 * 也支援 D-pad (TV 遙控器方向鍵可聚焦操作)。
 * manifest 宣告 configChanges，旋轉不會重建播放器。
 */
@OptIn(UnstableApi::class)
@AndroidEntryPoint
class VideoPlayerActivity : ComponentActivity() {

    override fun attachBaseContext(newBase: android.content.Context) {
        super.attachBaseContext(com.openfx.common.util.AppLocale.wrap(newBase))
    }

    /** 本機檔案與 smb:// 串流共用的資料來源 */
    @Inject
    lateinit var dataSourceFactory: OpenFxDataSourceFactory

    companion object {
        const val EXTRA_PATH = "extra_path"
        const val EXTRA_TITLE = "extra_title"
    }

    private var player: ExoPlayer? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        val path = intent.getStringExtra(EXTRA_PATH)
        if (path.isNullOrBlank()) {
            finish()
            return
        }
        val title = intent.getStringExtra(EXTRA_TITLE) ?: File(path).name

        val exoPlayer = ExoPlayer.Builder(this)
            .setMediaSourceFactory(DefaultMediaSourceFactory(dataSourceFactory))
            .setAudioAttributes(AudioAttributes.DEFAULT, /* handleAudioFocus= */ true)
            .setHandleAudioBecomingNoisy(true)
            .build()
        exoPlayer.setMediaItem(
            MediaItem.Builder()
                .setUri(MediaUris.forPath(path))
                .setMediaMetadata(MediaMetadata.Builder().setTitle(title).build())
                .build()
        )
        exoPlayer.prepare()
        exoPlayer.playWhenReady = true
        player = exoPlayer

        setContent {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(Color.Black)
            ) {
                AndroidView(
                    factory = { context ->
                        PlayerView(context).apply {
                            useController = true
                            controllerShowTimeoutMs = 4000
                            setShowNextButton(false)
                            setShowPreviousButton(false)
                            this.player = exoPlayer
                        }
                    },
                    modifier = Modifier.fillMaxSize()
                )

                IconButton(
                    onClick = { finish() },
                    modifier = Modifier
                        .align(Alignment.TopStart)
                        .systemBarsPadding()
                        .padding(8.dp)
                ) {
                    Icon(
                        Icons.AutoMirrored.Filled.ArrowBack,
                        contentDescription = stringResource(R.string.back),
                        tint = Color.White
                    )
                }
            }
        }
    }

    override fun onPause() {
        super.onPause()
        player?.pause()
    }

    override fun onDestroy() {
        player?.release()
        player = null
        super.onDestroy()
    }
}
