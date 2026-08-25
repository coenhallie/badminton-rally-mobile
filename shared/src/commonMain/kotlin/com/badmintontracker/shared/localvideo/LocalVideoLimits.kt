package com.badmintontracker.shared.localvideo

/**
 * The intake size cap, shared so Android and iOS cannot drift apart on either
 * the number or the wording. Both platforms check this before an entry is
 * created; nothing downstream re-checks, so this is the app's only size gate.
 *
 * The cap only bounds what the client will attempt. Supabase Storage applies
 * its own per-project upload limit at TUS session creation, and an upload above
 * that fails there regardless of what this says.
 */
object LocalVideoLimits {

    /** 10 GiB. */
    const val MAX_SIZE_BYTES: Long = 10_737_418_240L

    /**
     * The user-facing rejection for [sizeBytes], or null when intake may proceed.
     *
     * A [sizeBytes] of 0 means the picker reported no size rather than an empty
     * file, and passes: there is nothing to compare against.
     */
    fun oversizeMessage(sizeBytes: Long): String? =
        if (sizeBytes > MAX_SIZE_BYTES) {
            "Video is larger than 10GB. Please use a shorter recording."
        } else {
            null
        }
}
