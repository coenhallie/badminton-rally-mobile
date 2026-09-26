package com.badmintontracker.android.ui.components

import android.content.Context
import androidx.annotation.OptIn
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.SeekParameters

/**
 * The player every review surface uses: one that lands on the frame it was asked
 * for. ExoPlayer's default seeks to the nearest sync frame, which is up to a GOP
 * away, and frame stepping and the skeleton overlay both need the exact one.
 *
 * In one place because `setSeekParameters` is still an unstable media3 API, so
 * the opt-in (and the day it changes) is this function's alone.
 */
@OptIn(UnstableApi::class)
fun frameExactPlayer(context: Context): ExoPlayer =
    ExoPlayer.Builder(context).build().apply { setSeekParameters(SeekParameters.EXACT) }
