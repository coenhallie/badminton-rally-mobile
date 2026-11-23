import Shared
import SwiftUI

/// Suggested drills and exercises derived from a completed video analysis.
/// Port of androidApp's `ExercisesPanel`, following `BasePositionPanel`'s
/// threading, side-toggle placement, and empty-message ladder.
struct ExercisesPanel: View {
    let source: HeatmapSource?
    let windows: [RallyWindow]
    let sideNames: CourtSideNames?

    @State private var report: ExerciseReport? = nil
    @State private var resolved = false
    @State private var side: CourtSide = .near
    @State private var expandedIds: Set<String> = []

    var body: some View {
        Group {
            if let source, source.tracks.count > 1, let shown {
                VStack(spacing: 0) {
                    SideToggle(
                        sides: source.tracks.map(\.side),
                        selected: shown.side,
                        onSelect: { side = $0 },
                        names: sideNames
                    )
                    .padding(.horizontal, ShuttlGutter.page)
                    .padding(.vertical, 8)
                    inner
                }
            } else {
                inner
            }
        }
        .task(id: inputs) {
            let chosen = shown?.track
            let rate = source?.fps ?? 0
            let rallies = windows
            let currentSide = shownSide
            let arm = shownArm
            let measured = await Task.detached(priority: .userInitiated) {
                ExercisesPanel.evaluate(
                    track: chosen,
                    fps: rate,
                    windows: rallies,
                    side: currentSide,
                    racketArm: arm
                )
            }.value
            report = measured
            if let firstId = measured?.suggestions.first?.exercise.id {
                expandedIds = [firstId]
            } else {
                expandedIds = []
            }
            resolved = true
        }
    }

    @ViewBuilder
    private var inner: some View {
        Group {
            if let message {
                PanelMessage(text: message)
            } else if let report {
                content(report)
            } else {
                Color.clear.frame(height: 0)
            }
        }
    }

    @ViewBuilder
    private func content(_ report: ExerciseReport) -> some View {
        VStack(alignment: .leading, spacing: 0) {
            if let summary = report.summary {
                summaryBlock(summary)
            }
            switch report.status {
            case .insufficientData, .balanced:
                PanelMessage(text: report.message ?? ExercisesCopy.shared.NO_COUNTED_RALLIES)
            case .suggestions:
                Shuttl.sectionLabel(ExercisesCopy.shared.SUGGESTIONS_HEADING)
                    .padding(.horizontal, ShuttlGutter.page)
                    .padding(.top, 20)
                    .padding(.bottom, 10)
                VStack(spacing: 12) {
                    ForEach(report.suggestions, id: \.exercise.id) { suggestion in
                        let id = suggestion.exercise.id
                        let expanded = expandedIds.contains(id)
                        ExerciseCard(
                            suggestion: suggestion,
                            expanded: expanded,
                            onToggle: {
                                if expanded {
                                    expandedIds.remove(id)
                                } else {
                                    expandedIds.insert(id)
                                }
                            }
                        )
                    }
                }
                .padding(.horizontal, ShuttlGutter.page)
            }
            if report.status != .insufficientData {
                Text(ExercisesCopy.shared.HONESTY_CAPTION)
                    .shuttlType(ShuttlType.bodySmall)
                    .foregroundStyle(Shuttl.textTertiary)
                    .padding(.horizontal, ShuttlGutter.page)
                    .padding(.top, 16)
            }
            Spacer(minLength: ShuttlGutter.page)
        }
        .frame(maxWidth: .infinity, alignment: .leading)
    }

    @ViewBuilder
    private func summaryBlock(_ summary: MovementSummary) -> some View {
        Shuttl.sectionLabel(ExercisesCopy.shared.SUMMARY_HEADING)
            .padding(.horizontal, ShuttlGutter.page)
            .padding(.top, 8)
            .padding(.bottom, 10)
        HStack(spacing: 8) {
            ShuttlStatTile(
                value: "\(summary.countedRallies)",
                unit: ExercisesCopy.shared.ralliesUnit(countedRallies: summary.countedRallies),
                label: ExercisesCopy.shared.ralliesCaption(coveragePercent: summary.coveragePercent),
                labelLineLimit: 2
            )
            ShuttlStatTile(
                value: summary.peakSpeedText ?? MetricsFormatKt.ABSENT_METRIC,
                unit: summary.peakSpeedText != nil ? " m/s" : "",
                label: ExercisesCopy.shared.PEAK_SPEED_LABEL,
                labelLineLimit: 2
            )
        }
        .padding(.horizontal, ShuttlGutter.page)
        Text(
            ExercisesCopy.shared.zoneBreakdown(
                frontPercent: summary.frontPercent,
                midPercent: summary.midPercent,
                rearPercent: summary.rearPercent
            )
        )
        .shuttlType(ShuttlType.bodySmall)
        .foregroundStyle(Shuttl.textSecondary)
        .padding(.horizontal, ShuttlGutter.page)
        .padding(.top, 10)
    }

    private var message: String? {
        exercisesMessage(
            track: shown?.track,
            windows: windows,
            report: resolved ? report : nil,
            measured: resolved
        )
    }

    private struct Inputs: Equatable {
        let samples: Int
        let side: CourtSide
        let fps: Double
        let bounds: [Double]
        let racketArm: RacketArm?
    }

    private var shownSide: CourtSide { shown?.side ?? .near }

    private var shownArm: RacketArm? {
        CourtSideNamesKt.profileArm(side: shownSide, names: sideNames)
    }

    private var shown: PlayerTrackStore.SideTrack? {
        source.map { $0.tracks.first { $0.side == side } ?? $0.tracks[0] }
    }

    private var inputs: Inputs {
        Inputs(
            samples: shown?.track.samples.count ?? 0,
            side: shownSide,
            fps: source?.fps ?? 0,
            bounds: windows.flatMap { [Double($0.index), $0.startSeconds, $0.endSeconds] },
            racketArm: shownArm
        )
    }

    private nonisolated static func evaluate(
        track: PlayerTrack?,
        fps: Double,
        windows: [RallyWindow],
        side: CourtSide,
        racketArm: RacketArm?
    ) -> ExerciseReport? {
        guard let track else { return nil }
        return ExerciseAdvisorKt.adviseExercisesForTrack(
            track: track,
            fps: fps,
            windows: windows,
            side: side,
            racketArm: racketArm
        )
    }
}

/// Why the Drills panel has nothing to render yet, or nil when `report` is ready
/// to display. Mirrors Android's `exercisesMessage` ladder in the same order.
func exercisesMessage(
    track: PlayerTrack?,
    windows: [RallyWindow],
    report: ExerciseReport?,
    measured: Bool
) -> String? {
    guard let track else {
        return ExercisesCopy.shared.NOT_LOADED
    }
    if track.samples.isEmpty { return whyEmpty(track) }
    if !windows.contains(where: { $0.isBounded }) {
        return ExercisesCopy.shared.NO_BOUNDED_WINDOWS
    }
    if measured, report == nil {
        return ExercisesCopy.shared.NO_COUNTED_RALLIES
    }
    return nil
}

private struct ExerciseCard: View {
    let suggestion: ExerciseSuggestion
    let expanded: Bool
    let onToggle: () -> Void

    var body: some View {
        let exercise = suggestion.exercise
        VStack(alignment: .leading, spacing: 0) {
            Button(action: onToggle) {
                VStack(alignment: .leading, spacing: 6) {
                    HStack(alignment: .center, spacing: 8) {
                        Text(exercise.category.label.uppercased())
                            .shuttlType(ShuttlType.labelSmall)
                            .foregroundStyle(Shuttl.accentDark)
                        Spacer(minLength: 8)
                        Text(exercise.prescription)
                            .shuttlType(ShuttlType.bodySmall)
                            .foregroundStyle(Shuttl.textTertiary)
                        Image(systemName: expanded ? "chevron.up" : "chevron.down")
                            .imageScale(.small)
                            .foregroundStyle(Shuttl.textSecondary)
                            .accessibilityHidden(true)
                    }
                    Text(exercise.title)
                        .shuttlType(ShuttlType.titleMedium)
                        .foregroundStyle(Shuttl.textHeading)
                        .multilineTextAlignment(.leading)
                    Text(suggestion.evidence)
                        .shuttlType(ShuttlType.bodySmall)
                        .foregroundStyle(Shuttl.textSecondary)
                        .multilineTextAlignment(.leading)
                }
                .frame(maxWidth: .infinity, alignment: .leading)
                .padding(16)
                .contentShape(Rectangle())
            }
            .buttonStyle(.plain)
            .accessibilityLabel("\(exercise.title), \(exercise.category.label), \(exercise.prescription). \(suggestion.evidence)")
            .accessibilityValue(expanded ? "Expanded" : "Collapsed")

            if expanded {
                Rectangle()
                    .fill(Shuttl.border)
                    .frame(height: 1)
                VStack(alignment: .leading, spacing: 8) {
                    Text("\(ExercisesCopy.shared.SETUP_LABEL): \(exercise.setup)")
                        .shuttlType(ShuttlType.bodySmall)
                        .foregroundStyle(Shuttl.text)
                    VStack(alignment: .leading, spacing: 4) {
                        ForEach(Array(exercise.steps.enumerated()), id: \.offset) { index, step in
                            Text("\(index + 1). \(step)")
                                .shuttlType(ShuttlType.bodySmall)
                                .foregroundStyle(Shuttl.textSecondary)
                        }
                    }
                    Text("\(ExercisesCopy.shared.CUE_LABEL): \(exercise.coachingCue)")
                        .shuttlType(ShuttlType.bodySmall)
                        .foregroundStyle(Shuttl.text)
                }
                .frame(maxWidth: .infinity, alignment: .leading)
                .padding(16)
            }
        }
        .background(Shuttl.bgSecondary, in: RoundedRectangle(cornerRadius: ShuttlRadius.large))
        .overlay(
            RoundedRectangle(cornerRadius: ShuttlRadius.large)
                .stroke(Shuttl.border, lineWidth: 1)
        )
    }
}
