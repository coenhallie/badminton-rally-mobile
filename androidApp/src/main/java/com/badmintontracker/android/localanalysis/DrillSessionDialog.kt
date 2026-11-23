package com.badmintontracker.android.localanalysis

import android.media.AudioManager
import android.media.ToneGenerator
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.badmintontracker.android.ui.components.ConfirmDialog
import com.badmintontracker.android.ui.components.ShuttlButton
import com.badmintontracker.android.ui.components.ShuttlButtonVariant
import com.badmintontracker.android.ui.components.ShuttlStatTile
import com.badmintontracker.android.ui.theme.ShuttlRadius
import com.badmintontracker.android.ui.theme.ShuttlTheme
import com.badmintontracker.android.ui.theme.ShuttlTypeExtras
import com.badmintontracker.shared.exercises.DrillCueEvent
import com.badmintontracker.shared.exercises.DrillEffort
import com.badmintontracker.shared.exercises.DrillPhase
import com.badmintontracker.shared.exercises.DrillProgressRepository
import com.badmintontracker.shared.exercises.DrillSessionState
import com.badmintontracker.shared.exercises.DrillWorkMode
import com.badmintontracker.shared.exercises.Exercise
import com.badmintontracker.shared.exercises.ExercisesCopy
import com.badmintontracker.shared.exercises.adjustPlanRest
import com.badmintontracker.shared.exercises.adjustPlanSets
import com.badmintontracker.shared.exercises.adjustPlanWork
import com.badmintontracker.shared.exercises.allDrillEfforts
import com.badmintontracker.shared.exercises.completeCurrentSet
import com.badmintontracker.shared.exercises.createDrillSession
import com.badmintontracker.shared.exercises.extendRest
import com.badmintontracker.shared.exercises.finishSessionEarly
import com.badmintontracker.shared.exercises.formatClockSeconds
import com.badmintontracker.shared.exercises.formatPlanSummary
import com.badmintontracker.shared.exercises.skipGetReady
import com.badmintontracker.shared.exercises.skipRest
import com.badmintontracker.shared.exercises.startDrillSession
import com.badmintontracker.shared.exercises.tickDrillSession
import com.badmintontracker.shared.exercises.toggleSessionPause
import com.badmintontracker.shared.prefs.RacketArm
import kotlinx.coroutines.delay

/**
 * Full-screen courtside guided drill runner supporting both timed interval
 * drills (auto Get Ready -> Work -> Rest) and self-paced rep/shuttle drills
 * (Complete Set -> auto Rest), with audio/haptic cues, keep-awake, and 1-tap
 * completion logging.
 */
@Composable
fun DrillSessionDialog(
    exercise: Exercise,
    racketArm: RacketArm?,
    playerId: String?,
    matchId: String?,
    drillProgress: DrillProgressRepository,
    onDismiss: () -> Unit,
) {
    var state by remember(exercise.id) { mutableStateOf(createDrillSession(exercise)) }
    var soundEnabled by remember { mutableStateOf(true) }
    var confirmExit by remember { mutableStateOf(false) }
    var selectedEffort by remember(exercise.id) { mutableStateOf(DrillEffort.SOLID) }
    var noteText by remember(exercise.id) { mutableStateOf("") }

    val view = LocalView.current
    val haptic = LocalHapticFeedback.current
    val toneGenerator = remember {
        runCatching { ToneGenerator(AudioManager.STREAM_MUSIC, 80) }.getOrNull()
    }
    DisposableEffect(toneGenerator) {
        onDispose { runCatching { toneGenerator?.release() } }
    }

    val activeRunning = !state.paused &&
        (state.phase == DrillPhase.GET_READY || state.phase == DrillPhase.WORK || state.phase == DrillPhase.REST)
    DisposableEffect(view, activeRunning) {
        val previous = view.keepScreenOn
        if (activeRunning) view.keepScreenOn = true
        onDispose { view.keepScreenOn = previous }
    }

    fun playCue(cue: DrillCueEvent) {
        if (cue == DrillCueEvent.NONE) return
        when (cue) {
            DrillCueEvent.COUNTDOWN_TICK -> {
                haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                if (soundEnabled) runCatching { toneGenerator?.startTone(ToneGenerator.TONE_PROP_BEEP, 90) }
            }
            DrillCueEvent.WORK_START -> {
                haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                if (soundEnabled) runCatching { toneGenerator?.startTone(ToneGenerator.TONE_PROP_BEEP2, 220) }
            }
            DrillCueEvent.REST_START -> {
                haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                if (soundEnabled) runCatching { toneGenerator?.startTone(ToneGenerator.TONE_PROP_ACK, 180) }
            }
            DrillCueEvent.SESSION_COMPLETE -> {
                haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                if (soundEnabled) runCatching { toneGenerator?.startTone(ToneGenerator.TONE_PROP_PROMPT, 350) }
            }
            DrillCueEvent.NONE -> Unit
        }
    }

    LaunchedEffect(activeRunning, state.phase) {
        if (!activeRunning) return@LaunchedEffect
        while (true) {
            delay(1000L)
            val transition = tickDrillSession(state)
            state = transition.state
            playCue(transition.cue)
        }
    }

    fun requestClose() {
        val midWorkout = state.phase in setOf(DrillPhase.GET_READY, DrillPhase.WORK, DrillPhase.REST) &&
            (state.completedSets > 0 || state.elapsedSeconds >= 5)
        if (midWorkout) {
            confirmExit = true
        } else {
            onDismiss()
        }
    }

    Dialog(
        onDismissRequest = ::requestClose,
        properties = DialogProperties(
            usePlatformDefaultWidth = false,
            dismissOnBackPress = true,
            dismissOnClickOutside = false,
        ),
    ) {
        Surface(
            modifier = Modifier.fillMaxSize(),
            color = MaterialTheme.colorScheme.background,
        ) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .windowInsetsPadding(WindowInsets.safeDrawing),
            ) {
                // Top bar: Close, Drill title, Sound toggle.
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 12.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    IconButton(onClick = ::requestClose) {
                        Icon(
                            imageVector = Icons.Default.Close,
                            contentDescription = "Close",
                            tint = MaterialTheme.colorScheme.onBackground,
                        )
                    }
                    Text(
                        text = exercise.title,
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.onBackground,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f).padding(horizontal = 4.dp),
                    )
                    if (state.phase != DrillPhase.SUMMARY) {
                        val soundShape = RoundedCornerShape(ShuttlRadius.pill)
                        Box(
                            modifier = Modifier
                                .clip(soundShape)
                                .background(
                                    if (soundEnabled) MaterialTheme.colorScheme.surfaceVariant
                                    else MaterialTheme.colorScheme.background,
                                    soundShape,
                                )
                                .border(1.dp, MaterialTheme.colorScheme.outlineVariant, soundShape)
                                .clickable(role = Role.Button) { soundEnabled = !soundEnabled }
                                .padding(horizontal = 10.dp, vertical = 6.dp),
                        ) {
                            Text(
                                text = if (soundEnabled) "Sound on" else "Muted",
                                style = MaterialTheme.typography.labelSmall,
                                color = if (soundEnabled) ShuttlTheme.extended.accentDark
                                else MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }

                when (state.phase) {
                    DrillPhase.SETUP -> SetupStage(
                        exercise = exercise,
                        racketArm = racketArm,
                        state = state,
                        onAdjustSets = { state = adjustPlanSets(state, it) },
                        onAdjustWork = { state = adjustPlanWork(state, it) },
                        onAdjustRest = { state = adjustPlanRest(state, it) },
                        onStart = {
                            val next = startDrillSession(state)
                            state = next.state
                            playCue(next.cue)
                        },
                        modifier = Modifier.weight(1f),
                    )

                    DrillPhase.GET_READY, DrillPhase.WORK, DrillPhase.REST -> ActiveStage(
                        exercise = exercise,
                        racketArm = racketArm,
                        state = state,
                        onSkipReady = {
                            val next = skipGetReady(state)
                            state = next.state
                            playCue(next.cue)
                        },
                        onCompleteSet = {
                            val next = completeCurrentSet(state)
                            state = next.state
                            playCue(next.cue)
                        },
                        onSkipRest = {
                            val next = skipRest(state)
                            state = next.state
                            playCue(next.cue)
                        },
                        onExtendRest = { state = extendRest(state, 15) },
                        onTogglePause = { state = toggleSessionPause(state) },
                        onFinishEarly = {
                            val next = finishSessionEarly(state)
                            state = next.state
                            playCue(next.cue)
                        },
                        modifier = Modifier.weight(1f),
                    )

                    DrillPhase.SUMMARY -> SummaryStage(
                        exercise = exercise,
                        state = state,
                        selectedEffort = selectedEffort,
                        onSelectEffort = { selectedEffort = it },
                        noteText = noteText,
                        onNoteChange = { noteText = it },
                        onSave = {
                            drillProgress.recordSession(
                                exerciseId = exercise.id,
                                playerId = playerId,
                                matchId = matchId,
                                setsCompleted = state.completedSets,
                                setsTarget = state.plan.sets,
                                durationSeconds = state.elapsedSeconds,
                                effort = selectedEffort,
                                note = noteText,
                            )
                            onDismiss()
                        },
                        onDiscard = onDismiss,
                        modifier = Modifier.weight(1f),
                    )
                }
            }
        }
    }

    if (confirmExit) {
        ConfirmDialog(
            title = "End drill session?",
            message = "Your current sets will not be saved if you leave now.",
            confirmLabel = "End session",
            onConfirm = {
                confirmExit = false
                onDismiss()
            },
            onDismiss = { confirmExit = false },
        )
    }
}

@Composable
private fun SetupStage(
    exercise: Exercise,
    racketArm: RacketArm?,
    state: DrillSessionState,
    onAdjustSets: (Int) -> Unit,
    onAdjustWork: (Int) -> Unit,
    onAdjustRest: (Int) -> Unit,
    onStart: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val plan = state.plan
    Column(modifier = modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier
                .weight(1f)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = COURT_GUTTER),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            ExerciseVisualView(exerciseId = exercise.id, racketArm = racketArm)

            // Customizable volume steppers.
            val cardShape = RoundedCornerShape(ShuttlRadius.large)
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(cardShape)
                    .background(MaterialTheme.colorScheme.surfaceVariant, cardShape)
                    .border(1.dp, MaterialTheme.colorScheme.outlineVariant, cardShape)
                    .padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Text(
                    formatPlanSummary(plan),
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onBackground,
                )
                StepperRow(
                    label = "Sets",
                    valueText = "${plan.sets}",
                    onMinus = { onAdjustSets(-1) },
                    onPlus = { onAdjustSets(1) },
                    minusEnabled = plan.sets > 1,
                    plusEnabled = plan.sets < 15,
                )
                if (plan.mode == DrillWorkMode.TIMED) {
                    StepperRow(
                        label = "Work",
                        valueText = "${plan.workSeconds} s",
                        onMinus = { onAdjustWork(-5) },
                        onPlus = { onAdjustWork(5) },
                        minusEnabled = plan.workSeconds > 5,
                        plusEnabled = plan.workSeconds < 180,
                    )
                } else {
                    StepperRow(
                        label = "Target / set",
                        valueText = "${plan.targetReps} ${plan.repUnit}",
                        onMinus = { onAdjustWork(-1) },
                        onPlus = { onAdjustWork(1) },
                        minusEnabled = plan.targetReps > 1,
                        plusEnabled = plan.targetReps < 60,
                    )
                }
                StepperRow(
                    label = "Rest",
                    valueText = if (plan.restSeconds == 0) "Off" else "${plan.restSeconds} s",
                    onMinus = { onAdjustRest(-5) },
                    onPlus = { onAdjustRest(5) },
                    minusEnabled = plan.restSeconds > 0,
                    plusEnabled = plan.restSeconds < 120,
                )
            }

            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(
                    "${ExercisesCopy.SETUP_LABEL}: ${exercise.setup}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text(
                    "${ExercisesCopy.CUE_LABEL}: ${exercise.coachingCue}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onBackground,
                )
            }
            Spacer(Modifier.height(8.dp))
        }

        Box(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = COURT_GUTTER, vertical = 16.dp),
        ) {
            ShuttlButton(
                text = ExercisesCopy.START_SESSION,
                onClick = onStart,
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
}

@Composable
private fun StepperRow(
    label: String,
    valueText: String,
    onMinus: () -> Unit,
    onPlus: () -> Unit,
    minusEnabled: Boolean,
    plusEnabled: Boolean,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.weight(1f),
        )
        StepperButton("-", enabled = minusEnabled, onClick = onMinus)
        Text(
            text = valueText,
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.onBackground,
            textAlign = TextAlign.Center,
            modifier = Modifier.width(108.dp),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        StepperButton("+", enabled = plusEnabled, onClick = onPlus)
    }
}

@Composable
private fun StepperButton(
    symbol: String,
    enabled: Boolean,
    onClick: () -> Unit,
) {
    Box(
        modifier = Modifier
            .size(36.dp)
            .alpha(if (enabled) 1f else 0.35f)
            .clip(CircleShape)
            .background(MaterialTheme.colorScheme.background, CircleShape)
            .border(1.dp, MaterialTheme.colorScheme.outlineVariant, CircleShape)
            .clickable(enabled = enabled, role = Role.Button, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = symbol,
            style = MaterialTheme.typography.titleLarge,
            color = MaterialTheme.colorScheme.onBackground,
        )
    }
}

@Composable
private fun ActiveStage(
    exercise: Exercise,
    racketArm: RacketArm?,
    state: DrillSessionState,
    onSkipReady: () -> Unit,
    onCompleteSet: () -> Unit,
    onSkipRest: () -> Unit,
    onExtendRest: () -> Unit,
    onTogglePause: () -> Unit,
    onFinishEarly: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val plan = state.plan
    val isRest = state.phase == DrillPhase.REST
    val isReady = state.phase == DrillPhase.GET_READY
    val isTimedDisplay = isReady || isRest || plan.mode == DrillWorkMode.TIMED

    Column(
        modifier = modifier.fillMaxWidth().padding(horizontal = COURT_GUTTER),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        // Segmented set progress bar.
        Row(
            modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            for (setIdx in 1..plan.sets) {
                val color = when {
                    setIdx <= state.completedSets -> MaterialTheme.colorScheme.primary
                    setIdx == state.currentSet && state.phase == DrillPhase.WORK ->
                        MaterialTheme.colorScheme.primary.copy(alpha = 0.50f)
                    else -> MaterialTheme.colorScheme.outlineVariant
                }
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .height(6.dp)
                        .clip(RoundedCornerShape(ShuttlRadius.pill))
                        .background(color),
                )
            }
        }
        Spacer(Modifier.height(6.dp))
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Text(
                text = ExercisesCopy.setProgressLabel(state.currentSet, plan.sets),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                text = formatClockSeconds(state.elapsedSeconds),
                style = MaterialTheme.typography.labelSmall,
                color = ShuttlTheme.extended.textTertiary,
            )
        }

        Spacer(Modifier.height(12.dp))

        // Live animation dims calmly during Rest so the eye rests between sets.
        Box(modifier = Modifier.alpha(if (isRest) 0.42f else 1f)) {
            ExerciseVisualView(exerciseId = exercise.id, racketArm = racketArm)
        }

        Spacer(Modifier.height(10.dp))
        Text(
            text = "${ExercisesCopy.CUE_LABEL}: ${exercise.coachingCue}",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.fillMaxWidth(),
        )

        // Centered glanceable readout (timer ring or big rep target).
        Column(
            modifier = Modifier.weight(1f).fillMaxWidth(),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            val phaseTitle = when {
                state.paused -> "PAUSED"
                isReady -> ExercisesCopy.GET_READY_LABEL
                isRest -> "${ExercisesCopy.REST_LABEL} · NEXT SET ${state.currentSet + 1}"
                else -> "${ExercisesCopy.WORK_LABEL} · SET ${state.currentSet} OF ${plan.sets}"
            }
            val phaseColor = when {
                state.paused -> MaterialTheme.colorScheme.onSurfaceVariant
                isRest -> ShuttlTheme.extended.warning
                else -> ShuttlTheme.extended.accentDark
            }
            Text(
                text = phaseTitle,
                style = MaterialTheme.typography.labelSmall,
                color = phaseColor,
            )
            Spacer(Modifier.height(12.dp))

            if (isTimedDisplay) {
                val progress = if (state.phaseTotalSeconds > 0) {
                    state.remainingSeconds.toFloat() / state.phaseTotalSeconds.toFloat()
                } else {
                    0f
                }
                val ringColor = if (isRest) ShuttlTheme.extended.warning else MaterialTheme.colorScheme.primary
                val trackColor = MaterialTheme.colorScheme.outlineVariant
                Box(
                    modifier = Modifier.size(144.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    Canvas(modifier = Modifier.fillMaxSize()) {
                        val strokeW = 8.dp.toPx()
                        drawArc(
                            color = trackColor,
                            startAngle = -90f,
                            sweepAngle = 360f,
                            useCenter = false,
                            style = Stroke(width = strokeW, cap = StrokeCap.Round),
                        )
                        drawArc(
                            color = ringColor,
                            startAngle = -90f,
                            sweepAngle = 360f * progress.coerceIn(0f, 1f),
                            useCenter = false,
                            style = Stroke(width = strokeW, cap = StrokeCap.Round),
                        )
                    }
                    Text(
                        text = if (isReady) "${state.remainingSeconds}" else formatClockSeconds(state.remainingSeconds),
                        style = ShuttlTypeExtras.display,
                        color = MaterialTheme.colorScheme.onBackground,
                    )
                }
            } else {
                Text(
                    text = "${plan.targetReps}",
                    style = ShuttlTypeExtras.display,
                    color = MaterialTheme.colorScheme.onBackground,
                )
                Text(
                    text = plan.repUnit,
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }

        // Thumb-reachable bottom controls.
        Column(
            modifier = Modifier.fillMaxWidth().padding(bottom = 16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            when {
                isReady -> {
                    ShuttlButton(
                        text = "Start now",
                        onClick = onSkipReady,
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
                isRest -> {
                    ShuttlButton(
                        text = ExercisesCopy.SKIP_REST,
                        onClick = onSkipRest,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        ShuttlButton(
                            text = ExercisesCopy.ADD_REST_15,
                            onClick = onExtendRest,
                            variant = ShuttlButtonVariant.Secondary,
                            modifier = Modifier.weight(1f),
                        )
                        ShuttlButton(
                            text = if (state.paused) "Resume" else "Pause",
                            onClick = onTogglePause,
                            variant = ShuttlButtonVariant.Secondary,
                            modifier = Modifier.weight(1f),
                        )
                    }
                }
                plan.mode == DrillWorkMode.REPS -> {
                    ShuttlButton(
                        text = ExercisesCopy.completeSetButton(state.currentSet, plan.sets),
                        onClick = onCompleteSet,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    if (state.completedSets > 0) {
                        ShuttlButton(
                            text = ExercisesCopy.FINISH_EARLY,
                            onClick = onFinishEarly,
                            variant = ShuttlButtonVariant.Secondary,
                            modifier = Modifier.fillMaxWidth(),
                        )
                    }
                }
                else -> {
                    ShuttlButton(
                        text = if (state.paused) "Resume" else "Pause",
                        onClick = onTogglePause,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        ShuttlButton(
                            text = ExercisesCopy.COMPLETE_SET,
                            onClick = onCompleteSet,
                            variant = ShuttlButtonVariant.Secondary,
                            modifier = Modifier.weight(1f),
                        )
                        if (state.completedSets > 0) {
                            ShuttlButton(
                                text = ExercisesCopy.FINISH_EARLY,
                                onClick = onFinishEarly,
                                variant = ShuttlButtonVariant.Secondary,
                                modifier = Modifier.weight(1f),
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun SummaryStage(
    exercise: Exercise,
    state: DrillSessionState,
    selectedEffort: DrillEffort,
    onSelectEffort: (DrillEffort) -> Unit,
    noteText: String,
    onNoteChange: (String) -> Unit,
    onSave: () -> Unit,
    onDiscard: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier
                .weight(1f)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = COURT_GUTTER, vertical = 12.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Box(
                modifier = Modifier
                    .size(56.dp)
                    .clip(CircleShape)
                    .background(MaterialTheme.colorScheme.primary, CircleShape),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    imageVector = Icons.Default.Check,
                    contentDescription = null,
                    tint = ShuttlTheme.extended.onAccent,
                    modifier = Modifier.size(30.dp),
                )
            }
            Text(
                text = ExercisesCopy.SESSION_COMPLETE_TITLE,
                style = MaterialTheme.typography.headlineMedium,
                color = MaterialTheme.colorScheme.onBackground,
            )
            Text(
                text = exercise.title,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
            )

            Row(
                modifier = Modifier.fillMaxWidth().height(IntrinsicSize.Min),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                ShuttlStatTile(
                    value = ExercisesCopy.setsCompletedStat(state.completedSets, state.plan.sets),
                    unit = "",
                    label = "Sets completed",
                    modifier = Modifier.weight(1f).fillMaxHeight(),
                )
                ShuttlStatTile(
                    value = formatClockSeconds(state.elapsedSeconds),
                    unit = "",
                    label = "Duration",
                    modifier = Modifier.weight(1f).fillMaxHeight(),
                )
            }

            Column(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text(
                    text = ExercisesCopy.HOW_DID_IT_FEEL,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    allDrillEfforts.forEach { effort ->
                        val active = effort == selectedEffort
                        val shape = RoundedCornerShape(ShuttlRadius.pill)
                        Box(
                            modifier = Modifier
                                .weight(1f)
                                .clip(shape)
                                .background(
                                    if (active) MaterialTheme.colorScheme.primary
                                    else MaterialTheme.colorScheme.surfaceVariant,
                                    shape,
                                )
                                .border(
                                    1.dp,
                                    if (active) MaterialTheme.colorScheme.primary
                                    else MaterialTheme.colorScheme.outlineVariant,
                                    shape,
                                )
                                .semantics { selected = active }
                                .clickable(role = Role.RadioButton) { onSelectEffort(effort) }
                                .padding(vertical = 10.dp),
                            contentAlignment = Alignment.Center,
                        ) {
                            Text(
                                text = effort.label,
                                style = MaterialTheme.typography.labelMedium,
                                color = if (active) ShuttlTheme.extended.onAccent
                                else MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
            }

            OutlinedTextField(
                value = noteText,
                onValueChange = { if (it.length <= 160) onNoteChange(it) },
                placeholder = {
                    Text(
                        ExercisesCopy.NOTE_PLACEHOLDER,
                        style = MaterialTheme.typography.bodySmall,
                        color = ShuttlTheme.extended.textTertiary,
                    )
                },
                singleLine = true,
                shape = RoundedCornerShape(ShuttlRadius.small),
                colors = OutlinedTextFieldDefaults.colors(
                    focusedContainerColor = ShuttlTheme.extended.bgInput,
                    unfocusedContainerColor = ShuttlTheme.extended.bgInput,
                    focusedBorderColor = MaterialTheme.colorScheme.primary,
                    unfocusedBorderColor = MaterialTheme.colorScheme.outlineVariant,
                    focusedTextColor = MaterialTheme.colorScheme.onBackground,
                    unfocusedTextColor = MaterialTheme.colorScheme.onBackground,
                ),
                modifier = Modifier.fillMaxWidth(),
            )
        }

        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = COURT_GUTTER, vertical = 16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            ShuttlButton(
                text = ExercisesCopy.SAVE_SESSION,
                onClick = onSave,
                modifier = Modifier.fillMaxWidth(),
            )
            ShuttlButton(
                text = ExercisesCopy.DISCARD_SESSION,
                onClick = onDiscard,
                variant = ShuttlButtonVariant.Secondary,
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
}
