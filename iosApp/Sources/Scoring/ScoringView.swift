import SwiftUI
import Shared

struct ScoringRoute: Hashable, Identifiable {
    let scoreLogId: String
    var id: String { scoreLogId }
}

/// Where finishing a match can take the coach next, chosen on the "Add the
/// video?" prompt. Mirrors Android's `AttachIntent`; `Hashable` because
/// `MatchRoute` carries one and `MatchRoute` itself is `Hashable`.
enum AttachIntent: Hashable {
    case importVideo
    case record
}

/// The courtside board. One tap on a side scores the rally it just won; a second
/// tap on a label tags it. Before the first rally, or whenever else the row has no
/// rally to tag, a label tap logs a moment instead.
///
/// Mirrors `ScoringScreen` element for element. Everything drawn is folded from the
/// log - the serve, the service court, which player of a pair is standing where,
/// and which end each side is on. Nothing here computes a rule.
struct ScoringView: View {
    let rally: RallyApp
    let scoreLogId: String
    /// Whether the match page this board is scoring for is already sitting on
    /// the stack directly underneath it - true when pushed from that page's own
    /// Score/Resume button, false when pushed straight from creating a new
    /// match (nothing underneath but the list yet). Not inferred from the
    /// navigation route, or from whether a match page for this id happens to
    /// exist somewhere: the caller states it, because it is the one thing that
    /// decides what `onFinished` is allowed to do - see the call sites.
    let matchPageAlreadyOpen: Bool
    let onFinished: (AttachIntent?) -> Void

    @Environment(\.dismiss) private var dismiss
    /// Optional so a board built outside `RootView` - a preview, a test - draws
    /// rather than trapping on a missing environment object.
    @Environment(KeepAwake.self) private var keepAwake: KeepAwake?
    /// This board, as `KeepAwake` knows it. See there for why an id.
    @State private var boardId = UUID()
    @State private var model: ScoringModel?
    @State private var confirming: Confirmation?
    @State private var noteOpen = false
    @State private var editingDetails = false
    @State private var editingScore = false
    /// The chip a quick moment was just logged from, while its flash is up.
    @State private var flashing: String?
    @Environment(\.dynamicTypeSize) private var typeSize
    @Environment(\.accessibilityReduceMotion) private var reduceMotion

    // Fires on the transition to un-scoreable, from either exit: the last point
    // the rules allow, and "Finish match" in the menu. The rule is shared with
    // Android; see `FinishPrompt` for why a board opened on a match that is
    // ALREADY over must not ask. View state, so one per visit to the board; nil
    // until the first evaluation, which is when the log is first in hand.
    @State private var finishPrompt: FinishPrompt?
    @State private var addVideoOpen = false

    var body: some View {
        Group {
            if let model {
                if let log = model.log, let match = model.match {
                    board(model, log, match)
                } else {
                    // Swiped away in the match list while this screen was open.
                    VStack(spacing: 12) {
                        Text("This match is no longer on this phone.")
                            .foregroundStyle(Shuttl.textSecondary)
                        Button("Back") { dismiss() }
                    }
                }
            } else {
                LoadingView()
            }
        }
        .navigationBarBackButtonHidden(true)
        .task {
            // Built once and kept. This `.task` runs again on every return to
            // the board, and a fresh model each time threw away the open tag row
            // (`pendingTagOrdinal` lives on the model) along with the old one.
            let m = model ?? ScoringModel(rally: rally, scoreLogId: scoreLogId)
            model = m
            await m.start()
        }
        // Kept awake for as long as the board is on screen - by saying so to
        // `KeepAwake`, not by writing `isIdleTimerDisabled`. This view used to
        // write it, and clearing it on the way out turned the lock back on under
        // any upload or analysis still running. `RootView` is the one writer.
        .onAppear { keepAwake?.boardAppeared(boardId) }
        .onDisappear { keepAwake?.boardDisappeared(boardId) }
    }

    @ViewBuilder
    private func board(_ model: ScoringModel, _ log: ScoreLog, _ match: MatchState) -> some View {
        // The end each side is playing from, not which side it is. The board turns
        // over with the players so it still matches the court being watched.
        let left: Side = match.endsSwapCount % 2 == 0 ? .home : .away

        VStack(spacing: 0) {
            statusStrip(model, log, match)

            HStack(spacing: 0) {
                sideZone(left, log, match, model)
                sideZone(left == .home ? .away : .home, log, match, model)
            }

            controlBar(model, match)
        }
        // Keyed on both, not on `model.canScore` alone: an early "Finish match"
        // from the menu can leave `match.isOver` false (the rules never ended it)
        // while `log.status` moves off LIVE, and that exit must raise the prompt
        // too. `.task(id:)` runs on first appearance as well as on a changed key,
        // matching Android's `LaunchedEffect(match.isOver, log.status)`, and that
        // first run is what tells `FinishPrompt` how the board was found.
        .task(id: FinishPromptKey(isOver: match.isOver, status: log.status)) {
            let prompt = finishPrompt ?? FinishPrompt(playableOnEntry: log.isPlayable())
            finishPrompt = prompt
            if prompt.shouldAsk(isPlayable: log.isPlayable()) { addVideoOpen = true }
        }
        // Keyed on the notice rather than on its words, so a second notice with the
        // same words - a second undo of the same side's rally, a second moment
        // with the same label - still gets its full time to be reversed. Cancelled,
        // and so never dismissing anything, when its action, the next notice or
        // the next rally replaces it.
        .task(id: model.notice) {
            guard model.notice != nil else { return }
            if let message = model.noticeMessage {
                UIAccessibility.post(notification: .announcement, argument: message)
            }
            try? await Task.sleep(for: Self.noticeDuration)
            if !Task.isCancelled { model.dismissNotice() }
        }
        // A rally note belongs to a rally. When the row stops pointing at one, the
        // field closes rather than reopening on the next rally scored.
        .onChange(of: model.target is BoardTarget.Moments) { _, moments in
            if moments { noteOpen = false }
        }
        .sheet(isPresented: $editingScore) {
            ScoreEditSheet(log: log, match: match) { home, away, server in
                model.editScore(home: home, away: away, nextServer: server)
            }
        }
        .sheet(isPresented: $editingDetails) {
            EditMatchDetailsSheet(initial: MatchDetailsDraft(log: log), titleCap: Int(ScoreLogKt.MAX_MATCH_TITLE)) { draft in
                model.updateDetails(
                    title: draft.title, format: draft.format,
                    homePlayers: draft.homePlayers, awayPlayers: draft.awayPlayers
                )
            }
        }
        .confirmationDialog(
            confirming?.title ?? "",
            isPresented: Binding(get: { confirming != nil }, set: { if !$0 { confirming = nil } }),
            titleVisibility: .visible
        ) {
            if let pending = confirming {
                Button(pending.confirmLabel, role: .destructive) {
                    switch pending {
                    case .resetGame: model.resetCurrentGame()
                    case .finish: model.finish()
                    }
                    confirming = nil
                }
            }
            Button("Cancel", role: .cancel) { confirming = nil }
        } message: {
            Text(confirming?.body ?? "")
        }
        .confirmationDialog(
            "Add the video?",
            // Any way the dialog closes - a button, or a swipe/tap outside it -
            // must end in exactly one call: `finishBoard` guards on `addVideoOpen`
            // still being true, so the tap that already handled it (via a button's
            // own action) makes the system's own dismissal a no-op.
            isPresented: Binding(get: { addVideoOpen }, set: { if !$0 { finishBoard(nil) } }),
            titleVisibility: .visible
        ) {
            Button("Import video") { finishBoard(.importVideo) }
            Button("Record") { finishBoard(.record) }
            Button("Not now", role: .cancel) { finishBoard(nil) }
        } message: {
            Text(
                "Import or record the video of this match and Shuttl will cut it into " +
                "one clip per rally. You can also do this later from the match itself."
            )
        }
    }

    /// The single exit from the "Add the video?" prompt, whichever of its three
    /// choices or its own dismissal reaches it - and the "Done" button below,
    /// which reaches the same hand-off through `deliverFinish` directly.
    private func finishBoard(_ intent: AttachIntent?) {
        guard addVideoOpen else { return }
        addVideoOpen = false
        deliverFinish(intent)
    }

    /// What happens with the chosen intent (or nil) differs by
    /// `matchPageAlreadyOpen`, and the two branches are not interchangeable -
    /// each is shaped around which SwiftUI transition its pusher actually
    /// settles:
    ///
    /// - `true` (a match page is already on the stack below this board):
    ///   `dismiss()` runs first and `onFinished` is deferred a runloop tick
    ///   past it, because a pop and a state change landing in the same
    ///   transaction do not reliably settle - confirmed on-device, where the
    ///   match page came up with its content area permanently blank until
    ///   this was split apart. The deferral lets this view's own pop finish
    ///   committing before the match page underneath mutates its state in
    ///   response.
    /// - `false` (this board was pushed straight from creating a match,
    ///   nothing underneath it yet): no `dismiss()`, and no defer. This board
    ///   and the match page it hands off to are two cases of the SAME
    ///   `item:`-bound destination on `ClipListView` (`CreateFlowDestination`)
    ///   - `onFinished` reassigns that one binding to the "finished" case,
    ///   which is the transition SwiftUI reliably replaces a destination with
    ///   in a single step. Popping this view first and pushing a *different*
    ///   binding a tick later - the same shape as the `true` branch - was
    ///   tried here too and produced the identical permanently-blank freeze,
    ///   which is what led to folding both stages into one binding instead of
    ///   chasing a longer deferral. See `CreateFlowDestination`'s own doc
    ///   comment and task-13-report.md's create-and-finish investigation.
    private func deliverFinish(_ intent: AttachIntent?) {
        if matchPageAlreadyOpen {
            dismiss()
            DispatchQueue.main.async { onFinished(intent) }
        } else {
            onFinished(intent)
        }
    }

    /// How long a notice and its action stay up: "Point to Home undone" with Redo,
    /// and "Moment added" or "Moment removed" with Undo. Long enough to notice a
    /// mis-tap from the court and reach back for it, short enough that the strip
    /// is back to calling game point before the next rally is over.
    static let noticeDuration: Duration = .seconds(5)

    /// The one line the coach glances up at. An announcement outranks the running
    /// summary, because "match point" is the only thing here worth looking away
    /// from the court for.
    ///
    /// A notice outranks both for a few seconds: an undo, or a moment added or
    /// removed. Each is something the coach did that the board cannot show on its
    /// own - the number going down does not say whose rally went, and a moment
    /// changes nothing else on the board - and it is where the action that
    /// reverses it lives, Redo or Undo, clear of the halves that score.
    @ViewBuilder
    private func statusStrip(_ model: ScoringModel, _ log: ScoreLog, _ match: MatchState) -> some View {
        let notice = model.noticeMessage
        let announcement = notice ?? Self.announcement(log, match)
        HStack {
            Button { dismiss() } label: {
                Image(systemName: "chevron.left").shuttlType(ShuttlType.titleLarge)
            }
            .foregroundStyle(Shuttl.text)
            .accessibilityLabel("Back")

            Spacer()
            Text(announcement ?? Self.runningSummary(log, match))
                // One role for both states, not a weight toggle: this line
                // reflows every rally an announcement appears or clears, and
                // a size change here (which a per-state role would need,
                // since the scale has no single size at two weights) would
                // make it visibly shift at arm's length. The colour switch
                // below already carries the emphasis.
                .shuttlType(ShuttlType.titleMedium)
                .foregroundStyle(announcement != nil ? Shuttl.accent : Shuttl.textSecondary)
                .lineLimit(1)
            Spacer()

            if let action = model.noticeAction {
                Button(action) { model.reverseNotice() }
                    .shuttlType(ShuttlType.titleMedium)
                    .foregroundStyle(Shuttl.accent)
                    .padding(.trailing, 8)
            }

            Menu {
                // Here rather than on the bar since the score editor took its
                // place: the editor only reaches the game being played, and a
                // mis-tap that ends a game has already moved the board on to
                // the next one. Its notice and Redo still come up in the strip.
                Button("Undo last rally") { model.undo() }
                    .disabled(!model.canUndo)
                // Only when there is one. Beside Undo last rally rather than
                // replacing it: after Reset game the row logs moments while
                // earlier games still have rallies to take back.
                if model.canRemoveMoment {
                    Button("Remove last moment") { model.removeLastMoment() }
                }
                // Names and format are not asked for when the match is created, so
                // courtside, between games, is one of the places they get filled
                // in. Never disabled: it does not touch the score.
                Button("Edit details") { editingDetails = true }
                Button("Reset game") { confirming = .resetGame }
                    .disabled(match.isOver)
                Button("Finish match") { confirming = .finish }
                    .disabled(match.isOver)
            } label: {
                Image(systemName: "ellipsis").shuttlType(ShuttlType.titleLarge)
            }
            .foregroundStyle(Shuttl.text)
            .accessibilityLabel("Match options")
        }
        .padding(.horizontal, 12)
        .frame(height: 48)
        .background(Shuttl.bg)
    }

    /// One half of the board, and the whole scoring control: the tap target is the
    /// half itself rather than a button on it, because a button courtside is a
    /// thing to aim at.
    @ViewBuilder
    private func sideZone(
        _ side: Side,
        _ log: ScoreLog,
        _ match: MatchState,
        _ model: ScoringModel
    ) -> some View {
        let serving = match.server == side

        GeometryReader { geo in
            // Sized off the half it has to fill, because "readable at arm's length"
            // is the one hard requirement on this layout. The width factor budgets
            // for two digits at every score, so 9 and 30 are drawn the same size.
            let numeral = min(geo.size.height * 0.40, geo.size.width * 0.75)

            VStack(spacing: 6) {
                // The side's players, or "Home" / "Away" while nobody has been named.
                Text(log.sideName(side: side))
                    .shuttlType(ShuttlType.titleLarge)
                    .foregroundStyle(.white)
                    .multilineTextAlignment(.center)
                    .lineLimit(2)

                // Held rather than hidden on the receiving side, so the games and
                // the score below line up across the two halves instead of stepping
                // down on whichever side happens to be serving. Only the visible
                // pill switches the serve: the hidden one must not take taps.
                servePill(
                    match.serviceCourt,
                    onSwitch: serving && model.canSwitchFirstServer ? { model.switchFirstServer() } : nil
                )
                .opacity(serving ? 1 : 0)

                if match.rules.gamesToWin > 1 {
                    gamesWonBox(Int(match.gamesWon.of(side: side)))
                }

                // The score takes whatever the names left, centred in it. Anchoring
                // the names to the top instead of centring the whole stack keeps the
                // numeral in the same place all match, however long the names are.
                Spacer(minLength: 0)
                Text("\(match.currentGame.of(side: side))")
                    // A fixed size rather than a scaled one: the half is measured in
                    // points and does not grow with the reader's text setting, so a
                    // dynamic-type numeral would overflow what it was sized to fit.
                    .font(.system(size: numeral, weight: .bold))
                    .foregroundStyle(.white)
                    .lineLimit(1)
                Spacer(minLength: 0)
            }
            .frame(width: geo.size.width, height: geo.size.height)
            .padding(.horizontal, 8)
            .padding(.vertical, 10)
        }
        .background(side == .home ? Shuttl.sideHome : Shuttl.sideAway)
        .contentShape(Rectangle())
        .onTapGesture {
            guard model.canScore else { return }
            UIImpactFeedbackGenerator(style: .medium).impactOccurred()
            model.score(side)
        }
    }

    /// The side itself carries the serve and its court, there being no player to mark.
    ///
    /// `onSwitch` is non-nil only until the first point is scored. The form does not
    /// ask who won the toss, so a new match opens with home serving, and this is
    /// where that is corrected: the pill grows a swap icon and hands the serve across
    /// when tapped. It sits inside the half that scores on a tap, so its own tap is
    /// claimed here or switching the serve would also score a point.
    @ViewBuilder
    private func servePill(_ court: ServiceCourt?, onSwitch: (() -> Void)?) -> some View {
        let label = HStack(spacing: 5) {
            if onSwitch != nil {
                Image(systemName: "arrow.left.arrow.right")
                    .shuttlType(ShuttlType.labelSmall)
                    // No height of its own: the symbol measures a point taller than
                    // the text, and the pill on the other half has no symbol, so the
                    // games boxes below would sit at different heights.
                    .frame(height: 0)
            }
            Text("SERVE" + (court == nil ? "" : (court == .right ? " R" : " L")))
                .shuttlType(ShuttlType.labelMedium)
        }
        .foregroundStyle(Color.black)
        .padding(.horizontal, 8)
        .padding(.vertical, 4)
        .background(Color.white)
        .clipShape(RoundedRectangle(cornerRadius: ShuttlRadius.extraSmall))
        // A child's tap wins over the half's own, which is what keeps this from
        // scoring a rally.
        label
            .gesture(onSwitch.map { action in TapGesture().onEnded { action() } })
            .accessibilityElement(children: .combine)
            .accessibilityAddTraits(onSwitch == nil ? [] : .isButton)
            .accessibilityHint(onSwitch == nil ? "" : "Switches who serves first")
    }

    @ViewBuilder
    private func gamesWonBox(_ games: Int) -> some View {
        Text("\(games)")
            // Not the scoreboard exemption: this is a fixed 17pt digit in a
            // fixed 40x34 box, not a size derived from the half it fills.
            .shuttlType(ShuttlType.titleLarge)
            .foregroundStyle(.white)
            .frame(width: 40, height: 34)
            .background(Color.white.opacity(0.16))
            .clipShape(RoundedRectangle(cornerRadius: ShuttlRadius.extraSmall))
    }

    /// The tag row and Edit score. The row is on screen before the rally it will tag,
    /// which is the whole reason tagging costs one tap rather than a dialog.
    ///
    /// With no rally to tag, the same chips log moments: one tap logs one, and Note
    /// opens a composer whose chips check labels onto a single moment instead.
    @ViewBuilder
    private func controlBar(_ model: ScoringModel, _ match: MatchState) -> some View {
        let target = model.target
        // Only while the row targets moments, as the caption reads it. Should a
        // sync turn the row onto a rally under an open composer, its field leaves
        // the view and saves the moment rather than hanging over the rally.
        let draft = target is BoardTarget.Moments ? model.momentDraft : nil
        let point = model.pendingPoint
        let quick = target is BoardTarget.Moments && draft == nil

        VStack(alignment: .leading, spacing: 4) {
            if model.labels.isEmpty {
                Text(
                    model.hasAnyLabels
                        ? "No board labels - choose them on the labels screen."
                        : "No labels yet - add them on the labels screen."
                )
                .shuttlType(ShuttlType.bodySmall)
                .foregroundStyle(Shuttl.textSecondary)
                .padding(.horizontal, 8)
            } else {
                // Wraps rather than scrolls. Only board-scoped labels reach
                // here, so the set is small by construction - and when one more
                // than fits is scoped, the cost is a line of board height that
                // is visible, not a chip hidden off the right edge.
                ChipFlow(spacing: 6) {
                    ForEach(model.labels, id: \.id) { label in
                        // Nil for a quick moment: that tap is a one-shot action
                        // with nothing to tick, not a toggle that is off.
                        let selected: Bool? = if let draft {
                            draft.tags.contains { $0.labelName == label.name }
                        } else if let point {
                            point.tags.contains { $0.labelName == label.name }
                        } else {
                            nil
                        }
                        tagChip(
                            label: label,
                            selected: selected,
                            hint: point != nil
                                ? (selected == true ? "Removes the tag" : "Tags this rally")
                                : draft != nil
                                    ? (selected == true ? "Removes it from this moment" : "Adds it to this moment")
                                    : "Logs a moment",
                            flashes: quick
                        ) {
                            if let ordinal = model.pendingTagOrdinal {
                                model.toggleTag(ordinal: ordinal, label: label)
                            } else {
                                // A quick moment changes nothing else on the board,
                                // so it is felt as well as seen.
                                if quick { UIImpactFeedbackGenerator(style: .light).impactOccurred() }
                                model.tapMomentLabel(label)
                            }
                        }
                    }
                }
                .padding(.horizontal, 8)
            }

            if let draft {
                MomentNoteField(
                    onChange: { model.setMomentDraftText($0) },
                    onClose: { model.commitMomentDraft() }
                )
                // A fresh field per moment, so the next composer opens empty.
                .id(draft.openedAt.toEpochMilliseconds())
                .padding(.horizontal, 8)
            } else if noteOpen, let point, let ordinal = model.pendingTagOrdinal {
                NoteField(
                    ordinal: ordinal,
                    original: point.comment ?? "",
                    onCommit: { model.setComment(ordinal: ordinal, text: $0) }
                )
                .padding(.horizontal, 8)
            }

            HStack(spacing: 8) {
                // Outlined rather than plain text like Note beside it: this is the
                // control reached for in a hurry after a mis-tap or a run of
                // unscored rallies, so it has to read as a button at a glance.
                Button { editingScore = true } label: {
                    // Not a `Label`: its icon gap is wider than this pill wants.
                    HStack(spacing: 6) {
                        Image(systemName: "pencil")
                        // The pencil alone at the accessibility sizes, where the
                        // words truncated to "✎ …", which says nothing at all.
                        // By size rather than ViewThatFits: inside this row that
                        // measured against an even share and dropped the words
                        // at sizes where they fit.
                        if !typeSize.isAccessibilitySize {
                            Text("Edit score").lineLimit(1)
                        }
                    }
                }
                .accessibilityLabel("Edit score")
                .buttonStyle(OutlinedPillButtonStyle())
                // Offered its width before the caption, or the row's even split
                // leaves it the pencil alone at sizes where the words fit.
                .layoutPriority(1)
                .disabled(!model.canEditScore)
                Text(model.caption)
                    .shuttlType(ShuttlType.bodySmall)
                    .foregroundStyle(Shuttl.textSecondary)
                    // Two lines: the opening hint does not fit beside the Edit
                    // score pill on a narrow phone, and cut short it loses "server".
                    .lineLimit(2)
                Spacer()
                // Outside the wrapping area on purpose: it used to be the last
                // chip in a horizontally scrolling row, which is exactly why it
                // became unreachable once a few labels existed.
                // The rally's note while the row tags a rally, otherwise the moment
                // composer: opened here, and saved here as well as by the field.
                Button("Note") {
                    if target is BoardTarget.Rally {
                        noteOpen.toggle()
                    } else if model.momentDraft == nil {
                        model.openMomentDraft()
                    } else {
                        model.commitMomentDraft()
                    }
                }
                    // Accent like Edit score beside it, and like Android's Note, rather
                    // than the system blue it fell back to.
                    .tint(Shuttl.accent)
                // Only once the match is actually over. While it is live, finishing
                // lives in the menu behind a confirm: a call to action beside a
                // board being tapped every rally is a match ended by accident.
                if match.isOver {
                    // Reachable two ways: on a board opened on a match that was
                    // already won, which `FinishPrompt` does not ask about, and
                    // after undo-then-refinish, which it does not ask about twice.
                    Button(action: { deliverFinish(nil) }) {
                        Text("Done")
                            .shuttlType(ShuttlType.titleLarge)
                            .foregroundStyle(Shuttl.onAccent)
                            .padding(.horizontal, 12)
                            .padding(.vertical, 6)
                            .background(Shuttl.accent)
                            .clipShape(Capsule())
                    }
                    .buttonStyle(.borderless)
                }
            }
            .padding(.horizontal, 12)
            .padding(.bottom, 8)
        }
        .padding(.top, 6)
        .background(Shuttl.bg)
    }

    /// One label on the board. Live whatever the row targets: it tags the rally,
    /// checks the label onto the open moment, or logs a moment of its own.
    ///
    /// `selected` is nil when the tap is not a toggle, so VoiceOver does not call a
    /// quick-moment chip unselected. A quick moment flashes, a press-in and a white
    /// wash, and neither changes the chip's size, so the row never reflows under
    /// the coach's finger. Under Reduce Motion the press-in goes; the wash and the
    /// haptic stay.
    @ViewBuilder
    private func tagChip(
        label: AnnotationLabel,
        selected: Bool?,
        hint: String,
        flashes: Bool,
        action: @escaping () -> Void
    ) -> some View {
        let swatch = LabelColor.companion.from(key: label.colorKey)
        let container = swatch.map { Color(rgb: UInt32($0.background & 0xFFFFFF)) } ?? Shuttl.bgTertiary
        let onContainer = swatch.map { Color(rgb: UInt32($0.foreground & 0xFFFFFF)) } ?? Shuttl.text
        let ticked = selected == true
        let lit = flashing == label.id

        Button {
            action()
            guard flashes else { return }
            withAnimation(.easeOut(duration: 0.08)) { flashing = label.id }
            Task { @MainActor in
                try? await Task.sleep(for: .milliseconds(90))
                withAnimation(.easeOut(duration: 0.26)) { if flashing == label.id { flashing = nil } }
            }
        } label: {
            Text(ticked ? "✓ \(label.name)" : label.name)
                // One role for both states, not a weight toggle: the chip's
                // fixed horizontal padding would make it visibly change width
                // on every tap if the role's size changed with it. The "✓"
                // prefix already carries the selected state.
                .shuttlType(ShuttlType.titleMedium)
                .foregroundStyle(onContainer)
                .padding(.horizontal, 12)
                .padding(.vertical, 8)
                .background(container)
                .overlay(Color.white.opacity(lit ? 0.32 : 0))
                .clipShape(Capsule())
                .scaleEffect(lit && !reduceMotion ? 0.94 : 1)
        }
        .buttonStyle(.plain)
        .accessibilityHint(hint)
        .accessibilityAddTraits(ticked ? .isSelected : [])
    }

    /// The announcement the point just played earned, or nil. Order is by how much
    /// it matters courtside, and match point outranks game point because it is one.
    static func announcement(_ log: ScoreLog, _ match: MatchState) -> String? {
        func name(_ side: Side) -> String { log.sideName(side: side) }
        func both(_ flags: SideFlags, _ what: String) -> String {
            if flags.home && flags.away { return "\(what) - both" }
            return flags.home ? "\(what) - \(name(.home))" : "\(what) - \(name(.away))"
        }
        if let winner = match.winner { return "\(name(winner)) won" }
        if match.matchPoint.any { return both(match.matchPoint, "Match point") }
        if match.gamePoint.any { return both(match.gamePoint, "Game point") }
        if match.isChangeEndsPoint { return "Change ends" }
        if match.isIntervalPoint { return "Interval" }
        return nil
    }

    /// Games already finished, or the match's name while the first one is still on.
    static func runningSummary(_ log: ScoreLog, _ match: MatchState) -> String {
        if match.completedGames.isEmpty { return log.title }
        return match.completedGames.map { "\($0.home)-\($0.away)" }.joined(separator: ", ")
    }

    /// The `.task(id:)` key for the finish-prompt watcher. Both fields matter:
    /// see the watcher's own comment for why `log.status` alone or `match.isOver`
    /// alone would each miss one of the two terminal exits.
    private struct FinishPromptKey: Equatable {
        let isOver: Bool
        let status: ScoreLogStatus
    }

    enum Confirmation {
        case resetGame
        case finish

        var title: String {
            switch self {
            case .resetGame: return "Reset this game?"
            case .finish: return "Finish this match?"
            }
        }
        var body: String {
            switch self {
            case .resetGame:
                return "Every point of the game being played is removed. Games already finished are left alone."
            case .finish:
                return "The match is closed at the current score. You can still undo afterwards."
            }
        }
        var confirmLabel: String {
            switch self {
            case .resetGame: return "Reset game"
            case .finish: return "Finish match"
            }
        }
    }
}

/// The board's Edit score: accent text on the app's secondary fill and outline (the
/// outline alone all but disappears in dark), the partner
/// to the filled Done pill beside it. The visible pill is shorter than the 44pt
/// tap target it sits in, like the rest of the row.
private struct OutlinedPillButtonStyle: ButtonStyle {
    @Environment(\.isEnabled) private var isEnabled

    func makeBody(configuration: Configuration) -> some View {
        configuration.label
            .shuttlType(ShuttlType.titleMedium)
            .foregroundStyle(Shuttl.accent)
            .padding(.horizontal, 12)
            .padding(.vertical, 6)
            .background(Shuttl.bgTertiary, in: Capsule())
            .overlay(Capsule().strokeBorder(Shuttl.borderSecondary, lineWidth: 1))
            .opacity(configuration.isPressed ? 0.6 : (isEnabled ? 1 : 0.4))
            .frame(minHeight: 44)
            .contentShape(Rectangle())
    }
}

/// The composer's note. Pushes its text to the model as it is typed, so a score
/// tap can save the moment, but reads its own copy: reading the text back through
/// the model is how a cursor ends up jumping. Saved once the field goes away, for
/// whatever reason; saving is a no-op once the moment is in.
private struct MomentNoteField: View {
    let onChange: (String) -> Void
    let onClose: () -> Void

    @State private var text = ""
    @FocusState private var focused: Bool

    var body: some View {
        TextField("Note on this moment", text: $text)
            .textFieldStyle(.roundedBorder)
            .focused($focused)
            .submitLabel(.done)
            .onSubmit { onClose() }
            .onChange(of: text) { _, new in onChange(new) }
            // Focused as it opens, like the rally's note: Note was tapped to write.
            .onAppear { focused = true }
            .onDisappear { onClose() }
    }
}

/// The note on one rally, committed once when the field goes away rather than per
/// keystroke. `tagPoint` appends, so typing straight through would add one log
/// entry per character to a log that is stored and synced whole.
private struct NoteField: View {
    let ordinal: Int
    let original: String
    let onCommit: (String) -> Void

    @State private var draft: String
    @FocusState private var focused: Bool

    init(ordinal: Int, original: String, onCommit: @escaping (String) -> Void) {
        self.ordinal = ordinal
        self.original = original
        self.onCommit = onCommit
        _draft = State(initialValue: original)
    }

    var body: some View {
        TextField("Note on this rally", text: $draft)
            .textFieldStyle(.roundedBorder)
            .focused($focused)
            .id(ordinal)
            // Focused as it opens. Opening the note is already a deliberate detour
            // from scoring; making the coach tap twice to start typing is the kind
            // of thing that gets the feature abandoned.
            .onAppear { focused = true }
            .onDisappear { if draft != original { onCommit(draft) } }
    }
}
