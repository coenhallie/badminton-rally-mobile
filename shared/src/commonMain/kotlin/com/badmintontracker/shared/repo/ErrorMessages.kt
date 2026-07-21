package com.badmintontracker.shared.repo

import io.github.jan.supabase.exceptions.RestException

/**
 * A message safe to show in a dialog/snackbar. Keeps short meaningful messages
 * ("Not signed in"), but replaces the unusable ones with [fallback]:
 * supabase-kt transport errors (the full request URL, often with a blank tail)
 * and RestException's multi-line Code/Hint/Details/URL/Headers dump.
 */
fun Throwable.userFacingMessage(fallback: String): String {
    if (this is RestException) return "HTTP $statusCode — $error"
    val m = message?.trim()
    return if (m.isNullOrEmpty() || '\n' in m || m.startsWith("HTTP request to")) fallback else m
}
