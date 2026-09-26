import Foundation
import Shared

/// Which of the Matches tab's own two sections a row belongs to, in display
/// order. Port of Android's `AnalyticsGroup`.
///
/// Two, not the drawer era's earlier three (local videos, owned matches,
/// shared): the shared builder already folds a match's local video and its
/// cloud clips into one `MatchListItem` regardless of where the file is, so
/// "on this phone" versus "my matches" was never a distinction between two
/// different *matches* here, only between two ways the same row's
/// `MatchStatusLine` draws its where-glyphs.
enum AnalyticsGroup: CaseIterable {
    case mine
    case shared

    /// `nil` for `.mine`: the Matches tab's list does not label its own
    /// matches either, only the shared ones.
    var label: String? {
        switch self {
        case .mine:   return nil
        case .shared: return MatchListCopy.shared.SHARED_SECTION
        }
    }
}

/// What an `analysable` row's control should show, in place of a live "Analyze"
/// button, when the pipeline is already busy with its video or its last attempt
/// failed. Port of Android's `AnalyseAffordance`.
enum AnalyseAffordance: Equatable {
    /// Nothing is running: the button is live.
    case ready
    /// A run is under way. `phase` is the Matches tab list's own wording for
    /// that stage.
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
    ///
    /// This drives only the row's trailing control (`trailingControl` in
    /// `AnalyticsListView`) - a disabled "Paused" pill - not a second line of
    /// text: `MatchStatusLine` already draws the same pause message from the
    /// item's own `statusText`, and a duplicate line under it would say the
    /// same thing twice in two different words.
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

/// One row of the Analytics list, one per `MatchListItem` in the shared match
/// list - the same rows, in the same order, the Matches tab draws, because
/// both read the one `MatchList` the shared builder produces from the same
/// inputs.
///
/// `entryId` is nil only when `state` is `.notOnDevice` with no video to point
/// at (a score-only match, or a shared match) - inert either way.
struct AnalyticsRow: Identifiable, Equatable {
    let key: String
    let entryId: String?
    let group: AnalyticsGroup
    /// `item.title`, carried as its own field rather than read off `item` at
    /// each use because that is what the pinned tests already assert on.
    let title: String
    /// `item.detail`, for the same reason.
    let subtitle: String
    let state: AnalyticsRowState
    let affordance: AnalyseAffordance
    /// Kept alongside the fields above for everything else a row draws that
    /// they never had to: the status line, the players line.
    let item: MatchListItem

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

    var text: String? {
        switch self {
        case .silent:
            return nil
        case .nothingOnThisPhone:
            return "None of these matches are on this phone yet."
        case .dot:
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
/// because the entry is already running.
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

/// Builds the Analytics list's rows: one per `MatchListItem` in `matchList`,
/// the coach's own matches first and shared ones after - the same rows, in the
/// same order, the Matches tab draws, because both read the one `MatchList`
/// the shared builder produces from the same inputs.
///
/// `analyticsRowState` alone decides READY / READY_NO_VIDEO / ANALYSABLE /
/// NOT_ON_DEVICE, from the two facts the item already carries - whether it has
/// a local entry (`item.entryId`) and whether it has a stored track
/// (`item.heatmapId`) - so this function only re-reads those two fields. For
/// an ANALYSABLE row it also reads both pipelines' own liveness through
/// `analyseAffordance`, so the row's control can show progress or a failure
/// instead of a live "Analyze" label; `hasStoredTrack` alone cannot tell a run
/// in flight, or one that just failed, from one never attempted, and that is
/// the one distinction `MatchListItem.action` does not carry a matching
/// affordance for. A READY row's own liveness is not lost by skipping it here:
/// it already reaches the screen through the item's own `action`/`statusText`,
/// which `MatchStatusLine` draws for every row, the same component the
/// Matches tab uses for the same fields.
///
/// Port of Android's `buildAnalyticsRows`. No `storedTrackIds` parameter:
/// Android dropped it once the shared builder started writing `heatmapId`
/// itself, which is now the single source for whether a track exists.
func buildAnalyticsRows(
    matchList: MatchList,
    localEntries: [LocalVideoEntry],
    progressByEntryId: [String: AnalyzeProgress],
    /// What the on-device runner is doing, per entry. Absent means idle.
    deviceStates: [String: LocalAnalysisState] = [:]
) -> [AnalyticsRow] {
    func rowFor(_ item: MatchListItem, group: AnalyticsGroup) -> AnalyticsRow {
        let entry = item.entryId.flatMap { id in localEntries.first { $0.id == id } }
        let trackId = item.heatmapId
        let state = analyticsRowState(
            hasLocalEntry: item.entryId != nil,
            hasStoredTrack: trackId != nil
        )
        var affordance = AnalyseAffordance.ready
        // Only an analysable row reads liveness. A ready row keeps its dot while
        // a second run is in flight, because the track the first run produced is
        // still there; and a not-on-device row has no run of its own to report.
        //
        // `entry` is never nil in this branch: analysable requires a local entry,
        // which is exactly `item.entryId != nil` above, and entry is looked up by
        // that same id. The binding is a guard, not a second source of truth.
        if state == .analysable, let entry {
            affordance = analyseAffordance(
                for: entry,
                progress: progressByEntryId[entry.id],
                device: deviceStates[entry.id] ?? .idle
            )
        }
        return AnalyticsRow(
            key: item.key,
            // The track's own id stands in once there is no local entry to open
            // instead: `.readyNoVideo` is exactly this case, and this row still
            // has to open on a tap.
            entryId: item.entryId ?? trackId,
            group: group,
            title: item.title,
            subtitle: item.detail,
            state: state,
            affordance: affordance,
            item: item
        )
    }

    return matchList.mine.map { rowFor($0, group: .mine) }
        + matchList.shared.map { rowFor($0, group: .shared) }
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
