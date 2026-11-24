package com.badmintontracker.shared.invites

/** Unambiguous Crockford-style alphabet (omits 0, O, 1, I, L). */
const val INVITE_CODE_ALPHABET: String = "23456789ABCDEFGHJKMNPQRSTUVWXYZ"
const val INVITE_CODE_LENGTH: Int = 8
const val INVITE_QR_PREFIX: String = "shuttl-invite:"

private val ALLOWED_CHARS: Set<Char> = INVITE_CODE_ALPHABET.toSet()

/**
 * Strips whitespace, hyphens, and optional `shuttl-invite:` prefix, uppercases,
 * and returns the 8-char code when valid or null otherwise.
 */
fun normalizeInviteCode(raw: String): String? {
    val trimmed = raw.trim()
    val withoutPrefix = if (trimmed.startsWith(INVITE_QR_PREFIX, ignoreCase = true)) {
        trimmed.substring(INVITE_QR_PREFIX.length)
    } else {
        trimmed
    }
    val cleaned = buildString {
        for (ch in withoutPrefix) {
            if (ch.isLetterOrDigit()) append(ch.uppercaseChar())
        }
    }
    if (cleaned.length != INVITE_CODE_LENGTH) return null
    return if (cleaned.all { it in ALLOWED_CHARS }) cleaned else null
}

/** Formats an 8-character code as `XXXX-XXXX` for display. */
fun formatInviteCode(code: String): String {
    val cleaned = buildString {
        for (ch in code) {
            if (ch.isLetterOrDigit()) append(ch.uppercaseChar())
        }
    }
    return if (cleaned.length > 4) {
        "${cleaned.substring(0, 4)}-${cleaned.substring(4, minOf(cleaned.length, INVITE_CODE_LENGTH))}"
    } else {
        cleaned
    }
}

/**
 * Formats live user typing into an uppercased `XXXX-XXXX` string capped at 8
 * alphanumeric characters. Does not reject characters mid-edit so typing feels responsive.
 */
fun formatInviteInput(raw: String): String {
    val trimmed = raw.trimStart()
    val body = if (trimmed.startsWith(INVITE_QR_PREFIX, ignoreCase = true)) {
        trimmed.substring(INVITE_QR_PREFIX.length)
    } else {
        trimmed
    }
    val chars = buildString {
        for (ch in body) {
            if (ch.isLetterOrDigit() && length < INVITE_CODE_LENGTH) {
                append(ch.uppercaseChar())
            }
        }
    }
    return if (chars.length > 4) "${chars.substring(0, 4)}-${chars.substring(4)}" else chars
}

/** One box of the code entry: the character typed into it ("" while empty), and whether a code can contain it. */
data class InviteCodeCell(val text: String, val isAllowed: Boolean)

/**
 * What has been typed into the invite code boxes: at most [INVITE_CODE_LENGTH]
 * uppercased letters and digits, with the QR prefix, hyphens and spaces dropped.
 * Both apps draw their boxes from this, so they agree on what each box shows,
 * which boxes are flagged, and when the code is complete.
 */
class InviteCodeEntry internal constructor(val chars: String) {
    /** Always [INVITE_CODE_LENGTH] boxes, filled from the left. */
    val cells: List<InviteCodeCell> = List(INVITE_CODE_LENGTH) { i ->
        val ch = chars.getOrNull(i)
        InviteCodeCell(text = ch?.toString() ?: "", isAllowed = ch == null || ch in ALLOWED_CHARS)
    }

    /** True when any box holds a character no code contains (0, O, 1, I, L). */
    val hasDisallowedChar: Boolean = chars.any { it !in ALLOWED_CHARS }

    /** The code, once all eight boxes hold characters a code can contain; null until then. */
    val completeCode: String? = normalizeInviteCode(chars)
}

/** Reads typed or pasted text into the code boxes; see [InviteCodeEntry]. */
fun inviteCodeEntry(raw: String): InviteCodeEntry =
    InviteCodeEntry(formatInviteInput(raw).filter { it != '-' })

/** Encodes a code into the app's QR payload (`shuttl-invite:K7M4QP9R`). */
fun qrPayloadForCode(code: String): String {
    val normalized = normalizeInviteCode(code)
        ?: code.filter { it.isLetterOrDigit() }.uppercase()
    return "$INVITE_QR_PREFIX$normalized"
}

/**
 * Extracts a valid normalized 8-char invite code from a scanned QR string.
 * Accepts `shuttl-invite:<CODE>` or a bare valid 8-char code; rejects URLs and
 * unrelated QR payloads.
 */
fun parseQrInvitePayload(raw: String): String? {
    val trimmed = raw.trim()
    if (trimmed.contains("://")) return null
    return normalizeInviteCode(trimmed)
}
