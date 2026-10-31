package com.badmintontracker.android.localvideo

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.badmintontracker.shared.localvideo.LocalVideoRepository
import com.badmintontracker.shared.localvideo.normalizeDescription
import com.badmintontracker.shared.localvideo.normalizeTitle
import com.badmintontracker.shared.localvideo.setDetails

/**
 * The "Match details" sheet that opens on its own over a video just recorded or
 * imported with no match: whatever screen the coach came back to (Players, a
 * player's page, All matches), so it sits above the NavHost rather than in any
 * one destination.
 *
 * [entryId] is the entry to name, held by the caller until [onClose]: the
 * finished-run watcher reads it as "something is above", so a run's View is
 * not offered over a half-typed name. The entry is persisted before this is
 * asked to open, so a dismissed sheet, a backgrounded app or a crash never
 * costs the coach the video. An entry no longer on the phone closes the sheet
 * rather than leaving the caller waiting on it.
 *
 * Dismissing is "Skip" (autoOpened): straight after taking the video this is
 * something to get past. The row menu's own "Edit details" stays on the list.
 */
@Composable
fun AutoMatchDetailsHost(
    entryId: String?,
    localVideos: LocalVideoRepository,
    onClose: () -> Unit,
) {
    entryId ?: return
    val entries by localVideos.entries.collectAsStateWithLifecycle()
    val entry = entries.firstOrNull { it.id == entryId }
    if (entry == null) {
        LaunchedEffect(entryId) { onClose() }
        return
    }
    // Keyed on the entry, so the fields always start from this entry's own.
    key(entry.id) {
        MatchDetailsSheet(
            initialTitle = entry.title,
            initialDescription = entry.description,
            autoOpened = true,
            onDismiss = onClose,
            onSave = { title, description ->
                localVideos.setDetails(entry.id, normalizeTitle(title), normalizeDescription(description))
                onClose()
            },
        )
    }
}
