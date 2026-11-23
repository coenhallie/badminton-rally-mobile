import AudioToolbox
import Shared
import SwiftUI
import UIKit

/// Full-screen courtside guided drill runner supporting timed interval drills
/// (auto Get Ready -> Work -> Rest) and self-paced rep/shuttle drills
/// (Complete Set -> auto Rest), with audio/haptic cues, `KeepAwake`, and 1-tap
/// completion logging. Port of androidApp's `DrillSessionDialog`.
struct DrillSessionSheet: View {
    let exercise: Exercise
    let racketArm: RacketArm?
    let playerId: String?
    let matchId: String?
    let drillProgress: DrillProgressRepository

    @Environment(\.dismiss) private var dismiss
    @Environment(KeepAwake.self) private var keepAwake: KeepAwake?

    @State private var state: DrillSessionState
    @State private var soundEnabled = true
    @State private var confirmExit = false
    @State private var selectedEffort: DrillEffort = .solid
    @State private var noteText = ""
    @State private var awakeId = UUID()

    init(
        exercise: Exercise,
        racketArm: RacketArm?,
        playerId: String?,
        matchId: String?,
        drillProgress: DrillProgressRepository
    ) {
        self.exercise = exercise
        self.racketArm = racketArm
        self.playerId = playerId
        self.matchId = matchId
        self.drillProgress = drillProgress
        _state = State(initialValue: DrillSessionKt.createDrillSession(exercise: exercise))
    }

    private var activeRunning: Bool {
        !state.paused && (state.phase == .getReady || state.phase == .work || state.phase == .rest)
    }

    private struct TimerKey: Equatable {
        let running: Bool
        let phase: DrillPhase
    }

    var body: some View {
        VStack(spacing: 0) {
            topBar

            switch state.phase {
            case .setup:
                setupStage
            case .getReady, .work, .rest:
                activeStage
            case .summary:
                summaryStage
            }
        }
        .frame(maxWidth: .infinity, maxHeight: .infinity)
        .background(Shuttl.bg)
        .onChange(of: activeRunning, initial: true) { _, running in
            if running {
                keepAwake?.boardAppeared(awakeId)
            } else {
                keepAwake?.boardDisappeared(awakeId)
            }
        }
        .onDisappear {
            keepAwake?.boardDisappeared(awakeId)
        }
        .task(id: TimerKey(running: activeRunning, phase: state.phase)) {
            guard activeRunning else { return }
            while !Task.isCancelled {
                try? await Task.sleep(for: .seconds(1))
                guard !Task.isCancelled else { break }
                let next = DrillSessionKt.tickDrillSession(state: state)
                state = next.state
                playCue(next.cue)
            }
        }
        .alert("End drill session?", isPresented: $confirmExit) {
            Button("End session", role: .destructive) { dismiss() }
            Button("Keep going", role: .cancel) {}
        } message: {
            Text("Your current sets will not be saved if you leave now.")
        }
    }

    private var topBar: some View {
        HStack(spacing: 8) {
            Button {
                requestClose()
            } label: {
                Image(systemName: "xmark")
                    .imageScale(.medium)
                    .foregroundStyle(Shuttl.textHeading)
                    .frame(width: 40, height: 40)
                    .contentShape(Rectangle())
            }
            .buttonStyle(.plain)
            .accessibilityLabel("Close")

            Text(exercise.title)
                .shuttlType(ShuttlType.titleMedium)
                .foregroundStyle(Shuttl.textHeading)
                .lineLimit(1)
                .truncationMode(.tail)
                .frame(maxWidth: .infinity, alignment: .leading)

            if state.phase != .summary {
                Button {
                    soundEnabled.toggle()
                } label: {
                    Text(soundEnabled ? "Sound on" : "Muted")
                        .shuttlType(ShuttlType.labelSmall)
                        .foregroundStyle(soundEnabled ? Shuttl.accentDark : Shuttl.textSecondary)
                        .padding(.horizontal, 10)
                        .padding(.vertical, 6)
                        .background(soundEnabled ? Shuttl.bgSecondary : Shuttl.bg, in: Capsule())
                        .overlay(Capsule().stroke(Shuttl.border, lineWidth: 1))
                }
                .buttonStyle(.plain)
            }
        }
        .padding(.horizontal, 12)
        .padding(.vertical, 8)
    }

    private var setupStage: some View {
        let plan = state.plan
        return VStack(spacing: 0) {
            ScrollView {
                VStack(alignment: .leading, spacing: 16) {
                    ExerciseVisualView(exerciseId: exercise.id, racketArm: racketArm)

                    VStack(alignment: .leading, spacing: 12) {
                        Text(DrillSessionKt.formatPlanSummary(plan: plan))
                            .shuttlType(ShuttlType.titleMedium)
                            .foregroundStyle(Shuttl.textHeading)

                        StepperRow(
                            label: "Sets",
                            valueText: "\(plan.sets)",
                            minusEnabled: plan.sets > 1,
                            plusEnabled: plan.sets < 15,
                            onMinus: { state = DrillSessionKt.adjustPlanSets(state: state, delta: -1) },
                            onPlus: { state = DrillSessionKt.adjustPlanSets(state: state, delta: 1) }
                        )

                        if plan.mode == .timed {
                            StepperRow(
                                label: "Work",
                                valueText: "\(plan.workSeconds) s",
                                minusEnabled: plan.workSeconds > 5,
                                plusEnabled: plan.workSeconds < 180,
                                onMinus: { state = DrillSessionKt.adjustPlanWork(state: state, delta: -5) },
                                onPlus: { state = DrillSessionKt.adjustPlanWork(state: state, delta: 5) }
                            )
                        } else {
                            StepperRow(
                                label: "Target / set",
                                valueText: "\(plan.targetReps) \(plan.repUnit)",
                                minusEnabled: plan.targetReps > 1,
                                plusEnabled: plan.targetReps < 60,
                                onMinus: { state = DrillSessionKt.adjustPlanWork(state: state, delta: -1) },
                                onPlus: { state = DrillSessionKt.adjustPlanWork(state: state, delta: 1) }
                            )
                        }

                        StepperRow(
                            label: "Rest",
                            valueText: plan.restSeconds == 0 ? "Off" : "\(plan.restSeconds) s",
                            minusEnabled: plan.restSeconds > 0,
                            plusEnabled: plan.restSeconds < 120,
                            onMinus: { state = DrillSessionKt.adjustPlanRest(state: state, delta: -5) },
                            onPlus: { state = DrillSessionKt.adjustPlanRest(state: state, delta: 5) }
                        )
                    }
                    .padding(16)
                    .background(Shuttl.bgSecondary, in: RoundedRectangle(cornerRadius: ShuttlRadius.large))
                    .overlay(
                        RoundedRectangle(cornerRadius: ShuttlRadius.large)
                            .stroke(Shuttl.border, lineWidth: 1)
                    )

                    VStack(alignment: .leading, spacing: 6) {
                        Text("\(ExercisesCopy.shared.SETUP_LABEL): \(exercise.setup)")
                            .shuttlType(ShuttlType.bodySmall)
                            .foregroundStyle(Shuttl.textSecondary)
                        Text("\(ExercisesCopy.shared.CUE_LABEL): \(exercise.coachingCue)")
                            .shuttlType(ShuttlType.bodySmall)
                            .foregroundStyle(Shuttl.text)
                    }
                }
                .padding(.horizontal, ShuttlGutter.page)
                .padding(.bottom, 12)
            }

            Button(ExercisesCopy.shared.START_SESSION) {
                let next = DrillSessionKt.startDrillSession(state: state)
                state = next.state
                playCue(next.cue)
            }
            .buttonStyle(PrimaryButtonStyle())
            .padding(.horizontal, ShuttlGutter.page)
            .padding(.vertical, 16)
        }
    }

    private var activeStage: some View {
        let plan = state.plan
        let isRest = state.phase == .rest
        let isReady = state.phase == .getReady
        let isTimedDisplay = isReady || isRest || plan.mode == .timed

        let phaseTitle: String = {
            if state.paused { return "PAUSED" }
            if isReady { return ExercisesCopy.shared.GET_READY_LABEL }
            if isRest { return "\(ExercisesCopy.shared.REST_LABEL) · NEXT SET \(state.currentSet + 1)" }
            return "\(ExercisesCopy.shared.WORK_LABEL) · SET \(state.currentSet) OF \(plan.sets)"
        }()
        let phaseColor: Color = {
            if state.paused { return Shuttl.textSecondary }
            if isRest { return Shuttl.warning }
            return Shuttl.accentDark
        }()

        return VStack(spacing: 0) {
            // Segmented set progress bar.
            HStack(spacing: 6) {
                ForEach(1...Int(plan.sets), id: \.self) { setIdx in
                    let color: Color = {
                        if setIdx <= Int(state.completedSets) { return Shuttl.accent }
                        if setIdx == Int(state.currentSet) && state.phase == .work {
                            return Shuttl.accent.opacity(0.50)
                        }
                        return Shuttl.border
                    }()
                    Capsule()
                        .fill(color)
                        .frame(height: 6)
                }
            }
            .padding(.top, 4)

            HStack {
                Text(ExercisesCopy.shared.setProgressLabel(currentSet: state.currentSet, totalSets: plan.sets))
                    .shuttlType(ShuttlType.labelSmall)
                    .foregroundStyle(Shuttl.textSecondary)
                Spacer()
                Text(DrillSessionKt.formatClockSeconds(seconds: state.elapsedSeconds))
                    .shuttlType(ShuttlType.labelSmall)
                    .foregroundStyle(Shuttl.textTertiary)
            }
            .padding(.top, 6)

            ExerciseVisualView(exerciseId: exercise.id, racketArm: racketArm)
                .opacity(isRest ? 0.42 : 1.0)
                .padding(.top, 12)

            Text("\(ExercisesCopy.shared.CUE_LABEL): \(exercise.coachingCue)")
                .shuttlType(ShuttlType.bodySmall)
                .foregroundStyle(Shuttl.textSecondary)
                .multilineTextAlignment(.center)
                .lineLimit(2)
                .padding(.top, 10)

            Spacer(minLength: 12)

            VStack(spacing: 12) {
                Text(phaseTitle)
                    .shuttlType(ShuttlType.labelSmall)
                    .foregroundStyle(phaseColor)

                if isTimedDisplay {
                    let progress: CGFloat = state.phaseTotalSeconds > 0
                        ? CGFloat(state.remainingSeconds) / CGFloat(state.phaseTotalSeconds)
                        : 0
                    let ringColor = isRest ? Shuttl.warning : Shuttl.accent
                    ZStack {
                        Circle()
                            .stroke(Shuttl.border, lineWidth: 8)
                        Circle()
                            .trim(from: 0, to: max(0, min(1, progress)))
                            .stroke(ringColor, style: StrokeStyle(lineWidth: 8, lineCap: .round))
                            .rotationEffect(.degrees(-90))
                        Text(isReady ? "\(state.remainingSeconds)" : DrillSessionKt.formatClockSeconds(seconds: state.remainingSeconds))
                            .shuttlType(ShuttlType.display)
                            .foregroundStyle(Shuttl.textHeading)
                    }
                    .frame(width: 144, height: 144)
                } else {
                    VStack(spacing: 2) {
                        Text("\(plan.targetReps)")
                            .shuttlType(ShuttlType.display)
                            .foregroundStyle(Shuttl.textHeading)
                        Text(plan.repUnit)
                            .shuttlType(ShuttlType.titleMedium)
                            .foregroundStyle(Shuttl.textSecondary)
                    }
                }
            }

            Spacer(minLength: 12)

            VStack(spacing: 10) {
                if isReady {
                    Button("Start now") {
                        let next = DrillSessionKt.skipGetReady(state: state)
                        state = next.state
                        playCue(next.cue)
                    }
                    .buttonStyle(PrimaryButtonStyle())
                } else if isRest {
                    Button(ExercisesCopy.shared.SKIP_REST) {
                        let next = DrillSessionKt.skipRest(state: state)
                        state = next.state
                        playCue(next.cue)
                    }
                    .buttonStyle(PrimaryButtonStyle())
                    HStack(spacing: 10) {
                        Button(ExercisesCopy.shared.ADD_REST_15) {
                            state = DrillSessionKt.extendRest(state: state, extraSeconds: 15)
                        }
                        .buttonStyle(SecondaryPillButtonStyle())
                        Button(state.paused ? "Resume" : "Pause") {
                            state = DrillSessionKt.toggleSessionPause(state: state)
                        }
                        .buttonStyle(SecondaryPillButtonStyle())
                    }
                } else if plan.mode == .reps {
                    Button(ExercisesCopy.shared.completeSetButton(currentSet: state.currentSet, totalSets: plan.sets)) {
                        let next = DrillSessionKt.completeCurrentSet(state: state)
                        state = next.state
                        playCue(next.cue)
                    }
                    .buttonStyle(PrimaryButtonStyle())
                    if state.completedSets > 0 {
                        Button(ExercisesCopy.shared.FINISH_EARLY) {
                            let next = DrillSessionKt.finishSessionEarly(state: state)
                            state = next.state
                            playCue(next.cue)
                        }
                        .buttonStyle(SecondaryPillButtonStyle())
                    }
                } else {
                    Button(state.paused ? "Resume" : "Pause") {
                        state = DrillSessionKt.toggleSessionPause(state: state)
                    }
                    .buttonStyle(PrimaryButtonStyle())
                    HStack(spacing: 10) {
                        Button(ExercisesCopy.shared.COMPLETE_SET) {
                            let next = DrillSessionKt.completeCurrentSet(state: state)
                            state = next.state
                            playCue(next.cue)
                        }
                        .buttonStyle(SecondaryPillButtonStyle())
                        if state.completedSets > 0 {
                            Button(ExercisesCopy.shared.FINISH_EARLY) {
                                let next = DrillSessionKt.finishSessionEarly(state: state)
                                state = next.state
                                playCue(next.cue)
                            }
                            .buttonStyle(SecondaryPillButtonStyle())
                        }
                    }
                }
            }
            .padding(.bottom, 16)
        }
        .padding(.horizontal, ShuttlGutter.page)
    }

    private var summaryStage: some View {
        VStack(spacing: 0) {
            ScrollView {
                VStack(spacing: 16) {
                    ZStack {
                        Circle()
                            .fill(Shuttl.accent)
                            .frame(width: 56, height: 56)
                        Image(systemName: "checkmark")
                            .imageScale(.large)
                            .foregroundStyle(Shuttl.onAccent)
                    }
                    .padding(.top, 12)

                    Text(ExercisesCopy.shared.SESSION_COMPLETE_TITLE)
                        .shuttlType(ShuttlType.headlineMedium)
                        .foregroundStyle(Shuttl.textHeading)

                    Text(exercise.title)
                        .shuttlType(ShuttlType.bodyMedium)
                        .foregroundStyle(Shuttl.textSecondary)
                        .multilineTextAlignment(.center)

                    HStack(spacing: 8) {
                        ShuttlStatTile(
                            value: ExercisesCopy.shared.setsCompletedStat(
                                completed: state.completedSets,
                                target: state.plan.sets
                            ),
                            unit: "",
                            label: "Sets completed"
                        )
                        ShuttlStatTile(
                            value: DrillSessionKt.formatClockSeconds(seconds: state.elapsedSeconds),
                            unit: "",
                            label: "Duration"
                        )
                    }

                    VStack(alignment: .leading, spacing: 8) {
                        Text(ExercisesCopy.shared.HOW_DID_IT_FEEL)
                            .shuttlType(ShuttlType.labelSmall)
                            .foregroundStyle(Shuttl.textSecondary)
                        HStack(spacing: 8) {
                            ForEach(DrillSessionKt.allDrillEfforts, id: \.self) { effort in
                                let active = effort == selectedEffort
                                Button {
                                    selectedEffort = effort
                                } label: {
                                    Text(effort.label)
                                        .shuttlType(ShuttlType.labelMedium)
                                        .foregroundStyle(active ? Shuttl.onAccent : Shuttl.textSecondary)
                                        .frame(maxWidth: .infinity)
                                        .padding(.vertical, 10)
                                        .background(active ? Shuttl.accent : Shuttl.bgSecondary, in: Capsule())
                                        .overlay(
                                            Capsule().stroke(active ? Shuttl.accent : Shuttl.border, lineWidth: 1)
                                        )
                                }
                                .buttonStyle(.plain)
                                .accessibilityAddTraits(active ? .isSelected : [])
                            }
                        }
                    }

                    TextField(ExercisesCopy.shared.NOTE_PLACEHOLDER, text: $noteText)
                        .shuttlType(ShuttlType.bodySmall)
                        .foregroundStyle(Shuttl.textHeading)
                        .padding(12)
                        .background(Shuttl.bgInput, in: RoundedRectangle(cornerRadius: ShuttlRadius.small))
                        .overlay(
                            RoundedRectangle(cornerRadius: ShuttlRadius.small)
                                .stroke(Shuttl.border, lineWidth: 1)
                        )
                        .onChange(of: noteText) { _, value in
                            if value.count > 160 { noteText = String(value.prefix(160)) }
                        }
                }
                .padding(.horizontal, ShuttlGutter.page)
            }

            VStack(spacing: 10) {
                Button(ExercisesCopy.shared.SAVE_SESSION) {
                    drillProgress.recordSession(
                        exerciseId: exercise.id,
                        playerId: playerId,
                        matchId: matchId,
                        setsCompleted: state.completedSets,
                        setsTarget: state.plan.sets,
                        durationSeconds: state.elapsedSeconds,
                        effort: selectedEffort,
                        note: noteText
                    )
                    dismiss()
                }
                .buttonStyle(PrimaryButtonStyle())

                Button(ExercisesCopy.shared.DISCARD_SESSION) {
                    dismiss()
                }
                .buttonStyle(SecondaryPillButtonStyle())
            }
            .padding(.horizontal, ShuttlGutter.page)
            .padding(.vertical, 16)
        }
    }

    private func requestClose() {
        let midWorkout = (state.phase == .getReady || state.phase == .work || state.phase == .rest)
            && (state.completedSets > 0 || state.elapsedSeconds >= 5)
        if midWorkout {
            confirmExit = true
        } else {
            dismiss()
        }
    }

    private func playCue(_ cue: DrillCueEvent) {
        switch cue {
        case .none:
            return
        case .countdownTick:
            UIImpactFeedbackGenerator(style: .light).impactOccurred()
            if soundEnabled { AudioServicesPlaySystemSound(1104) }
        case .workStart:
            UIImpactFeedbackGenerator(style: .medium).impactOccurred()
            if soundEnabled { AudioServicesPlaySystemSound(1057) }
        case .restStart:
            UIImpactFeedbackGenerator(style: .soft).impactOccurred()
            if soundEnabled { AudioServicesPlaySystemSound(1052) }
        case .sessionComplete:
            UINotificationFeedbackGenerator().notificationOccurred(.success)
            if soundEnabled { AudioServicesPlaySystemSound(1025) }
        }
    }
}

private struct StepperRow: View {
    let label: String
    let valueText: String
    let minusEnabled: Bool
    let plusEnabled: Bool
    let onMinus: () -> Void
    let onPlus: () -> Void

    var body: some View {
        HStack(spacing: 8) {
            Text(label)
                .shuttlType(ShuttlType.bodyMedium)
                .foregroundStyle(Shuttl.textSecondary)
                .frame(maxWidth: .infinity, alignment: .leading)
            StepperCircleButton(symbol: "-", enabled: minusEnabled, action: onMinus)
            Text(valueText)
                .shuttlType(ShuttlType.titleMedium)
                .foregroundStyle(Shuttl.textHeading)
                .frame(width: 108)
                .lineLimit(1)
                .truncationMode(.tail)
            StepperCircleButton(symbol: "+", enabled: plusEnabled, action: onPlus)
        }
    }
}

private struct StepperCircleButton: View {
    let symbol: String
    let enabled: Bool
    let action: () -> Void

    var body: some View {
        Button(action: action) {
            Text(symbol)
                .shuttlType(ShuttlType.titleLarge)
                .foregroundStyle(Shuttl.textHeading)
                .frame(width: 36, height: 36)
                .background(Shuttl.bg, in: Circle())
                .overlay(Circle().stroke(Shuttl.border, lineWidth: 1))
        }
        .buttonStyle(.plain)
        .disabled(!enabled)
        .opacity(enabled ? 1 : 0.35)
    }
}
