package com.badmintontracker.android.home

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.DrawerValue
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalDrawerSheet
import androidx.compose.material3.ModalNavigationDrawer
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.rememberDrawerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.disabled
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.badmintontracker.android.BuildConfig
import com.badmintontracker.android.cliplist.ClipListScreen
import com.badmintontracker.android.cliplist.ClipListViewModel
import com.badmintontracker.android.cliplist.MatchSummary
import com.badmintontracker.android.localanalysis.BackgroundWorkAction
import com.badmintontracker.android.localanalysis.LocalAnalysisBanner
import com.badmintontracker.android.localanalysis.LocalAnalysisRunner
import com.badmintontracker.android.localvideo.LocalVideoRow
import com.badmintontracker.android.ui.components.ShuttlButton
import com.badmintontracker.android.ui.components.ShuttlButtonVariant
import com.badmintontracker.android.ui.theme.ShuttlTheme
import com.badmintontracker.android.ui.theme.ShuttlTypeExtras
import com.badmintontracker.shared.localvideo.LocalVideoEntry
import com.badmintontracker.shared.prefs.ThemeMode
import com.badmintontracker.shared.prefs.ThemePreferenceRepository
import com.badmintontracker.shared.repo.MediaRepository
import com.badmintontracker.shared.repo.SharesRepository
import com.badmintontracker.shared.scoring.ScoreMatchCard
import kotlinx.coroutines.launch

/**
 * The app's front door: a rotating hero line, two pill buttons, and the
 * drawer that used to be the whole screen. Owns the [ModalNavigationDrawer]
 * and the top bar; [ClipListScreen] reports taps upward through the same
 * closures it always has, because it no longer has a bar of its own to carry
 * them.
 *
 * Mirrors iosApp's HomeView.swift. The parameter list is exactly
 * [ClipListScreen]'s own (this screen owns it and passes it straight
 * through, unchanged, into the drawer's content slot) plus Home's own
 * concerns: the banner, the hero, the two pills, and the bar.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen(
    vm: ClipListViewModel,
    media: MediaRepository,
    shares: SharesRepository,
    themePrefs: ThemePreferenceRepository,
    localAnalysis: LocalAnalysisRunner,
    onMatchClick: (MatchSummary) -> Unit,
    onScoreMatchClick: (ScoreMatchCard) -> Unit,
    onNewMatch: () -> Unit,
    onOpenHeatmap: ((LocalVideoEntry) -> Unit)? = null,
    hasHeatmap: (LocalVideoEntry) -> Boolean = { false },
    onOpenLocalClips: ((LocalVideoEntry) -> Unit)? = null,
    localClipCount: (LocalVideoEntry) -> Int = { 0 },
    localRows: List<LocalVideoRow> = emptyList(),
    intakeError: String? = null,
    onIntakeErrorShown: () -> Unit = {},
    onLocalClick: (LocalVideoEntry) -> Unit = {},
    onLocalAnalyze: (LocalVideoRow) -> Unit = {},
    onLocalRemove: (LocalVideoEntry) -> Unit = {},
    onLocalResultSeen: (LocalVideoEntry) -> Unit = {},
    onLocalDetailsSaved: (id: String, title: String, description: String) -> Unit = { _, _, _ -> },
    autoDetailsEntryId: String? = null,
    onAutoDetailsShown: () -> Unit = {},
    onRecord: () -> Unit = {},
    onImport: () -> Unit = {},
    onLabels: () -> Unit = {},
    onAttachedMarkCourt: (String) -> Unit = {},
    onAttachedRetry: (String) -> Unit = {},
    onOpenHeatmapFromBanner: (String) -> Unit = {},
    /** Set by the background-work indicator: its job is to show the run, which
     * lives in the drawer's list, so arriving here also opens the drawer. */
    openDrawerRequested: Boolean = false,
    onDrawerOpenConsumed: () -> Unit = {},
) {
    val scope = rememberCoroutineScope()
    val drawerState = rememberDrawerState(DrawerValue.Closed)
    val themeMode by themePrefs.mode.collectAsStateWithLifecycle()
    var overflowOpen by remember { mutableStateOf(false) }
    var addSheetOpen by remember { mutableStateOf(false) }
    val snackbarHostState = remember { SnackbarHostState() }

    LaunchedEffect(openDrawerRequested) {
        if (openDrawerRequested) {
            drawerState.open()
            onDrawerOpenConsumed()
        }
    }

    // ModalNavigationDrawer does not claim the system back button/gesture on
    // its own - unhandled, it falls through to the NavHost, which has
    // nothing below Home to pop to, so the whole app would exit. A closed
    // drawer leaves this disabled, so back still exits normally everywhere
    // else.
    BackHandler(enabled = drawerState.isOpen) {
        scope.launch { drawerState.close() }
    }

    // Record/import failures surface here, not inside ClipListScreen: both
    // triggers (the "Add new match" pill, below) live on Home now, and the
    // drawer's content is composed off-screen while it is closed, which is
    // exactly when these can fire. See ClipListScreen's own state.error
    // snackbar for the counterpart raised by actions on the list itself,
    // which only ever happen while the drawer is open and visible.
    LaunchedEffect(intakeError) {
        val err = intakeError ?: return@LaunchedEffect
        snackbarHostState.showSnackbar(err)
        onIntakeErrorShown()
    }

    // The mock's own cap: proportional on a small phone, capped at 330dp so
    // the drawer never swallows the whole screen on a tablet.
    val screenWidth = LocalConfiguration.current.screenWidthDp.dp
    val proportional = screenWidth * 0.86f
    val drawerWidth = if (proportional < 330.dp) proportional else 330.dp

    ModalNavigationDrawer(
        drawerState = drawerState,
        // A bonus, not the route in: see the hint line's own comment below for
        // why the hamburger is primary.
        gesturesEnabled = true,
        drawerContent = {
            val borderColor = MaterialTheme.colorScheme.outlineVariant
            ModalDrawerSheet(
                modifier = Modifier
                    .width(drawerWidth)
                    // The mock draws this panel as bgInput with a 1px
                    // border-coloured right edge, matching iOS's
                    // MatchesDrawer (`.background(Shuttl.bgInput)` plus a
                    // trailing `Shuttl.border` overlay). Drawn directly
                    // rather than an outer Box overlay, so ModalDrawerSheet's
                    // own height sizing is untouched.
                    .drawWithContent {
                        drawContent()
                        val strokeWidth = 1.dp.toPx()
                        drawLine(
                            color = borderColor,
                            start = Offset(size.width - strokeWidth / 2, 0f),
                            end = Offset(size.width - strokeWidth / 2, size.height),
                            strokeWidth = strokeWidth,
                        )
                    },
                // M3's own default resolves a surfaceContainer role this
                // app's ColorScheme never sets, which falls back to
                // Material's stock tinted grey - a platform colour, not a
                // token. Pinned to `bgInput` explicitly instead.
                drawerContainerColor = ShuttlTheme.extended.bgInput,
                // M3's default rounds the two trailing corners. The mock and
                // iOS's own hand-built panel (a plain `Rectangle()` overlay)
                // both draw a flat rectangle, and a rounded corner would also
                // leave the straight border line above poking past the
                // background's curved silhouette.
                drawerShape = RectangleShape,
            ) {
                MatchesDrawerContent(onLabels = onLabels, onSignOut = vm::signOut) {
                    ClipListScreen(
                        vm = vm,
                        media = media,
                        shares = shares,
                        onMatchClick = onMatchClick,
                        onScoreMatchClick = onScoreMatchClick,
                        onOpenHeatmap = onOpenHeatmap,
                        hasHeatmap = hasHeatmap,
                        onOpenLocalClips = onOpenLocalClips,
                        localClipCount = localClipCount,
                        localRows = localRows,
                        onLocalClick = onLocalClick,
                        onLocalAnalyze = onLocalAnalyze,
                        onLocalRemove = onLocalRemove,
                        onLocalResultSeen = onLocalResultSeen,
                        onLocalDetailsSaved = onLocalDetailsSaved,
                        autoDetailsEntryId = autoDetailsEntryId,
                        onAutoDetailsShown = onAutoDetailsShown,
                        onAttachedMarkCourt = onAttachedMarkCourt,
                        onAttachedRetry = onAttachedRetry,
                    )
                }
            }
        },
    ) {
        Scaffold(
            topBar = {
                TopAppBar(
                    navigationIcon = {
                        IconButton(onClick = { scope.launch { drawerState.open() } }) {
                            Icon(Icons.Default.Menu, contentDescription = "Open matches")
                        }
                    },
                    title = {
                        Text(
                            "SHUTTL.",
                            style = ShuttlTypeExtras.wordmark,
                            color = MaterialTheme.colorScheme.onBackground,
                        )
                    },
                    actions = {
                        // First in the bar so it keeps its place as every other
                        // screen's own bar does.
                        BackgroundWorkAction()
                        IconButton(onClick = { overflowOpen = true }) {
                            Icon(Icons.Default.MoreVert, contentDescription = "Menu")
                        }
                        DropdownMenu(expanded = overflowOpen, onDismissRequest = { overflowOpen = false }) {
                            DropdownMenuItem(
                                text = { Text("Labels") },
                                onClick = { overflowOpen = false; onLabels() },
                            )
                            DropdownMenuItem(
                                text = { Text("Sign out") },
                                onClick = { overflowOpen = false; vm.signOut() },
                            )
                            HorizontalDivider()
                            DropdownMenuItem(
                                text = {
                                    Text(
                                        if (themeMode == ThemeMode.DARK) "Switch to light mode"
                                        else "Switch to dark mode",
                                    )
                                },
                                onClick = { overflowOpen = false; themePrefs.toggle() },
                            )
                            HorizontalDivider()
                            DropdownMenuItem(
                                text = {
                                    Text(
                                        "Version ${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})",
                                        style = MaterialTheme.typography.bodySmall,
                                    )
                                },
                                onClick = {},
                                enabled = false,
                            )
                        }
                    },
                )
            },
            snackbarHost = { SnackbarHost(snackbarHostState) },
        ) { padding ->
            Column(modifier = Modifier.fillMaxSize().padding(padding)) {
                // Above the hero for the same reason it sat above the list:
                // an on-device run takes minutes and belongs where it is
                // visible on return, and Home is now where that is.
                LocalAnalysisBanner(runner = localAnalysis, onOpenHeatmap = onOpenHeatmapFromBanner)

                Spacer(Modifier.height(48.dp))
                HeroTickerView(
                    isPaused = drawerState.isOpen,
                    modifier = Modifier.padding(horizontal = 24.dp),
                )
                Spacer(Modifier.weight(1f))

                Column(
                    modifier = Modifier.padding(horizontal = 24.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    ShuttlButton(
                        text = "Add new match",
                        onClick = { addSheetOpen = true },
                        variant = ShuttlButtonVariant.Primary,
                        leadingIcon = Icons.Default.Add,
                        // 60dp is the design's one exact metric for these two
                        // pills (see HomeView.swift's HomePillButtonStyle on
                        // iOS). Set here at the call site rather than as
                        // ShuttlButton's default height, which nine other
                        // screens also rely on.
                        modifier = Modifier.fillMaxWidth().height(60.dp),
                    )
                    // Analytics ships in Phase 3. Genuinely disabled, not just
                    // inert: an earlier iOS pass shipped this enabled-but-
                    // no-op, and review rejected it - a pill that visibly
                    // reacts to a tap and then does nothing reads as a hang,
                    // not an unbuilt feature. ShuttlButton(enabled = false)
                    // dims the whole pill itself (it does not rely on a
                    // system style to do that).
                    //
                    // The outer Box with its own mergeDescendants(true) is
                    // the documented Compose pattern for adding accessibility
                    // properties to a pre-built clickable from the outside:
                    // it makes THIS node the merge root, folding
                    // ShuttlButton's own clickable(enabled = false) semantics
                    // into it for TalkBack's merged tree. A plain (non-
                    // merging) semantics() placed directly on ShuttlButton's
                    // own modifier parameter was tried first and rejected:
                    // dumping the raw accessibility tree with uiautomator
                    // still showed it as a second node one level down,
                    // carrying its own `enabled=true` that contradicted the
                    // pill's actual disabled state - uiautomator surfaces
                    // Compose's unmerged tree, the same one Compose's own
                    // testing APIs call `useUnmergedTree`, so this is not
                    // proof TalkBack itself would double-announce, but the
                    // Box wrapper is the pattern Google documents for this
                    // exact case and it does put `enabled=false` on the
                    // node that owns the merge, which the first attempt did
                    // not.
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .semantics(mergeDescendants = true) {
                                disabled()
                                contentDescription = "Analytics. Coming soon."
                            },
                    ) {
                        ShuttlButton(
                            text = "Analytics",
                            onClick = {},
                            enabled = false,
                            variant = ShuttlButtonVariant.Secondary,
                            modifier = Modifier.fillMaxWidth().height(60.dp),
                        )
                    }
                    // "Your matches", not "Swipe right for your matches": on
                    // gesture navigation the system owns the left edge, so an
                    // edge swipe loses to the back gesture and is unreliable
                    // as a way in. The hamburger above is the primary
                    // affordance; gesturesEnabled stays on as a bonus where
                    // the platform's navigation mode allows it, but the copy
                    // must not promise a gesture the OS might eat. Do not
                    // "fix" this to match iOS's wording - the platforms
                    // differ here on purpose.
                    Text(
                        "Your matches",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        // The touch target must clear Android's 48dp minimum.
                        // Moving the Column's old `padding(bottom = 24.dp)`
                        // in here (as top = 4.dp, bottom = 28.dp, replacing
                        // the old vertical = 4.dp) keeps the total space from
                        // this Text's top to the screen edge unchanged at
                        // every font scale, so the pills above cannot shift.
                        // `defaultMinSize` sits outside `padding` so it floors
                        // the whole element, not just the bare text; at
                        // default scale the padding alone already clears
                        // 48dp, so this only engages if the type scale ever
                        // shrinks below that.
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable(role = Role.Button) { scope.launch { drawerState.open() } }
                            .defaultMinSize(minHeight = 48.dp)
                            .padding(top = 4.dp, bottom = 28.dp),
                        textAlign = TextAlign.Center,
                    )
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
}
