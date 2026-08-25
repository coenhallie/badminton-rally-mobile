package com.badmintontracker.shared.localvideo

/** Web's MAX_TITLE_LENGTH, so a name typed on either client survives the other. */
const val MAX_MATCH_TITLE_LENGTH = 60
const val MAX_MATCH_DESCRIPTION_LENGTH = 500

/**
 * Normalize user input for [LocalVideoEntry.title] / [LocalVideoEntry.description].
 *
 * Over-long input is truncated rather than rejected, matching the web app's
 * `matchTitle.trim().slice(0, MAX_TITLE_LENGTH) || null`. Order is fixed: trim,
 * truncate to the cap, then trim the tail again so the cut cannot leave a trailing
 * space. Both input fields also cap typing at the same length, so truncation is a
 * guard against paste rather than the normal path.
 *
 * Blank becomes null and never "": videos_title_length_check rejects the empty
 * string, and a rejected insert reaches the user as a CREATE_ROW pipeline failure
 * with nothing actionable in it. The live project may carry no CHECK at all, so
 * this normalization is the real guard, not the database.
 */
fun normalizeTitle(raw: String): String? = normalize(raw, MAX_MATCH_TITLE_LENGTH)

fun normalizeDescription(raw: String): String? = normalize(raw, MAX_MATCH_DESCRIPTION_LENGTH)

private fun normalize(raw: String, max: Int): String? =
    raw.trim().take(max).trimEnd().ifEmpty { null }
