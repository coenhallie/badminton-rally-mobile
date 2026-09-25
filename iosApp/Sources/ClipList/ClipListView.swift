import SwiftUI
import Shared

/// The list of matches, now the drawer's content rather than the app's front
/// door: one row per match, wherever its video is, with "Shared with me" below.
///
/// Every row comes from the shared builder (`ClipListModel.matchList`); this
/// view only draws them and turns their taps, menus and swipes into model
/// calls and the closures below. Mirrors androidApp's ClipListScreen.kt.
///
/// Home owns every navigation destination this list used to register
/// (including `CreateFlowDestination` - see `HomeView.swift`'s own copy of its
/// doc comment) and hands this view closures instead: `onMatchTap`,
/// `onCourtMarking` and `onLocalPlayer` report a tap upward so Home can drive
/// its own `item:` bindings, the same shape `CreateFlowDestination` depends on.
struct MatchesList: View {
    let rally: RallyApp
    let analyze: AnalyzeCoordinator
    /// Owned by Home, not here: Home's "Add new match" sheet is what creates
    /// entries now, and `intake.error`/`intake.lastAddedId` are surfaced and
    /// consumed on Home's screen, not behind a closed drawer.
    let intake: LocalVideoIntake
    /// Owned by Home, like every destination this list used to register. It has
    /// to be one instance for the whole app rather than one per screen: its own
    /// `localEntries` is documented as "the single source of truth for every
    /// video on this phone", and a second instance would make that false while
    /// doubling four flow subscriptions and four network calls per refresh.
    /// Analytics reads the same one.
    let model: ClipListModel
    let onMatchTap: (MatchRoute) -> Void
    let onCourtMarking: (CourtMarkingRoute) -> Void
    let onLocalPlayer: (LocalPlayerRoute) -> Void
    /// The two destinations an on-device run's own output lives at, reported
    /// upward like every other one this list carries. Home owns them.
    let onLocalClips: (LocalClipsRoute) -> Void
    let onAnalyticsDetail: (AnalyticsDetailRoute) -> Void
    /// The empty state's own "Add new match": Home closes the drawer and
    /// opens its add sheet.
    let onAddMatch: () -> Void

    @State private var shareTarget: ShareTarget? = nil
    @State private var thumbnails = LocalThumbnails()
    @State private var resultEntry: LocalVideoEntry? = nil
    @State private var detailsTarget: MatchDetailsTarget? = nil
    @State private var legendOpen = false
    /// The chosen filter. What the list applies is `effectiveFilter` of it, so
    /// a filter whose chips are hidden never filters anything.
    @State private var filter: MatchListFilter = .all
    // A row the coach asked to delete or remove, waiting on a confirmation:
    // the two-way choice when a swipe finds both a file here and a match to
    // delete, the single dialog otherwise.
    @State private var choiceTarget: MatchListItem? = nil
    @State private var deleteTarget: MatchListItem? = nil
    /// The video the coach has asked to remove, held until they confirm: a
    /// destructive action with a consequence worth stating needs a beat to
    /// read it.
    @State private var removeTarget: MatchListItem? = nil

    var body: some View {
        // Split into stages rather than one chain: the list's modifier chain is
        // at the type-checker's limit, and one more modifier tips it into
        // "unable to type-check this expression in reasonable time".
        dialogs(listBody)
    }

    private var listBody: some View {
        content
        .task { await model.start() }
        .task {
            // Drives only the auto-alert side effect. `model.localEntries` (fed by
            // its own subscription to this same flow) is the list's source of
            // truth for rendering, so this loop must not keep a second copy that
            // could momentarily disagree with it.
            for await entries in rally.localVideos.entries {
                if resultEntry == nil {
                    // A video claimed by a match fails on its match's row, which
                    // says so in its own failure line and offers Retry.
                    resultEntry = entries.filter { $0.scoreLogId == nil }
                        .first { $0.stage == .failed && !$0.resultSeen }
                }
            }
        }
        .sheet(item: $shareTarget) { target in
            ShareSheetView(rally: rally, videoId: target.videoId)
        }
        .sheet(isPresented: $legendOpen) { MatchLegendSheet() }
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
                analyzeAction(entryId: entry.id)
            }
            Button("Close", role: .cancel) {
                rally.localVideos.acknowledgeResult(id: entry.id)
                resultEntry = nil
            }
        } message: { entry in
            Text(entry.failureMessage ?? "Unknown error")
        }
        .refreshable { await model.refresh() }
    }

    private var content: some View {
        let mine = model.matchList.mine
        let shared = model.matchList.shared
        let showChips = MatchListFilterKt.showsFilters(mineCount: Int32(mine.count))
        let applied = MatchListFilterKt.effectiveFilter(chosen: filter, mineCount: Int32(mine.count))
        let visible = mine.filter { $0.passes(filter: applied) }
        return List {
            // Rows, not `Section(header:)`: a plain list pins a header to the
            // top and scrolls the rows under it, which put a blurred card
            // behind the label. Ordinary rows scroll with the content, and the
            // list opens at the top.
            MatchFilterRow(
                showChips: showChips,
                selected: applied,
                onSelect: { filter = $0 },
                onLegend: { legendOpen = true }
            )
            .listRowInsets(EdgeInsets(
                top: DrawerList.listTopInset, leading: DrawerList.sideMargin,
                bottom: DrawerList.rowGap, trailing: MatchFilterRow.trailingInset
            ))
            .listRowBackground(Color.clear)
            .listRowSeparator(.hidden)
            if let error = model.error {
                ErrorBanner(message: error)
                    .drawerListRow()
            }
            ForEach(visible, id: \.key) { item in
                row(item)
            }
            if visible.isEmpty && !mine.isEmpty {
                Text(applied == .onThisPhone
                     ? MatchListCopy.shared.EMPTY_ON_THIS_PHONE
                     : MatchListCopy.shared.EMPTY_NOT_ANALYZED)
                    .shuttlType(ShuttlType.bodySmall)
                    .foregroundStyle(Shuttl.textSecondary)
                    .padding(.vertical, 12)
                    .frame(maxWidth: .infinity, alignment: .leading)
                    .drawerListRow()
            }
            if !shared.isEmpty {
                DrawerSectionLabel(MatchListCopy.shared.SHARED_SECTION)
                    .drawerListRow(top: DrawerList.sectionGap)
                // Never filtered: the chips are about the coach's own matches,
                // and a shared one is neither on this phone nor waiting on this
                // coach to analyze it.
                ForEach(shared, id: \.key) { item in
                    row(item)
                }
            }
        }
        .listStyle(.plain)
        // The drawer's panel is what should show between the inset rows, not
        // the material `List` paints over it.
        .scrollContentBackground(.hidden)
        .listSectionSpacing(0)
        // A `List` floors every row at ~44pt. A 12pt section label is well under
        // that, so the floor was padding it out and putting 29pt between a label
        // and its first card where the mock has 10.
        .environment(\.defaultMinListRowHeight, 0)
        // Review focus 5: a filter whose chips have gone would go on hiding
        // matches with nothing on screen to undo it. Reset on the chips going
        // away, not on their being absent: `onChange` does not fire for the
        // first value, so the empty list of the first frame cannot wipe a
        // chosen filter. `effectiveFilter` above already stops it applying
        // while the chips are hidden; this makes the choice itself forget.
        .onChange(of: showChips) { wasShown, isShown in
            if wasShown && !isShown { filter = .all }
        }
        // Over the list rather than in it: a row cannot centre itself in the
        // panel, and the list underneath keeps `.refreshable` working.
        .overlay {
            if mine.isEmpty && shared.isEmpty && !model.isRefreshing {
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
    }

    private func row(_ item: MatchListItem) -> some View {
        MatchListRowView(
            item: item,
            thumbnails: thumbnails,
            media: rally.media,
            refreshGeneration: model.refreshGeneration,
            menu: menu(for: item),
            onTap: { open(item) },
            onAction: { act(item) }
        )
        .drawerListRow()
        .swipeActions(edge: .trailing, allowsFullSwipe: true) {
            if let swipe = RowSwipe(item) {
                Button {
                    switch swipe {
                    case .choose: choiceTarget = item
                    case .delete, .leave: deleteTarget = item
                    case .remove: removeTarget = item
                    }
                } label: {
                    Label(swipe.label, systemImage: "trash")
                }
                // Swipe labels render white on the tint, and white on
                // Shuttl.error is 3.76:1. onError is the pairing that exists
                // for exactly this and gives 5.08:1.
                .foregroundStyle(Shuttl.onError)
                .tint(Shuttl.error)
            }
        }
    }

    // MARK: - What a row does

    private func open(_ item: MatchListItem) {
        if item.opensLocalPlayer, let entryId = item.entryId {
            onLocalPlayer(LocalPlayerRoute(entryId: entryId))
        } else if item.scoreLogId != nil || item.videoId != nil {
            onMatchTap(MatchRoute(scoreLogId: item.scoreLogId, videoId: item.videoId))
        }
    }

    /// Every action goes by the row's own entry, never by looking it up again
    /// from the match: the builder may have joined the entry to its log by the
    /// log's video id, which a scoreLogId lookup would miss, leaving a button
    /// that does nothing under a status that says why it is there. Retry is
    /// Analyze: the resume-or-mark-court decision is the same one either way.
    private func act(_ item: MatchListItem) {
        guard let entryId = item.entryId else { return }
        switch item.action {
        case .analyze, .retry: analyzeAction(entryId: entryId)
        case .markCourt: onCourtMarking(CourtMarkingRoute(entryId: entryId))
        case .none, .running, .paused: break
        }
    }

    private func analyzeAction(entryId: String) {
        // Read from the store, not the list's cache: the decision turns on the
        // entry's saved court points and failed step as they are now.
        guard let entry = rally.localVideos.get(id: entryId) else { return }
        // The shared rule, not a hand-written copy. Android had four and the
        // drift had already happened: its Analytics list shipped without the
        // keypoints half and sent coaches back to re-mark a court that was
        // already saved.
        switch analyseAction(for: entry) {
        case .resume(let id): analyze.retry(entryId: id)
        case .markCourt(let id): onCourtMarking(CourtMarkingRoute(entryId: id))
        }
    }

    /// The same items in the same order as Android's row menu.
    private func menu(for item: MatchListItem) -> [RowMenuItem] {
        var items: [RowMenuItem] = []
        let copy = MatchListCopy.shared
        if item.canShare, let videoId = item.videoId {
            items.append(RowMenuItem(label: copy.MENU_SHARE) { shareTarget = ShareTarget(videoId: videoId) })
        }
        if item.deviceClipCount > 0, let entryId = item.entryId {
            items.append(RowMenuItem(label: copy.menuDeviceClips(count: item.deviceClipCount)) {
                onLocalClips(LocalClipsRoute(entryId: entryId))
            })
        }
        if let heatmapId = item.heatmapId {
            items.append(RowMenuItem(label: copy.MENU_HEATMAP) {
                onAnalyticsDetail(AnalyticsDetailRoute(entryId: heatmapId, title: item.title))
            })
        }
        if item.canEditDetails, let entry = model.localEntries.first(where: { $0.id == item.entryId }) {
            items.append(RowMenuItem(label: copy.MENU_EDIT_DETAILS) {
                detailsTarget = MatchDetailsTarget(entry: entry, autoOpened: false)
            })
        }
        if item.canAnalyzeAgain, let entryId = item.entryId {
            items.append(RowMenuItem(label: copy.MENU_ANALYZE_AGAIN) { analyzeAction(entryId: entryId) })
        }
        if item.canRemoveFromPhone {
            items.append(RowMenuItem(label: copy.MENU_REMOVE_FROM_PHONE, role: .destructive) { removeTarget = item })
        }
        // Straight to the one confirmation, not the choice: removing the file
        // alone is already its own item just above.
        switch item.delete {
        case .none: break
        case .leaveShare:
            items.append(RowMenuItem(label: copy.MENU_LEAVE_SHARE, role: .destructive) { deleteTarget = item })
        case .videoMatch, .scoreMatch, .boundMatch:
            items.append(RowMenuItem(label: copy.MENU_DELETE, role: .destructive) { deleteTarget = item })
        }
        return items
    }

    private func deleteEverywhere(_ item: MatchListItem) async {
        switch DeleteEverywhere(item) {
        case .videoMatch(let videoId, let entryId):
            await model.deleteMatch(videoId: videoId, entryId: entryId)
        case .scoreMatch(let scoreLogId, let entryId):
            await model.deleteScoreMatch(scoreLogId: scoreLogId, entryId: entryId)
        case .boundMatch(let videoId, let scoreLogId, let entryId):
            await model.deleteBoundMatch(videoId: videoId, scoreLogId: scoreLogId, entryId: entryId)
        case .leaveShare(let videoId):
            await model.leaveShare(videoId: videoId)
        case nil:
            break
        }
    }

    private func removeFromPhone(_ item: MatchListItem) {
        guard let entryId = item.entryId else { return }
        model.removeFromPhone(entryId: entryId)
        thumbnails.evict(id: entryId)
    }
}

// MARK: - Confirmations

private extension MatchesList {
    func dialogs(_ list: some View) -> some View {
        list
        // A swipe on a row with both a file here and a match to delete: two
        // different things, so the coach picks. The one that cannot lose data
        // is listed first.
        .confirmationDialog(
            choiceTarget?.title ?? "",
            isPresented: presence($choiceTarget),
            titleVisibility: .visible,
            presenting: choiceTarget
        ) { item in
            Button(MatchListCopy.shared.CHOICE_REMOVE) {
                choiceTarget = nil
                removeFromPhone(item)
            }
            Button(MatchListCopy.shared.CHOICE_DELETE, role: .destructive) {
                choiceTarget = nil
                Task { await deleteEverywhere(item) }
            }
            Button(MatchListCopy.shared.CHOICE_CANCEL, role: .cancel) { choiceTarget = nil }
        } message: { item in
            // Both options' details, not only the delete's: the dialog has one
            // message and no line under each button, and "Remove video from
            // this phone" needs its own consequences said as much as the delete.
            Text(MatchListCopy.shared.choiceMessage(item: item))
        }
        // The single confirmation, titled and worded as Android's dialog: the
        // message is shared copy, which also says when the video in this app
        // goes with the match (a delete is only offered on such a row once the
        // file is free to discard).
        .confirmationDialog(
            deleteTarget.flatMap { MatchListCopy.shared.deleteTitle(delete: $0.delete) } ?? "",
            isPresented: presence($deleteTarget),
            titleVisibility: .visible,
            presenting: deleteTarget
        ) { item in
            Button(MatchListCopy.shared.deleteConfirmLabel(delete: item.delete) ?? MatchListCopy.shared.CONFIRM_DELETE,
                   role: .destructive) {
                deleteTarget = nil
                Task { await deleteEverywhere(item) }
            }
            Button(MatchListCopy.shared.CHOICE_CANCEL, role: .cancel) { deleteTarget = nil }
        } message: { item in
            Text(MatchListCopy.shared.deleteMessage(delete: item.delete, onThisPhone: item.entryId != nil) ?? "")
        }
        .alert(
            MatchListCopy.shared.REMOVE_VIDEO_TITLE,
            isPresented: presence($removeTarget),
            presenting: removeTarget
        ) { item in
            Button(MatchListCopy.shared.CONFIRM_REMOVE, role: .destructive) {
                removeTarget = nil
                removeFromPhone(item)
            }
            Button(MatchListCopy.shared.CHOICE_CANCEL, role: .cancel) { removeTarget = nil }
        } message: { item in
            // Per row: what goes and what stays depends on whether the match
            // has a cloud copy, which is the only thing that lists a kept
            // track again once the file is gone.
            Text(MatchListCopy.shared.removeFromPhoneDetail(item: item))
        }
    }

    /// A dialog's `isPresented`, driven by whether its target is set.
    func presence<T>(_ target: Binding<T?>) -> Binding<Bool> {
        Binding(get: { target.wrappedValue != nil }, set: { if !$0 { target.wrappedValue = nil } })
    }
}

/// What a swipe on a row does, and what the revealed label says. Nil for a row
/// nothing may be deleted from: a run in flight holds its file, and deleting
/// the match would delete it too.
///
/// Internal, not private: `RowSwipeTests` drives it directly, the same way a
/// swipe reveals the label and picks the confirmation this row's `act` runs.
enum RowSwipe {
    /// Both a file here and a match to delete: the two-way choice.
    case choose
    case delete
    case leave
    /// Only the file: the "Remove video?" dialog.
    case remove

    init?(_ item: MatchListItem) {
        switch item.delete {
        case .leaveShare: self = .leave
        case .none: if item.canRemoveFromPhone { self = .remove } else { return nil }
        case .videoMatch, .scoreMatch, .boundMatch: self = item.canRemoveFromPhone ? .choose : .delete
        }
    }

    var label: String {
        switch self {
        case .choose, .delete: return MatchListCopy.shared.SWIPE_DELETE
        case .leave, .remove: return MatchListCopy.shared.SWIPE_REMOVE
        }
    }
}

/// The model call a row's delete everywhere makes, with the ids it passes.
/// Always the row's own entry, never one looked up again from the match: the
/// builder may have joined the entry to its log by the log's video id, which a
/// scoreLogId lookup would miss, and the file would survive as a phone-only
/// row of the match just deleted. Internal so `RowSwipeTests` can hold it to
/// that. Mirrors Android's `deleteEverywhereFor`.
enum DeleteEverywhere: Equatable {
    case videoMatch(videoId: String, entryId: String?)
    case scoreMatch(scoreLogId: String, entryId: String?)
    case boundMatch(videoId: String, scoreLogId: String, entryId: String?)
    case leaveShare(videoId: String)

    init?(_ item: MatchListItem) {
        switch item.delete {
        case .videoMatch:
            guard let videoId = item.videoId else { return nil }
            self = .videoMatch(videoId: videoId, entryId: item.entryId)
        case .scoreMatch:
            guard let scoreLogId = item.scoreLogId else { return nil }
            self = .scoreMatch(scoreLogId: scoreLogId, entryId: item.entryId)
        case .boundMatch:
            guard let videoId = item.videoId, let scoreLogId = item.scoreLogId else { return nil }
            self = .boundMatch(videoId: videoId, scoreLogId: scoreLogId, entryId: item.entryId)
        case .leaveShare:
            guard let videoId = item.videoId else { return nil }
            self = .leaveShare(videoId: videoId)
        case .none:
            return nil
        }
    }
}

/// A match to share, by its cloud video.
private struct ShareTarget: Identifiable {
    let videoId: String
    var id: String { videoId }
}

/// `autoOpened` switches the sheet's dismiss label between "Skip" and "Cancel".
struct MatchDetailsTarget: Identifiable {
    let entry: LocalVideoEntry
    let autoOpened: Bool
    var id: String { entry.id }
}
