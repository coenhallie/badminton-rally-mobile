package com.badmintontracker.android.cliplist

import androidx.compose.foundation.clickable
import androidx.compose.foundation.indication
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
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
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.ripple
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
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
import com.badmintontracker.shared.matchlist.MatchListCopy
import com.badmintontracker.shared.matchlist.MatchListItem
import com.badmintontracker.shared.model.RallyClip
import com.badmintontracker.shared.repo.MediaRepository

/** One entry in a row's overflow menu, in the order the menu lists them. */
data class RowMenuItem(val label: String, val onClick: () -> Unit)

/**
 * One match on the list: thumbnail, title, detail, players, the
 * status line, then whatever the match is asking of the coach.
 *
 * The list keeps exactly one raised surface, and since it became one list it
 * marks "needs something from you" ([MatchListItem.isRaised]) rather
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
    /** [ClipListState.refreshGeneration]: a failed cover tries again when it moves. */
    refreshGeneration: Int = 0,
) {
    DrawerRowContainer(
        shape = RoundedCornerShape(if (item.isRaised) ShuttlRadius.large else ShuttlRadius.medium),
        fill = if (item.isRaised) ShuttlTheme.extended.bgTertiary else MaterialTheme.colorScheme.background,
        swipeLabel = swipeLabel,
        // false: the caller confirms first, so the row must spring back.
        onSwiped = { onSwiped(); false },
    ) {
        MatchListRowContent(item, media, onClick, onAction, menu, refreshGeneration)
    }
}

@Composable
private fun MatchListRowContent(
    item: MatchListItem,
    media: MediaRepository,
    onClick: () -> Unit,
    onAction: () -> Unit,
    menu: List<RowMenuItem>,
    refreshGeneration: Int,
) {
    val hasMenu = menu.isNotEmpty()
    // The overflow glyph sits in one column with the icons above the list, the
    // Settings gear and the legend's info button, whose centres are 36dp from
    // the screen edge (12dp inside the list's 24dp margin). A row's card ends
    // at that margin, so the menu takes the row's whole end inset and draws
    // its glyph at the end of its 48dp target, 12dp from the card's edge; see
    // RowMenu. It had sat 16dp in, in line with the thumbnail's inset, which
    // left a column of dots 16dp short of the icons over them. Whatever spans
    // the row's full width below takes the inset back, so it still ends flush
    // with the card's right inset.
    val menuCompensation = if (hasMenu) DrawerList.rowPaddingH else 0.dp
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
            MatchThumbnail(item, media, refreshGeneration)
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
        MatchListCopy.actionLabel(item.action)?.let { label ->
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

/**
 * The row's overflow menu: a full 48dp touch target that ends at the card's
 * edge, with the glyph drawn at its end rather than its centre, so the glyph
 * lines up with the header icons (see MatchListRowContent) while the whole
 * target stays inside the card, whose clip would otherwise cut into it. The
 * ripple is centred on the glyph, not on the target.
 */
@Composable
private fun RowMenu(menu: List<RowMenuItem>) {
    var open by remember { mutableStateOf(false) }
    val interaction = remember { MutableInteractionSource() }
    // The menu must share a Box with its anchor: DropdownMenu positions itself
    // relative to its parent.
    Box {
        Box(
            modifier = Modifier
                .size(48.dp)
                .clickable(interactionSource = interaction, indication = null, role = Role.Button) { open = true }
                .semantics { contentDescription = MatchListCopy.MATCH_MENU },
            contentAlignment = Alignment.CenterEnd,
        ) {
            Icon(
                Icons.Default.MoreVert,
                contentDescription = null,
                modifier = Modifier.indication(interaction, ripple(bounded = false, radius = 20.dp)),
            )
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
 * cover clip, then the score, then a plain tile: first source that exists
 * AND loads wins. A local frame that fails (the gallery file deleted, the
 * permission revoked) falls through to the cover clip rather than leaving a
 * hole in a row that has a perfectly good cloud thumbnail.
 *
 * Every state sits on the same tile: a fill one step off the row's own
 * surface and a 1dp ring, drawn behind the picture. While a picture loads the
 * tile reads as an empty slot; once every source has failed it carries the
 * score or a glyph. On the raised card the fill is the page colour: the card
 * is bgTertiary itself, and a slot in outlineVariant there all but vanished
 * in dark (1A1D1E on 161819).
 */
@Composable
private fun MatchThumbnail(item: MatchListItem, media: MediaRepository, refreshGeneration: Int) {
    val shape = RoundedCornerShape(ShuttlRadius.small)
    val tileFill = if (item.isRaised) MaterialTheme.colorScheme.background else ShuttlTheme.extended.bgTertiary
    val ring = MaterialTheme.colorScheme.outline
    var localFailed by remember(item.localUri) { mutableStateOf(false) }
    val cover = item.coverClip
    // Keyed on the refresh as well as the path: a refresh keeps the path, and
    // keyed on it alone a cover that failed once (signing offline) would never
    // be asked for again. The local frame is not retried: its failures (a
    // deleted file, a revoked permission) do not heal on a refresh.
    var coverFailed by remember(cover?.thumbnailStoragePath, refreshGeneration) { mutableStateOf(false) }
    val localUri = item.localUri?.takeUnless { localFailed }
    val coverToLoad = cover?.takeUnless { coverFailed || it.thumbnailStoragePath == null }
    Box(
        Modifier
            .size(DrawerList.thumbWidth, DrawerList.thumbHeight)
            .clip(shape)
            .drawBehind {
                drawRect(tileFill)
                val stroke = 1.dp.toPx()
                val radius = ShuttlRadius.small.toPx() - stroke / 2
                drawRoundRect(
                    color = ring,
                    topLeft = Offset(stroke / 2, stroke / 2),
                    size = Size(size.width - stroke, size.height - stroke),
                    cornerRadius = CornerRadius(radius, radius),
                    style = Stroke(width = stroke),
                )
            },
        contentAlignment = Alignment.Center,
    ) {
        when {
            localUri != null -> AsyncImage(
                model = localUri,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                onError = { localFailed = true },
                modifier = Modifier.matchParentSize(),
            )
            coverToLoad != null -> CloudThumbnail(coverToLoad, media, onFailed = { coverFailed = true })
            else -> when (thumbnailFallback(hasVideo = item.localUri != null || cover != null, hasScore = item.scoreText != null)) {
                ThumbnailFallback.SCORE -> Text(
                    item.scoreText.orEmpty(),
                    style = MaterialTheme.typography.titleSmall.copy(fontFeatureSettings = "tnum"),
                    color = MaterialTheme.colorScheme.onBackground,
                    maxLines = 1,
                )
                ThumbnailFallback.VIDEO_GLYPH -> TileGlyph(ShuttlIcons.Video)
                ThumbnailFallback.SCORE_GLYPH -> TileGlyph(ShuttlIcons.Hash)
            }
        }
    }
}

/** What a thumbnail tile shows once every picture has failed or there was none. */
internal enum class ThumbnailFallback { SCORE, VIDEO_GLYPH, SCORE_GLYPH }

/**
 * A row with a video whose picture would not load is still a video: it gets
 * the video glyph, never the score's "#", which would read as a scored match.
 * The score itself wins when there is one (the tile has room for "21-18"), and
 * "#" is left for a scored match nobody has started, with no video at all.
 */
internal fun thumbnailFallback(hasVideo: Boolean, hasScore: Boolean): ThumbnailFallback = when {
    hasScore -> ThumbnailFallback.SCORE
    hasVideo -> ThumbnailFallback.VIDEO_GLYPH
    else -> ThumbnailFallback.SCORE_GLYPH
}

@Composable
private fun TileGlyph(icon: ImageVector) {
    Icon(icon, contentDescription = null, modifier = Modifier.size(16.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
}

/**
 * The cover clip's thumbnail. Cached by its storage path, not by the URL:
 * the signed URL changes every hour, and keyed on it Coil would fetch the same
 * picture again each time the signature rolled over. [onFailed] when there is
 * no URL to be had or the picture does not load.
 */
@Composable
private fun BoxScope.CloudThumbnail(clip: RallyClip, media: MediaRepository, onFailed: () -> Unit) {
    val path = clip.thumbnailStoragePath
    val context = LocalPlatformContext.current
    var url by remember(path) { mutableStateOf<String?>(null) }
    LaunchedEffect(path) {
        // Never throws: a thumbnail is decorative, and the repository returns
        // null for anything that goes wrong.
        media.signedThumbnailUrl(clip)?.let { url = it } ?: onFailed()
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
    if (request != null) {
        AsyncImage(
            model = request,
            contentDescription = null,
            contentScale = ContentScale.Crop,
            onError = { onFailed() },
            modifier = Modifier.matchParentSize(),
        )
    }
}
