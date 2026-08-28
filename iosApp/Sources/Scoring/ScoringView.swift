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
/// tap on a label tags it.
///
/// Mirrors `ScoringScreen` element for element. Everything drawn is folded from the
/// log - the serve, the service court, which player of a pair is standing where,
/// and which end each side is on. Nothing here computes a rule.
struct ScoringView: View {
    let rally: RallyApp
    let scoreLogId: String
    var onFinished: (AttachIntent?) -> Void = { _ in }

    @Environment(\.dismiss) private var dismiss
    @State private var model: ScoringModel?
    @State private var confirming: Confirmation?
    @State private var noteOpen = false

    // Fires on the transition to un-scoreable, from either exit: the Done button
    // once the rules end it, and "Finish match" in the menu. `addVideoAsked` is
    // plain view state, scoped to this ScoringView instance's lifetime the same
    // way Android's board scopes its flag to the composition - so undo-then-
    // refinish does not re-nag, but leaving and coming back to the board (a new
    // instance, since it's pushed fresh each time) asks again.
    @State private var addVideoAsked = false
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
                SplashView()
            }
        }
        .navigationBarBackButtonHidden(true)
        .task {
            let m = ScoringModel(rally: rally, scoreLogId: scoreLogId)
            model = m
            // Kept awake for as long as the board is on screen, matching RootView's
            // existing use of the same flag.
            UIApplication.shared.isIdleTimerDisabled = true
            await m.start()
        }
        .onDisappear { UIApplication.shared.isIdleTimerDisabled = false }
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
        // matching Android's `LaunchedEffect(match.isOver, log.status)` - a board
        // opened on a match that is already un-scoreable asks immediately.
        .task(id: FinishPromptKey(isOver: match.isOver, status: log.status)) {
            guard !addVideoAsked, !log.isPlayable() else { return }
            addVideoAsked = true
            addVideoOpen = true
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
    /// choices or its own dismissal reaches it.
    ///
    /// `dismiss()` runs first, and `onFinished` is deferred a runloop tick past
    /// it rather than called inline: popping this view and pushing the match
    /// page's `onFinished` sets up both land in the same SwiftUI transaction
    /// otherwise, and NavigationStack does not reliably settle from a pop and a
    /// push landing together - confirmed on-device, where the destination came
    /// up with its content area permanently blank until this was split apart.
    private func finishBoard(_ intent: AttachIntent?) {
        guard addVideoOpen else { return }
        addVideoOpen = false
        dismiss()
        DispatchQueue.main.async { onFinished(intent) }
    }

    /// The one line the coach glances up at. An announcement outranks the running
    /// summary, because "match point" is the only thing here worth looking away
    /// from the court for.
    @ViewBuilder
    private func statusStrip(_ model: ScoringModel, _ log: ScoreLog, _ match: MatchState) -> some View {
        let announcement = Self.announcement(log, match)
        HStack {
            Button { dismiss() } label: {
                Image(systemName: "chevron.left").font(.body.weight(.semibold))
            }
            .foregroundStyle(Shuttl.text)
            .accessibilityLabel("Back")

            Spacer()
            Text(announcement ?? Self.runningSummary(log, match))
                .font(.subheadline.weight(announcement != nil ? .bold : .regular))
                .foregroundStyle(announcement != nil ? Shuttl.accent : Shuttl.textSecondary)
                .lineLimit(1)
            Spacer()

            Menu {
                Button("Reset game") { confirming = .resetGame }
                    .disabled(match.isOver)
                Button("Finish match") { confirming = .finish }
                    .disabled(match.isOver)
            } label: {
                Image(systemName: "ellipsis").font(.body.weight(.semibold))
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
        let players = side == .home ? log.homePlayers : log.awayPlayers
        let serving = match.server == side

        GeometryReader { geo in
            // Sized off the half it has to fill, because "readable at arm's length"
            // is the one hard requirement on this layout. The width factor budgets
            // for two digits at every score, so 9 and 30 are drawn the same size.
            let numeral = min(geo.size.height * 0.40, geo.size.width * 0.75)

            VStack(spacing: 6) {
                Text(players.joined(separator: " / "))
                    .font(.headline)
                    .foregroundStyle(.white)
                    .multilineTextAlignment(.center)
                    .lineLimit(2)

                if match.setup.doubles {
                    playerChips(side, players, match)
                } else {
                    // Held rather than hidden on the receiving side, so the games
                    // and the score below line up across the two halves instead of
                    // stepping down on whichever side happens to be serving.
                    servePill(match.serviceCourt).opacity(serving ? 1 : 0)
                }

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

    /// Both names of a pair, each marked with the service court it is standing in,
    /// and the one about to serve filled in. All three facts come from the fold.
    @ViewBuilder
    private func playerChips(_ side: Side, _ players: [String], _ match: MatchState) -> some View {
        let rightCourt = match.rightCourtPlayer(side: side)
        let serving = match.server == side ? match.servingPlayer : nil

        HStack(spacing: 6) {
            ForEach(Array(players.prefix(2).enumerated()), id: \.offset) { index, name in
                let member: PairPlayer = index == 0 ? .first : .second
                let court: ServiceCourt? = rightCourt.map { $0 == member ? .right : .left }
                playerChip(name: name, court: court, isServing: member == serving)
            }
        }
    }

    @ViewBuilder
    private func playerChip(name: String, court: ServiceCourt?, isServing: Bool) -> some View {
        HStack(spacing: 5) {
            if let court {
                Text(court == .right ? "R" : "L")
                    .font(.caption2.weight(.bold))
                    .foregroundStyle(isServing ? Color.black.opacity(0.45) : Color.white.opacity(0.6))
            }
            Text(name)
                .font(.subheadline)
                .lineLimit(1)
                .truncationMode(.tail)
                .foregroundStyle(isServing ? Color.black : Color.white)
        }
        .padding(.horizontal, 8)
        .padding(.vertical, 4)
        .background(isServing ? Color.white : Color.white.opacity(0.14))
        .clipShape(RoundedRectangle(cornerRadius: 6))
    }

    /// Singles has no player to mark, so the side itself carries the serve and its court.
    @ViewBuilder
    private func servePill(_ court: ServiceCourt?) -> some View {
        Text("SERVE" + (court == nil ? "" : (court == .right ? " R" : " L")))
            .font(.footnote.weight(.bold))
            .foregroundStyle(Color.black)
            .padding(.horizontal, 8)
            .padding(.vertical, 4)
            .background(Color.white)
            .clipShape(RoundedRectangle(cornerRadius: 6))
    }

    @ViewBuilder
    private func gamesWonBox(_ games: Int) -> some View {
        Text("\(games)")
            .font(.headline)
            .foregroundStyle(.white)
            .frame(width: 40, height: 34)
            .background(Color.white.opacity(0.16))
            .clipShape(RoundedRectangle(cornerRadius: 6))
    }

    /// The tag row and Undo. The row is on screen before the rally it will tag,
    /// which is the whole reason tagging costs one tap rather than a dialog.
    @ViewBuilder
    private func controlBar(_ model: ScoringModel, _ match: MatchState) -> some View {
        let point = model.pendingPoint

        VStack(alignment: .leading, spacing: 4) {
            ScrollView(.horizontal, showsIndicators: false) {
                HStack(spacing: 6) {
                    ForEach(model.labels, id: \.id) { label in
                        tagChip(
                            label: label,
                            selected: point?.tags.contains { $0.labelName == label.name } ?? false,
                            enabled: point != nil
                        ) {
                            if let ordinal = model.pendingTagOrdinal {
                                model.toggleTag(ordinal: ordinal, label: label)
                            }
                        }
                    }
                    if model.labels.isEmpty {
                        Text("No labels yet - add them on the labels screen.")
                            .font(.footnote)
                            .foregroundStyle(Shuttl.textSecondary)
                    }
                    Button("Note") { noteOpen.toggle() }
                        .disabled(point == nil)
                        .padding(.horizontal, 4)
                }
                .padding(.horizontal, 8)
            }

            if noteOpen, let point, let ordinal = model.pendingTagOrdinal {
                NoteField(
                    ordinal: ordinal,
                    original: point.comment ?? "",
                    onCommit: { model.setComment(ordinal: ordinal, text: $0) }
                )
                .padding(.horizontal, 8)
            }

            HStack(spacing: 8) {
                Button("Undo") { model.undo() }
                    .disabled(!model.canUndo)
                Text(Self.caption(match, model.pendingTagOrdinal))
                    .font(.footnote)
                    .foregroundStyle(Shuttl.textSecondary)
                    .lineLimit(1)
                Spacer()
                // Only once the match is actually over. While it is live, finishing
                // lives in the menu behind a confirm: a call to action beside a
                // board being tapped every rally is a match ended by accident.
                if match.isOver {
                    // In practice this fires only after the prompt above has
                    // already been answered once this visit - it appears the
                    // instant the match becomes un-scoreable, covering the board
                    // before Done is reachable - but it stays wired the same way
                    // Android's does, for the undo-then-refinish case the comment
                    // on `addVideoAsked` describes.
                    Button("Done") {
                        dismiss()
                        DispatchQueue.main.async { onFinished(nil) }
                    }.buttonStyle(.borderedProminent)
                }
            }
            .padding(.horizontal, 12)
            .padding(.bottom, 8)
        }
        .padding(.top, 6)
        .background(Shuttl.bg)
    }

    @ViewBuilder
    private func tagChip(
        label: AnnotationLabel,
        selected: Bool,
        enabled: Bool,
        action: @escaping () -> Void
    ) -> some View {
        let swatch = LabelColor.companion.from(key: label.colorKey)
        let container = swatch.map { Color(rgb: UInt32($0.background & 0xFFFFFF)) } ?? Shuttl.bgTertiary
        let onContainer = swatch.map { Color(rgb: UInt32($0.foreground & 0xFFFFFF)) } ?? Shuttl.text

        Button(action: action) {
            Text(selected ? "✓ \(label.name)" : label.name)
                .font(.subheadline.weight(selected ? .bold : .regular))
                // Greyed and inert until a rally exists to put it on, rather than
                // hidden: the row has to occupy its space before the point is
                // scored, or it would shove the board around every time one is.
                .foregroundStyle(enabled ? onContainer : onContainer.opacity(0.45))
                .padding(.horizontal, 12)
                .padding(.vertical, 8)
                .background(enabled ? container : container.opacity(0.30))
                .clipShape(Capsule())
        }
        .buttonStyle(.plain)
        .disabled(!enabled)
    }

    /// What the tag row is pointing at, so a tap on a chip is never a guess.
    static func caption(_ match: MatchState, _ pendingTagOrdinal: Int?) -> String {
        if match.isOver { return "Match over" }
        guard let ordinal = pendingTagOrdinal,
              ordinal >= 0, ordinal < match.points.count else { return "Tap a side to score" }
        let point = match.points[ordinal]
        return "Point \(point.ordinal + 1): \(point.scoreAfter.home)-\(point.scoreAfter.away)"
    }

    /// The announcement the point just played earned, or nil. Order is by how much
    /// it matters courtside, and match point outranks game point because it is one.
    static func announcement(_ log: ScoreLog, _ match: MatchState) -> String? {
        func name(_ side: Side) -> String {
            (side == .home ? log.homePlayers : log.awayPlayers).joined(separator: " / ")
        }
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

/// The note on one rally, committed once when the field goes away rather than per
/// keystroke. `tagPoint` appends and undo drops the last entry, so typing straight
/// through would make undo take a note back a letter at a time instead of taking
/// back the rally.
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
