package com.badmintontracker.android.ui

import android.content.Context
import android.content.Intent

/**
 * Hands [text] to the system share sheet.
 *
 * Plain text rather than a file: the match export is meant to be pasted into a
 * message the same evening, and a chooser that offers every messaging app is
 * exactly the right shape for that.
 */
fun shareText(context: Context, subject: String, text: String) {
    val send = Intent(Intent.ACTION_SEND).apply {
        type = "text/plain"
        putExtra(Intent.EXTRA_SUBJECT, subject)
        putExtra(Intent.EXTRA_TEXT, text)
    }
    context.startActivity(Intent.createChooser(send, "Share match"))
}
