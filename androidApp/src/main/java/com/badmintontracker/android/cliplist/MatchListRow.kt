package com.badmintontracker.android.cliplist

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import coil3.compose.LocalPlatformContext
import coil3.request.ImageRequest
import com.badmintontracker.android.ui.components.ShuttlButton
import com.badmintontracker.android.ui.components.ShuttlButtonVariant
import com.badmintontracker.android.ui.icons.ShuttlIcons
import com.badmintontracker.android.ui.theme.ShuttlRadius
import com.badmintontracker.android.ui.theme.ShuttlTheme
import com.badmintontracker.shared.matchlist.MatchAction
import com.badmintontracker.shared.matchlist.MatchListItem
import com.badmintontracker.shared.model.RallyClip
import com.badmintontracker.shared.repo.MediaRepository

/** One entry in a row's overflow menu, in the order the menu lists them. */
data class RowMenuItem(val label: String, val onClick: () -> Unit)

/**
 * One match in the drawer: thumbnail, title, detail, players, the status line,
 * then whatever the match is asking of the coach.
 *
 * The drawer keeps exactly one raised surface, and since the list became one
 * list it marks "needs something from you" ([MatchListItem.isRaised]) rather
 * than "on this phone", which the status line now says. Every other row is
 * the quiet inset box, transparent until pressed.
 *
 * [swipeLabel] null renders the same box without the gesture: a row mid-run,
 * which nothing may delete from under it.
 */
@Composable
internal fun MatchListRow(
    item: MatchListItem,
    media: MediaRepository,
    onClick: () -> Unit,
    onAction: () -> Unit,
    menu: List<RowMenuItem>,
    swipeLabel: String?,
    onSwiped: () -> Unit,
) {
    DrawerRowContainer(
        shape = RoundedCornerShape(if (item.isRaised) ShuttlRadius.large else ShuttlRadius.medium),
        fill = if (item.isRaised) ShuttlTheme.extended.bgTertiary else MaterialTheme.colorScheme.background,
        swipeLabel = swipeLabel,
        // false: the caller confirms first, so the row must spring back.
        onSwiped = { onSwiped(); false },
    ) {
        MatchListRowContent(item, media, onClick, onAction, menu)
    }
}

@Composable
private fun MatchListRowContent(
    item: MatchListItem,
    media: MediaRepository,
    onClick: () -> Unit,
    onAction: () -> Unit,
    menu: List<RowMenuItem>,
) {
    val hasMenu = menu.isNotEmpty()
    // An IconButton carries 12dp of its own padding inside a 48dp box. Backing
    // that out of the row's end inset is what puts the glyph the same 16dp from
    // the card's edge as the thumbnail on the other side, instead of an optical
    // 28dp that reads as a misaligned card. Whatever spans the row's full width
    // below adds the 12dp back, so it ends flush with the card's right inset.
    val menuCompensation = if (hasMenu) 12.dp else 0.dp
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(
                start = DrawerList.rowPaddingH,
                end = DrawerList.rowPaddingH - menuCompensation,
                top = DrawerList.rowPaddingV,
                bottom = DrawerList.rowPaddingV,
            ),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            MatchThumbnail(item, media)
            Spacer(Modifier.width(DrawerList.gap))
            Column(Modifier.weight(1f)) {
                RowText(item.title, primary = true)
                RowText(item.detail)
                // Absent until somebody is named: "Home vs Away" on every row
                // would say nothing.
                item.playersLine?.let { RowText(it) }
                item.sharedBy?.let { RowText(it) }
                if (item.hasStatusLine) {
                    MatchStatusLine(item, Modifier.padding(top = 4.dp))
                }
            }
            if (hasMenu) RowMenu(menu)
        }
        item.statusText?.takeIf { item.statusIsError }?.let { failure ->
            Text(
                text = failure,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
                // Two, not one: a pipeline's reason is a sentence, and cut to
                // one line it loses the part that says what to do.
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(
                    // Under the text column, not under the thumbnail.
                    start = DrawerList.thumbWidth + DrawerList.gap,
                    end = menuCompensation,
                    top = 4.dp,
                ),
            )
        }
        actionLabel(item.action)?.let { label ->
            // Its own line, not a trailing pill: beside the overflow menu a pill
            // left the title about four characters wide. Full width is also how
            // Home states its two primary actions, so the card reads as one
            // clear next step.
            Spacer(Modifier.height(DrawerList.gap))
            ShuttlButton(
                text = label,
                onClick = onAction,
                variant = ShuttlButtonVariant.Primary,
                compact = true,
                modifier = Modifier.fillMaxWidth().padding(end = menuCompensation),
            )
        }
    }
}

/**
 * The button for what the row asks. A failed run is "Retry" even when it will
 * resume without court marking: the caller decides what retrying does.
 */
private fun actionLabel(action: MatchAction): String? = when (action) {
    MatchAction.ANALYZE -> "Analyze"
    MatchAction.RETRY -> "Retry"
    MatchAction.MARK_COURT -> "Mark court"
    // Running and paused speak through the status line; nothing to press.
    MatchAction.NONE, MatchAction.RUNNING, MatchAction.PAUSED -> null
}

@Composable
private fun RowText(text: String, primary: Boolean = false) {
    Text(
        text,
        style = if (primary) MaterialTheme.typography.titleMedium else MaterialTheme.typography.bodySmall,
        color = if (primary) MaterialTheme.colorScheme.onBackground else MaterialTheme.colorScheme.onSurfaceVariant,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
    )
}

@Composable
private fun RowMenu(menu: List<RowMenuItem>) {
    var open by remember { mutableStateOf(false) }
    // The menu must share a Box with its anchor: DropdownMenu positions itself
    // relative to its parent, not the IconButton.
    Box {
        IconButton(onClick = { open = true }) {
            Icon(Icons.Default.MoreVert, contentDescription = "Match menu")
        }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            menu.forEach { entry ->
                DropdownMenuItem(
                    text = { Text(entry.label) },
                    onClick = { open = false; entry.onClick() },
                )
            }
        }
    }
}

/**
 * The file on this phone first (its own frame, no network), then the cloud's
 * cover clip, then the score, then a plain tile: first source that exists wins.
 */
@Composable
private fun MatchThumbnail(item: MatchListItem, media: MediaRepository) {
    val shape = RoundedCornerShape(ShuttlRadius.small)
    val frame = Modifier
        .size(DrawerList.thumbWidth, DrawerList.thumbHeight)
        .clip(shape)
    // A thumbnail that has not decoded yet would otherwise be a card-coloured
    // hole; this reads as an empty slot instead.
    val slot = frame.background(MaterialTheme.colorScheme.outlineVariant)
    val localUri = item.localUri
    val cover = item.coverClip
    val scoreText = item.scoreText
    when {
        localUri != null -> AsyncImage(
            model = localUri,
            contentDescription = null,
            contentScale = ContentScale.Crop,
            modifier = slot,
        )
        cover != null -> CloudThumbnail(cover, media, slot)
        else -> {
            // A tile, not a slot: nothing is loading into it. On the raised
            // card bgTertiary would be the card itself, so the tile takes the
            // page colour there to still read as a tile.
            val tileFill = if (item.isRaised) MaterialTheme.colorScheme.background else ShuttlTheme.extended.bgTertiary
            Box(
                frame
                    .background(tileFill)
                    .border(1.dp, MaterialTheme.colorScheme.outline, shape),
                contentAlignment = Alignment.Center,
            ) {
                if (scoreText != null) {
                    Text(
                        scoreText,
                        style = MaterialTheme.typography.titleSmall.copy(fontFeatureSettings = "tnum"),
                        color = MaterialTheme.colorScheme.onBackground,
                        maxLines = 1,
                    )
                } else {
                    Icon(
                        ShuttlIcons.Hash,
                        contentDescription = null,
                        modifier = Modifier.size(16.dp),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}

/**
 * The cover clip's thumbnail. Cached by its storage path, not by the URL:
 * the signed URL changes every hour, and keyed on it Coil would fetch the same
 * picture again each time the signature rolled over.
 */
@Composable
private fun CloudThumbnail(clip: RallyClip, media: MediaRepository, modifier: Modifier) {
    val path = clip.thumbnailStoragePath
    val context = LocalPlatformContext.current
    val url by produceState<String?>(initialValue = null, path) {
        // Never throws: a thumbnail is decorative, and the repository returns
        // null for anything that goes wrong.
        value = media.signedThumbnailUrl(clip)
    }
    val request = remember(url, path) {
        url?.let {
            ImageRequest.Builder(context)
                .data(it)
                .memoryCacheKey(path)
                .diskCacheKey(path)
                .build()
        }
    }
    AsyncImage(
        model = request,
        contentDescription = null,
        contentScale = ContentScale.Crop,
        modifier = modifier,
    )
}
