package com.badmintontracker.android.players

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Snackbar
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.collapse
import androidx.compose.ui.semantics.expand
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.compose.currentStateAsState
import com.badmintontracker.android.cliplist.ClipListScreen
import com.badmintontracker.android.cliplist.ClipListViewModel
import com.badmintontracker.android.home.ADD_BUTTON_CLEARANCE
import com.badmintontracker.android.home.AddMatchButton
import com.badmintontracker.android.home.AddMatchSheet
import com.badmintontracker.android.home.HorizontalSafeDrawing
import com.badmintontracker.android.home.addButtonExpanded
import com.badmintontracker.android.localanalysis.BackgroundWorkAction
import com.badmintontracker.android.match.MatchPageGutter
import com.badmintontracker.android.match.MatchSectionGap
import com.badmintontracker.android.ui.components.ConfirmDialog
import com.badmintontracker.android.ui.components.ShuttlPillTabs
import com.badmintontracker.android.ui.components.ShuttlStatTile
import com.badmintontracker.android.ui.icons.ShuttlIcons
import com.badmintontracker.android.ui.theme.ShuttlRadius
import com.badmintontracker.shared.matchlist.MatchListItem
import com.badmintontracker.shared.players.Player
import com.badmintontracker.shared.players.PlayerSummary
import com.badmintontracker.shared.players.PlayersCopy
import com.badmintontracker.shared.players.lastMatchTile
import com.badmintontracker.shared.players.playerSummary
import com.badmintontracker.shared.repo.MediaRepository
import com.badmintontracker.shared.repo.SharesRepository
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

/** The page's two sections, in the order the pills draw them. */
private const val TAB_PROFILE = 0
private const val TAB_MATCHES = 1

/** The switcher's avatar: the mock's small disc beside the name. */
private val SwitcherAvatarSize = 26.dp

/**
 * Whether the player this page is for has gone: deleted here (the shared
 * delete drops them from the list once the server has), or on another device
 * and dropped by a refresh. [wasPresent] is whether the page has seen them; a
 * page restored before the players have loaded waits for the first refresh to
 * settle ([loaded]) before it concludes they are not coming.
 */
internal fun playerPageGone(present: Boolean, wasPresent: Boolean, loaded: Boolean): Boolean =
    !present && (wasPresent || loaded)

/**
 * One player's page: Profile (avatar, name, playing hand, and three tiles) and
 * Matches (the match list filtered to this player, with its filters and sort).
 *
 * The title is the player's name as a switcher: a menu of every player, where
 * picking one replaces this page ([onSwitchPlayer]). The overflow renames the
 * player, sets the playing hand, or deletes the player; once they leave the
 * list, by that delete or any other way, the page asks to be closed
 * ([onGone]).
 *
 * The Add button here is for this player: [onNewMatch], [onRecord] and
 * [onImport] start a match that has them at home.
 *
 * [vm] and [clipListVm] are the Players entry's instances, so the tiles, the
 * Matches tab and the Players row all read one list. The page draws the app's
 * one root snackbar host ([snackbarHostState]), as Players and All matches do.
 *
 * Mirrors iosApp's PlayerView.swift.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PlayerScreen(
    playerId: String,
    vm: PlayersViewModel,
    clipListVm: ClipListViewModel,
    shares: SharesRepository,
    media: MediaRepository,
    onBack: () -> Unit,
    /** Another player picked in the title's menu: their page replaces this one. */
    onSwitchPlayer: (playerId: String) -> Unit,
    /** The player has left the list: see [playerPageGone]. */
    onGone: () -> Unit,
    onOpenMatch: (MatchListItem) -> Unit,
    onAnalyze: (entryId: String) -> Unit,
    onMarkCourt: (entryId: String) -> Unit,
    onRemoveFromPhone: (entryId: String) -> Unit,
    onNewMatch: () -> Unit,
    onRecord: () -> Unit,
    onImport: () -> Unit,
    /** AuthGate's one root host: see RootAnnouncementsHost. */
    snackbarHostState: SnackbarHostState,
    /** Any sheet or dialog over the page: see AuthGate's finished-run watcher. */
    onOverlayChange: (Boolean) -> Unit,
) {
    val state by vm.state.collectAsStateWithLifecycle()
    val listState by clipListVm.state.collectAsStateWithLifecycle()
    val player = state.players.firstOrNull { it.id == playerId }
    // The player as last seen, so a page closing after a delete keeps its
    // name on screen while it animates away rather than going blank.
    var lastPlayer by remember { mutableStateOf<Player?>(null) }
    LaunchedEffect(player) { if (player != null) lastPlayer = player }
    val shown = player ?: lastPlayer

    // Closed only while in front: a page still drawn under a push (or a pop)
    // must not pop whatever is above it. Keyed on the lifecycle too, so a
    // player who went while the page was covered closes it once it is back.
    val resumed = LocalLifecycleOwner.current.lifecycle.currentStateAsState().value
        .isAtLeast(Lifecycle.State.RESUMED)
    val gone = playerPageGone(present = player != null, wasPresent = lastPlayer != null, loaded = state.loaded)
    LaunchedEffect(gone, resumed) { if (gone && resumed) onGone() }

    // Remembered against the list: it is rebuilt on each analysis progress tick.
    val summary = remember(listState.matchList, playerId) { playerSummary(listState.matchList, playerId) }

    var tab by rememberSaveable { mutableIntStateOf(TAB_PROFILE) }
    // Hoisted so the Add button can read it, and so it keeps its scroll
    // across a trip to Profile and back.
    val matchesListState = rememberLazyListState()
    // The Matches tab leaves composition on a trip to Profile; this keeps the
    // list's own saved state (its filter and sort) for when it comes back.
    val tabStates = rememberSaveableStateHolder()

    // Every flag saved, so a recreation brings back whichever was open.
    var switcherOpen by rememberSaveable { mutableStateOf(false) }
    var overflowOpen by rememberSaveable { mutableStateOf(false) }
    var renameOpen by rememberSaveable { mutableStateOf(false) }
    var handOpen by rememberSaveable { mutableStateOf(false) }
    var deleteOpen by rememberSaveable { mutableStateOf(false) }
    var addSheetOpen by rememberSaveable { mutableStateOf(false) }
    var listOverlayOpen by remember { mutableStateOf(false) }
    // The rename's save, as PlayersScreen holds the add's: in flight, or failed
    // with this sentence, and the wait cancelled when the dialog closes.
    var renameSaving by remember { mutableStateOf(false) }
    var renameError by remember { mutableStateOf<String?>(null) }
    var renameWait by remember { mutableStateOf<Job?>(null) }
    val scope = rememberCoroutineScope()

    val overlayOpen = renameOpen || handOpen || deleteOpen || addSheetOpen || listOverlayOpen
    LaunchedEffect(overlayOpen) { onOverlayChange(overlayOpen) }
    // Leaving the screen takes its overlays with it, whatever was last said.
    DisposableEffect(Unit) { onDispose { onOverlayChange(false) } }

    // A failed hand change or delete. Taken off the view model before it is
    // posted, so the Players screen does not say it again after Back.
    LaunchedEffect(state.errorMessage) {
        val err = state.errorMessage ?: return@LaunchedEffect
        vm.errorShown()
        scope.launch { snackbarHostState.showSnackbar(err) }
    }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        // The bar takes the status bar itself; the Add button, the snackbar and
        // the list take the bottom inset, as on All matches.
        contentWindowInsets = WindowInsets.safeDrawing.only(WindowInsetsSides.Bottom),
        topBar = {
            TopAppBar(
                title = {
                    shown?.let { p ->
                        PlayerSwitcher(
                            current = p,
                            players = state.players,
                            open = switcherOpen,
                            onOpen = { switcherOpen = it },
                            onPick = { picked ->
                                switcherOpen = false
                                if (picked.id != p.id) onSwitchPlayer(picked.id)
                            },
                        )
                    }
                },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
                actions = {
                    // First in the bar so it keeps its place as each screen's own
                    // actions come and go.
                    BackgroundWorkAction()
                    if (player != null) {
                        Box {
                            IconButton(onClick = { overflowOpen = true }) {
                                Icon(Icons.Default.MoreVert, contentDescription = PlayersCopy.PLAYER_OPTIONS)
                            }
                            DropdownMenu(
                                expanded = overflowOpen,
                                onDismissRequest = { overflowOpen = false },
                                containerColor = MaterialTheme.colorScheme.surfaceContainer,
                            ) {
                                DropdownMenuItem(
                                    text = { Text(PlayersCopy.MENU_RENAME) },
                                    onClick = { overflowOpen = false; renameOpen = true },
                                )
                                DropdownMenuItem(
                                    text = { Text(PlayersCopy.MENU_PLAYING_HAND) },
                                    onClick = { overflowOpen = false; handOpen = true },
                                )
                                DropdownMenuItem(
                                    text = { Text(PlayersCopy.MENU_DELETE) },
                                    onClick = { overflowOpen = false; deleteOpen = true },
                                )
                            }
                        }
                    }
                },
                // Pinned to what All matches' bar draws, so the two list
                // screens' bars cannot drift apart.
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surface,
                    scrolledContainerColor = MaterialTheme.colorScheme.surface,
                    navigationIconContentColor = MaterialTheme.colorScheme.onSurface,
                    titleContentColor = MaterialTheme.colorScheme.onSurface,
                    actionIconContentColor = MaterialTheme.colorScheme.onSurfaceVariant,
                ),
            )
        },
        floatingActionButton = {
            if (player != null) {
                // Labelled on Profile, which does not scroll far; on Matches it
                // folds to "+" once the list is scrolled, as on Players.
                val expanded by remember {
                    derivedStateOf { tab == TAB_PROFILE || addButtonExpanded(matchesListState.firstVisibleItemIndex) }
                }
                AddMatchButton(
                    expanded = expanded,
                    onClick = { addSheetOpen = true },
                    modifier = Modifier.windowInsetsPadding(HorizontalSafeDrawing),
                )
            }
        },
        // The shape set here, not left to the theme's extraSmall (8dp): the
        // iOS toast is ShuttlRadius.medium, and the two are one message.
        snackbarHost = {
            SnackbarHost(snackbarHostState, Modifier.windowInsetsPadding(HorizontalSafeDrawing)) { data ->
                Snackbar(data, shape = RoundedCornerShape(ShuttlRadius.medium))
            }
        },
    ) { padding ->
        // Both tabs scroll clear of the Add button: `padding` is the gesture
        // inset alone, so the button's own footprint goes on top of it.
        val bottom = padding.calculateBottomPadding() + ADD_BUTTON_CLEARANCE
        val p = shown ?: return@Scaffold
        Column(
            Modifier
                .fillMaxSize()
                .padding(top = padding.calculateTopPadding())
                .windowInsetsPadding(HorizontalSafeDrawing),
        ) {
            ShuttlPillTabs(
                labels = listOf(PlayersCopy.TAB_PROFILE, PlayersCopy.TAB_MATCHES),
                selectedIndex = tab,
                onSelect = { tab = it },
                modifier = Modifier.padding(start = MatchPageGutter, end = MatchPageGutter, top = 8.dp),
                groupLabel = PlayersCopy.PAGE_SECTIONS,
            )
            Box(Modifier.weight(1f).fillMaxWidth()) {
                when (tab) {
                    TAB_PROFILE -> ProfileTab(player = p, summary = summary, bottom = bottom)
                    else -> tabStates.SaveableStateProvider(TAB_MATCHES) {
                        MatchesTab(
                            playerId = playerId,
                            clipListVm = clipListVm,
                            shares = shares,
                            media = media,
                            listState = matchesListState,
                            onOpenMatch = onOpenMatch,
                            onAnalyze = onAnalyze,
                            onMarkCourt = onMarkCourt,
                            onRemoveFromPhone = onRemoveFromPhone,
                            onAddPlayer = vm::create,
                            onOverlayChange = { listOverlayOpen = it },
                            bottom = PaddingValues(bottom = bottom),
                        )
                    }
                }
            }
        }
    }

    // The dialogs act on the player as the list has them now; each closes
    // with the page if the player goes (player null).
    if (addSheetOpen && player != null) {
        AddMatchSheet(
            onNewMatch = { addSheetOpen = false; onNewMatch() },
            onRecord = { addSheetOpen = false; onRecord() },
            onImport = { addSheetOpen = false; onImport() },
            onDismiss = { addSheetOpen = false },
        )
    }

    if (renameOpen && player != null) {
        fun close() {
            renameWait?.cancel()
            renameWait = null
            renameOpen = false
            renameSaving = false
            renameError = null
        }
        PlayerNameDialog(
            title = PlayersCopy.RENAME_DIALOG_TITLE,
            initialName = player.name,
            initialArm = player.racketArm,
            showArm = false,
            onDismiss = ::close,
            onSave = { name, _ ->
                renameSaving = true
                renameError = null
                renameWait = scope.launch {
                    // A failure stays in the dialog with the name typed, as
                    // the add dialog's does.
                    when (val problem = vm.rename(player.id, name)) {
                        null -> close()
                        else -> {
                            renameSaving = false
                            renameError = problem
                        }
                    }
                }
            },
            error = renameError,
            saving = renameSaving,
        )
    }

    if (handOpen && player != null) {
        PlayingHandDialog(
            initialArm = player.racketArm,
            onDismiss = { handOpen = false },
            // Nothing typed to lose, so it closes at once; a failure is said
            // on the snackbar.
            onSave = { arm -> handOpen = false; vm.setRacketArm(player.id, arm) },
        )
    }

    if (deleteOpen && player != null) {
        ConfirmDialog(
            title = PlayersCopy.deleteTitle(player.name),
            // Counted from the list the Matches tab shows, so the dialog says
            // exactly what the coach has just been looking at.
            message = PlayersCopy.deleteBody(summary.matchCount),
            confirmLabel = PlayersCopy.DELETE_CONFIRM,
            // The page closes once the player leaves the list (onGone), which
            // the shared delete does only when the server has deleted them.
            onConfirm = { deleteOpen = false; vm.delete(player) },
            onDismiss = { deleteOpen = false },
        )
    }
}

/**
 * The title: the player's avatar and name on a pill with a chevron, which
 * opens a menu of every player (sorted by name, as the repository publishes
 * them), the current one ticked. A 40-character name is cut, never the chevron.
 *
 * The pill draws 38dp tall, and its touch target is the 48dp minimum
 * (minimumInteractiveComponentSize, outside the clip, as IconButton does). A
 * screen reader hears whether the menu is open, and can open or close it.
 */
@Composable
private fun PlayerSwitcher(
    current: Player,
    players: List<Player>,
    open: Boolean,
    onOpen: (Boolean) -> Unit,
    onPick: (Player) -> Unit,
) {
    val shape = RoundedCornerShape(ShuttlRadius.pill)
    Box {
        Row(
            modifier = Modifier
                .minimumInteractiveComponentSize()
                .clip(shape)
                .background(MaterialTheme.colorScheme.surfaceVariant, shape)
                .clickable(role = Role.Button, onClickLabel = PlayersCopy.SWITCH_PLAYER) { onOpen(true) }
                .semantics {
                    stateDescription = if (open) PlayersCopy.MENU_EXPANDED else PlayersCopy.MENU_COLLAPSED
                    if (open) collapse { onOpen(false); true } else expand { onOpen(true); true }
                }
                // The mock's 6dp round the avatar, 12dp after the chevron.
                .padding(start = 6.dp, end = 12.dp, top = 6.dp, bottom = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            PlayerAvatar(current.name, SwitcherAvatarSize)
            Spacer(Modifier.width(8.dp))
            Text(
                current.name,
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f, fill = false),
            )
            Spacer(Modifier.width(6.dp))
            Icon(
                ShuttlIcons.ChevronDown,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(16.dp),
            )
        }
        DropdownMenu(
            expanded = open,
            onDismissRequest = { onOpen(false) },
            containerColor = MaterialTheme.colorScheme.surfaceContainer,
        ) {
            players.forEach { player ->
                DropdownMenuItem(
                    text = { Text(player.name, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                    leadingIcon = { PlayerAvatar(player.name, SwitcherAvatarSize) },
                    trailingIcon = if (player.id == current.id) {
                        { Icon(Icons.Default.Check, contentDescription = null) }
                    } else {
                        null
                    },
                    onClick = { onPick(player) },
                )
            }
        }
    }
}

/**
 * Profile: the avatar beside the name and the playing hand (no line when it is
 * not set), then Matches, Rallies and Last match. Scrolls, so a long name at a
 * large font scale never pushes the tiles out of reach.
 */
@Composable
private fun ProfileTab(player: Player, summary: PlayerSummary, bottom: Dp) {
    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(start = MatchPageGutter, end = MatchPageGutter, top = MatchSectionGap, bottom = bottom),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            PlayerAvatar(player.name, PlayerAvatarProfileSize)
            Spacer(Modifier.width(14.dp))
            Column(Modifier.weight(1f)) {
                // Two lines at most: a 40-character name at 22sp beside the
                // avatar would otherwise run to three, breaking inside a word.
                Text(
                    player.name,
                    style = MaterialTheme.typography.headlineMedium,
                    color = MaterialTheme.colorScheme.onBackground,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                PlayersCopy.handLine(player.racketArm)?.let { hand ->
                    Text(
                        hand,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
        Spacer(Modifier.height(20.dp))
        // One height for the three. Every value is one line at the default
        // font scale ("Oct 28", "Oct '25", "None yet"); at a large one a value
        // can wrap, and the captions still stay level: see ShuttlStatTile's
        // compact.
        Row(
            modifier = Modifier.fillMaxWidth().height(IntrinsicSize.Min),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            val tile = Modifier.weight(1f).fillMaxHeight()
            ShuttlStatTile(
                value = summary.matchCount.toString(), unit = "", label = PlayersCopy.TILE_MATCHES,
                modifier = tile, compact = true,
            )
            ShuttlStatTile(
                value = summary.rallyCount.toString(), unit = "", label = PlayersCopy.TILE_RALLIES,
                modifier = tile, compact = true,
            )
            ShuttlStatTile(
                value = lastMatchTile(summary), unit = "", label = PlayersCopy.TILE_LAST_MATCH,
                modifier = tile, compact = true,
            )
        }
    }
}

/** Matches: the match list, this player's rows only. */
@Composable
private fun MatchesTab(
    playerId: String,
    clipListVm: ClipListViewModel,
    shares: SharesRepository,
    media: MediaRepository,
    listState: LazyListState,
    onOpenMatch: (MatchListItem) -> Unit,
    onAnalyze: (entryId: String) -> Unit,
    onMarkCourt: (entryId: String) -> Unit,
    onRemoveFromPhone: (entryId: String) -> Unit,
    onAddPlayer: AddPlayer,
    onOverlayChange: (Boolean) -> Unit,
    bottom: PaddingValues,
) {
    // The list reports its own sheets and dialogs but not its leaving, and a
    // trip to Profile takes it out of composition.
    DisposableEffect(Unit) { onDispose { onOverlayChange(false) } }
    ClipListScreen(
        vm = clipListVm, shares = shares, media = media,
        onOpenMatch = onOpenMatch,
        onAnalyze = onAnalyze,
        onMarkCourt = onMarkCourt,
        onRemoveFromPhone = onRemoveFromPhone,
        onAddPlayer = onAddPlayer,
        listState = listState,
        onOverlayChange = onOverlayChange,
        contentPadding = bottom,
        playerId = playerId,
    )
}
