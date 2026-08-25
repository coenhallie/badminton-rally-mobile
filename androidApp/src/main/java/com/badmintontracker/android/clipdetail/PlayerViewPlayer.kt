package com.badmintontracker.android.clipdetail

import androidx.media3.common.ForwardingPlayer
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer

/**
 * The player handed to PlayerView, narrowed so Media3's own controls cannot
 * offer a second playback-speed control.
 *
 * PlayerControlView's settings gear offers a playback-speed menu that would
 * disagree with [PlaybackControlBar] the moment either one is used. There is no
 * public setter for that menu (unlike rewind and fast-forward), but it is gated
 * on COMMAND_SET_SPEED_AND_PITCH, so withholding that command drops the speed
 * entry. The gear itself stays - hasSettingsToShow() also covers audio-track
 * selection, which was already there and is not ours to remove. The underlying
 * ExoPlayer is untouched: [PlaybackControlBar] still calls setPlaybackSpeed on
 * it directly.
 */
fun ExoPlayer.withoutMedia3SpeedMenu(): Player = object : ForwardingPlayer(this) {
    override fun getAvailableCommands(): Player.Commands =
        super.getAvailableCommands()
            .buildUpon()
            .remove(Player.COMMAND_SET_SPEED_AND_PITCH)
            .build()

    override fun isCommandAvailable(command: Int): Boolean =
        command != Player.COMMAND_SET_SPEED_AND_PITCH && super.isCommandAvailable(command)
}
