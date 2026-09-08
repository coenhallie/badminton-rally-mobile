package com.badmintontracker.android.scoring

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import com.badmintontracker.android.ui.components.ShuttlPillTabs
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.badmintontracker.android.localanalysis.BackgroundWorkAction
import com.badmintontracker.android.ui.components.ShuttlButton
import com.badmintontracker.android.ui.components.ShuttlOutlinedTextField
import com.badmintontracker.shared.scoring.MAX_MATCH_TITLE
import com.badmintontracker.shared.scoring.MAX_PLAYER_NAME
import com.badmintontracker.shared.scoring.ScoringRules
import com.badmintontracker.shared.scoring.Side

/**
 * The match a coach creates before there is any video - §2 step 1. Everything it
 * asks for is what the fold needs plus the names to put on the board; the date is
 * deliberately not asked, because a match is created at the moment it is played.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun NewMatchScreen(
    vm: NewMatchViewModel,
    onCreated: (String) -> Unit,
    onBack: () -> Unit,
) {
    val state by vm.state.collectAsStateWithLifecycle()

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("New match") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
                actions = { BackgroundWorkAction() },
            )
        },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .imePadding()
                .padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Spacer(Modifier.height(4.dp))

            ShuttlOutlinedTextField(
                value = state.title,
                onValueChange = vm::setTitle,
                label = "Match name",
                maxLength = MAX_MATCH_TITLE,
                modifier = Modifier.fillMaxWidth(),
            )

            SectionLabel("Format")
            ShuttlPillTabs(
                labels = listOf("Singles", "Doubles"),
                selectedIndex = if (state.doubles) 1 else 0,
                onSelect = { vm.setDoubles(it == 1) },
            )

            SectionLabel("Players")
            PlayerField(state.homePlayers[0], "Home player") { vm.setPlayer(Side.HOME, 0, it) }
            // Animated rather than instant: the field below it is already on screen,
            // and a row appearing under the user's thumb without motion reads as a jump.
            AnimatedVisibility(visible = state.doubles) {
                PlayerField(state.homePlayers[1], "Home partner") { vm.setPlayer(Side.HOME, 1, it) }
            }
            PlayerField(state.awayPlayers[0], "Away player") { vm.setPlayer(Side.AWAY, 0, it) }
            AnimatedVisibility(visible = state.doubles) {
                PlayerField(state.awayPlayers[1], "Away partner") { vm.setPlayer(Side.AWAY, 1, it) }
            }

            SectionLabel("Scoring")
            ShuttlPillTabs(
                labels = ScoringRules.PRESETS.map { it.label() },
                selectedIndex = ScoringRules.PRESETS.indexOf(state.rules),
                onSelect = { vm.setRules(ScoringRules.PRESETS[it]) },
            )

            SectionLabel("Coin toss")
            ShuttlPillTabs(
                labels = listOf("Home serves", "Away serves"),
                selectedIndex = if (state.firstServer == Side.AWAY) 1 else 0,
                onSelect = { vm.setFirstServer(if (it == 1) Side.AWAY else Side.HOME) },
            )

            state.problem?.let {
                Text(
                    text = it,
                    color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodySmall,
                )
            }

            ShuttlButton(
                text = "Create match",
                onClick = { vm.create()?.let(onCreated) },
                enabled = state.canCreate,
                modifier = Modifier.fillMaxWidth(),
            )
            Spacer(Modifier.height(16.dp))
        }
    }
}

/**
 * User-facing names for the shared presets. The list is shared so both platforms
 * offer the same rule sets; the wording is per platform copy.
 */
private fun ScoringRules.label(): String = when {
    pointsToWin == 21 -> "21 points"
    winBy == 1 -> "15 straight"
    else -> "15 points"
}

@Composable
private fun PlayerField(value: String, label: String, onChange: (String) -> Unit) {
    ShuttlOutlinedTextField(
        value = value,
        onValueChange = onChange,
        label = label,
        maxLength = MAX_PLAYER_NAME,
        modifier = Modifier.fillMaxWidth(),
    )
}

@Composable
private fun SectionLabel(text: String) {
    Text(
        text = text.uppercase(),
        fontSize = 11.sp,
        fontWeight = FontWeight.Medium,
        letterSpacing = 0.55.sp,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}
