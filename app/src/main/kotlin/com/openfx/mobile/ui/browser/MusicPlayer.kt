package com.openfx.mobile.ui.browser

import androidx.compose.ui.res.stringResource
import com.openfx.mobile.R
import android.graphics.BitmapFactory
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material.icons.filled.SkipPrevious
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.hilt.navigation.compose.hiltViewModel
import com.openfx.core.file.FileItem
import com.openfx.mobile.ui.media.MusicPlayerViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * 音樂播放器畫面
 *
 * 播放由 PlaybackService (Media3) 執行 — 關掉這個畫面音樂會繼續播，
 * 通知列可以暫停/切歌。再次點擊同一首歌會回到目前播放狀態，不會重頭播。
 *
 * 手機和 TV 共用 (TV 走 D-pad 聚焦按鈕操作)。
 */
@Composable
fun AudioPlayerDialog(
    playlist: List<FileItem>,
    currentIndex: Int,
    onIndexChange: (Int) -> Unit,
    onDismiss: () -> Unit,
    viewModel: MusicPlayerViewModel = hiltViewModel()
) {
    if (playlist.isEmpty() || currentIndex !in playlist.indices) {
        LaunchedEffect(Unit) { onDismiss() }
        return
    }

    val state by viewModel.state.collectAsState()

    LaunchedEffect(Unit) {
        viewModel.playPlaylist(playlist, currentIndex)
    }

    // 播放中自動切歌時，同步外部 (TV 呼叫端) 的 index
    LaunchedEffect(state.currentIndex) {
        if (state.currentIndex >= 0 && state.currentIndex != currentIndex) {
            onIndexChange(state.currentIndex)
        }
    }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        Surface(
            modifier = Modifier
                .fillMaxWidth()
                .padding(20.dp),
            shape = RoundedCornerShape(8.dp),
            tonalElevation = 8.dp,
            color = MaterialTheme.colorScheme.surface
        ) {
            Column(
                modifier = Modifier.padding(20.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                // ===== 標題列 =====
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            stringResource(R.string.music_now_playing),
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.primary
                        )
                        Text(
                            state.currentTrack?.title ?: "",
                            style = MaterialTheme.typography.titleLarge,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                    IconButton(onClick = onDismiss) {
                        Icon(Icons.Default.Close, contentDescription = stringResource(R.string.close))
                    }
                }

                Spacer(Modifier.height(18.dp))

                // ===== 專輯封面 (背景 decode + 降採樣,避免大圖 OOM) =====
                val artworkBitmap by produceState<ImageBitmap?>(
                    initialValue = null,
                    state.currentTrack?.path,
                    state.currentTrack?.artwork
                ) {
                    value = state.currentTrack?.artwork?.let { bytes ->
                        withContext(Dispatchers.IO) { decodeArtwork(bytes) }
                    }
                }

                Box(
                    modifier = Modifier
                        .size(220.dp)
                        .clip(RoundedCornerShape(8.dp))
                        .background(MaterialTheme.colorScheme.primaryContainer),
                    contentAlignment = Alignment.Center
                ) {
                    val bitmap = artworkBitmap
                    if (bitmap != null) {
                        Image(
                            bitmap = bitmap,
                            contentDescription = stringResource(R.string.music_album_art),
                            modifier = Modifier.size(220.dp),
                            contentScale = ContentScale.Crop
                        )
                    } else {
                        Icon(
                            Icons.Default.MusicNote,
                            contentDescription = null,
                            modifier = Modifier.size(92.dp),
                            tint = MaterialTheme.colorScheme.primary
                        )
                    }
                }

                Spacer(Modifier.height(18.dp))

                // ===== 演出者 / 專輯 =====
                Text(
                    state.currentTrack?.artist ?: "",
                    style = MaterialTheme.typography.bodyMedium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    state.currentTrack?.album ?: "",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )

                Spacer(Modifier.height(12.dp))

                // ===== 進度條 =====
                var dragPosition by remember(state.currentTrack?.path) {
                    mutableStateOf<Float?>(null)
                }
                val sliderRange = state.durationMs.coerceAtLeast(1).toFloat()
                Slider(
                    value = (dragPosition ?: state.positionMs.toFloat())
                        .coerceIn(0f, sliderRange),
                    onValueChange = { dragPosition = it },
                    onValueChangeFinished = {
                        dragPosition?.let { viewModel.seekTo(it.toLong()) }
                        dragPosition = null
                    },
                    valueRange = 0f..sliderRange,
                    modifier = Modifier.fillMaxWidth()
                )

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text(
                        formatPlaybackDuration(dragPosition?.toLong() ?: state.positionMs),
                        fontSize = 12.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Text(
                        formatPlaybackDuration(state.durationMs),
                        fontSize = 12.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }

                Spacer(Modifier.height(12.dp))

                // ===== 播放控制 =====
                Row(
                    horizontalArrangement = Arrangement.spacedBy(18.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    FilledTonalIconButton(
                        enabled = state.currentIndex > 0,
                        onClick = { viewModel.previous() }
                    ) {
                        Icon(Icons.Default.SkipPrevious, contentDescription = stringResource(R.string.music_previous))
                    }

                    FilledIconButton(
                        modifier = Modifier.size(64.dp),
                        onClick = { viewModel.togglePlayPause() }
                    ) {
                        Icon(
                            if (state.isPlaying) Icons.Default.Pause else Icons.Default.PlayArrow,
                            contentDescription = stringResource(if (state.isPlaying) R.string.music_pause else R.string.music_play),
                            modifier = Modifier.size(34.dp)
                        )
                    }

                    FilledTonalIconButton(
                        enabled = state.currentIndex < state.playlist.lastIndex,
                        onClick = { viewModel.next() }
                    ) {
                        Icon(Icons.Default.SkipNext, contentDescription = stringResource(R.string.music_next))
                    }
                }

                Spacer(Modifier.height(12.dp))

                Text(
                    "${state.currentIndex + 1} / ${state.playlist.size}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Text(
                    stringResource(R.string.music_keeps_playing),
                    fontSize = 11.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f)
                )
            }
        }
    }
}

private fun formatPlaybackDuration(ms: Long): String {
    val totalSeconds = (ms / 1000).coerceAtLeast(0)
    val minutes = totalSeconds / 60
    val seconds = totalSeconds % 60
    return "%d:%02d".format(minutes, seconds)
}

/**
 * 封面圖 decode — 內嵌封面常是好幾千像素的大圖，
 * 全解析度 decode 一張就要幾十 MB，快速切歌會直接 OOM。
 * 先讀邊界再降採樣到 512px 以內。
 */
private fun decodeArtwork(bytes: ByteArray): ImageBitmap? {
    return runCatching {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)

        val maxDim = maxOf(bounds.outWidth, bounds.outHeight)
        var sampleSize = 1
        while (sampleSize * 2 <= maxDim / 512) {
            sampleSize *= 2
        }

        val options = BitmapFactory.Options().apply { inSampleSize = sampleSize }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, options)?.asImageBitmap()
    }.getOrNull()
}
