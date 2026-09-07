package com.badmintontracker.shared.prefs

import com.russhwolf.settings.Settings

/** Which arm holds the racket, as the coach said; unset means show both. */
enum class RacketArm { LEFT, RIGHT }

/**
 * The racket arm per analysed video, remembered on this phone.
 *
 * The pipeline cannot tell handedness (see `Skeleton.ARM_EDGES`), so the
 * skeleton view shows both arms until a coach picks one, and remembers the
 * pick per video: a left-hander's match stays a left-hander's match. Stored
 * as a string like the other preference repositories, so a value written by
 * another build can never fail on a type mismatch; an unknown string reads
 * as unset.
 */
class RacketArmPreferenceRepository(private val settings: Settings) {

    fun racketArm(entryId: String): RacketArm? =
        settings.getStringOrNull(key(entryId))?.let { stored -> RacketArm.entries.firstOrNull { it.name == stored } }

    fun setRacketArm(entryId: String, arm: RacketArm?) {
        if (arm == null) settings.remove(key(entryId)) else settings.putString(key(entryId), arm.name)
    }

    private fun key(entryId: String) = "$KEY_PREFIX$entryId"

    private companion object {
        const val KEY_PREFIX = "racket_arm:"
    }
}
