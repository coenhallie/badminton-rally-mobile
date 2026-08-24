package com.badmintontracker.shared.model

/**
 * The colours a label may take, shared so Android, iOS and the DB CHECK agree on
 * one set. Values are ARGB: Android maps them with Color(value), iOS with the
 * Color(rgb:) helper.
 *
 * Theme independent by design. Each entry is a solid saturated fill with a
 * foreground picked to clear 4.5:1 against it, which reads correctly on both the
 * light (#FFFFFF) and dark (#0D0D0D) app backgrounds. GREEN, AMBER and RED carry
 * the exact values the three original badges shipped with.
 */
enum class LabelColor(
    val key: String,
    val background: Long,
    val foreground: Long,
) {
    GREEN( "green",  0xFF2E7D32, WHITE),
    TEAL(  "teal",   0xFF00695C, WHITE),
    BLUE(  "blue",   0xFF1565C0, WHITE),
    INDIGO("indigo", 0xFF283593, WHITE),
    PURPLE("purple", 0xFF6A1B9A, WHITE),
    PINK(  "pink",   0xFFAD1457, WHITE),
    RED(   "red",    0xFFC62828, WHITE),
    ORANGE("orange", 0xFFEF6C00, BLACK),
    AMBER( "amber",  0xFFB26A00, BLACK),
    SLATE( "slate",  0xFF37474F, WHITE),
    ;

    companion object {
        /** Declaration order, which is also the order the swatch grid renders. */
        val PALETTE: List<LabelColor> = entries.toList()

        /**
         * Resolves a stored key. Returns null rather than throwing, so a snapshot
         * naming a swatch this build does not know renders as a neutral chip
         * instead of taking down the whole annotation list.
         */
        fun from(key: String?): LabelColor? = entries.firstOrNull { it.key == key }
    }
}

private const val WHITE = 0xFFFFFFFF
private const val BLACK = 0xFF000000
