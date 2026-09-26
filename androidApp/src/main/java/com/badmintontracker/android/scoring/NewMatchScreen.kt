package com.badmintontracker.android.scoring

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
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
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.badmintontracker.android.localanalysis.BackgroundWorkAction
import com.badmintontracker.android.ui.components.ShuttlButton
import com.badmintontracker.android.ui.components.ShuttlOutlinedTextField
import com.badmintontracker.shared.scoring.GamePoints
import com.badmintontracker.shared.scoring.MAX_MATCH_TITLE
import com.badmintontracker.shared.scoring.MatchSets

/**
 * The match a coach creates before there is any video - §2 step 1. It asks only for
 * what the fold cannot guess: how long a game is and how many sets. The name arrives
 * filled in with the time and, where the phone will say, the place, so the form can
 * be submitted the moment it opens.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun NewMatchScreen(
    vm: NewMatchViewModel,
    onCreated: (String) -> Unit,
    onBack: () -> Unit,
) {
    val state by vm.state.collectAsStateWithLifecycle()
    PlaceForTitle(onAllowed = vm::addPlaceToTitle)

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

            SectionLabel("Points")
            ShuttlPillTabs(
                labels = GamePoints.entries.map { "${it.pointsToWin}" },
                selectedIndex = state.points.ordinal,
                onSelect = { vm.setPoints(GamePoints.entries[it]) },
            )

            SectionLabel("Sets")
            ShuttlPillTabs(
                labels = MatchSets.entries.map { "${it.sets}" },
                selectedIndex = state.sets.ordinal,
                onSelect = { vm.setSets(MatchSets.entries[it]) },
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
 * Asks for the place once, and only ever once: a second prompt after a no is
 * nagging for something the form works perfectly well without. After a refusal
 * Android reports that a rationale is due, and that is read here as "already
 * asked, and the answer was no".
 */
@Composable
private fun PlaceForTitle(onAllowed: () -> Unit) {
    val context = LocalContext.current
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) onAllowed()
    }
    LaunchedEffect(Unit) {
        when {
            AndroidPlaceNames.hasPermission(context) -> onAllowed()
            context.findActivity()?.shouldShowRequestPermissionRationale(AndroidPlaceNames.PERMISSION) == true -> Unit
            else -> launcher.launch(AndroidPlaceNames.PERMISSION)
        }
    }
}

private tailrec fun Context.findActivity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.findActivity()
    else -> null
}

/** The small uppercase heading over a group of fields. Shared with [EditMatchDetailsDialog]. */
@Composable
internal fun SectionLabel(text: String) {
    Text(
        text = text.uppercase(),
        fontSize = 11.sp,
        fontWeight = FontWeight.Medium,
        letterSpacing = 0.55.sp,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}
