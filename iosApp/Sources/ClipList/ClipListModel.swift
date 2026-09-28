import Foundation
import Shared

@Observable @MainActor
final class ClipListModel {
    let rally: RallyApp
    let analyze: AnalyzeCoordinator
    private(set) var clips: [RallyClip] = []
    /// The match list's rows, which the match page finds its match in too: one
    /// per match wherever its video is, built by the shared `buildMatchList`
    /// from everything below, so this list and Android's cannot disagree about
    /// what a match is or what it has.
    private(set) var matchList = MatchList(mine: [], shared: [])
    /// The single source of truth for every video on this phone. `ClipListView`
    /// reads this rather than keeping its own copy, so the rows and the attach
    /// status this model derives from the same entries can never disagree.
    private(set) var localEntries: [LocalVideoEntry] = []
    /// Ids (entry or cloud video) with a stored player track: a heatmap to open.
    private(set) var storedTrackIds: Set<String> = []
    /// How many pulls-to-refresh have completed. Read by a row's thumbnail to
    /// decide whether a cover clip that failed to load deserves another try:
    /// `RallyClip.thumbnailStoragePath` is a stable backend key that does not
    /// change just because a fetch failed, so a coach who goes offline, fails
    /// once, then pulls to refresh once back online sees the same path with
    /// nothing to observe a change on - `refreshGeneration` is that signal
    /// instead. See `MatchThumbnail.CoverRetryKey`.
    private(set) var refreshGeneration = 0
    var isRefreshing = false
    var error: String? = nil
    private var sharerByVideoId: [String: String] = [:]
    private var metadataByVideoId: [String: MatchMetadata] = [:]
    private var scoreLogs: [ScoreLog] = []
    private var progressByEntryId: [String: AnalyzeProgress] = [:]
    /// On-device runs, from `LocalAnalysisRunner.deviceRuns` via `.feedsMatchList`:
    /// the runner is owned by the app, not this model, and either screen that
    /// applies the modifier keeps this current.
    private var deviceRuns: [String: DeviceRun] = [:]
    /// The runner's own live device runs, read fresh at the moment a delete
    /// runs rather than through `deviceRuns` above.
    ///
    /// `deviceRuns` is fed by whichever screen's `.feedsMatchList` last ran,
    /// which is enough for building `matchList` - a card a beat out of date
    /// just redraws again once the feed catches up - but not for a delete's
    /// own guard: a run that started on a screen this model has not yet been
    /// fed for must still hold its file. Reading the runner's live state
    /// directly, the way Android's `discardEntry` reads `deviceStates.value`,
    /// closes that gap. Defaults to "nothing running" so a build with no
    /// on-device models staged, or a test that supplies no runner, behaves as
    /// it always has.
    private let liveDeviceRuns: @MainActor () -> [String: DeviceRun]
    private var storedSkeletonIds: Set<String> = []
    private var deviceClipCounts: [String: KotlinInt] = [:]
    /// Whether the refresh that opens the list has been kicked off. The ONLY
    /// thing start() does once per model rather than once per appearance - see
    /// `start()` for why the loops must not share this latch.
    private var initialRefreshStarted = false

    // The stores this model reads and deletes through. `rally`'s own unless a
    // test hands in a double: a delete's outcome turns on what the server
    // says, and only a double can say no, or yes, on cue.
    private let clipsRepo: ClipsRepository
    private let videos: VideosRepository
    private let scoreLogsRepo: ScoreLogsRepository
    private let localVideos: LocalVideoRepository
    private let localAnnotations: LocalAnnotationsRepository
    private let shares: SharesRepository

    init(
        rally: RallyApp,
        analyze: AnalyzeCoordinator,
        clips: ClipsRepository? = nil,
        videos: VideosRepository? = nil,
        scoreLogs: ScoreLogsRepository? = nil,
        localVideos: LocalVideoRepository? = nil,
        localAnnotations: LocalAnnotationsRepository? = nil,
        shares: SharesRepository? = nil,
        liveDeviceRuns: @escaping @MainActor () -> [String: DeviceRun] = { [:] }
    ) {
        self.rally = rally
        self.analyze = analyze
        self.clipsRepo = clips ?? rally.clips
        self.videos = videos ?? rally.videos
        self.scoreLogsRepo = scoreLogs ?? rally.scoreLogs
        self.localVideos = localVideos ?? rally.localVideos
        self.localAnnotations = localAnnotations ?? rally.localAnnotations
        self.shares = shares ?? rally.shares
        self.liveDeviceRuns = liveDeviceRuns
    }

    /// Runs for as long as a screen that reads the list is on screen: the
    /// list's (`MatchesList`'s) and the match page's `.task` call this on
    /// every appearance and cancel it on every disappearance - opening a
    /// match, or Settings, is a disappearance of the list. The match page runs
    /// it because it reads `matchList` for its sections, and the list's own
    /// loops stop under it.
    /// Not idempotent: each call runs its own five loops, and only the initial
    /// refresh is latched.
    ///
    /// This used to latch on a `started` flag, to stop each return to the list
    /// stacking another set of loops. The latch was covering for the real
    /// defect - three of the four loops were unstructured `Task {}`s that
    /// cancellation could not reach - and it made a worse one: the fourth loop,
    /// the clips one, ran inline and DID end with the cancelled task, and the
    /// latch then turned every later start() into a no-op. The list stopped
    /// following the clips flow the first time a match was opened.
    func start() async {
        // Once per model, not once per appearance: coming back from a match
        // must not put the spinner up and re-fetch four things. Unstructured on
        // purpose, unlike the loops - it is finite, and cancelling it because
        // the coach opened a match would surface "Couldn't refresh matches" for
        // a request that was merely abandoned.
        if !initialRefreshStarted {
            initialRefreshStarted = true
            Task { await refresh() }
        }
        await observe()
    }

    /// The five loops, as structured children of the caller's task - the same
    /// shape as `BackgroundWorkModel.observe`. Cancelling the caller stops all
    /// five, calling again starts all five, and there is never a second set:
    /// the group does not return until every child has.
    ///
    /// Apart from `start()` so the tests can hold the loops to that contract
    /// without the network refresh riding along.
    func observe() async {
        await withTaskGroup(of: Void.self) { group in
            group.addTask { @MainActor [weak self] in
                guard let self else { return }
                for await logs in self.scoreLogsRepo.logs {
                    self.scoreLogs = logs
                    self.regroup()
                }
            }
            group.addTask { @MainActor [weak self] in
                guard let self else { return }
                for await entries in self.localVideos.entries {
                    self.localEntries = entries
                    self.regroup()
                }
            }
            group.addTask { @MainActor [weak self] in
                guard let self else { return }
                for await map in self.analyze.progress {
                    self.progressByEntryId = map
                    self.regroup()
                }
            }
            group.addTask { @MainActor [weak self] in
                guard let self else { return }
                for await latest in self.clipsRepo.observeClips() {
                    self.clips = latest
                    self.regroup()
                }
            }
            // The repository's cache rather than a map of this model's: a rename
            // made on the match page has to reach every row the moment it lands.
            group.addTask { @MainActor [weak self] in
                guard let self else { return }
                for await map in self.videos.observeMatchMetadata() {
                    self.metadataByVideoId = map
                    self.regroup()
                }
            }
        }
    }

    func refresh() async {
        isRefreshing = true
        error = nil
        do {
            try await clipsRepo.refresh()
        } catch {
            self.error = "Couldn't refresh matches. Pull to try again."
        }
        // Soft failure: leave sharerByVideoId untouched, no user-facing error (matches Android).
        if let received = try? await shares.listReceived() {
            sharerByVideoId = Dictionary(
                uniqueKeysWithValues: received.compactMap { r in
                    r.sharerEmail.map { (r.videoId, $0) }
                }
            )
        }
        // A success lands in the repository's cache, which `observe()` follows.
        // Same soft-failure contract as the shares lookup: a failure leaves the
        // previous map alone rather than blanking every name the user can see.
        _ = try? await SwiftInteropKt.listMatchMetadataOrNull(videos)
        // Soft failure, same contract as the shares and metadata reads: the matches
        // are all still on this phone, so there is nothing to say and nothing for
        // the user to do about it.
        _ = try? await scoreLogsRepo.syncScoreLogsOrMessage()
        regroup()
        // Unconditionally, even when `clipsRepo.refresh()` above threw: every
        // one of this method's four requests fails softly on its own (the
        // clips failure is the only one that sets `error`, and the other
        // three just leave their caches alone), so "the pull failed" is not
        // one clean outcome to gate this on - a cover clip's own fetch is a
        // fifth, independent request that this generation exists to let retry,
        // and it can succeed even when the clips list itself did not change at
        // all. A retry that finds nothing new costs one redundant signed-URL
        // fetch; refusing one that would have worked leaves a coach staring at
        // a glyph tile his next pull-to-refresh was supposed to fix.
        refreshGeneration += 1
        isRefreshing = false
    }

    /// Deletes the cloud match, and with it the file on this phone when the row
    /// had one (`entryId`). The file has to go too: the entry outlives its
    /// analysis at ANALYZED, and a surviving entry would bring the row straight
    /// back as a phone-only match the coach just deleted everywhere.
    ///
    /// Only on success. A failed delete leaves the match in the cloud, and a
    /// file thrown away under it would turn "nothing happened" into "half of it
    /// happened". Mirrors Android's `ClipListViewModel.deleteMatchVideo`.
    func deleteMatch(videoId: String, entryId: String? = nil) async {
        // do/catch, not `try?`: `try?` reads a throw as nil, which is this call's
        // success, and went on to discard the file under a match the server
        // never confirmed deleting. Same reasoning as `deleteScoreLog`.
        do {
            if let message = try await SwiftInteropKt.deleteMatchOrMessage(videos, videoId: videoId) {
                error = message
                return
            }
        } catch {
            self.error = SwiftInteropKt.DELETE_MATCH_FAILED_MESSAGE
            return
        }
        if let entryId { discardEntry(entryId) }
        clipsRepo.pruneVideo(videoId: videoId)
        // The database does this too, through ON DELETE SET NULL and the unbind
        // trigger, but the phone would not learn it until the next sync and
        // would go on advertising clips for a deleted video. Idempotent against
        // the trigger, which has already done it.
        //
        // This path deletes the match outright; "remove the video, keep the
        // match" is the match page's own gesture and runs the shared
        // MatchVideoRemovalKt.removeMatchVideoOrMessage instead. The call below
        // is here to mirror the trigger above, so that a bound match deleted
        // from the list does not leave a stale binding behind on this phone
        // between the delete and the next sync.
        for log in scoreLogsRepo.logs.value where log.videoId == videoId {
            scoreLogsRepo.detachVideo(id: log.id)
        }
        await refresh()
    }

    func leaveShare(videoId: String) async {
        // do/catch, not `try?`: a throw read as nil, the call's success, and
        // pruned a share the server never confirmed leaving. See `deleteMatch`.
        do {
            if let message = try await SwiftInteropKt.leaveShareOrMessage(shares, videoId: videoId) {
                error = message
                return
            }
        } catch {
            self.error = SwiftInteropKt.LEAVE_SHARE_FAILED_MESSAGE
            return
        }
        clipsRepo.pruneVideo(videoId: videoId)
        await refresh()
    }

    /// Frees the file and keeps the tracks: the coach wants the space back, not
    /// the match gone, so the heatmap and base position keep working. Mirrors
    /// androidApp's `LocalVideoListViewModel.remove`. The file goes with the
    /// entry: the registry's onRemoved hook is wired to LocalVideoFiles.delete
    /// in createRallyApp, so every removal path cleans up.
    func removeFromPhone(entryId: String) {
        localVideos.freeVideo(id: entryId)
        localAnnotations.removeAllFor(videoId: entryId)
    }

    /// A scored match with no clips. There is no leaveShare counterpart:
    /// match_shares is keyed on video_id, so a score-only match cannot be
    /// shared and therefore cannot be left.
    func deleteScoreMatch(scoreLogId: String, entryId: String? = nil) async {
        // The row's entry, if the log did not orphan it (see deleteScoreLog):
        // only once the server has agreed. A no-op when it is already gone.
        if await deleteScoreLog(scoreLogId: scoreLogId, hasVideo: false, entryId: entryId), let entryId {
            discardEntry(entryId)
        }
    }

    /// A bound row with clips deletes two things at once, score log first:
    /// `deleteMatch`'s refresh() syncs score logs, and if that sync landed while
    /// the score log's own delete was still on the wire, it would pull the row
    /// back from the server and resurrect the match the coach just deleted.
    ///
    /// If the score log's server delete fails, the video delete does not run
    /// at all: the row is already gone from this phone (the failure message
    /// says so), and the video and its clips are left untouched rather than
    /// deleted on a best-effort basis while the coach cannot see whether the
    /// rest of it landed. Mirrors Android's `ClipListViewModel.deleteBoundMatch`.
    func deleteBoundMatch(videoId: String, scoreLogId: String, entryId: String? = nil) async {
        if await deleteScoreLog(scoreLogId: scoreLogId, hasVideo: true, entryId: entryId) {
            // The row's entry goes with the video, on the video's own success:
            // a file discarded before then would be lost under a cloud match
            // that could still survive.
            await deleteMatch(videoId: videoId, entryId: entryId)
        }
    }

    /// Returns whether the server accepted the delete, not whether the row left
    /// this phone - the repository removes it locally either way, win or lose.
    /// `deleteBoundMatch` needs that server outcome to decide whether it is
    /// safe to go on.
    private func deleteScoreLog(scoreLogId: String, hasVideo: Bool, entryId: String?) async -> Bool {
        // The server first, then the orphaned entry, in Android's order. The
        // outcome is kept rather than returned early: the discard below must run
        // either way.
        //
        // do/catch, not `try?`: `try?` flattens a thrown error and a nil (success)
        // into the same case, so a throw would return true here and let the caller
        // go on to the irreversible video delete plus a resurrecting refresh().
        let accepted: Bool
        do {
            if let message = try await scoreLogsRepo.deleteScoreMatchOrMessage(id: scoreLogId, hasVideo: hasVideo) {
                error = message
                accepted = false
            } else {
                accepted = true
            }
        } catch {
            self.error = SwiftInteropKt.scoreLogDeleteFailedMessage(hasVideo: hasVideo)
            accepted = false
        }
        // The log is gone from this device either way - ScoreLogsRepository.delete
        // removes it locally unconditionally, win or lose on the server - so an
        // entry pointed at it is orphaned regardless of the outcome above, and
        // this must not be gated on that outcome. Without this a settled entry -
        // most reachably one AnalyzeCoordinator's zero-rally detection left
        // FAILED - would be invisible (the match list hides an entry whose match
        // is not in the list) and unremovable.
        //
        // Only an entry this log orphans goes here, win or lose: one whose own
        // scoreLogId names the log. The match list also joins an entry to its
        // log by the log's video id (`entryId`, the row's own entry); such an
        // entry does not point at the log, is not orphaned by its removal, and
        // would come back as an ordinary video on this phone. Discarding it
        // before the server has agreed would lose the file while the match
        // survives in the cloud, so the callers discard it on success instead.
        // The scoreLogId lookup stays for a row with no entry of its own, minus
        // any entry that is another log's video (see `entryNamingLog`): the list
        // put that one on the other log's row, and it is not this log's to lose.
        //
        // Reads the repositories directly rather than the cached `localEntries`:
        // that cache is only as fresh as `start()`'s loop has delivered so far.
        // Mirrors Android's ClipListViewModel.deleteScoreLog.
        let candidate = entryId ?? MatchVideoRemovalKt.entryNamingLog(
            scoreLogId: scoreLogId, entries: localVideos.entries.value, logs: scoreLogsRepo.logs.value
        )?.id
        if let candidate, localVideos.get(id: candidate)?.scoreLogId == scoreLogId {
            discardEntry(candidate)
        }
        return accepted
    }

    /// The entry and its notes, all of it (the tracks and skeleton go with a
    /// DISCARDED entry). Guarded by the same rule the row's delete is offered
    /// under, re-asked here because a run can start between the confirmation
    /// and the server's answer: deleting the file under it would corrupt the
    /// run and swallow its outcome. The row's match is gone either way; a
    /// guarded entry simply stays as a video on this phone.
    private func discardEntry(_ entryId: String) {
        guard let entry = localVideos.get(id: entryId),
              LocalVideoEntryKt.canRemoveLocalVideo(stage: entry.stage)
        else { return }
        switch liveDeviceRuns()[entryId]?.kind {
        case .running, .paused: return
        case .failed, nil: break
        }
        localVideos.remove(id: entryId)
        localAnnotations.removeAllFor(videoId: entryId)
    }

    /// The on-device runs, as `LocalAnalysisRunner.deviceRuns` reduces them, for
    /// building `matchList`. Not what `discardEntry` guards a delete on; see
    /// `liveDeviceRuns`.
    func updateDeviceRuns(_ runs: [String: DeviceRun]) {
        guard runs != deviceRuns else { return }
        deviceRuns = runs
        regroup()
    }

    /// What earlier runs left on disk: tracks and skeletons keyed by entry or
    /// video id, and the rallies a device run cut, keyed by entry id.
    func updateStoredResults(tracks: Set<String>, skeletons: Set<String>, clipCounts: [String: Int]) {
        let counts = clipCounts.mapValues { KotlinInt(int: Int32($0)) }
        // Most re-reads find what the last one found; rebuilding the list for
        // them would redraw every row for nothing.
        guard tracks != storedTrackIds || skeletons != storedSkeletonIds || counts != deviceClipCounts else { return }
        storedTrackIds = tracks
        storedSkeletonIds = skeletons
        deviceClipCounts = counts
        regroup()
    }

    private func regroup() {
        matchList = MatchListBuilderKt.buildMatchList(input: MatchListInput(
            clips: clips,
            currentUserId: rally.auth.currentUserId(),
            sharerByVideoId: sharerByVideoId,
            metadataByVideoId: metadataByVideoId,
            scoreLogs: scoreLogs,
            localEntries: localEntries,
            cloudProgress: progressByEntryId,
            deviceRuns: deviceRuns,
            storedTrackIds: storedTrackIds,
            storedSkeletonIds: storedSkeletonIds,
            deviceClipCounts: deviceClipCounts
        ))
    }
}
