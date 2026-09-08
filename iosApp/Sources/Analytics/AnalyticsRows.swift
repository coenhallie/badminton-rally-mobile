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
    /// A run is under way. [phase] is the drawer's own wording for that stage.
    case inProgress(phase: String)
    /// The last attempt ended in a failure, with the pipeline's own message.
    case failed(reason: String)
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
/// legend over a list with no dots the moment anything was ANALYSABLE. Every
/// local video on iOS is ANALYSABLE, since no track store exists here yet, so
/// that switch could not be copied; it turned out to be a live defect on Android
/// as well, and this decision was ported back there.
///
/// Both of the middle cases are ordinary on iOS: a coach whose matches were all
/// filmed elsewhere sees an inert list, and a coach with videos on the phone sees
/// live "Analyze" buttons and no dot to explain. Explaining a dot that cannot
/// render is the same defect as a comment outliving its code.
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
            // Android adds "- tap to view". Not here: only a READY row is
            // tappable on Android, and this list opens nothing at all (there is
            // no iOS analysis screen to open), so the dot means "analyzed" and
            // promises nothing further.
            return "Analyzed on this phone."
        case .analyseButton:
            // What the button actually does on iPhone, said plainly, so a screen
            // called Analytics does not look like it is about to draw a chart.
            return "Analyze sends a video to the cloud and cuts it into rallies."
        }
    }
}

/// What the pipeline is doing with this video right now.
///
/// ONE liveness source, not two. Android composes the on-device runner's
/// `LocalAnalysisState` with the cloud pipeline's `AnalyzeStage`, because it has
/// both; iOS has no on-device runner at all (no `LocalAnalysisRunner`, and no
/// track store, which is the same reason no row here can be READY yet), so the
/// cloud pipeline's stage is the whole of it. A reader arriving from
/// `AnalyticsRows.kt` will expect a second signal and there is none to compose.
///
/// The phase wording is `LocalVideoStatus`', the same text the drawer's own local
/// video rows show for the same stages, rather than a second copy of it here.
func analyseAffordance(for entry: LocalVideoEntry, progress: AnalyzeProgress?) -> AnalyseAffordance {
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
/// Port of Android's `buildAnalyticsRows`, minus its `storedTrackIds` and its
/// on-device liveness map, neither of which exists on this platform.
func buildAnalyticsRows(
    localEntries: [LocalVideoEntry],
    ownedRows: [MatchRow],
    sharedMatches: [MatchSummary],
    progressByEntryId: [String: AnalyzeProgress]
) -> [AnalyticsRow] {
    func rowFor(
        key: String, entryId: String?, group: AnalyticsGroup, title: String, subtitle: String
    ) -> AnalyticsRow {
        let entry = entryId.flatMap { id in localEntries.first { $0.id == id } }
        // `false`, always, and deliberately NOT an iOS special case: there is no
        // track store on this platform yet, so the classifier's own argument is
        // simply not satisfied. The day one exists, this line starts returning
        // READY and the screen needs no other change.
        let state = analyticsRowState(hasLocalEntry: entry != nil, hasStoredTrack: false)
        var affordance = AnalyseAffordance.ready
        // Only an analysable row reads liveness. A READY row keeps its dot while
        // a second run is in flight, because the track the first run produced is
        // still there; and a NOT_ON_DEVICE row has no run of its own to report.
        //
        // `entry` is never nil in this branch: analysable requires a local entry,
        // which is exactly `entry != nil` above. The binding is a guard, not a
        // second source of truth.
        if state == .analysable, let entry {
            affordance = analyseAffordance(for: entry, progress: progressByEntryId[entry.id])
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
    if rows.contains(where: { $0.state == .ready }) { return .dot }
    return .analyseButton
}
