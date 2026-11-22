package com.badmintontracker.android.players

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Snackbar
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.badmintontracker.android.cliplist.ClipListViewModel
import com.badmintontracker.android.cliplist.DrawerList
import com.badmintontracker.android.cliplist.DrawerRowContainer
import com.badmintontracker.android.home.ADD_BUTTON_CLEARANCE
import com.badmintontracker.android.home.AddMatchButton
import com.badmintontracker.android.home.AddMatchSheet
import com.badmintontracker.android.home.HomeTopBar
import com.badmintontracker.android.home.HorizontalSafeDrawing
import com.badmintontracker.android.home.addButtonExpanded
import com.badmintontracker.android.localanalysis.BackgroundWorkAction
import com.badmintontracker.android.ui.components.ShuttlButton
import com.badmintontracker.android.ui.components.ShuttlButtonVariant
import com.badmintontracker.android.ui.components.ShuttlEmptyState
import com.badmintontracker.android.ui.icons.ShuttlIcons
import com.badmintontracker.android.ui.theme.ShuttlRadius
import com.badmintontracker.android.ui.theme.ShuttlTheme
import com.badmintontracker.shared.matchpage.MatchPageCopy
import com.badmintontracker.shared.players.Player
import com.badmintontracker.shared.players.PlayersCopy
import com.badmintontracker.shared.players.playerRowDetail
import com.badmintontracker.shared.players.playerSummary
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

/**
 * The root screen: one row per player, an "Add player" row, the way to All
 * matches, and the Add button. Every other route pushes over the whole of it.
 * It draws the app's one root snackbar host ([snackbarHostState]), which
 * RootAnnouncementsHost also posts to: finished runs, record and import
 * failures, a failed match refresh.
 *
 * Each row's detail is read off the match list ([clipListVm], the app's one
 * instance), the same list a player's Matches tab filters, so the count on the
 * row and the matches behind it cannot disagree.
 *
 * Mirrors iosApp's PlayersScreen.swift.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PlayersScreen(
    vm: PlayersViewModel,
    clipListVm: ClipListViewModel,
    /** Any sheet or dialog over the screen: see AuthGate's finished-run watcher. */
    onOverlayChange: (Boolean) -> Unit,
    onOpenPlayer: (playerId: String) -> Unit,
    onAllMatches: () -> Unit,
    onSettings: () -> Unit,
    onNewMatch: () -> Unit,
    onRecord: () -> Unit,
    onImport: () -> Unit,
    /** AuthGate's one root host: see RootAnnouncementsHost. */
    snackbarHostState: SnackbarHostState,
) {
    val state by vm.state.collectAsStateWithLifecycle()
    val listState by clipListVm.state.collectAsStateWithLifecycle()
    val matchList = listState.matchList
    // Both saved, so a recreation brings back whichever was open.
    var addSheetOpen by rememberSaveable { mutableStateOf(false) }
    var addPlayerOpen by rememberSaveable { mutableStateOf(false) }
    // The add dialog's save: in flight, or failed with this sentence. Not
    // saved: a recreation mid-save drops the wait, not the insert (see
    // PlayersViewModel.create), and the dialog comes back ready to save.
    var addSaving by remember { mutableStateOf(false) }
    var addError by remember { mutableStateOf<String?>(null) }
    // The wait on that save, cancelled when the dialog closes so a late answer
    // cannot land on a dialog opened afterwards. Only the wait: the insert
    // itself runs on the view model's scope.
    var addWait by remember { mutableStateOf<Job?>(null) }
    val scope = rememberCoroutineScope()
    // Hoisted: the Add button reads firstVisibleItemIndex to decide whether
    // to show its label, and it is not a descendant of the list.
    val lazyState = rememberLazyListState()

    // Remembered against the list and the players, not worked out per row on
    // every frame: the match list is rebuilt on each analysis progress tick.
    val details = remember(matchList, state.players) {
        state.players.associate { it.id to playerRowDetail(playerSummary(matchList, it.id)) }
    }

    LaunchedEffect(addSheetOpen, addPlayerOpen) { onOverlayChange(addSheetOpen || addPlayerOpen) }
    // Leaving the screen takes its overlays with it, whatever was last said.
    DisposableEffect(Unit) { onDispose { onOverlayChange(false) } }

    LaunchedEffect(state.errorMessage) {
        val err = state.errorMessage ?: return@LaunchedEffect
        snackbarHostState.showSnackbar(err)
        vm.errorShown()
    }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        // The bottom inset only: HomeTopBar draws under the status bar itself,
        // and with no bottom bar the Scaffold must lift the Add button and the
        // snackbar clear of the gesture bar. The side insets are taken back
        // below, by each of the three that needs them.
        contentWindowInsets = WindowInsets.safeDrawing.only(WindowInsetsSides.Bottom),
        floatingActionButton = {
            val expanded by remember { derivedStateOf { addButtonExpanded(lazyState.firstVisibleItemIndex) } }
            AddMatchButton(
                expanded = expanded,
                onClick = { addSheetOpen = true },
                modifier = Modifier.windowInsetsPadding(HorizontalSafeDrawing),
            )
        },
        // The shape set here, not left to the theme's extraSmall (8dp): the
        // iOS toast is ShuttlRadius.medium, and the two are one message.
        snackbarHost = {
            SnackbarHost(snackbarHostState, Modifier.windowInsetsPadding(HorizontalSafeDrawing)) { data ->
                Snackbar(data, shape = RoundedCornerShape(ShuttlRadius.medium))
            }
        },
    ) { padding ->
        // The last row and All matches must scroll clear of the Add button.
        // `padding` is the gesture inset alone, so the button's own footprint
        // goes on top of it.
        val bottom = padding.calculateBottomPadding() + ADD_BUTTON_CLEARANCE
        Column(Modifier.fillMaxSize().windowInsetsPadding(HorizontalSafeDrawing)) {
            HomeTopBar(PlayersCopy.SCREEN_TITLE) {
                BackgroundWorkAction()
                IconButton(onClick = onSettings) {
                    Icon(
                        ShuttlIcons.Settings,
                        contentDescription = MatchPageCopy.SETTINGS,
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        // The video card's 14-unit gear, drawn at 20dp: its
                        // 1.4 stroke lands on 2dp there, the weight of the
                        // app's 24dp Lucide glyphs.
                        modifier = Modifier.size(20.dp),
                    )
                }
            }
            // weight(1f), not fillMaxSize: measured alongside the title bar,
            // fillMaxSize would claim the bar's height too.
            PullToRefreshBox(
                isRefreshing = state.isRefreshing && (state.loaded || state.players.isNotEmpty()),
                onRefresh = { vm.refresh(); clipListVm.refresh() },
                modifier = Modifier.weight(1f).fillMaxWidth(),
            ) {
                if (state.players.isEmpty()) {
                    if (!state.loaded) {
                        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                            CircularProgressIndicator()
                        }
                    } else if (state.showsNoPlayers) {
                        NoPlayers(
                            loadFailed = state.loadFailed,
                            onRetry = vm::refresh,
                            onAddPlayer = { addPlayerOpen = true },
                            onAllMatches = onAllMatches,
                            bottom = bottom,
                        )
                    }
                } else {
                    LazyColumn(
                        state = lazyState,
                        modifier = Modifier.fillMaxSize(),
                        contentPadding = PaddingValues(
                            top = DrawerList.listTopInset,
                            bottom = DrawerList.sideMargin + bottom,
                        ),
                        verticalArrangement = Arrangement.spacedBy(DrawerList.rowGap),
                    ) {
                        items(state.players, key = { it.id }) { player ->
                            PlayerRow(
                                player = player,
                                detail = details[player.id].orEmpty(),
                                onClick = { onOpenPlayer(player.id) },
                            )
                        }
                        item(key = "add-player") { AddPlayerRow(onClick = { addPlayerOpen = true }) }
                        item(key = "all-matches") {
                            AllMatchesButton(
                                onClick = onAllMatches,
                                // The mock's 12dp above the button, of which
                                // the list's row gap is already half.
                                modifier = Modifier
                                    .padding(horizontal = DrawerList.sideMargin)
                                    .padding(top = 12.dp - DrawerList.rowGap),
                            )
                        }
                    }
                }
            }
        }
    }

    if (addSheetOpen) {
        AddMatchSheet(
            onNewMatch = { addSheetOpen = false; onNewMatch() },
            onRecord = { addSheetOpen = false; onRecord() },
            onImport = { addSheetOpen = false; onImport() },
            onDismiss = { addSheetOpen = false },
        )
    }

    if (addPlayerOpen) {
        fun close() {
            addWait?.cancel()
            addWait = null
            addPlayerOpen = false
            addSaving = false
            addError = null
        }
        PlayerNameDialog(
            title = PlayersCopy.ADD_DIALOG_TITLE,
            initialName = "",
            initialArm = null,
            showArm = true,
            onDismiss = ::close,
            onSave = { name, arm ->
                addSaving = true
                addError = null
                addWait = scope.launch {
                    // The new row appears on its own through the repository's
                    // list; a failure stays in the dialog with the name typed.
                    when (val result = vm.create(name, arm)) {
                        is PlayerCreation.Created -> close()
                        is PlayerCreation.Failed -> {
                            addSaving = false
                            addError = result.message
                        }
                    }
                }
            },
            error = addError,
            saving = addSaving,
        )
    }
}

/**
 * No players to list: the first-run invitation, or, when the load failed with
 * nothing cached, a retry, so a failed load never reads as "no players yet"
 * and invites a duplicate. All matches stays below either way, so a coach with
 * history is never stranded.
 *
 * Scrollable even though it rarely scrolls: at a large font scale in landscape
 * the button would otherwise fall off the bottom. Top-aligned at a fixed
 * offset, as the match list's empty state is.
 */
@Composable
private fun NoPlayers(
    loadFailed: Boolean,
    onRetry: () -> Unit,
    onAddPlayer: () -> Unit,
    onAllMatches: () -> Unit,
    bottom: Dp,
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(top = 48.dp, bottom = bottom),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        if (loadFailed) {
            ShuttlEmptyState(
                icon = ShuttlIcons.PersonStanding,
                title = PlayersCopy.LOAD_FAILED,
                body = PlayersCopy.LOAD_FAILED_BODY,
                action = {
                    ShuttlButton(
                        text = PlayersCopy.RETRY,
                        onClick = onRetry,
                        variant = ShuttlButtonVariant.Secondary,
                        compact = true,
                    )
                },
            )
        } else {
            ShuttlEmptyState(
                icon = ShuttlIcons.PersonStanding,
                title = PlayersCopy.EMPTY_TITLE,
                body = PlayersCopy.EMPTY_BODY,
                action = {
                    ShuttlButton(
                        text = PlayersCopy.ADD_PLAYER,
                        onClick = onAddPlayer,
                        leadingIcon = ShuttlIcons.Plus,
                        compact = true,
                    )
                },
            )
        }
        Spacer(Modifier.height(8.dp))
        AllMatchesButton(onClick = onAllMatches, modifier = Modifier.padding(horizontal = DrawerList.sideMargin))
    }
}

@Composable
private fun AllMatchesButton(onClick: () -> Unit, modifier: Modifier = Modifier) {
    ShuttlButton(
        text = PlayersCopy.ALL_MATCHES,
        onClick = onClick,
        variant = ShuttlButtonVariant.Secondary,
        modifier = modifier.fillMaxWidth(),
    )
}

/** One player: avatar, name, "12 matches · last Oct 28", and a chevron to their page. */
@Composable
private fun PlayerRow(player: Player, detail: String, onClick: () -> Unit) {
    DrawerRowContainer(
        shape = RoundedCornerShape(ShuttlRadius.medium),
        fill = ShuttlTheme.extended.bgTertiary,
    ) {
        RowLayout(onClick = onClick) {
            PlayerAvatar(player.name, PlayerAvatarRowSize)
            Spacer(Modifier.width(DrawerList.gap))
            Column(Modifier.weight(1f)) {
                Text(
                    player.name,
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onBackground,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    detail,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Spacer(Modifier.width(DrawerList.gap))
            Icon(
                ShuttlIcons.ChevronRight,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/**
 * The list's last row: the mock's dashed outline on no fill, so it reads as a
 * slot for a player rather than as one, with a "+" where the avatar goes.
 */
@Composable
private fun AddPlayerRow(onClick: () -> Unit) {
    val outline = MaterialTheme.colorScheme.outline
    val corner = ShuttlRadius.medium
    DrawerRowContainer(shape = RoundedCornerShape(corner), fill = Color.Transparent) {
        RowLayout(
            onClick = onClick,
            modifier = Modifier.drawBehind {
                // Inset by half the stroke: the container clips to the shape,
                // and a stroke centred on the edge would lose its outer half.
                val stroke = 1.dp.toPx()
                drawRoundRect(
                    color = outline,
                    topLeft = Offset(stroke / 2, stroke / 2),
                    size = Size(size.width - stroke, size.height - stroke),
                    cornerRadius = CornerRadius(corner.toPx() - stroke / 2),
                    style = Stroke(
                        width = stroke,
                        pathEffect = PathEffect.dashPathEffect(floatArrayOf(4.dp.toPx(), 3.dp.toPx())),
                    ),
                )
            },
        ) {
            Box(
                Modifier
                    .size(PlayerAvatarRowSize)
                    .background(ShuttlTheme.extended.bgTertiary, CircleShape),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    ShuttlIcons.Plus,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(20.dp),
                )
            }
            Spacer(Modifier.width(DrawerList.gap))
            Text(
                PlayersCopy.ADD_PLAYER,
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.weight(1f),
            )
        }
    }
}

@Composable
private fun RowLayout(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    content: @Composable RowScope.() -> Unit,
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .clickable(role = Role.Button, onClick = onClick)
            .padding(horizontal = DrawerList.rowPaddingH, vertical = DrawerList.rowPaddingV),
        verticalAlignment = Alignment.CenterVertically,
        content = content,
    )
}
