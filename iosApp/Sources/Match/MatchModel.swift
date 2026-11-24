import Foundation
import Shared

/// A new "Home was at the" answer for the video of a scored match: the side
/// nearest the camera (nil: not set) and the video's ids, as the page knows
/// them. Mirrors Android's `VideoEndChange`.
struct VideoEndChange {
    let nearSide: Side?
    let videoId: String?
    let entryId: String?
}

/// One match, folded. Everything is derived from the store directly, so a point
/// scored elsewhere or a video finishing its pipeline redraws this page without a
/// refresh once `start()`'s loop picks up the change.
///
/// The Swift half of `MatchViewModel`.
@Observable @MainActor
final class MatchModel {
    /// Nil for a video-first or shared match - the page it opens on has no score
    /// log to look up.
    let scoreLogId: String?

    /// Nil for a video-first or shared match, and for one deleted while open.
    private(set) var log: ScoreLog? = nil
    /// True only when `log` belongs to the signed-in user (never for a read-only
    /// linked coach log). Gates every score-log write affordance on `MatchView`.
    private(set) var isOwnedLog: Bool = false
    private(set) var match: MatchState? = nil
    /// The coach's players plus any linked profiles: a side a profile holds is
    /// named from these, on the card, the Points facet, the header and exported text.
    private(set) var players: [Player] = []
    private(set) var card: ScoreMatchCard? = nil
    private(set) var tally: ScoreTagSummary = ScoreTagSummary.companion.EMPTY
    /// A finished match with no video, and no video already picked for it. The
    /// one condition "Add video" is offered on.
    private(set) var canAddVideo: Bool = false
    /// This match has a video and nothing is in flight for it: the one condition
    /// "Change video" and "Remove video" are offered on, see
    /// `MatchVideoRemovalKt.scoreLogCanRemoveVideo`. Mutually exclusive with
    /// `canAddVideo`.
    private(set) var canRemoveVideo: Bool = false
    /// Whether a videos row exists, which decides what the confirm says is about
    /// to be lost - an entry that never reached CREATE_ROW has no rallies and no
    /// notes to lose.
    private(set) var hasServerVideo: Bool = false
    /// A failed removal or details save, for the page's error banner. Nothing
    /// else on this page can fail: everything else it shows is derived from
    /// stores it only reads.
    var error: String? = nil

    private let scoreLogs: ScoreLogsRepository
    private let localVideos: LocalVideoRepository
    private let clips: ClipsRepository
    private let videos: VideosRepository
    private let localAnnotations: LocalAnnotationsRepository
    /// Nil in tests that name no player: every side then reads its typed names.
    private let playersRepository: PlayersRepository?
    private let playerAccessRepository: PlayerAccessRepository?

    private var localEntries: [LocalVideoEntry]
    private var ownedRoster: [Player]
    private var linkedProfiles: [LinkedPlayerProfile]

    init(
        scoreLogs: ScoreLogsRepository,
        localVideos: LocalVideoRepository,
        clips: ClipsRepository,
        videos: VideosRepository,
        localAnnotations: LocalAnnotationsRepository,
        players: PlayersRepository? = nil,
        playerAccess: PlayerAccessRepository? = nil,
        scoreLogId: String?
    ) {
        self.scoreLogs = scoreLogs
        self.localVideos = localVideos
        self.clips = clips
        self.videos = videos
        self.localAnnotations = localAnnotations
        self.playersRepository = players
        self.playerAccessRepository = playerAccess
        self.ownedRoster = players?.players.value ?? []
        self.linkedProfiles = playerAccess?.linkedProfiles.value ?? []
        self.scoreLogId = scoreLogId
        self.localEntries = localVideos.entries.value
        readStore()
    }

    convenience init(rally: RallyApp, scoreLogId: String?) {
        self.init(
            scoreLogs: rally.scoreLogs, localVideos: rally.localVideos,
            clips: rally.clips, videos: rally.videos, localAnnotations: rally.localAnnotations,
            players: rally.players, playerAccess: rally.playerAccess, scoreLogId: scoreLogId
        )
    }

    /// The details editor's save for a scored match: the name, the format and who
    /// played, in the one write to the log (local-first like every other edit to a
    /// match; sync carries it). A combination the database would refuse, a profile
    /// on a doubles log, is said instead and nothing is written.
    ///
    /// `end` is passed only when the editor's "Home was at the" row changed. The
    /// log speaks for the players, so the attached video learns the end alone,
    /// with nobody on it, wherever its own target sends it (the entry before
    /// upload, the row after). Mirrors Android's `MatchViewModel.updateDetails`.
    func updateDetails(title: String, format: MatchFormat?, players: MatchPlayers, end: VideoEndChange?) async {
        guard let scoreLogId, scoreLogs.get(id: scoreLogId) != nil else { return }
        error = nil
        if let problem = MatchPlayersKt.matchPlayersProblem(players: players, format: format) {
            error = problem
            return
        }
        scoreLogs.updateDetails(id: scoreLogId, title: title, format: format, players: players)
        readStore()
        guard let end else { return }
        let entry = end.entryId.flatMap { localVideos.get(id: $0) }
        guard let target = SaveMatchPlayersKt.playersTarget(scoreLogId: nil, entry: entry, videoId: end.videoId) else {
            return
        }
        // do/catch, not `try?`: a throw read as nil would be this save's success.
        do {
            if let message = try await SaveMatchPlayersKt.saveMatchPlayersOrMessage(
                target: target, scoreLogs: scoreLogs, videos: videos, localVideos: localVideos,
                scoreLogId: nil, videoId: end.videoId, entryId: end.entryId,
                players: MatchPlayers.companion.NONE, nearSide: end.nearSide, format: nil
            ) {
                error = message
            }
        } catch {
            self.error = PlayersCopy.shared.WHOS_PLAYING_SAVE_FAILED
        }
    }

    /// The details editor's save for a match that was only ever filmed, whose
    /// details live on its videos row rather than on a score log: name,
    /// description, format, who played and which end Home was at, in one write.
    /// Server first, then the local entry that uploaded it: the ordering, and the
    /// message on failure, are `updateVideoMatchDetailsOrMessage`'s. The page reads
    /// the result back through the repository's metadata cache, so nothing here
    /// has to hand it over. Mirrors Android's `MatchViewModel.updateVideoDetails`.
    func updateVideoDetails(
        videoId: String, title: String, description: String, format: MatchFormat?,
        players: MatchPlayers, nearSide: Side?
    ) async {
        error = nil
        do {
            let message = try await VideoMatchDetailsKt.updateVideoMatchDetailsOrMessage(
                videos: videos, localVideos: localVideos, videoId: videoId,
                title: title, description: description, format: format,
                players: players, nearSide: nearSide
            )
            if let message { error = message }
        } catch {
            self.error = VideoMatchDetailsKt.VIDEO_DETAILS_SAVE_FAILED_MESSAGE
        }
    }

    /// The "Match details" sheet's save, for a match only its local entry speaks
    /// for (no score log, no clips yet): who's playing wherever the entry's stage
    /// sends it, then the name and description while the entry is still LOCAL.
    /// See `saveEntryMatchDetailsOrMessage`; its sentence goes on the page's
    /// banner. Mirrors Android's `MatchViewModel.saveEntryDetails`.
    func saveEntryDetails(
        entryId: String, title: String, description: String, players: MatchPlayers, nearSide: Side?
    ) async {
        error = nil
        do {
            if let message = try await SaveMatchPlayersKt.saveEntryMatchDetailsOrMessage(
                scoreLogs: scoreLogs, videos: videos, localVideos: localVideos, entryId: entryId,
                title: title, description: description, players: players, nearSide: nearSide
            ) {
                error = message
            }
        } catch {
            self.error = PlayersCopy.shared.WHOS_PLAYING_SAVE_FAILED
        }
    }

    /// Takes the video off this match and leaves the match: the ordering, and the
    /// message on failure, are `removeMatchVideoOrMessage`'s. Returns whether the
    /// video is gone, which is what makes "Change video" one gesture rather than
    /// two - once removal lands this match has no video in either sense, so the
    /// caller can go straight to the picker the page already owns. Mirrors
    /// Android's `MatchViewModel.removeVideo`.
    @discardableResult
    func removeVideo() async -> Bool {
        guard let scoreLogId, scoreLogs.get(id: scoreLogId) != nil else { return false }
        // A previous failure must not sit over a fresh attempt: the banner has no
        // timeout of its own, matching every other ErrorBanner in the app.
        error = nil
        // do/catch, not `try?`: `try?` would flatten a thrown error and a nil
        // (success) into the same case, and this return value gates a picker.
        do {
            let message = try await MatchVideoRemovalKt.removeMatchVideoOrMessage(
                scoreLogId: scoreLogId,
                scoreLogs: scoreLogs,
                videos: videos,
                clips: clips,
                localVideos: localVideos,
                localAnnotations: localAnnotations
            )
            // The repository directly, not `start()`'s cached copy: that loop may
            // not have delivered the shorter list yet, and this page must not go
            // on offering a gesture for a video it has just removed.
            localEntries = localVideos.entries.value
            readStore()
            if let message {
                error = message
                return false
            }
            return true
        } catch {
            self.error = MatchVideoRemovalKt.MATCH_VIDEO_REMOVE_FAILED_MESSAGE
            return false
        }
    }

    /// Runs for as long as the page is on screen: `MatchView`'s `.task` calls this
    /// on every appearance and cancels it on every disappearance, so a point
    /// scored elsewhere or a video finishing its pipeline redraws this page
    /// without a manual refresh.
    ///
    /// Every loop is a structured child of that task - the same shape as
    /// `BackgroundWorkModel.observe`. Loops here used to be unstructured
    /// `Task {}`s, which cancellation cannot reach: leaving the page left them
    /// running for good, and each return stacked more, every one of them
    /// re-folding the match on every emission. What the match's video is doing
    /// is not observed here: the page's status banner draws the match list's
    /// own row, the one place that also sees an on-device run.
    func start() async {
        await withTaskGroup(of: Void.self) { group in
            group.addTask { @MainActor [weak self] in
                guard let self else { return }
                for await entries in self.localVideos.entries {
                    self.localEntries = entries
                    self.readStore()
                }
            }
            group.addTask { @MainActor [weak self] in
                guard let self else { return }
                for await _ in self.scoreLogs.logs {
                    self.readStore()
                }
            }
            group.addTask { @MainActor [weak self] in
                guard let self else { return }
                for await _ in self.scoreLogs.linkedLogs {
                    self.readStore()
                }
            }
            if let playersRepository {
                group.addTask { @MainActor [weak self] in
                    guard let self else { return }
                    // The card is stored, not computed, so a rename re-reads it.
                    for await list in playersRepository.players {
                        self.ownedRoster = list
                        self.readStore()
                    }
                }
            }
            if let playerAccessRepository {
                group.addTask { @MainActor [weak self] in
                    guard let self else { return }
                    for await profiles in playerAccessRepository.linkedProfiles {
                        self.linkedProfiles = profiles
                        self.readStore()
                    }
                }
            }
        }
    }

    /// A synchronous re-read for a caller that mutates the store directly rather
    /// than going through `start()`'s loop. Mirrors `ScoringModel.reload()`, kept
    /// for the same reason: not every caller can afford to wait on the flow.
    func reload() { readStore() }

    private func mergedPlayers() -> [Player] {
        var seen = Set<String>()
        var result: [Player] = []
        for p in ownedRoster where seen.insert(p.id).inserted {
            result.append(p)
        }
        for profile in linkedProfiles where seen.insert(profile.playerId).inserted {
            result.append(profile.toPlayer())
        }
        return result
    }

    private func readStore() {
        players = mergedPlayers()
        guard let scoreLogId else {
            log = nil
            isOwnedLog = false
            match = nil
            card = nil
            tally = ScoreTagSummary.companion.EMPTY
            canAddVideo = false
            canRemoveVideo = false
            hasServerVideo = false
            return
        }
        let owned = scoreLogs.get(id: scoreLogId)
        let found = owned ?? scoreLogs.linkedLogs.value.first { $0.id == scoreLogId }
        guard let found else {
            log = nil
            isOwnedLog = false
            match = nil
            card = nil
            tally = ScoreTagSummary.companion.EMPTY
            canAddVideo = false
            canRemoveVideo = false
            hasServerVideo = false
            return
        }
        log = found
        isOwnedLog = owned != nil
        let state = found.state()
        match = state
        card = ScoreMatchCardKt.buildScoreMatchCard(log: found, players: players)
        tally = ScoreTagSummaryKt.buildScoreTagSummary(state: state)
        // An entry can exist for a while before the pipeline gives the match a
        // videoId (LOCAL, UPLOADING, PROCESSING); gating on videoId alone would
        // let "Add video" reopen the picker mid-attach.
        let hasPendingEntry = localEntries.contains { $0.scoreLogId == scoreLogId }
        // isPlayable(), not `status != .live`: the board's Done button navigates
        // without finishing, deliberately, so that a match ended on a mis-tap can
        // still be undone - which leaves a won match sitting at .live. This is the
        // predicate every surface should ask, rather than either half of it.
        canAddVideo = isOwnedLog && !found.isPlayable() && found.videoId == nil && !hasPendingEntry
        canRemoveVideo = isOwnedLog && MatchVideoRemovalKt.scoreLogCanRemoveVideo(log: found, entries: localEntries)
        hasServerVideo = found.videoId != nil
    }
}
