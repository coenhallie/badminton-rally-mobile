package com.badmintontracker.android.match

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import com.badmintontracker.android.localanalysis.ClipCutter
import com.badmintontracker.android.localanalysis.LocalAnalysisRunner
import com.badmintontracker.android.localanalysis.LocalClipPlayerDialog
import com.badmintontracker.android.localanalysis.PanelMessage
import com.badmintontracker.shared.matchlist.rallyLengthText
import com.badmintontracker.shared.matchpage.MatchPageCopy
import java.util.Locale

/**
 * The rallies an on-device run cut, as the Rallies section shows them until
 * the 2026-09-11 design turns them into real rally rows.
 *
 * Only for a match with no cloud clips: when both exist the cloud clips are
 * the rallies, the rule the row's own rally count follows. Drawn as the cloud
 * rally rows are (full-bleed rows under dividers, the rally's name over a
 * small caps line, the page's gutter) so a match's rallies look the same
 * wherever they were cut, less the thumbnail and the note count, which device
 * clips do not have yet. They had been rounded cards with a play glyph, which
 * read as a different kind of thing from the cloud list one match over.
 */
@Composable
fun DeviceRalliesSection(entryId: String, localAnalysis: LocalAnalysisRunner, modifier: Modifier = Modifier) {
    // Read once per entry: a sidecar parse and one stat per clip, and the set
    // cannot grow while the section is up without a run this page would show.
    val clips = remember(entryId) { localAnalysis.storedClips(entryId) }
    var playing by remember { mutableStateOf<ClipCutter.Clip?>(null) }
    playing?.let { LocalClipPlayerDialog(clip = it, title = deviceClipLabel(it), onDismiss = { playing = null }) }
    LazyColumn(modifier.fillMaxSize()) {
        if (clips.isEmpty()) item(key = "empty") { PanelMessage(MatchPageCopy.DEVICE_CLIPS_EMPTY) }
        items(clips, key = { it.index }) { clip ->
            Column(
                Modifier
                    .fillMaxWidth()
                    .clickable(role = Role.Button) { playing = clip }
                    .padding(horizontal = MatchPageGutter, vertical = 14.dp),
            ) {
                Text(
                    deviceClipTitle(clip),
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onBackground,
                )
                deviceClipLength(clip)?.let { length ->
                    Text(
                        length.uppercase(Locale.ROOT),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            HorizontalDivider(color = RallyDivider)
        }
    }
}

/**
 * "Rally #3", the cloud rally row's own name for a rally
 * (`clipRowTitle`), so a device rally and a cloud one are named alike.
 */
internal fun deviceClipTitle(clip: ClipCutter.Clip): String = "Rally #${clip.index}"

/**
 * "7.5s" through the shared formatter the cloud rows use, or null for a clip
 * recovered by filename, whose bounds are unknown: "0.0s" would read as a
 * broken clip rather than as missing bookkeeping.
 */
internal fun deviceClipLength(clip: ClipCutter.Clip): String? =
    if (clip.endSeconds > clip.startSeconds) rallyLengthText(clip.endSeconds - clip.startSeconds) else null

/**
 * The row's two lines on one, "Rally #3 · 7.5s": the clip player's title, so
 * the dialog names the rally the way the row that opened it did.
 */
internal fun deviceClipLabel(clip: ClipCutter.Clip): String =
    listOfNotNull(deviceClipTitle(clip), deviceClipLength(clip)).joinToString(" · ")
