import SwiftUI
import Shared

/// The list of matches, now the drawer's content rather than the app's front
/// door. Home owns every navigation destination this list used to register
/// (including `CreateFlowDestination` - see `HomeView.swift`'s own copy of its
/// doc comment) and hands this view closures instead: `onMatchTap`,
/// `onCourtMarking` and `onLocalPlayer` report a tap upward so Home can drive
/// its own `item:` bindings, the same shape `CreateFlowDestination` depends on.
struct MatchesList: View {
    let rally: RallyApp
    let analyze: AnalyzeCoordinator
    /// Owned by Home, not here: Home's "Add new match" sheet is what creates
    /// entries now, and `intake.error`/`intake.lastAddedId` are surfaced and
    /// consumed on Home's screen, not behind a closed drawer. This list only
    /// ever calls `remove(entry:)`, which is stateless from its point of view.
    let intake: LocalVideoIntake
    /// Owned by Home, like every destination this list used to register. It has
    /// to be one instance for the whole app rather than one per screen: its own
    /// `localEntries` is documented as "the single source of truth for every
    /// video on this phone", and a second instance would make that false while
    /// doubling four flow subscriptions and four network calls per refresh.
    /// Analytics reads the same one. This view no longer builds it, so the splash
    /// it used to show for the one frame before `.task` ran is gone with it.
    let model: ClipListModel
    let onMatchTap: (MatchRoute) -> Void
    let onCourtMarking: (CourtMarkingRoute) -> Void
    let onLocalPlayer: (LocalPlayerRoute) -> Void
    /// The empty state's own "Add new match": Home closes the drawer and
    /// opens its add sheet.
    let onAddMatch: () -> Void

    @State private var shareTarget: MatchSummary? = nil
    @State private var confirmTarget: PendingMatchAction? = nil
    @State private var thumbnails = LocalThumbnails()
    @State private var progressById: [String: AnalyzeProgress] = [:]
    @State private var resultEntry: LocalVideoEntry? = nil
    @State private var detailsTarget: MatchDetailsTarget? = nil
    @State private var deleteScoreTarget: ScoreMatchCard? = nil

    var body: some View {
        content(model)
        .task { await model.start() }
        .task {
            // Drives only the auto-alert side effect. `model.localEntries` (fed by
            // its own subscription to this same flow) is the list's source of
            // truth for rendering, so this loop must not keep a second copy that
            // could momentarily disagree with it.
            for await entries in rally.localVideos.entries {
                if resultEntry == nil {
                    // A video claimed by a match is that match's row, not a
                    // standalone one - see `standalone` in `content(_:)`.
                    resultEntry = entries.filter { $0.scoreLogId == nil }
                        .first { $0.stage == .failed && !$0.resultSeen }
                }
            }
        }
        .task {
            for await map in analyze.progress {
                progressById = map
            }
        }
        .sheet(item: $shareTarget) { match in
            ShareSheetView(rally: rally, videoId: match.videoId)
        }
        .sheet(item: $detailsTarget) { target in
            MatchDetailsSheet(
                entry: target.entry,
                autoOpened: target.autoOpened,
                onSave: { title, description in
                    rally.localVideos.setDetails(
                        id: target.entry.id,
                        title: LocalVideoDetailsKt.normalizeTitle(raw: title),
                        description: LocalVideoDetailsKt.normalizeDescription(raw: description)
                    )
                }
            )
        }
        .alert(
            (resultEntry?.failureMessage ?? "").localizedCaseInsensitiveContains("no rallies")
                ? "No rallies found" : "Analysis failed",
            isPresented: Binding(
                get: { resultEntry != nil },
                set: { if !$0 { resultEntry = nil } }
            ),
            presenting: resultEntry
        ) { entry in
            Button("Retry") {
                rally.localVideos.acknowledgeResult(id: entry.id)
                resultEntry = nil
                analyzeAction(entry)
            }
            Button("Close", role: .cancel) {
                rally.localVideos.acknowledgeResult(id: entry.id)
                resultEntry = nil
            }
        } message: { entry in
            Text(entry.failureMessage ?? "Unknown error")
        }
    }

    private func analyzeAction(_ entry: LocalVideoEntry) {
        // The shared rule, not a sixth hand-written copy. Android had four and
        // the drift had already happened: its Analytics list shipped without the
        // keypoints half and sent coaches back to re-mark a court that was
        // already saved.
        switch analyseAction(for: entry) {
        case .resume(let entryId):
            analyze.retry(entryId: entryId)
        case .markCourt(let entryId):
            onCourtMarking(CourtMarkingRoute(entryId: entryId))
        }
    }

    @ViewBuilder
    private func content(_ model: ClipListModel) -> some View {
        // A video picked for a match is represented by that match's row
        // (MatchRow.score's `video`). Listing it again here under "On this
        // phone" would be the same match twice.
        let standalone = model.localEntries.filter { $0.scoreLogId == nil }
        return List {
            if !standalone.isEmpty {
                Section {
                    ForEach(standalone) { entry in
                        LocalVideoRowView(
                            entry: entry,
                            thumbnails: thumbnails,
                            progress: progressById[entry.id],
                            onTap: { onLocalPlayer(LocalPlayerRoute(entryId: entry.id)) },
                            onAnalyze: { analyzeAction(entry) },
                            onRemove: {
                                intake.remove(entry: entry)
                                thumbnails.evict(id: entry.id)
                            },
                            onEditDetails: {
                                detailsTarget = MatchDetailsTarget(entry: entry, autoOpened: false)
                            }
                        )
                        .swipeActions(edge: .trailing, allowsFullSwipe: true) {
                            // Hidden mid-pipeline: removing would delete the file
                            // under the active upload and swallow the run's outcome.
                            if LocalVideoStatus.canRemove(stage: entry.stage) {
                                Button(role: .destructive) {
                                    intake.remove(entry: entry)
                                    thumbnails.evict(id: entry.id)
                                } label: {
                                    Label("Remove", systemImage: "trash")
                                }
                            }
                        }
                    }
                } header: { Shuttl.sectionLabel("On this phone") }
            }
            if let error = model.error {
                ErrorBanner(message: error)
                    .listRowInsets(EdgeInsets())
            }
            if !model.ownedRows.isEmpty {
                Section {
                    ForEach(model.ownedRows) { listRow in
                        switch listRow {
                        case .video(let match):
                            row(match, model: model)
                                .swipeActions(edge: .trailing, allowsFullSwipe: true) {
                                    Button {
                                        confirmTarget = PendingMatchAction(match: match, kind: .deleteMatch)
                                    } label: {
                                        Label("Delete", systemImage: "trash")
                                    }
                                    // Swipe labels render white on the tint, and white on
                                    // Shuttl.error is 3.76:1. onError is the pairing that
                                    // exists for exactly this and gives 5.08:1, so it is
                                    // stated rather than left to the system default.
                                    .foregroundStyle(Shuttl.onError)
                                    .tint(Shuttl.error)
                                }
                        case .score(let content):
                            scoreRow(content, model: model)
                                .swipeActions(edge: .trailing, allowsFullSwipe: true) {
                                    Button {
                                        // A row with clips deletes two things, not one: the
                                        // video and the score log. That case gets its own
                                        // confirmation so the wording can say so.
                                        if let video = content.video {
                                            confirmTarget = PendingMatchAction(
                                                match: video,
                                                kind: .deleteBoundMatch(scoreLogId: content.card.scoreLogId)
                                            )
                                        } else {
                                            deleteScoreTarget = content.card
                                        }
                                    } label: {
                                        Label("Delete", systemImage: "trash")
                                    }
                                    .foregroundStyle(Shuttl.onError)
                                    .tint(Shuttl.error)
                                }
                        }
                    }
                } header: { Shuttl.sectionLabel("My matches") }
            }
            if !model.shared.isEmpty {
                Section {
                    ForEach(model.shared, id: \.videoId) { match in
                        row(match, model: model)
                            .swipeActions(edge: .trailing, allowsFullSwipe: true) {
                                Button {
                                    confirmTarget = PendingMatchAction(match: match, kind: .leaveShare)
                                } label: {
                                    Label("Remove", systemImage: "trash")
                                }
                                .foregroundStyle(Shuttl.onError)
                                .tint(Shuttl.error)
                            }
                    }
                } header: { Shuttl.sectionLabel("Shared with me") }
            }
        }
        .listStyle(.plain)
        // Over the list rather than in it: a row cannot centre itself in the
        // panel, and the list underneath keeps `.refreshable` working.
        .overlay {
            if standalone.isEmpty && model.ownedRows.isEmpty && model.shared.isEmpty && !model.isRefreshing {
                // The control itself, not directions to it: this list sits
                // behind the drawer, and "tap Add new match on Home" sent a
                // first-time user back out to find a button they had not
                // seen yet. Mirrors ClipListScreen.kt's own copy.
                ShuttlEmptyState(
                    systemImage: "trophy",
                    title: "No matches yet",
                    message: "Record, import or score a match and it will show up here."
                ) {
                    Button(action: onAddMatch) {
                        HStack(spacing: 8) {
                            Image(systemName: "plus")
                            Text("Add new match")
                        }
                    }
                    .buttonStyle(CompactPillButtonStyle())
                    .accessibilityLabel("Add new match")
                }
            }
        }
        .confirmationDialog(
            "Delete this match and every point you scored? This can't be undone.",
            isPresented: Binding(
                get: { deleteScoreTarget != nil },
                set: { if !$0 { deleteScoreTarget = nil } }
            ),
            titleVisibility: .visible
        ) {
            Button("Delete", role: .destructive) {
                if let card = deleteScoreTarget {
                    deleteScoreTarget = nil
                    Task { await model.deleteScoreMatch(scoreLogId: card.scoreLogId) }
                }
            }
            Button("Cancel", role: .cancel) { deleteScoreTarget = nil }
        }
        .confirmationDialog(
            confirmTarget?.prompt ?? "",
            isPresented: Binding(
                get: { confirmTarget != nil },
                set: { if !$0 { confirmTarget = nil } }
            ),
            titleVisibility: .visible,
            presenting: confirmTarget
        ) { pending in
            Button(pending.confirmLabel, role: .destructive) {
                // Read the target before clearing it: the Task outlives the binding.
                let videoId = pending.match.videoId
                let kind = pending.kind
                confirmTarget = nil
                Task {
                    switch kind {
                    case .deleteMatch: await model.deleteMatch(videoId: videoId)
                    case .leaveShare: await model.leaveShare(videoId: videoId)
                    case .deleteBoundMatch(let scoreLogId):
                        // Score log first, and only go on to the video if that
                        // succeeded on the server: deleteMatch's refresh() syncs
                        // score logs, and if that sync landed while the score
                        // log's own delete was still on the wire, it would pull
                        // the row back from the server and resurrect the match
                        // the user just deleted. On failure this stops here -
                        // the score log is already gone from this phone
                        // (deleteScoreMatch's error message says so), and the
                        // video and its clips are left untouched rather than
                        // deleted with no way for the coach to see whether the
                        // rest of it landed. Must match Android's
                        // ClipListViewModel.deleteBoundMatch.
                        if await model.deleteScoreMatch(scoreLogId: scoreLogId, hasVideo: true) {
                            await model.deleteMatch(videoId: videoId)
                        }
                    }
                }
            }
            Button("Cancel", role: .cancel) { confirmTarget = nil }
        }
        .refreshable { await model.refresh() }
    }

    private func row(_ match: MatchSummary, model: ClipListModel) -> some View {
        Button {
            onMatchTap(MatchRoute(scoreLogId: nil, videoId: match.videoId))
        } label: {
            HStack(spacing: 12) {
                AsyncImage(url: model.thumbnailUrls[match.coverClipId]) { image in
                    image.resizable().aspectRatio(contentMode: .fill)
                } placeholder: {
                    Shuttl.bgTertiary
                }
                .frame(width: 96, height: 54)
                .clipped()
                .task { await model.thumbnail(forCoverOf: match) }

                VStack(alignment: .leading, spacing: 4) {
                    // 2, not 1: the drawer is 330pt wide, narrower than the
                    // full-screen list this row used to sit in, and the mock's
                    // own "My matches" row (unlike "On this phone"'s explicit
                    // nowrap+ellipsis) has no truncation styling - a title
                    // that has room to wrap should, not clip.
                    Text(matchRowPrimary(match))
                        .shuttlType(ShuttlType.titleMedium)
                        .foregroundStyle(Shuttl.text)
                        .lineLimit(2)
                    Text(matchRowSecondary(match))
                        .shuttlType(ShuttlType.labelSmall)
                        .foregroundStyle(Shuttl.textSecondary)
                    if let description = match.description {
                        Text(description)
                            .shuttlType(ShuttlType.bodySmall)
                            .foregroundStyle(Shuttl.textSecondary)
                            .lineLimit(2)
                    }
                    if let sharer = match.sharerEmail {
                        Text("Shared by \(sharer)")
                            .shuttlType(ShuttlType.bodySmall)
                            .foregroundStyle(Shuttl.textSecondary)
                            .lineLimit(1)
                    }
                }
                Spacer()
                if match.isOwned {
                    Button {
                        shareTarget = match
                    } label: {
                        Image(systemName: "square.and.arrow.up")
                    }
                    // Same 44x44 as the score row's trailing controls: the two row
                    // kinds sit adjacent in one list, so their trailing footprints
                    // must match or the boundary between them reads as a seam.
                    .frame(width: 44, height: 44)
                    .buttonStyle(.borderless)
                }
                // NavigationLink drew this for free; a Button does not, so it is
                // restored explicitly to keep the row reading as navigable.
                Image(systemName: "chevron.right")
                    .shuttlType(ShuttlType.bodySmall)
                    .foregroundStyle(Shuttl.textSecondary)
            }
            // Without this, the Button's hit area is only its children's -
            // the Spacer in the middle has none of its own - so the empty
            // stretch between the text and the trailing control would go dead
            // and silently stop opening the match. NavigationLink gave the
            // whole row a hit area for free; a Button does not.
            .contentShape(Rectangle())
        }
        .buttonStyle(.plain)
    }

    /// A match scored courtside, with whatever video it has acquired. Same 96x54
    /// leading slot, same 12pt spacing and the same three text lines as
    /// `row(_:model:)`: a row a few points shorter than its neighbour reads as a bug.
    @ViewBuilder
    private func scoreRow(_ content: ScoreRowContent, model: ClipListModel) -> some View {
        let card = content.card
        // The local entry behind a court-marking / retry action, while it still
        // exists. Looked up rather than carried on ScoreRowContent because it is a
        // UI-only need - the merge itself only cares about the attach status text.
        let entry = model.localEntries.first { $0.scoreLogId == card.scoreLogId }

        Button {
            onMatchTap(MatchRoute(scoreLogId: card.scoreLogId, videoId: card.videoId))
        } label: {
            HStack(spacing: 12) {
                Group {
                    if let video = content.video, let url = model.thumbnailUrls[video.coverClipId] {
                        AsyncImage(url: url) { image in
                            image.resizable().aspectRatio(contentMode: .fill)
                        } placeholder: {
                            Shuttl.bgTertiary
                        }
                    } else {
                        ZStack {
                            Shuttl.bgTertiary
                            Image(systemName: "list.number")
                                .foregroundStyle(Shuttl.textSecondary)
                        }
                    }
                }
                .frame(width: 96, height: 54)
                .clipped()
                // Keyed on the video id, not a plain `.task`: a row visible while
                // its clips are still arriving must re-fetch once `content.video`
                // goes from nil to non-nil, not just once on first appearance.
                .task(id: content.video?.videoId) {
                    if let video = content.video { await model.thumbnail(forCoverOf: video) }
                }

                VStack(alignment: .leading, spacing: 4) {
                    // Same reasoning as row(_:model:) above: the drawer is
                    // narrower than the full-screen list this row came from,
                    // and the mock's "My matches" title has no truncation
                    // styling, so a wrappable title should wrap rather than
                    // clip. A single unbroken word longer than the row (e.g.
                    // "LabelScopeCheck") still truncates - no line-break
                    // opportunity exists for it at any width, mock included.
                    Text(card.title)
                        .shuttlType(ShuttlType.titleMedium)
                        .foregroundStyle(Shuttl.text)
                        .lineLimit(2)
                    Text("\(card.scoreLine.uppercased()) · \(formatMatchDate(millis: card.createdAtEpochMs).uppercased())")
                        .shuttlType(ShuttlType.labelSmall)
                        .foregroundStyle(Shuttl.textSecondary)
                    Text(card.playersLine)
                        .shuttlType(ShuttlType.bodySmall)
                        .foregroundStyle(Shuttl.textSecondary)
                        .lineLimit(1)
                    if let attach = content.attach {
                        Text(attach.text)
                            .shuttlType(ShuttlType.bodySmall)
                            // Shuttl.error rather than a literal red: it is the
                            // same token Android reaches for (colorScheme.error)
                            // for this line.
                            .foregroundStyle(attach.kind == .failed ? Shuttl.error : Shuttl.textSecondary)
                            .lineLimit(2)
                    }
                }
                Spacer()
                switch content.attach?.kind {
                case .courtNotMarked:
                    // Padding/background live inside the label, not chained onto
                    // the Button, so the tappable area is exactly the visible
                    // pill - matching `chip` in PlaybackControlBar.swift. This row
                    // is a Button's label; a dead zone here would silently
                    // open the match instead of marking the court.
                    Button {
                        if let entry { onCourtMarking(CourtMarkingRoute(entryId: entry.id)) }
                    } label: {
                        Text("Mark court")
                            .shuttlType(ShuttlType.labelMedium)
                            .foregroundStyle(Shuttl.onAccent)
                            .lineLimit(1)
                            .fixedSize(horizontal: true, vertical: false)
                            .padding(.horizontal, 12)
                            .padding(.vertical, 6)
                            .background(Shuttl.accent)
                            .clipShape(Capsule())
                    }
                    .buttonStyle(.borderless)
                case .failed:
                    Button {
                        if let entry { analyze.retry(entryId: entry.id) }
                    } label: {
                        Text("Retry")
                            .shuttlType(ShuttlType.labelMedium)
                            .foregroundStyle(Shuttl.onAccent)
                            .lineLimit(1)
                            .fixedSize(horizontal: true, vertical: false)
                            .padding(.horizontal, 12)
                            .padding(.vertical, 6)
                            .background(Shuttl.accent)
                            .clipShape(Capsule())
                    }
                    .buttonStyle(.borderless)
                case .uploading, .clipping, .finishingUp:
                    // Boxed to the same 44x44 footprint as the share button
                    // below, so the trailing slot doesn't shift width the
                    // moment the pipeline finishes and the row flips to nil.
                    ProgressView()
                        .controlSize(.small)
                        .frame(width: 44, height: 44)
                case nil:
                    // Present but visually muted, with the reason in its
                    // accessibility label: match_shares is keyed on video_id, so
                    // a score-only match cannot be shared. Deliberately not
                    // `.disabled(...)`: verified on-device (task-7 fix round) that
                    // a `.disabled` Button nested inside this row's own Button
                    // does not swallow its tap - it passes straight through to
                    // the row underneath, silently opening the match instead of
                    // doing nothing. Almost certainly pre-existing, not something
                    // this row's NavigationLink -> Button conversion introduced: a
                    // disabled child button falls through the same way inside a
                    // NavigationLink's label. The action already no-ops with no
                    // video, so leaving the button enabled and dimming it by hand
                    // keeps the tap right where it lands - the formal "not
                    // enabled" trait `.disabled` would have given for free is
                    // restored via `.accessibilityRepresentation` below, which
                    // swaps in a `.disabled` proxy Button for what the
                    // accessibility tree exposes only. That proxy has no effect
                    // on hit testing, so the real Button above still swallows the
                    // tap right where it lands instead of falling through.
                    // (SwiftUI's `AccessibilityTraits` has no public "not
                    // enabled" member to add directly - `.disabled` is the only
                    // way to produce that trait, hence the proxy.)
                    Button {
                        if let video = content.video { shareTarget = video }
                    } label: {
                        Image(systemName: "square.and.arrow.up")
                            .opacity(content.video != nil ? 1 : 0.35)
                    }
                    .frame(width: 44, height: 44)
                    .buttonStyle(.borderless)
                    .accessibilityRepresentation {
                        Button(
                            content.video != nil ? "Share match" : "Add a video to share this match"
                        ) {
                            if let video = content.video { shareTarget = video }
                        }
                        .disabled(content.video == nil)
                    }
                }
                // NavigationLink drew this for free; a Button does not, so it is
                // restored explicitly to keep the row reading as navigable.
                Image(systemName: "chevron.right")
                    .shuttlType(ShuttlType.bodySmall)
                    .foregroundStyle(Shuttl.textSecondary)
            }
            // Without this, the Button's hit area is only its children's -
            // the Spacer in the middle has none of its own - so the empty
            // stretch between the text and the trailing control would go dead
            // and silently stop opening the match. NavigationLink gave the
            // whole row a hit area for free; a Button does not.
            .contentShape(Rectangle())
        }
        .buttonStyle(.plain)
    }
}

/// `autoOpened` switches the sheet's dismiss label between "Skip" and "Cancel".
struct MatchDetailsTarget: Identifiable {
    let entry: LocalVideoEntry
    let autoOpened: Bool
    var id: String { entry.id }
}

extension MatchSummary: Identifiable {
    var id: String { videoId }
}

/// A destructive match action awaiting confirmation. Both kinds share one
/// dialog; the swipe only records the target, it never acts on its own.
private struct PendingMatchAction {
    enum Kind {
        case deleteMatch
        case leaveShare
        /// A scored match that already has clips: deleting it removes the video
        /// AND the score log, so the wording below must say both.
        case deleteBoundMatch(scoreLogId: String)
    }

    let match: MatchSummary
    let kind: Kind

    var prompt: String {
        switch kind {
        case .deleteMatch:
            return "Delete this match and all its rally clips? This can't be undone."
        case .leaveShare:
            return "Remove this shared match from your list? You'll need the owner to share it again."
        case .deleteBoundMatch:
            // Mirrors Android's ClipListScreen.kt wording exactly: a bound
            // match's delete removes two things at once, the clips and the
            // scored points, and neither of the wordings above says both.
            return "Delete this match, every point you scored and all its rally clips? This can't be undone."
        }
    }

    var confirmLabel: String {
        switch kind {
        case .deleteMatch, .deleteBoundMatch: return "Delete"
        case .leaveShare: return "Remove"
        }
    }
}
