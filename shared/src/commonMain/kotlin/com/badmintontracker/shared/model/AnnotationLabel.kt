package com.badmintontracker.shared.model

import kotlinx.datetime.Instant
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/** A label the signed-in user owns and may apply to annotations. */
@Serializable
data class AnnotationLabel(
    val id: String,
    val name: String,
    @SerialName("color_key")  val colorKey: String,
    @SerialName("created_at") val createdAt: Instant,
    /**
     * The raw `usage` column, not a @Serializable enum. The palette is fetched
     * with decodeList, so a value written by a newer build would take the whole
     * list down rather than one row; keeping it a string contains that to the
     * one row and lets [scope] decide what to do about it. The default covers a
     * payload with no `usage` key at all - the on-disk cache written by the
     * build before this column existed.
     */
    val usage: String = LabelUsage.BOTH.key,
) {
    /** Null when the stored key is not in this build's palette. */
    val color: LabelColor? get() = LabelColor.from(colorKey)

    /**
     * Where this label may be offered. An unknown key resolves to
     * [LabelUsage.BOTH]: the safe direction is a label that shows up somewhere
     * unexpected, not one that has disappeared with no way to find it.
     */
    val scope: LabelUsage get() = LabelUsage.from(usage) ?: LabelUsage.BOTH
}
