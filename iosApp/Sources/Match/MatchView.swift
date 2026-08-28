import SwiftUI
import Shared

/// A route to one match, however it was made: a video id, a score log id, or
/// both once a scored match has acquired a video. Replaces both `String` (a
/// video's id) and `ScoreMatchRoute` as the value pushed for a match row.
struct MatchRoute: Hashable {
    let scoreLogId: String?
    let videoId: String?
}

/// Which half of a match page is on screen. Only meaningful when the match has
/// both. Port of Android's private `Facet` enum in `MatchScreen.kt`.
private enum Facet {
    case points
    case rallies
}

/// `navigationDestination(item:)` needs an Identifiable, and the summary sheet's
/// "most labelled" row pushes a clip programmatically rather than through a
/// NavigationLink.
private struct ClipRoute: Identifiable, Hashable {
    let id: String
}

/// The two sheets this page can show, as one `.sheet(item:)` rather than two
/// stacked `.sheet(isPresented:)` modifiers - stacking two on the same view is a
/// known SwiftUI footgun where presentation can silently fail for one of them.
/// No associated data: the content closure reads `summary`/`effectiveVideoId`
/// from the enclosing view's state directly, so a summary that updates while its
/// sheet is open is still reflected live, the same as before this type existed.
private enum MatchSheet: Identifiable, Hashable {
    case share
    case summary
    var id: Self { self }
}

/// One match, however it was made: a video-first or shared match shows only the
/// rallies facet, a scored match with no video shows only the points facet, and a
/// match with both gets a selector between them. Replaces `MatchClipsView` and
/// `ScoreMatchView`.
struct MatchView: View {
    let rally: RallyApp
    let route: MatchRoute

    @State private var matchModel: MatchModel
    // Fetched here rather than handed down: the list navigates by video id alone,
    // and a soft-failing read is cheaper than threading a summary through the route.
    @State private var allClips: [RallyClip] = []
    @State private var sort: ClipSort = .rallyOrder
    @State private var metadata: MatchMetadata? = nil
    @State private var summary: MatchLabelSummary? = nil
    @State private var isLoadingSummary = false
    // A superseded fetch must not write its result: the awaited Kotlin bridge does
    // not observe Swift task cancellation, so a replaced .task keeps running to
    // completion. The generation it captured tells it to drop what it got.
    @State private var summaryGeneration = 0
    @State private var clipRoute: ClipRoute? = nil
    // Set while the summary sheet is closing; the push happens in the sheet's
    // onDismiss rather than inline with dismiss(), because a navigationDestination
    // on the view that is presenting a sheet can drop a push made mid-dismissal.
    @State private var pendingTopRallyClipId: String? = nil
    @State private var activeSheet: MatchSheet? = nil
    // Held for the life of this view, not re-keyed on hasPoints/hasRallies: the
    // clip list briefly empties during a refresh, and re-keying on that would
    // silently snap a reader on the rallies facet back to Points mid-read.
    @State private var chosenFacet: Facet

    init(rally: RallyApp, route: MatchRoute) {
        self.rally = rally
        self.route = route
        let model = MatchModel(rally: rally, scoreLogId: route.scoreLogId)
        _matchModel = State(initialValue: model)
        _chosenFacet = State(initialValue: model.log != nil ? .points : .rallies)
    }

    // The score log is authoritative: a match bound after this page was opened
    // must start showing its rallies without a re-entry.
    private var effectiveVideoId: String? { route.videoId ?? matchModel.log?.videoId }

    private var clipsForMatch: [RallyClip] {
        sort.sorted(allClips.filter { $0.videoId == effectiveVideoId })
    }

    // Shown only when the match has both. A selector over one facet is a control
    // that does nothing, and a match that has only rallies must look exactly like
    // it did before this page existed.
    private var hasPoints: Bool { matchModel.log != nil }
    private var hasRallies: Bool { !clipsForMatch.isEmpty }

    // What actually renders: the user's choice when it is still available, else
    // whichever facet the match currently has.
    private var facet: Facet {
        if chosenFacet == .points && hasPoints { return .points }
        if chosenFacet == .rallies && hasRallies { return .rallies }
        if hasPoints { return .points }
        return .rallies
    }

    // Reuses `MatchGrouping.matches` rather than re-deriving ownership from a clip
    // lookup here: that function is what `ClipListView`'s own share button already
    // trusts, and two independent answers to "is this mine" that can disagree is a
    // defect regardless of which one happens to be right at a given moment.
    private var isOwned: Bool {
        guard let effectiveVideoId else { return false }
        let (owned, _) = MatchGrouping.matches(
            from: allClips.map(ClipInfo.init),
            currentUserId: rally.auth.currentUserId(),
            sharerByVideoId: [:],
            metadataByVideoId: [:]
        )
        return owned.contains { $0.videoId == effectiveVideoId }
    }

    private var matchName: String? {
        // videos.title is authoritative; the clip-stamped copy keeps the name on
        // screen when the RPC is unreachable.
        metadata?.title ?? matchTitle(of: clipsForMatch.map(ClipInfo.init))
    }

    // Precedence matches Android's `MatchScreen`: a resolved video match owns its
    // title first, then a score log's title, then the bare "RALLIES" fallback -
    // never the other order. A match the coach named on the phone and then
    // attached a video to must keep showing that name while the video's clips and
    // metadata are still loading, not flash "RALLIES" in between.
    private var title: String {
        if let name = matchName { return name.uppercased() }
        if let latest = clipsForMatch.map({ $0.createdAt.toEpochMilliseconds() }).max() {
            return "MATCH · \(formatMatchDate(millis: latest).uppercased())"
        }
        // Score-only, or a video match whose clips/metadata have not loaded yet:
        // same title as the screen this page replaced.
        if let log = matchModel.log { return log.title }
        return "RALLIES"
    }

    // The score log this page was opened for is gone - deleted elsewhere while it
    // was open, or never synced to this device. A video-first match with no log at
    // all is not this state: `hasPoints` is false for it and it falls straight
    // through to the rallies facet, same as before this page existed.
    private var isMissing: Bool {
        route.scoreLogId != nil && matchModel.log == nil && effectiveVideoId == nil
    }

    /// `supersede` is for the triggers that must never be swallowed: a changed clip
    /// set and an explicit pull to refresh. Only `.onAppear` defers to a fetch that
    /// is already running, matching `MatchClipsView`'s original contract.
    private func refreshSummary(supersede: Bool = false) async {
        let target = clipsForMatch
        guard !target.isEmpty else {
            summary = nil
            return
        }
        guard supersede || !isLoadingSummary else { return }

        summaryGeneration += 1
        let generation = summaryGeneration
        isLoadingSummary = true
        defer { if generation == summaryGeneration { isLoadingSummary = false } }

        // Soft failure: keep whatever is on screen and say nothing.
        guard let rows = try? await SwiftInteropKt.listForClipsOrNull(
            rally.annotations, clipIds: target.map(\.id)
        ) else { return }
        guard generation == summaryGeneration else { return }
        // Built from the clips this fetch was made for, never from a newer list.
        summary = MatchLabelSummaryKt.buildMatchLabelSummary(clips: target, annotations: rows)
    }

    var body: some View {
        Group {
            if isMissing {
                VStack {
                    Text("This match is no longer on this phone.")
                        .foregroundStyle(Shuttl.textSecondary)
                }
                .frame(maxWidth: .infinity, maxHeight: .infinity)
            } else {
                VStack(spacing: 0) {
                    if hasPoints && hasRallies {
                        // Reads the derived facet, writes the chosen one: the
                        // control shows what is actually on screen, but a later
                        // fallback (rallies emptying mid-refresh) must not overwrite
                        // what the user picked - see `chosenFacet`'s own comment.
                        Picker("Facet", selection: Binding(get: { facet }, set: { chosenFacet = $0 })) {
                            Text("Points").tag(Facet.points)
                            Text("Rallies").tag(Facet.rallies)
                        }
                        .pickerStyle(.segmented)
                        .padding(.horizontal)
                        .padding(.vertical, 8)
                    }
                    listContent
                }
            }
        }
        .navigationTitle(title)
        .navigationBarTitleDisplayMode(.inline)
        .toolbar { toolbarContent }
        // A summary that goes away while its sheet is open must close the sheet,
        // not leave it standing over data that no longer exists.
        .onChange(of: summary?.isEmpty) { _, isEmpty in
            if isEmpty != false, activeSheet == .summary { activeSheet = nil }
        }
        .sheet(item: $activeSheet, onDismiss: {
            guard let clipId = pendingTopRallyClipId else { return }
            pendingTopRallyClipId = nil
            clipRoute = ClipRoute(id: clipId)
        }) { sheet in
            switch sheet {
            case .share:
                if let videoId = effectiveVideoId {
                    ShareSheetView(rally: rally, videoId: videoId)
                }
            case .summary:
                if let summary {
                    MatchSummarySheet(
                        summary: summary,
                        topRallyName: summary.topRally.map {
                            topRallyName(
                                clipId: $0.clipId, rallyIndex: $0.rallyIndex,
                                clips: clipsForMatch.map(ClipInfo.init), matchTitle: matchName
                            )
                        },
                        onTopRally: {
                            // Parity with Android, which looks the clip up and
                            // no-ops when it is gone rather than pushing a detail
                            // view for a clip that no longer exists.
                            if let top = summary.topRally,
                               clipsForMatch.contains(where: { $0.id == top.clipId }) {
                                pendingTopRallyClipId = top.clipId
                            }
                        }
                    )
                    .presentationDetents([.medium, .large])
                }
            }
        }
        .navigationDestination(item: $clipRoute) { route in
            ClipDetailView(rally: rally, clipId: route.id)
        }
        .task { await matchModel.start() }
        // Soft failure: no metadata just means the date headline, as before. Keyed
        // on the effective video id, not a bare `.task`: a score-only match that
        // acquires a video after this page opens must pick up its metadata without
        // a re-entry, the same reason `effectiveVideoId` itself is derived.
        .task(id: effectiveVideoId) {
            guard let effectiveVideoId else {
                metadata = nil
                return
            }
            let rows = try? await SwiftInteropKt.listMatchMetadataOrNull(rally.videos)
            metadata = rows?.first { $0.videoId == effectiveVideoId }
        }
        // A Set, not the sorted array: changing the sort order reorders
        // `clipsForMatch` without changing which clips are in it, and that must
        // not look like a changed clip set to this trigger.
        .task(id: Set(clipsForMatch.map(\.id))) { await refreshSummary(supersede: true) }
        // Pushing ClipDetailView does not remove this view, so its .task is not
        // restarted on the way back, and adding a note does not change clipIds.
        // Without this the strip would go stale exactly where it matters most.
        .onAppear { Task { await refreshSummary() } }
        .task {
            for await latest in rally.clips.observeClips() {
                allClips = latest
            }
        }
    }

    // A pull gesture that spins and refreshes nothing is worse than no gesture: a
    // score-only match has no video, so there is nothing here for a refresh to
    // touch. Only a video-backed match keeps the old `MatchClipsView`'s
    // pull-to-refresh, including when both facets exist and Points is selected.
    @ViewBuilder
    private var listContent: some View {
        if effectiveVideoId != nil {
            listBody.refreshable {
                try? await rally.clips.refresh()
                await refreshSummary(supersede: true)
            }
        } else {
            listBody
        }
    }

    private var listBody: some View {
        List {
            if facet == .points, let log = matchModel.log {
                PointsFacet(
                    log: log, card: matchModel.card, tally: matchModel.tally,
                    points: matchModel.match?.points ?? []
                )
            } else {
                RalliesFacet(
                    rally: rally,
                    clips: clipsForMatch,
                    summary: summary,
                    matchTitle: matchName,
                    description: metadata?.description_,
                    onSummaryClick: { activeSheet = .summary }
                )
            }
        }
        .listStyle(.plain)
    }

    @ToolbarContentBuilder
    private var toolbarContent: some ToolbarContent {
        // The sort order only means something while rallies are on screen.
        if facet == .rallies {
            ToolbarItem(placement: .topBarTrailing) {
                Menu {
                    Picker("Sort", selection: $sort) {
                        Text("Rally order").tag(ClipSort.rallyOrder)
                        Text("Most notes").tag(ClipSort.mostNotes)
                    }
                } label: {
                    Image(systemName: "arrow.up.arrow.down")
                }
            }
        }
        if isOwned, effectiveVideoId != nil {
            ToolbarItem(placement: .topBarTrailing) {
                Button {
                    activeSheet = .share
                } label: {
                    Image(systemName: "square.and.arrow.up")
                }
                .accessibilityLabel("Share match")
            }
        }
        // A distinct glyph from the button above: a match with both a video and a
        // log shows both at once, and two identical share icons side by side would
        // be indistinguishable, especially under VoiceOver.
        if let log = matchModel.log {
            ToolbarItem(placement: .topBarTrailing) {
                ShareLink(item: ScoreTagSummaryKt.exportMatchText(log: log)) {
                    Image(systemName: "doc.text")
                }
                .accessibilityLabel("Export as text")
            }
        }
    }
}
