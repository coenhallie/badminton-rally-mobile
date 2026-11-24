package com.badmintontracker.shared.prefs

import com.russhwolf.settings.Settings
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * Which arm holds the racket, as the coach said; unset means show both.
 *
 * The wire names are the `players.racket_arm` column's values. The per-video
 * preference below stores `name`, not the wire name, and is unaffected.
 */
@Serializable
enum class RacketArm {
    @SerialName("left") LEFT,
    @SerialName("right") RIGHT,
}

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
class RacketArmPreferenceRepository(
    private val settings: Settings,
    private val ownerId: (() -> String?)? = null,
) {

    fun racketArm(entryId: String): RacketArm? {
        val scope = currentScope() ?: return null
        if (scope == LOCAL_SCOPE) {
            return parseArm(settings.getStringOrNull(legacyKey(entryId)))
        }
        val scoped = settings.getStringOrNull(scopedKey(scope, entryId))
        if (scoped != null) return parseArm(scoped)
        // First upgraded user inherits pre-scoped picks lazily; other accounts never see them.
        val legacyOwner = claimLegacyOwner(scope)
        if (legacyOwner == scope) {
            val legacy = settings.getStringOrNull(legacyKey(entryId))
            if (legacy != null) {
                settings.putString(scopedKey(scope, entryId), legacy)
                settings.remove(legacyKey(entryId))
                return parseArm(legacy)
            }
        }
        return null
    }

    fun setRacketArm(entryId: String, arm: RacketArm?) {
        val scope = currentScope() ?: return
        if (scope == LOCAL_SCOPE) {
            val k = legacyKey(entryId)
            if (arm == null) settings.remove(k) else settings.putString(k, arm.name)
            return
        }
        val legacyOwner = claimLegacyOwner(scope)
        val k = scopedKey(scope, entryId)
        if (arm == null) settings.remove(k) else settings.putString(k, arm.name)
        // Drop any un-migrated pre-upgrade key for this entry so setting null cannot resurrect it.
        if (legacyOwner == scope) settings.remove(legacyKey(entryId))
    }

    private fun currentScope(): String? = if (ownerId == null) LOCAL_SCOPE else ownerId.invoke()

    private fun claimLegacyOwner(scope: String): String {
        val existing = settings.getStringOrNull(KEY_LEGACY_OWNER)
        if (existing != null) return existing
        settings.putString(KEY_LEGACY_OWNER, scope)
        return scope
    }

    private fun parseArm(stored: String?): RacketArm? =
        stored?.let { name -> RacketArm.entries.firstOrNull { it.name == name } }

    private fun legacyKey(entryId: String) = "$KEY_PREFIX$entryId"
    private fun scopedKey(scope: String, entryId: String) = "$KEY_SCOPED_PREFIX$scope:$entryId"

    private companion object {
        const val LOCAL_SCOPE = "__local__"
        const val KEY_PREFIX = "racket_arm:"
        const val KEY_SCOPED_PREFIX = "racket_arm_v2:"
        const val KEY_LEGACY_OWNER = "racket_arm_legacy_owner_v1"
    }
}
