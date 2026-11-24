package com.badmintontracker.android.invites

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
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Snackbar
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
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
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.badmintontracker.android.cliplist.ClipListScreen
import com.badmintontracker.android.cliplist.ClipListViewModel
import com.badmintontracker.android.home.HomeTopBar
import com.badmintontracker.android.home.HorizontalSafeDrawing
import com.badmintontracker.android.localanalysis.PlayerDrillsTab
import com.badmintontracker.android.match.MatchPageGutter
import com.badmintontracker.android.match.MatchSectionGap
import com.badmintontracker.android.players.PlayerAvatar
import com.badmintontracker.android.players.PlayerAvatarProfileSize
import com.badmintontracker.android.players.PlayerAvatarRowSize
import com.badmintontracker.android.players.PlayerCreation
import com.badmintontracker.android.ui.components.ShuttlButton
import com.badmintontracker.android.ui.components.ShuttlEmptyState
import com.badmintontracker.android.ui.components.ShuttlPillTabs
import com.badmintontracker.android.ui.components.ShuttlStatTile
import com.badmintontracker.android.ui.icons.ShuttlIcons
import com.badmintontracker.android.ui.theme.ShuttlRadius
import com.badmintontracker.android.ui.theme.ShuttlTypeExtras
import com.badmintontracker.shared.exercises.DrillProgressRepository
import com.badmintontracker.shared.invites.LinkedPlayerProfile
import com.badmintontracker.shared.invites.PlayerInviteCopy
import com.badmintontracker.shared.matchlist.MatchListItem
import com.badmintontracker.shared.players.PlayerSummary
import com.badmintontracker.shared.players.PlayersCopy
import com.badmintontracker.shared.players.lastMatchTile
import com.badmintontracker.shared.players.playerSummary
import com.badmintontracker.shared.repo.MediaRepository
import com.badmintontracker.shared.repo.SharesRepository
import com.badmintontracker.shared.signin.SignInCopy
import kotlinx.coroutines.launch

private const val PLAYER_TAB_PROFILE = 0
private const val PLAYER_TAB_MATCHES = 1
private const val PLAYER_TAB_DRILLS = 2

/**
 * Focused Player home for `AccountRole.PLAYER`. Skips the coach Players list,
 * All matches, and `+` FAB, landing directly on the player's own
 * `Profile | Matches | Drills` view (with a coach switcher when linked to 2+
 * coaches, and an Unlinked recovery screen when access has been revoked).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PlayerHomeScreen(
    accessVm: PlayerAccessViewModel,
    clipListVm: ClipListViewModel,
    shares: SharesRepository,
    media: MediaRepository,
    drillProgress: DrillProgressRepository,
    currentUserId: String?,
    onOpenMatch: (MatchListItem) -> Unit,
    onSettings: () -> Unit,
    snackbarHostState: SnackbarHostState,
) {
    val accessState by accessVm.state.collectAsStateWithLifecycle()
    val listState by clipListVm.state.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()

    var selectedPlayerId by rememberSaveable { mutableStateOf<String?>(null) }
    var redeemSheetOpen by rememberSaveable { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        accessVm.refreshLinkedProfiles()
    }

    val profiles = accessState.linkedProfiles
    val activeProfile = remember(profiles, selectedPlayerId) {
        profiles.firstOrNull { it.playerId == selectedPlayerId } ?: profiles.firstOrNull()
    }
    LaunchedEffect(activeProfile?.playerId) {
        if (activeProfile != null && selectedPlayerId != activeProfile.playerId) {
            selectedPlayerId = activeProfile.playerId
        }
    }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        contentWindowInsets = WindowInsets.safeDrawing.only(WindowInsetsSides.Bottom),
        snackbarHost = {
            SnackbarHost(snackbarHostState, Modifier.windowInsetsPadding(HorizontalSafeDrawing)) { data ->
                Snackbar(data, shape = RoundedCornerShape(ShuttlRadius.medium))
            }
        },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .windowInsetsPadding(HorizontalSafeDrawing),
        ) {
            HomeTopBar(SignInCopy.WORDMARK, titleStyle = ShuttlTypeExtras.wordmark) {
                IconButton(onClick = onSettings) {
                    Icon(
                        ShuttlIcons.Settings,
                        contentDescription = MatchPageCopySettings,
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(20.dp),
                    )
                }
            }

            Box(modifier = Modifier.weight(1f).fillMaxWidth()) {
                when {
                    !accessState.linkedProfilesLoaded && profiles.isEmpty() -> {
                        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                            CircularProgressIndicator(strokeWidth = 2.dp, modifier = Modifier.size(24.dp))
                        }
                    }

                    activeProfile == null -> {
                        PullToRefreshBox(
                            isRefreshing = listState.isRefreshing,
                            onRefresh = {
                                accessVm.refreshLinkedProfiles()
                                clipListVm.refresh()
                            },
                            modifier = Modifier.fillMaxSize(),
                        ) {
                            Column(
                                modifier = Modifier
                                    .fillMaxSize()
                                    .verticalScroll(rememberScrollState())
                                    .padding(top = 48.dp, bottom = padding.calculateBottomPadding() + 24.dp),
                                horizontalAlignment = Alignment.CenterHorizontally,
                            ) {
                                ShuttlEmptyState(
                                    icon = ShuttlIcons.PersonStanding,
                                    title = PlayerInviteCopy.UNLINKED_TITLE,
                                    body = PlayerInviteCopy.UNLINKED_BODY,
                                    action = {
                                        ShuttlButton(
                                            text = PlayerInviteCopy.UNLINKED_CTA,
                                            onClick = { redeemSheetOpen = true },
                                            compact = true,
                                        )
                                    },
                                )
                            }
                        }
                    }

                    else -> {
                        LinkedProfileBody(
                            profile = activeProfile,
                            allProfiles = profiles,
                            onSelectProfile = { selectedPlayerId = it.playerId },
                            clipListVm = clipListVm,
                            shares = shares,
                            media = media,
                            drillProgress = drillProgress,
                            currentUserId = currentUserId,
                            onOpenMatch = onOpenMatch,
                            onRefreshAll = {
                                accessVm.refreshLinkedProfiles()
                                clipListVm.refresh()
                            },
                            bottomInset = padding.calculateBottomPadding(),
                        )
                    }
                }
            }
        }
    }

    if (redeemSheetOpen) {
        RedeemInviteSheet(
            onRedeem = accessVm::redeemExistingCode,
            onLinked = { redeemed ->
                redeemSheetOpen = false
                selectedPlayerId = redeemed.playerId
                clipListVm.refresh()
                scope.launch {
                    snackbarHostState.showSnackbar(PlayerInviteCopy.linkedSuccessToast(redeemed.playerName))
                }
            },
            onDismiss = { redeemSheetOpen = false },
        )
    }
}

/**
 * Dual-role coach destination: opens a coach's own linked player profile from
 * another coach in read-only mode with a Back arrow returning to Coach Home.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LinkedProfileScreen(
    initialPlayerId: String,
    accessVm: PlayerAccessViewModel,
    clipListVm: ClipListViewModel,
    shares: SharesRepository,
    media: MediaRepository,
    drillProgress: DrillProgressRepository,
    currentUserId: String?,
    onOpenMatch: (MatchListItem) -> Unit,
    onBack: () -> Unit,
) {
    val accessState by accessVm.state.collectAsStateWithLifecycle()
    val profiles = accessState.linkedProfiles
    var selectedPlayerId by rememberSaveable { mutableStateOf(initialPlayerId) }
    val activeProfile = remember(profiles, selectedPlayerId) {
        profiles.firstOrNull { it.playerId == selectedPlayerId } ?: profiles.firstOrNull()
    }

    LaunchedEffect(accessState.linkedProfilesLoaded, activeProfile) {
        if (accessState.linkedProfilesLoaded && activeProfile == null) onBack()
    }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        contentWindowInsets = WindowInsets.safeDrawing.only(WindowInsetsSides.Bottom),
        topBar = {
            TopAppBar(
                title = { Text(PlayerInviteCopy.MY_PROFILE_SECTION) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surface,
                    scrolledContainerColor = MaterialTheme.colorScheme.surface,
                    navigationIconContentColor = MaterialTheme.colorScheme.onSurface,
                    titleContentColor = MaterialTheme.colorScheme.onSurface,
                ),
            )
        },
    ) { padding ->
        val profile = activeProfile ?: return@Scaffold
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(top = padding.calculateTopPadding())
                .windowInsetsPadding(HorizontalSafeDrawing),
        ) {
            LinkedProfileBody(
                profile = profile,
                allProfiles = profiles,
                onSelectProfile = { selectedPlayerId = it.playerId },
                clipListVm = clipListVm,
                shares = shares,
                media = media,
                drillProgress = drillProgress,
                currentUserId = currentUserId,
                onOpenMatch = onOpenMatch,
                onRefreshAll = {
                    accessVm.refreshLinkedProfiles()
                    clipListVm.refresh()
                },
                bottomInset = padding.calculateBottomPadding(),
            )
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun LinkedProfileBody(
    profile: LinkedPlayerProfile,
    allProfiles: List<LinkedPlayerProfile>,
    onSelectProfile: (LinkedPlayerProfile) -> Unit,
    clipListVm: ClipListViewModel,
    shares: SharesRepository,
    media: MediaRepository,
    drillProgress: DrillProgressRepository,
    currentUserId: String?,
    onOpenMatch: (MatchListItem) -> Unit,
    onRefreshAll: () -> Unit,
    bottomInset: Dp,
) {
    val listState by clipListVm.state.collectAsStateWithLifecycle()
    val summary = remember(listState.matchList, profile.playerId) {
        playerSummary(listState.matchList, profile.playerId)
    }

    var tab by rememberSaveable { mutableIntStateOf(PLAYER_TAB_PROFILE) }
    var switcherOpen by remember { mutableStateOf(false) }
    val matchesListState = rememberLazyListState()
    val tabStates = rememberSaveableStateHolder()

    Column(modifier = Modifier.fillMaxSize()) {
        // Header with player avatar, name, coach subtitle, and optional multi-coach switcher.
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = MatchPageGutter, vertical = 8.dp),
        ) {
            val canSwitch = allProfiles.size > 1
            val headerShape = RoundedCornerShape(ShuttlRadius.medium)
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(headerShape)
                    .then(
                        if (canSwitch) {
                            Modifier
                                .background(MaterialTheme.colorScheme.surfaceVariant, headerShape)
                                .clickable(role = Role.Button, onClickLabel = PlayerInviteCopy.SWITCH_COACH_PROFILE) {
                                    switcherOpen = true
                                }
                                .padding(horizontal = 12.dp, vertical = 10.dp)
                        } else {
                            Modifier.padding(vertical = 4.dp)
                        },
                    ),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                PlayerAvatar(profile.name, PlayerAvatarRowSize)
                Spacer(Modifier.width(12.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = profile.name,
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.onBackground,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        text = PlayerInviteCopy.coachedBy(profile.coachEmail),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                if (canSwitch) {
                    Spacer(Modifier.width(8.dp))
                    Icon(
                        ShuttlIcons.ChevronDown,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(16.dp),
                    )
                }
            }

            if (canSwitch) {
                DropdownMenu(
                    expanded = switcherOpen,
                    onDismissRequest = { switcherOpen = false },
                    containerColor = MaterialTheme.colorScheme.surfaceContainer,
                ) {
                    allProfiles.forEach { option ->
                        DropdownMenuItem(
                            text = {
                                Column {
                                    Text(option.name, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                    Text(
                                        text = PlayerInviteCopy.coachedBy(option.coachEmail),
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                    )
                                }
                            },
                            leadingIcon = { PlayerAvatar(option.name, 26.dp) },
                            trailingIcon = if (option.playerId == profile.playerId) {
                                { Icon(Icons.Default.Check, contentDescription = null) }
                            } else {
                                null
                            },
                            onClick = {
                                switcherOpen = false
                                onSelectProfile(option)
                            },
                        )
                    }
                }
            }
        }

        ShuttlPillTabs(
            labels = listOf(PlayersCopy.TAB_PROFILE, PlayersCopy.TAB_MATCHES, PlayersCopy.TAB_DRILLS),
            selectedIndex = tab,
            onSelect = { tab = it },
            modifier = Modifier.padding(start = MatchPageGutter, end = MatchPageGutter, top = 4.dp),
            groupLabel = PlayersCopy.PAGE_SECTIONS,
        )

        Box(modifier = Modifier.weight(1f).fillMaxWidth()) {
            when (tab) {
                PLAYER_TAB_PROFILE -> LinkedProfileTab(
                    profile = profile,
                    summary = summary,
                    isRefreshing = listState.isRefreshing,
                    onRefresh = onRefreshAll,
                    bottom = bottomInset + 24.dp,
                )

                PLAYER_TAB_DRILLS -> tabStates.SaveableStateProvider("drills-${profile.playerId}") {
                    PlayerDrillsTab(
                        playerId = profile.playerId,
                        racketArm = profile.racketArm,
                        drillProgress = drillProgress,
                        bottomPadding = bottomInset + 24.dp,
                        currentUserId = currentUserId,
                        otherPartyLabel = "Coach",
                        isCoach = false,
                        onSyncDrills = clipListVm::syncDrills,
                    )
                }

                else -> tabStates.SaveableStateProvider("matches-${profile.playerId}") {
                    ClipListScreen(
                        vm = clipListVm,
                        shares = shares,
                        media = media,
                        onOpenMatch = onOpenMatch,
                        onAddPlayer = { _, _ -> PlayerCreation.Failed(PlayersCopy.SAVE_FAILED) },
                        listState = matchesListState,
                        contentPadding = PaddingValues(bottom = bottomInset + 16.dp),
                        playerId = profile.playerId,
                        emptyBody = "Matches from your coach will appear here.",
                    )
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun LinkedProfileTab(
    profile: LinkedPlayerProfile,
    summary: PlayerSummary,
    isRefreshing: Boolean,
    onRefresh: () -> Unit,
    bottom: Dp,
) {
    PullToRefreshBox(
        isRefreshing = isRefreshing,
        onRefresh = onRefresh,
        modifier = Modifier.fillMaxSize(),
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(start = MatchPageGutter, end = MatchPageGutter, top = MatchSectionGap, bottom = bottom),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                PlayerAvatar(profile.name, PlayerAvatarProfileSize)
                Spacer(Modifier.width(14.dp))
                Column(Modifier.weight(1f)) {
                    Text(
                        text = profile.name,
                        style = MaterialTheme.typography.headlineMedium,
                        color = MaterialTheme.colorScheme.onBackground,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                    PlayersCopy.handLine(profile.racketArm)?.let { hand ->
                        Text(
                            text = hand,
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }

            Spacer(Modifier.height(20.dp))
            Row(
                modifier = Modifier.fillMaxWidth().height(IntrinsicSize.Min),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                val tile = Modifier.weight(1f).fillMaxHeight()
                ShuttlStatTile(
                    value = summary.matchCount.toString(),
                    unit = "",
                    label = PlayersCopy.TILE_MATCHES,
                    modifier = tile,
                    compact = true,
                )
                ShuttlStatTile(
                    value = summary.rallyCount.toString(),
                    unit = "",
                    label = PlayersCopy.TILE_RALLIES,
                    modifier = tile,
                    compact = true,
                )
                ShuttlStatTile(
                    value = lastMatchTile(summary),
                    unit = "",
                    label = PlayersCopy.TILE_LAST_MATCH,
                    modifier = tile,
                    compact = true,
                )
            }

            Spacer(Modifier.height(20.dp))
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(ShuttlRadius.large))
                    .background(MaterialTheme.colorScheme.surfaceVariant)
                    .padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                Text(
                    text = PlayerInviteCopy.coachedBy(profile.coachEmail),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onBackground,
                )
                Text(
                    text = PlayerInviteCopy.linkedDateLabel(profile.linkedAt),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

private const val MatchPageCopySettings = "Settings"
