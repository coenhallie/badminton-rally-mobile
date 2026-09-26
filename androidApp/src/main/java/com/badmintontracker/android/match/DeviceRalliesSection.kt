package com.badmintontracker.android.match

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.badmintontracker.android.localanalysis.ClipCutter
import com.badmintontracker.android.localanalysis.LocalAnalysisRunner
import com.badmintontracker.android.localanalysis.LocalClipPlayerDialog
import com.badmintontracker.android.localanalysis.PanelMessage
import com.badmintontracker.android.ui.icons.ShuttlIcons
import com.badmintontracker.android.ui.theme.ShuttlRadius
import com.badmintontracker.shared.matchpage.MatchPageCopy

/**
 * The rallies an on-device run cut, as the Rallies section shows them until
 * the 2026-09-11 design turns them into real rally rows.
 *
 * Only for a match with no cloud clips: when both exist the cloud clips are
 * the rallies, the rule the row's own rally count follows. Plain rows, with
 * no thumbnail or note count, because device clips have neither yet.
 */
@Composable
fun DeviceRalliesSection(entryId: String, localAnalysis: LocalAnalysisRunner, modifier: Modifier = Modifier) {
    // Read once per entry: a sidecar parse and one stat per clip, and the set
    // cannot grow while the section is up without a run this page would show.
    val clips = remember(entryId) { localAnalysis.storedClips(entryId) }
    var playing by remember { mutableStateOf<ClipCutter.Clip?>(null) }
    playing?.let { LocalClipPlayerDialog(clip = it, onDismiss = { playing = null }) }
    val shape = RoundedCornerShape(ShuttlRadius.large)
    LazyColumn(modifier.fillMaxSize(), contentPadding = PaddingValues(vertical = 12.dp)) {
        if (clips.isEmpty()) item(key = "empty") { PanelMessage(MatchPageCopy.DEVICE_CLIPS_EMPTY) }
        items(clips, key = { it.index }) { clip ->
            Row(
                Modifier
                    .fillMaxWidth()
                    .padding(start = 24.dp, end = 24.dp, top = 8.dp)
                    .clip(shape)
                    .background(MaterialTheme.colorScheme.surfaceVariant, shape)
                    .clickable(role = Role.Button) { playing = clip }
                    .padding(horizontal = 20.dp, vertical = 14.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    deviceClipLabel(clip),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onBackground,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
                Icon(ShuttlIcons.Play, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(16.dp))
            }
        }
    }
}

/**
 * "Rally 3  12.3s - 19.9s  (7.5s)", or the rally number alone for a clip
 * recovered by filename, whose bounds are unknown: "0.0s - 0.0s" would read as
 * a broken clip rather than as missing bookkeeping. iOS's `localClipLabel`.
 */
internal fun deviceClipLabel(clip: ClipCutter.Clip): String =
    if (clip.endSeconds > clip.startSeconds) {
        "Rally ${clip.index}  ${"%.1f".format(clip.startSeconds)}s - ${"%.1f".format(clip.endSeconds)}s  " +
            "(${"%.1f".format(clip.endSeconds - clip.startSeconds)}s)"
    } else {
        "Rally ${clip.index}"
    }
