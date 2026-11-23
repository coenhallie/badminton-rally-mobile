import Shared
import SwiftUI

/// Suggested drills and exercises derived from a completed video analysis,
/// followed by a collapsible, searchable, lazy-rendered library of all curated
/// drills and player-linked guided session tracking.
/// Port of androidApp's `ExercisesPanel`, following `BasePositionPanel`'s
/// threading, side-toggle placement, and empty-message ladder.
struct ExercisesPanel: View {
    let source: HeatmapSource?
    let windows: [RallyWindow]
    let sideNames: CourtSideNames?
    let drillProgress: DrillProgressRepository
    let matchId: String

    /// Precomputed reports for every tracked side so switching `SideToggle` is
    /// instantaneous and never races or shows the previous side's exercises.
    @State private var reportsBySide: [CourtSide: ExerciseReport] = [:]
    @State private var resolved = false
    @State private var side: CourtSide = .near
    @State private var expandedBySide: [CourtSide: Set<String>] = [:]
    @State private var logs: [DrillSessionLog] = []
    @State private var activeSession: ActiveDrillSession? = nil

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
        .task {
            logs = drillProgress.logs.value
            for await latest in drillProgress.logs {
                guard !Task.isCancelled else { break }
                logs = latest
            }
        }
        .task(id: inputs) {
            resolved = false
            reportsBySide = [:]
            expandedBySide = [:]
            let currentSource = source
            let rallies = windows
            let names = sideNames
            let measured = await Task.detached(priority: .userInitiated) {
                evaluateReportsBySide(source: currentSource, windows: rallies, sideNames: names)
            }.value
            guard !Task.isCancelled else { return }
            reportsBySide = measured
            var initialExpanded: [CourtSide: Set<String>] = [:]
            for (courtSide, sideReport) in measured {
                if let firstId = sideReport.suggestions.first?.exercise.id {
                    initialExpanded[courtSide] = [firstId]
                } else {
                    initialExpanded[courtSide] = []
                }
            }
            expandedBySide = initialExpanded
            resolved = true
        }
        .fullScreenCover(item: $activeSession) { launch in
            DrillSessionSheet(
                exercise: launch.exercise,
                racketArm: launch.racketArm,
                playerId: launch.playerId,
                matchId: matchId,
                drillProgress: drillProgress
            )
        }
    }

    @ViewBuilder
    private var inner: some View {
        Group {
            if let message {
                PanelMessage(text: message)
            } else if let report = currentReport {
                content(report)
            } else {
                Color.clear.frame(height: 0)
            }
        }
    }

    @ViewBuilder
    private func content(_ report: ExerciseReport) -> some View {
        let activeSide = shownSide
        let activeArm = CourtSideNamesKt.profileArm(side: activeSide, names: sideNames)
        let activePlayerId = CourtSideNamesKt.profilePlayerId(side: activeSide, names: sideNames)
        let scopedLogs = DrillProgressRepositoryKt.filterDrillLogs(logs: logs, playerId: activePlayerId)
        let nowMs = Int64(Date().timeIntervalSince1970 * 1000)
        let badges = DrillProgressRepositoryKt.badgeMapByExercise(logs: scopedLogs, nowEpochMs: nowMs)

        let launchDrill: (Exercise) -> Void = { exercise in
            activeSession = ActiveDrillSession(
                exercise: exercise,
                racketArm: activeArm,
                playerId: activePlayerId
            )
        }

        VStack(alignment: .leading, spacing: 0) {
            if let summary = report.summary {
                summaryBlock(summary)
            }
            if !scopedLogs.isEmpty {
                TrainingProgressStrip(
                    logs: scopedLogs,
                    nowMs: nowMs,
                    onDeleteLog: { drillProgress.deleteSession(logId: $0) },
                    onRepeatDrill: launchDrill,
                    topPadding: 18
                )
            }
            switch report.status {
            case .insufficientData, .balanced:
                PanelMessage(text: report.message ?? ExercisesCopy.shared.NO_COUNTED_RALLIES)
            case .suggestions:
                let expandedIds = expandedBySide[activeSide] ?? []
                Shuttl.sectionLabel(ExercisesCopy.shared.SUGGESTIONS_HEADING)
                    .padding(.horizontal, ShuttlGutter.page)
                    .padding(.top, 20)
                    .padding(.bottom, 10)
                LazyVStack(spacing: 12) {
                    ForEach(report.suggestions, id: \.exercise.id) { suggestion in
                        let id = suggestion.exercise.id
                        let expanded = expandedIds.contains(id)
                        ExerciseCard(
                            exercise: suggestion.exercise,
                            kicker: suggestion.exercise.category.label.uppercased(),
                            subtitle: suggestion.evidence,
                            progressBadge: badges[id],
                            racketArm: activeArm,
                            expanded: expanded,
                            onToggle: {
                                var next = expandedBySide[activeSide] ?? []
                                if expanded {
                                    next.remove(id)
                                } else {
                                    next.insert(id)
                                }
                                expandedBySide[activeSide] = next
                            },
                            onStartDrill: { launchDrill(suggestion.exercise) }
                        )
                    }
                }
                .padding(.horizontal, ShuttlGutter.page)
            }

            DrillLibrarySection(
                racketArm: activeArm,
                logs: scopedLogs,
                badges: badges,
                initiallyOpen: false,
                onStartDrill: launchDrill
            )

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
            report: resolved ? currentReport : nil,
            measured: resolved
        )
    }

    private var currentReport: ExerciseReport? {
        shown.flatMap { reportsBySide[$0.side] }
    }

    private struct Inputs: Equatable {
        let sideSamples: [Int]
        let fps: Double
        let bounds: [Double]
        let nearArm: RacketArm?
        let farArm: RacketArm?
    }

    private var shownSide: CourtSide { shown?.side ?? .near }

    private var shown: PlayerTrackStore.SideTrack? {
        source.map { $0.tracks.first { $0.side == side } ?? $0.tracks[0] }
    }

    private var inputs: Inputs {
        let counts = source?.tracks.flatMap {
            [$0.side == .near ? 0 : 1, $0.track.samples.count]
        } ?? []
        return Inputs(
            sideSamples: counts,
            fps: source?.fps ?? 0,
            bounds: windows.flatMap { [Double($0.index), $0.startSeconds, $0.endSeconds] },
            nearArm: sideNames?.nearArm,
            farArm: sideNames?.farArm
        )
    }
}

/// Standalone Drills tab shown directly on a player's profile page so a player
/// or coach can search, launch, and track any drill mirrored to that player's
/// hand without opening a match first. Port of androidApp's `PlayerDrillsTab`.
struct PlayerDrillsTab: View {
    let playerId: String
    let racketArm: RacketArm?
    let drillProgress: DrillProgressRepository

    @State private var allLogs: [DrillSessionLog] = []
    @State private var activeSession: ActiveDrillSession? = nil

    var body: some View {
        let playerLogs = DrillProgressRepositoryKt.filterDrillLogs(logs: allLogs, playerId: playerId)
        let nowMs = Int64(Date().timeIntervalSince1970 * 1000)
        let badges = DrillProgressRepositoryKt.badgeMapByExercise(logs: playerLogs, nowEpochMs: nowMs)

        let launchDrill: (Exercise) -> Void = { exercise in
            activeSession = ActiveDrillSession(
                exercise: exercise,
                racketArm: racketArm,
                playerId: playerId
            )
        }

        ScrollView {
            LazyVStack(alignment: .leading, spacing: 0) {
                if !playerLogs.isEmpty {
                    TrainingProgressStrip(
                        logs: playerLogs,
                        nowMs: nowMs,
                        onDeleteLog: { drillProgress.deleteSession(logId: $0) },
                        onRepeatDrill: launchDrill,
                        topPadding: 16
                    )
                } else {
                    Text(ExercisesCopy.shared.NO_SESSIONS_YET)
                        .shuttlType(ShuttlType.bodySmall)
                        .foregroundStyle(Shuttl.textSecondary)
                        .padding(.horizontal, ShuttlGutter.page)
                        .padding(.top, 16)
                }

                DrillLibrarySection(
                    racketArm: racketArm,
                    logs: playerLogs,
                    badges: badges,
                    initiallyOpen: true,
                    onStartDrill: launchDrill
                )

                Spacer(minLength: 32)
            }
            .frame(maxWidth: .infinity, alignment: .leading)
        }
        .background(Shuttl.bg)
        .task {
            allLogs = drillProgress.logs.value
            for await latest in drillProgress.logs {
                guard !Task.isCancelled else { break }
                allLogs = latest
            }
        }
        .fullScreenCover(item: $activeSession) { launch in
            DrillSessionSheet(
                exercise: launch.exercise,
                racketArm: launch.racketArm,
                playerId: launch.playerId,
                matchId: nil,
                drillProgress: drillProgress
            )
        }
    }
}

private struct ActiveDrillSession: Identifiable {
    let id = UUID()
    let exercise: Exercise
    let racketArm: RacketArm?
    let playerId: String?
}

/// Evaluates every side in `source` off the main actor with its own profile arm,
/// mirroring androidApp's `produceState` precomputation in `ExercisesPanel.kt`.
nonisolated func evaluateReportsBySide(
    source: HeatmapSource?,
    windows: [RallyWindow],
    sideNames: CourtSideNames?
) -> [CourtSide: ExerciseReport] {
    guard let source else { return [:] }
    var out: [CourtSide: ExerciseReport] = [:]
    for sideTrack in source.tracks {
        out[sideTrack.side] = ExerciseAdvisorKt.adviseExercisesForTrack(
            track: sideTrack.track,
            fps: source.fps,
            windows: windows,
            side: sideTrack.side,
            racketArm: CourtSideNamesKt.profileArm(side: sideTrack.side, names: sideNames)
        )
    }
    return out
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

private struct TrainingProgressStrip: View {
    let logs: [DrillSessionLog]
    let nowMs: Int64
    let onDeleteLog: (String) -> Void
    let onRepeatDrill: (Exercise) -> Void
    let topPadding: CGFloat

    @State private var historyOpen = false

    var body: some View {
        let overview = DrillProgressRepositoryKt.trainingOverview(logs: logs, nowEpochMs: nowMs)
        VStack(alignment: .leading, spacing: 0) {
            Shuttl.sectionLabel(ExercisesCopy.shared.TRAINING_LOG_HEADING)
                .padding(.horizontal, ShuttlGutter.page)
                .padding(.top, topPadding)
                .padding(.bottom, 10)

            VStack(alignment: .leading, spacing: 0) {
                Button {
                    historyOpen.toggle()
                } label: {
                    HStack(alignment: .center, spacing: 8) {
                        VStack(alignment: .leading, spacing: 2) {
                            Text(DrillProgressRepositoryKt.trainingOverviewHeadline(overview: overview))
                                .shuttlType(ShuttlType.titleMedium)
                                .foregroundStyle(Shuttl.textHeading)
                            let drillWord = overview.uniqueDrillsTried == 1 ? "drill" : "drills"
                            Text("\(overview.uniqueDrillsTried) \(drillWord) practiced · tap for log")
                                .shuttlType(ShuttlType.bodySmall)
                                .foregroundStyle(Shuttl.textSecondary)
                        }
                        Spacer(minLength: 8)
                        Image(systemName: historyOpen ? "chevron.up" : "chevron.down")
                            .imageScale(.small)
                            .foregroundStyle(Shuttl.textSecondary)
                            .accessibilityHidden(true)
                    }
                    .frame(maxWidth: .infinity, alignment: .leading)
                    .padding(16)
                    .contentShape(Rectangle())
                }
                .buttonStyle(.plain)

                if historyOpen {
                    Rectangle().fill(Shuttl.border).frame(height: 1)
                    VStack(alignment: .leading, spacing: 8) {
                        ForEach(Array(logs.prefix(8)), id: \.id) { log in
                            let exercise = ExerciseCatalog.shared.byIdOrNull(id: log.exerciseId)
                            let title = exercise?.title ?? log.exerciseId
                            let ago = DrillProgressRepositoryKt.relativeDaysLabel(
                                timestampEpochMs: log.completedAtEpochMs,
                                nowEpochMs: nowMs
                            )
                            let clock = DrillSessionKt.formatClockSeconds(seconds: log.durationSeconds)
                            let baseDetail = "\(ago) · \(log.setsCompleted)/\(log.setsTarget) sets · \(clock) · \(log.effort.label)"
                            let detail = log.note.isEmpty ? baseDetail : "\(baseDetail) · \(log.note)"
                            HStack(alignment: .center, spacing: 6) {
                                VStack(alignment: .leading, spacing: 2) {
                                    Text(title)
                                        .shuttlType(ShuttlType.bodyMedium)
                                        .foregroundStyle(Shuttl.textHeading)
                                        .lineLimit(1)
                                    Text(detail)
                                        .shuttlType(ShuttlType.bodySmall)
                                        .foregroundStyle(Shuttl.textSecondary)
                                        .lineLimit(2)
                                }
                                .frame(maxWidth: .infinity, alignment: .leading)

                                if let exercise {
                                    Button {
                                        onRepeatDrill(exercise)
                                    } label: {
                                        Image(systemName: "play.fill")
                                            .imageScale(.small)
                                            .foregroundStyle(Shuttl.accentDark)
                                            .frame(width: 28, height: 28)
                                            .contentShape(Rectangle())
                                    }
                                    .buttonStyle(.plain)
                                    .accessibilityLabel("\(ExercisesCopy.shared.REPEAT_DRILL_LABEL): \(title)")
                                }

                                Button {
                                    onDeleteLog(log.id)
                                } label: {
                                    Image(systemName: "xmark")
                                        .imageScale(.small)
                                        .foregroundStyle(Shuttl.textTertiary)
                                        .frame(width: 28, height: 28)
                                        .contentShape(Rectangle())
                                }
                                .buttonStyle(.plain)
                                .accessibilityLabel("Delete session")
                            }
                        }
                    }
                    .padding(.horizontal, 16)
                    .padding(.vertical, 10)
                }
            }
            .background(Shuttl.bgSecondary, in: RoundedRectangle(cornerRadius: ShuttlRadius.large))
            .overlay(
                RoundedRectangle(cornerRadius: ShuttlRadius.large)
                    .stroke(Shuttl.border, lineWidth: 1)
            )
            .padding(.horizontal, ShuttlGutter.page)
        }
    }
}

private struct DrillLibrarySection: View {
    let racketArm: RacketArm?
    let logs: [DrillSessionLog]
    let badges: [String: String]
    let initiallyOpen: Bool
    let onStartDrill: (Exercise) -> Void

    @State private var libraryOpen: Bool
    @State private var selectedFilter: DrillLibraryFilter = .all
    @State private var recentOnly = false
    @State private var searchQuery = ""
    @State private var expandedIds: Set<String> = []

    init(
        racketArm: RacketArm?,
        logs: [DrillSessionLog],
        badges: [String: String],
        initiallyOpen: Bool,
        onStartDrill: @escaping (Exercise) -> Void
    ) {
        self.racketArm = racketArm
        self.logs = logs
        self.badges = badges
        self.initiallyOpen = initiallyOpen
        self.onStartDrill = onStartDrill
        _libraryOpen = State(initialValue: initiallyOpen)
    }

    var body: some View {
        let catalog = ExerciseCatalog.shared
        let recent = catalog.recentExercises(logs: logs)
        let effectiveRecentOnly = recentOnly && !recent.isEmpty
        let baseList = effectiveRecentOnly ? recent : catalog.filtered(filter: selectedFilter)
        let visible = catalog.search(exercises: baseList, query: searchQuery)

        VStack(alignment: .leading, spacing: 0) {
            Shuttl.sectionLabel(ExercisesCopy.shared.LIBRARY_HEADING)
                .padding(.horizontal, ShuttlGutter.page)
                .padding(.top, 20)
                .padding(.bottom, 10)

            if !initiallyOpen {
                Button {
                    libraryOpen.toggle()
                } label: {
                    HStack(alignment: .center, spacing: 8) {
                        VStack(alignment: .leading, spacing: 2) {
                            Text(
                                ExercisesCopy.shared.browseLibraryLabel(
                                    totalCount: Int32(catalog.all.count),
                                    expanded: libraryOpen
                                )
                            )
                            .shuttlType(ShuttlType.titleMedium)
                            .foregroundStyle(Shuttl.textHeading)
                            Text(
                                ExercisesCopy.shared.librarySubtitle(
                                    onCourtCount: catalog.onCourtCount,
                                    offCourtCount: catalog.offCourtCount
                                )
                            )
                            .shuttlType(ShuttlType.bodySmall)
                            .foregroundStyle(Shuttl.textSecondary)
                        }
                        Spacer(minLength: 8)
                        Image(systemName: libraryOpen ? "chevron.up" : "chevron.down")
                            .imageScale(.small)
                            .foregroundStyle(Shuttl.textSecondary)
                            .accessibilityHidden(true)
                    }
                    .frame(maxWidth: .infinity, alignment: .leading)
                    .padding(16)
                    .background(Shuttl.bgSecondary, in: RoundedRectangle(cornerRadius: ShuttlRadius.large))
                    .overlay(
                        RoundedRectangle(cornerRadius: ShuttlRadius.large)
                            .stroke(Shuttl.border, lineWidth: 1)
                    )
                    .contentShape(Rectangle())
                }
                .buttonStyle(.plain)
                .accessibilityValue(libraryOpen ? "Expanded" : "Collapsed")
                .padding(.horizontal, ShuttlGutter.page)
            }

            if libraryOpen {
                // Compact search field above filter pills.
                HStack(spacing: 8) {
                    Image(systemName: "magnifyingglass")
                        .imageScale(.small)
                        .foregroundStyle(Shuttl.textSecondary)
                        .accessibilityHidden(true)
                    TextField(ExercisesCopy.shared.SEARCH_PLACEHOLDER, text: $searchQuery)
                        .shuttlType(ShuttlType.bodySmall)
                        .foregroundStyle(Shuttl.textHeading)
                        .textInputAutocapitalization(.never)
                        .autocorrectionDisabled(true)
                    if !searchQuery.isEmpty {
                        Button {
                            searchQuery = ""
                        } label: {
                            Image(systemName: "xmark.circle.fill")
                                .imageScale(.small)
                                .foregroundStyle(Shuttl.textTertiary)
                        }
                        .buttonStyle(.plain)
                        .accessibilityLabel(ExercisesCopy.shared.CLEAR_SEARCH)
                    }
                }
                .padding(.horizontal, 12)
                .padding(.vertical, 10)
                .background(Shuttl.bgSecondary, in: RoundedRectangle(cornerRadius: ShuttlRadius.medium))
                .overlay(
                    RoundedRectangle(cornerRadius: ShuttlRadius.medium)
                        .stroke(Shuttl.border, lineWidth: 1)
                )
                .padding(.horizontal, ShuttlGutter.page)
                .padding(.top, initiallyOpen ? 0 : 12)

                ScrollView(.horizontal, showsIndicators: false) {
                    HStack(spacing: 8) {
                        if !recent.isEmpty {
                            FilterChipPill(
                                label: "\(ExercisesCopy.shared.FILTER_RECENT) (\(recent.count))",
                                isSelected: effectiveRecentOnly
                            ) {
                                recentOnly = true
                            }
                        }
                        ForEach(catalog.allFilters, id: \.self) { filter in
                            let isSelected = !effectiveRecentOnly && filter == selectedFilter
                            let count = catalog.filterCount(filter: filter)
                            FilterChipPill(
                                label: "\(filter.label) (\(count))",
                                isSelected: isSelected
                            ) {
                                recentOnly = false
                                selectedFilter = filter
                            }
                        }
                    }
                    .padding(.horizontal, ShuttlGutter.page)
                }
                .padding(.top, 10)

                if visible.isEmpty {
                    VStack(spacing: 8) {
                        Text(ExercisesCopy.shared.NO_SEARCH_MATCHES)
                            .shuttlType(ShuttlType.bodySmall)
                            .foregroundStyle(Shuttl.textSecondary)
                        if !searchQuery.isEmpty {
                            Button(ExercisesCopy.shared.CLEAR_SEARCH) {
                                searchQuery = ""
                            }
                            .shuttlType(ShuttlType.labelMedium)
                            .foregroundStyle(Shuttl.accentDark)
                            .buttonStyle(.plain)
                        }
                    }
                    .frame(maxWidth: .infinity)
                    .padding(.horizontal, ShuttlGutter.page)
                    .padding(.vertical, 24)
                } else {
                    LazyVStack(spacing: 10) {
                        ForEach(visible, id: \.id) { exercise in
                            let expanded = expandedIds.contains(exercise.id)
                            ExerciseCard(
                                exercise: exercise,
                                kicker: "\(exercise.category.label) · \(exercise.focus.label)".uppercased(),
                                subtitle: nil,
                                progressBadge: badges[exercise.id],
                                racketArm: racketArm,
                                expanded: expanded,
                                onToggle: {
                                    if expanded {
                                        expandedIds.remove(exercise.id)
                                    } else {
                                        expandedIds.insert(exercise.id)
                                    }
                                },
                                onStartDrill: { onStartDrill(exercise) }
                            )
                        }
                    }
                    .padding(.horizontal, ShuttlGutter.page)
                    .padding(.top, 12)
                }
            }
        }
    }
}

private struct FilterChipPill: View {
    let label: String
    let isSelected: Bool
    let action: () -> Void

    var body: some View {
        Button(action: action) {
            Text(label)
                .shuttlType(ShuttlType.labelSmall)
                .foregroundStyle(isSelected ? Shuttl.onAccent : Shuttl.textSecondary)
                .padding(.horizontal, 12)
                .padding(.vertical, 7)
                .background(isSelected ? Shuttl.accent : Shuttl.bgSecondary, in: Capsule())
                .overlay(
                    Capsule()
                        .stroke(isSelected ? Shuttl.accent : Shuttl.border, lineWidth: 1)
                )
        }
        .buttonStyle(.plain)
        .accessibilityAddTraits(isSelected ? .isSelected : [])
    }
}

private struct ExerciseCard: View {
    let exercise: Exercise
    let kicker: String
    let subtitle: String?
    let progressBadge: String?
    let racketArm: RacketArm?
    let expanded: Bool
    let onToggle: () -> Void
    let onStartDrill: () -> Void

    var body: some View {
        VStack(alignment: .leading, spacing: 0) {
            HStack(alignment: .center, spacing: 8) {
                Button(action: onToggle) {
                    VStack(alignment: .leading, spacing: 5) {
                        HStack(alignment: .center, spacing: 8) {
                            Text(kicker)
                                .shuttlType(ShuttlType.labelSmall)
                                .foregroundStyle(Shuttl.accentDark)
                                .lineLimit(1)
                                .truncationMode(.tail)
                            Spacer(minLength: 8)
                            Text(exercise.prescription)
                                .shuttlType(ShuttlType.bodySmall)
                                .foregroundStyle(Shuttl.textTertiary)
                                .multilineTextAlignment(.trailing)
                                .lineLimit(1)
                                .truncationMode(.tail)
                        }
                        HStack(alignment: .center, spacing: 8) {
                            Text(exercise.title)
                                .shuttlType(ShuttlType.titleMedium)
                                .foregroundStyle(Shuttl.textHeading)
                                .multilineTextAlignment(.leading)
                            if let progressBadge {
                                Text(progressBadge)
                                    .shuttlType(ShuttlType.labelSmall)
                                    .foregroundStyle(Shuttl.analysisGlyph)
                                    .padding(.horizontal, 8)
                                    .padding(.vertical, 2)
                                    .background(Shuttl.analysisChip, in: Capsule())
                                    .lineLimit(1)
                            }
                        }
                        if let subtitle {
                            Text(subtitle)
                                .shuttlType(ShuttlType.bodySmall)
                                .foregroundStyle(Shuttl.textSecondary)
                                .multilineTextAlignment(.leading)
                        }
                    }
                    .frame(maxWidth: .infinity, alignment: .leading)
                    .contentShape(Rectangle())
                }
                .buttonStyle(.plain)
                .accessibilityLabel(
                    subtitle.map { "\(exercise.title), \(exercise.category.label), \(exercise.prescription). \($0)" }
                        ?? "\(exercise.title), \(exercise.category.label), \(exercise.prescription)"
                )
                .accessibilityValue(expanded ? "Expanded" : "Collapsed")

                // 1-tap quick launch button directly from the card header.
                Button(action: onStartDrill) {
                    Image(systemName: "play.fill")
                        .imageScale(.small)
                        .foregroundStyle(Shuttl.accentDark)
                        .frame(width: 34, height: 34)
                        .background(Shuttl.analysisChip, in: Circle())
                }
                .buttonStyle(.plain)
                .accessibilityLabel("\(ExercisesCopy.shared.QUICK_START_LABEL) \(exercise.title)")

                Button(action: onToggle) {
                    Image(systemName: expanded ? "chevron.up" : "chevron.down")
                        .imageScale(.small)
                        .foregroundStyle(Shuttl.textSecondary)
                        .frame(width: 22, height: 34)
                        .contentShape(Rectangle())
                }
                .buttonStyle(.plain)
                .accessibilityHidden(true)
            }
            .padding(.leading, 16)
            .padding(.trailing, 10)
            .padding(.vertical, 14)

            if expanded {
                Rectangle()
                    .fill(Shuttl.border)
                    .frame(height: 1)
                VStack(alignment: .leading, spacing: 10) {
                    ExerciseVisualView(exerciseId: exercise.id, racketArm: racketArm)
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
                    Button(ExercisesCopy.shared.START_DRILL, action: onStartDrill)
                        .buttonStyle(PrimaryButtonStyle())
                        .padding(.top, 2)
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
