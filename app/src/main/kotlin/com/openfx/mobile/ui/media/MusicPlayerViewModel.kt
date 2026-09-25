package com.openfx.mobile.ui.media

import androidx.lifecycle.ViewModel
import com.openfx.core.file.FileItem
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject

/**
 * 播放器畫面用 ViewModel — 轉發 MusicPlayerManager 的狀態與操作
 */
@HiltViewModel
class MusicPlayerViewModel @Inject constructor(
    private val manager: MusicPlayerManager
) : ViewModel() {

    val state = manager.state

    fun playPlaylist(files: List<FileItem>, startIndex: Int) =
        manager.playPlaylist(files, startIndex)

    fun togglePlayPause() = manager.togglePlayPause()

    fun next() = manager.next()

    fun previous() = manager.previous()

    fun seekTo(positionMs: Long) = manager.seekTo(positionMs)
}
