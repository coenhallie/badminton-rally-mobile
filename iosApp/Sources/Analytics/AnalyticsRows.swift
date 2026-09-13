import Foundation
import Shared

/// Which of the drawer's own sections a row belongs to, in display order.
/// Port of Android's `AnalyticsGroup`.
enum AnalyticsGroup: CaseIterable {
    case localVideos
    case ownedMatches
    case shared

    var label: String {
        switch self {
        case .localVideos:  return "On this phone"
        case .ownedMatches: return "My matches"
        case .shared:       return "Shared with me"
        }
    }
}

/// What an `analysable` row's control should show, in place of a live "Analyze"
/// button, when the pipeline is already busy with its video or its last attempt
/// failed. Port of Android's `AnalyseAffordance`.
enum AnalyseAffordance: Equatable {
    /// Nothing is running: the button is live.
    case ready
    /// A run is under way. `phase` is the drawer's own wording for that stage.
    case inProgress(phase: String)
    /// The last attempt ended in a failure, with the pipeline's own message.
    case failed(reason: String)
    /// A device run stopped because the app left the foreground.
    ///
    /// A fourth case Android does not have, and the one place this list
    /// deliberately diverges: over there a foreground service carries a run
    /// through a locked screen, and here nothing can. Folding it into `.failed`
    /// would put "Retry" on a run that did not go wrong and colour the line red;
    /// folding it into `.inProgress` would spin a control over something that
    /// has stopped. See `LocalAnalysisState.paused`.
    case paused(reason: String)
}

/// Where an `analysable` row's button goes when it is tapped.
enum AnalyseAction: Equatable {
    /// A failed cloud run whose court is already saved: `AnalyzeCoordinator.retry`
    /// picks up at the step that failed, so there is nothing to ask the coach for.
    case resume(entryId: String)
    /// Everything else starts at court marking.
    case markCourt(entryId: String)
}

/// One row of the Analytics list. `entryId` is nil exactly when there is no video
/// on this phone behind the row, which is also the only case that is inert.
struct AnalyticsRow: Identifiable, Equatable {
    let key: String
    let entryId: String?
    let group: AnalyticsGroup
    let title: String
    let subtitle: String
    let state: AnalyticsRowState
    let affordance: AnalyseAffordance

    var id: String { key }
}

/// The one explanatory line above the list, chosen from the row states actually
/// on screen rather than from the platform.
///
/// Four cases. Android had two - all-inert, or the dot legend - which put a dot
/// legend over a list with no dots the moment anything was analysable. That was
/// found here, where every local video was analysable while no track store
/// existed, and ported back there once it turned out to be a live defect on
/// Android too.
///
/// Both of the middle cases stay ordinary now that a track store does exist: a
/// coach whose matches were all filmed elsewhere sees an inert list, and a coach
/// with videos on the phone and nothing analysed yet sees live "Analyze" buttons
/// and no dot to explain. Explaining a dot that cannot render is the same defect
/// as a comment outliving its code.
enum AnalyticsLegend: Equatable {
    /// The list is empty; there is nothing to explain. Not spelled `none`: the
    /// moment anything holds an `AnalyticsLegend?`, `.none` binds to
    /// `Optional.none` instead and the mistake is silent.
    case silent
    /// Every row is inert, all for the same reason.
    case nothingOnThisPhone
    /// At least one row carries the availability dot.
    case dot
    /// No dots, but at least one live "Analyze" button.
    case analyseButton

    /// Whether any row draws the availability dot the `.dot` line explains.
    var showsDot: Bool { self == .dot }

    /// Whether an inert row spells out "Not on this phone" for itself. False only
    /// when the legend already said it once for the whole list, so the legend and
    /// the rows cannot disagree about which of them is doing the explaining.
    var showsNotOnDeviceSubtitle: Bool { self != .nothingOnThisPhone }

    var text: String? {
        switch self {
        case .silent:
            return nil
        case .nothingOnThisPhone:
            return "None of these matches are on this phone yet."
        case .dot:
            // "- tap to view", as Android has it. The words were dropped while
            // there was no detail screen to open and nothing to tap; both exist
            // now, so promising it is accurate again.
            return "Analyzed on this phone - tap to view"
        case .analyseButton:
            // What the button actually does, said plainly, so a screen called
            // Analytics does not look like it is about to draw a chart. Both
            // targets named on purpose: court marking offers cloud AND on
            // device, and it is the on-device run that writes the track a row
            // needs to turn ready, so copy naming only the cloud would steer a
            // coach away from the dot this same legend explains.
            return "Analyze cuts a video into rallies, on this phone or in the cloud."
        }
    }
}

/// What either pipeline is doing with this video right now.
///
/// TWO liveness sources, composed, as Android's `affordanceFor` composes them.
/// A device run never moves `LocalVideoEntry.stage`, so the stage-based rules in
/// shared cannot see one at all: a row reading the cloud stage alone would show
/// a live "Analyze" button over a run already in progress, and pressing it walks
/// the coach through marking the court again and then returns immediately
/// because the entry is already running. Android shipped exactly that - twelve
/// taps, no effect, no explanation - which is why `isDeviceRunInFlight` exists
/// and why it is consulted here.
///
/// The device speaks first when it has anything to say. It is the pipeline this
/// phone controls, and its states are the ones that end in a track the row can
/// then offer; a cloud stage left over from an earlier attempt must not describe
/// a device run happening now.
///
/// The phase wording is shared on both sides: `deviceWorkLabel` for the device
/// run, `LocalVideoStatus` for the cloud one, so this row and the chrome
/// indicator over the same video say the same words.
func analyseAffordance(
    for entry: LocalVideoEntry,
    progress: AnalyzeProgress?,
    device: LocalAnalysisState
) -> AnalyseAffordance {
    switch device {
    case .preparing, .analysing, .cutting:
        // Non-nil for every running state, since toDeviceWork returns nil only
        // for the ones this branch excludes.
        if let work = toDeviceWork(entryId: entry.id, state: device) {
            return .inProgress(phase: BackgroundWorkKt.deviceWorkLabel(
                phase: work.phase, fraction: work.fraction
            ))
        }
        return .inProgress(phase: "Analyzing on device")
    case .failed(let message):
        return .failed(reason: message)
    case .paused:
        // The percentage only when there is one: a run frozen while preparing,
        // or while cutting clips, has no analysis fraction to quote and must not
        // invent a zero.
        guard let fraction = device.pausedFraction else {
            return .paused(reason: "Paused - keep Shuttl open to finish")
        }
        return .paused(reason: "Paused at \(Int(fraction * 100))% - keep Shuttl open to finish")
    case .idle, .done:
        break
    }
    if LocalVideoStatus.isRunning(stage: entry.stage) {
        // Non-nil for both running stages, so the fallback is a guard against a
        // future stage rather than a case anything reaches today.
        let phase = LocalVideoStatus.text(stage: entry.stage, progress: progress)
        return .inProgress(phase: phase ?? "Analyzing…")
    }
    if entry.stage == .failed {
        return .failed(reason: entry.failureMessage ?? "Unknown error")
    }
    return .ready
}

/// Where this row's button goes. The rule itself is shared
/// (`canResumeFailedAnalysis`) rather than written out here: five hand-written
/// copies of `stage == .failed && keypoints != nil` are exactly what put it in
/// `shared/` in the first place, and this would have been the sixth.
func analyseAction(for entry: LocalVideoEntry) -> AnalyseAction {
    LocalVideoEntryKt.canResumeFailedAnalysis(entry: entry)
        ? .resume(entryId: entry.id)
        : .markCourt(entryId: entry.id)
}

/// How a local video describes itself under its title: how long it runs and when
/// it was added.
///
/// Shared with the drawer's own local video row rather than written out twice.
/// The two screens list the same videos side by side in one tap, and a comment
/// claiming they cannot describe a video two ways is worth less than a call that
/// makes it so.
///
/// Sentence case, as the mock has it ("1:05 · Sep 2"). Neither this screen nor
/// the drawer uppercases it any more: the redesign carries no uppercase
/// anywhere, and Analytics kept transforming it at its own call site for a
/// while after the drawer had stopped.
func localVideoSubtitle(_ entry: LocalVideoEntry) -> String {
    let duration = LocalVideoLogic.formatDuration(ms: entry.durationMs)
    let date = formatMatchDate(millis: entry.addedAtEpochMs)
    return "\(duration) · \(date)"
}

/// Builds the Analytics list's rows, grouped like the drawer: local videos, then
/// owned matches, then shared. `analyticsRowState` alone decides READY /
/// ANALYSABLE / NOT_ON_DEVICE; this only gathers its two booleans per match and,
/// for an analysable one, reads the cloud pipeline's liveness through
/// `analyseAffordance` so a row can show progress or a failure instead of a live
/// button. `hasStoredTrack` alone cannot tell a run in flight, or one that just
/// failed, from one never attempted.
///
/// Port of Android's `buildAnalyticsRows`, now including its `storedTrackIds`
/// and its on-device liveness map.
func buildAnalyticsRows(
    localEntries: [LocalVideoEntry],
    ownedRows: [MatchRow],
    sharedMatches: [MatchSummary],
    progressByEntryId: [String: AnalyzeProgress],
    /// Entries with a track this build can draw. Passed in as a set rather than
    /// probed per row: the store answers from a file header, and a list of forty
    /// matches would ask forty times on every redraw.
    storedTrackIds: Set<String> = [],
    /// What the on-device runner is doing, per entry. Absent means idle.
    deviceStates: [String: LocalAnalysisState] = [:]
) -> [AnalyticsRow] {
    func rowFor(
        key: String, entryId: String?, group: AnalyticsGroup, title: String, subtitle: String
    ) -> AnalyticsRow {
        let entry = entryId.flatMap { id in localEntries.first { $0.id == id } }
        let state = analyticsRowState(
            hasLocalEntry: entry != nil,
            hasStoredTrack: entry.map { storedTrackIds.contains($0.id) } ?? false
        )
        var affordance = AnalyseAffordance.ready
        // Only an analysable row reads liveness. A ready row keeps its dot while
        // a second run is in flight, because the track the first run produced is
        // still there; and a not-on-device row has no run of its own to report.
        //
        // `entry` is never nil in this branch: analysable requires a local entry,
        // which is exactly `entry != nil` above. The binding is a guard, not a
        // second source of truth.
        if state == .analysable, let entry {
            affordance = analyseAffordance(
                for: entry,
                progress: progressByEntryId[entry.id],
                device: deviceStates[entry.id] ?? .idle
            )
        }
        return AnalyticsRow(
            key: key,
            // Not on this phone and inert either way once there is no entry, so
            // there is nothing for a nil id to act on.
            entryId: entry?.id,
            group: group,
            title: title,
            subtitle: subtitle,
            state: state,
            affordance: affordance
        )
    }

    // A video picked for a match is that match's row, not a standalone one -
    // the same filter `MatchesList`'s own "On this phone" section applies.
    let localVideoRows = localEntries.filter { $0.scoreLogId == nil }.map { entry -> AnalyticsRow in
        return rowFor(
            key: "local-\(entry.id)",
            entryId: entry.id,
            group: .localVideos,
            title: entry.title ?? entry.displayName,
            subtitle: localVideoSubtitle(entry)
        )
    }

    let ownedMatchRows = ownedRows.map { row -> AnalyticsRow in
        switch row {
        case .video(let match):
            return rowFor(
                key: row.id,
                entryId: match.videoId,
                group: .ownedMatches,
                title: matchRowPrimary(match),
                subtitle: matchRowSecondary(match)
            )
        case .score(let content):
            return rowFor(
                key: row.id,
                // The entry that claims this log, which is how the rest of this
                // app pairs a scored match to its video (`scoreRow` in
                // ClipListView, and the shared `scoreLogAttachStatus`). NOT
                // `card.videoId`: that is written only by the cloud pipeline's
                // CREATE_ROW step, so a match scored courtside and filmed on this
                // phone has none, and reading it would call a video sitting on
                // the phone "Not on this phone" forever. Android shipped that
                // version and it was a Critical.
                entryId: localEntries.first { $0.scoreLogId == content.card.scoreLogId }?.id,
                group: .ownedMatches,
                title: content.card.title,
                subtitle: content.card.playersLine
            )
        }
    }

    let sharedRows = sharedMatches.map { match in
        rowFor(
            key: "shared-\(match.videoId)",
            entryId: match.videoId,
            group: .shared,
            title: matchRowPrimary(match),
            subtitle: matchRowSecondary(match)
        )
    }

    // One video, one row. A video-first import the cloud has finished clipping is
    // both a local video and an owned match, and the two rows would carry the
    // same entry id, the same state and two identical "Analyze" buttons - the
    // drawer gets away with that because its two rows look nothing alike, and
    // here they are indistinguishable. First wins, and the order below puts the
    // local row first deliberately: it is the stable one, alive from import until
    // the file leaves the phone, whereas the owned row only appears once a cloud
    // run has clipped, so keeping that one instead would make the row jump
    // sections mid-life. Rows with no entry fall back to their own key, and every
    // NOT_ON_DEVICE row has a nil entryId, so two matches that are merely both
    // absent from this phone never collapse into each other.
    var seen = Set<String>()
    return (localVideoRows + ownedMatchRows + sharedRows).filter {
        seen.insert($0.entryId ?? $0.key).inserted
    }
}

/// Which explanatory line belongs above these rows. See `AnalyticsLegend`.
func analyticsLegend(for rows: [AnalyticsRow]) -> AnalyticsLegend {
    if rows.isEmpty { return .silent }
    // Every row inert for the same reason: nothing on this list has ever touched
    // this phone. Repeating "Not on this phone" down the whole list would say the
    // same thing as many times as there are rows.
    if rows.allSatisfy({ $0.state == .notOnDevice }) { return .nothingOnThisPhone }
    // A dot is a symbol and needs explaining; a button labelled "Analyze" does
    // not, so the dot wins whenever both are on screen.
    if rows.contains(where: { AnalyticsRowStateKt.opensAnalytics(state: $0.state) }) { return .dot }
    return .analyseButton
}
