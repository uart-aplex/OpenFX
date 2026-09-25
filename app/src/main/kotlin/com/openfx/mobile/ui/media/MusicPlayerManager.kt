package com.openfx.mobile.ui.media

import android.content.ComponentName
import android.content.Context
import android.net.Uri
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.Player
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import com.google.common.util.concurrent.ListenableFuture
import com.google.common.util.concurrent.MoreExecutors
import com.openfx.core.file.FileItem
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 單曲資訊
 *
 * 建立播放清單時先用檔名當標題 (立即播放,不等 metadata)；
 * ExoPlayer 播放時會解析 ID3/FLAC 標籤，透過 Player.Listener 回填
 * 曲名/演出者/專輯/封面。
 */
data class MusicTrack(
    val path: String,
    val title: String,
    val artist: String,
    val album: String,
    val artwork: ByteArray?,
)

data class MusicPlayerState(
    val playlist: List<MusicTrack> = emptyList(),
    val currentIndex: Int = -1,
    val isPlaying: Boolean = false,
    val positionMs: Long = 0,
    val durationMs: Long = 0,
) {
    val currentTrack: MusicTrack? get() = playlist.getOrNull(currentIndex)
}

/**
 * 音樂播放中樞 — 連接 PlaybackService 的 MediaController
 *
 * 播放發生在 PlaybackService (MediaSessionService)，所以：
 * - 關掉播放器畫面音樂繼續播 (背景播放)
 * - 通知列 / 鎖屏自動有播放控制
 * - 耳機拔出、其他 App 搶音訊焦點時自動暫停
 */
@Singleton
class MusicPlayerManager @Inject constructor(
    @ApplicationContext private val context: Context
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    private val _state = MutableStateFlow(MusicPlayerState())
    val state: StateFlow<MusicPlayerState> = _state.asStateFlow()

    private var controllerFuture: ListenableFuture<MediaController>? = null
    private var controller: MediaController? = null
    private var positionJob: Job? = null

    /** 進行中的播放請求 — 新的點擊會取消舊的，保證最後點的那首生效 */
    private var playJob: Job? = null

    private val playerListener = object : Player.Listener {
        override fun onIsPlayingChanged(isPlaying: Boolean) {
            _state.update { it.copy(isPlaying = isPlaying) }
            if (isPlaying) startPositionUpdates() else stopPositionUpdates()
        }

        override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
            val c = controller ?: return
            _state.update {
                it.copy(
                    currentIndex = c.currentMediaItemIndex,
                    positionMs = 0,
                    durationMs = c.duration.coerceAtLeast(0)
                )
            }
            mediaItem?.let { mergeMetadata(c.currentMediaItemIndex, it.mediaMetadata) }
        }

        override fun onMediaMetadataChanged(mediaMetadata: MediaMetadata) {
            val c = controller ?: return
            mergeMetadata(c.currentMediaItemIndex, mediaMetadata)
        }

        override fun onPlaybackStateChanged(playbackState: Int) {
            if (playbackState == Player.STATE_READY) {
                _state.update {
                    it.copy(durationMs = (controller?.duration ?: 0L).coerceAtLeast(0))
                }
            }
        }
    }

    /**
     * 播放整個播放清單 — 立即開始，不等 metadata。
     * 重複點擊時最新一次生效 (取消前一個尚未完成的請求)。
     */
    fun playPlaylist(files: List<FileItem>, startIndex: Int) {
        if (files.isEmpty() || startIndex !in files.indices) return

        playJob?.cancel()
        playJob = scope.launch {
            // 立即用檔名建立清單 — 不讀 metadata,馬上播
            val tracks = files.map { item ->
                MusicTrack(
                    path = item.path,
                    title = item.name.substringBeforeLast('.', item.name),
                    artist = "",
                    album = File(item.path).parentFile?.name ?: "",
                    artwork = null
                )
            }

            val c = runCatching { awaitController() }.getOrElse { return@launch }

            val current = _state.value
            if (current.currentTrack?.path == tracks[startIndex].path &&
                current.playlist.size == tracks.size
            ) {
                // 同一首已在播放清單 (例如重新打開播放器畫面) — 不重頭播,只同步狀態
                _state.update {
                    it.copy(
                        currentIndex = c.currentMediaItemIndex,
                        isPlaying = c.isPlaying,
                        durationMs = c.duration.coerceAtLeast(0)
                    )
                }
                return@launch
            }

            // 先更新 UI 到新點的歌,再下播放指令
            _state.update {
                it.copy(
                    playlist = tracks,
                    currentIndex = startIndex,
                    positionMs = 0,
                    durationMs = 0
                )
            }

            c.setMediaItems(tracks.map { it.toMediaItem() }, startIndex, 0L)
            c.prepare()
            c.play()
        }
    }

    fun togglePlayPause() {
        val c = controller ?: return
        if (c.isPlaying) {
            c.pause()
        } else {
            if (c.playbackState == Player.STATE_ENDED) c.seekTo(0)
            c.play()
        }
    }

    fun next() {
        controller?.seekToNextMediaItem()
    }

    fun previous() {
        controller?.seekToPreviousMediaItem()
    }

    fun seekTo(positionMs: Long) {
        controller?.seekTo(positionMs)
        _state.update { it.copy(positionMs = positionMs) }
    }

    // ===== Private =====

    /**
     * 取得 (或建立) 唯一的 MediaController 連線。
     * 必須在 Main thread 呼叫 (MediaController.Builder 需要 Looper)。
     * Future 只建一次，並行呼叫會共享同一條連線。
     */
    private suspend fun awaitController(): MediaController {
        controller?.let { return it }

        val future = controllerFuture ?: MediaController.Builder(
            context,
            SessionToken(context, ComponentName(context, PlaybackService::class.java))
        ).buildAsync().also { controllerFuture = it }

        return suspendCancellableCoroutine { cont ->
            future.addListener({
                runCatching { future.get() }
                    .onSuccess { c ->
                        if (controller == null) {
                            c.addListener(playerListener)
                            controller = c
                        }
                        cont.resumeWith(Result.success(controller!!))
                    }
                    .onFailure { e ->
                        controllerFuture = null
                        cont.resumeWith(Result.failure(e))
                    }
            }, MoreExecutors.directExecutor())
        }
    }

    /** 把 player 解析到的標籤 (曲名/演出者/專輯/封面) 回填到播放清單對應位置 */
    private fun mergeMetadata(index: Int, metadata: MediaMetadata) {
        if (index < 0) return
        _state.update { state ->
            val track = state.playlist.getOrNull(index) ?: return@update state
            val updated = track.copy(
                title = metadata.title?.toString()?.takeIf { it.isNotBlank() } ?: track.title,
                artist = metadata.artist?.toString()?.takeIf { it.isNotBlank() }
                    ?: track.artist.ifBlank { "未知演出者" },
                album = metadata.albumTitle?.toString()?.takeIf { it.isNotBlank() } ?: track.album,
                artwork = metadata.artworkData ?: track.artwork
            )
            state.copy(
                playlist = state.playlist.toMutableList().also { it[index] = updated }
            )
        }
    }

    private fun MusicTrack.toMediaItem(): MediaItem {
        return MediaItem.Builder()
            .setMediaId(path)
            .setUri(Uri.fromFile(File(path)))
            .setMediaMetadata(MediaMetadata.Builder().setTitle(title).build())
            .build()
    }

    private fun startPositionUpdates() {
        positionJob?.cancel()
        positionJob = scope.launch {
            while (isActive) {
                val c = controller ?: break
                _state.update { state ->
                    state.copy(
                        positionMs = c.currentPosition.coerceAtLeast(0),
                        durationMs = c.duration.coerceAtLeast(0)
                            .takeIf { it > 0 } ?: state.durationMs
                    )
                }
                delay(500)
            }
        }
    }

    private fun stopPositionUpdates() {
        positionJob?.cancel()
        positionJob = null
    }
}
