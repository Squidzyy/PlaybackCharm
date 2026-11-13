package com.abdul.musicdisplay

import android.graphics.Bitmap

data class MusicData
    (
    val title: String?,
    val artist: String?,
    val album: String?,
    val duration: Long,
    val position: Long,
    val playbackState: Int,
    val artwork: Bitmap?
)

