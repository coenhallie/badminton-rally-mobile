import Shared
import SwiftUI

/// Sets the game being played to any score in one go: the board's answer to a
/// run of rallies that went unscored, or a few that went to the wrong side.
///
/// Laid out like the board it opens over - the same two colours, each side on the
/// end it is playing from - so there is no working out which number is whose.
/// Each number is a field for typing a jump and has a stepper for the usual
/// one-point fix, which needs no keyboard. The line under them says what saving
/// will do before it is done, since lowering a score takes rallies back with
/// their tags.
///
/// Mirrors Android's `ScoreEditSheet`. Every rule, and every word of that line,
/// comes from the shared `planScoreEdit`.
struct ScoreEditSheet: View {
    let log: ScoreLog
    let match: MatchState
    let onSave: (_ home: Int, _ away: Int, _ nextServer: Side?) -> Void

    @Environment(\.dismiss) private var dismiss
    @State private var homeText: String
    @State private var awayText: String
    /// Nil until the coach picks: the plan then keeps the serve where it is if it can.
    @State private var requestedServer: Side?
    @FocusState private var focused: Side?
    @State private var contentHeight: CGFloat = 0

    init(log: ScoreLog, match: MatchState, onSave: @escaping (_ home: Int, _ away: Int, _ nextServer: Side?) -> Void) {
        self.log = log
        self.match = match
        self.onSave = onSave
        _homeText = State(initialValue: "\(match.currentGame.home)")
        _awayText = State(initialValue: "\(match.currentGame.away)")
    }

    private var plan: ScoreEditPlan? {
        guard let home = Int32(homeText), let away = Int32(awayText) else { return nil }
        return log.planScoreEdit(home: home, away: away, nextServer: requestedServer)
    }

    var body: some View {
        let plan = self.plan
        let left: Side = match.endsSwapCount % 2 == 0 ? .home : .away
        let problem = plan?.problem ?? (plan == nil ? ScoreEditKt.SCORE_EDIT_BLANK : nil)

        VStack(alignment: .leading, spacing: 16) {
            Text(match.scoreEditTitle())
                .shuttlType(ShuttlType.headlineMedium)
                .foregroundStyle(Shuttl.textHeading)

            HStack(spacing: 12) {
                tile(left, plan)
                tile(left == .home ? .away : .home, plan)
            }
            // Equal heights, so a two-line pair of names on one side does not
            // leave the other side's number floating higher.
            .fixedSize(horizontal: false, vertical: true)

            Text(problem ?? plan?.summary ?? "")
                .shuttlType(ShuttlType.bodyMedium)
                .foregroundStyle(problem != nil ? Shuttl.error : Shuttl.textSecondary)
                .multilineTextAlignment(.center)
                // Two lines held whatever it says, so the buttons below stay put
                // while the numbers change under the coach's thumb.
                .lineLimit(2, reservesSpace: true)
                .frame(maxWidth: .infinity)

            HStack(spacing: 12) {
                Button("Cancel") { dismiss() }
                    .buttonStyle(SecondaryPillButtonStyle())
                Button("Save") {
                    guard let plan else { return }
                    onSave(Int(plan.home), Int(plan.away), plan.server)
                    dismiss()
                }
                .buttonStyle(PrimaryButtonStyle())
                .disabled(plan?.canSave != true)
            }
        }
        .padding(.horizontal, 20)
        .padding(.top, 24)
        .padding(.bottom, 16)
        .background {
            GeometryReader { proxy in
                Color.clear.onChange(of: proxy.size.height, initial: true) { _, height in
                    contentHeight = height
                }
            }
        }
        // The number pad has no return key of its own.
        .toolbar {
            ToolbarItemGroup(placement: .keyboard) {
                Spacer()
                Button("Done") { focused = nil }
            }
        }
        // Typing replaces the number rather than appending to it: a tap into a
        // field selects what is there, the way a score is corrected.
        .onReceive(NotificationCenter.default.publisher(for: UITextField.textDidBeginEditingNotification)) { note in
            guard let field = note.object as? UITextField else { return }
            DispatchQueue.main.async { field.selectAll(nil) }
        }
        // As tall as its content, the way PlaybackSettingsSheet sizes itself.
        .presentationDetents([.height(contentHeight)])
        .presentationDragIndicator(.visible)
        // Android's sheet colour, surfaceVariant, is this token.
        .presentationBackground(Shuttl.bgSecondary)
    }

    @ViewBuilder
    private func tile(_ side: Side, _ plan: ScoreEditPlan?) -> some View {
        let name = log.sideName(side: side)
        let text = side == .home ? $homeText : $awayText
        let value = Int(text.wrappedValue)
        let serve: ServeMark = plan?.server == side ? .serving
            : (plan?.servers.of(side: side) == true ? .available : .none)

        VStack(spacing: 12) {
            Text(name)
                .shuttlType(ShuttlType.titleLarge)
                .foregroundStyle(.white)
                .multilineTextAlignment(.center)
                .lineLimit(2)
            // Takes up whatever the names leave, so both numbers sit level.
            Spacer(minLength: 0)

            serveToggle(serve) { requestedServer = side }

            TextField("", text: Binding(
                get: { text.wrappedValue },
                set: { text.wrappedValue = String($0.filter(\.isNumber).prefix(2)) }
            ))
            .keyboardType(.numberPad)
            .multilineTextAlignment(.center)
            // The type scale's display role, bold like the board's numerals.
            // Android draws the same role, so the two sheets match.
            .shuttlType(ShuttlType.display)
            .fontWeight(.bold)
            .foregroundStyle(.white)
            .tint(.white)
            .focused($focused, equals: side)
            .padding(.vertical, 4)
            .frame(maxWidth: .infinity)
            // The field is drawn as a field: a well in the tile, so it reads as
            // something to tap and type into, not just a number.
            .background(Color.white.opacity(0.16), in: RoundedRectangle(cornerRadius: ShuttlRadius.small))
            .accessibilityLabel("\(name) score")

            HStack {
                Spacer()
                stepButton("minus", "Take a point from \(name)", enabled: (value ?? 0) > 0) {
                    text.wrappedValue = "\(max((value ?? 0) - 1, 0))"
                }
                Spacer()
                stepButton("plus", "Add a point for \(name)", enabled: (value ?? 0) < Int(ScoreEditKt.MAX_EDIT_SCORE)) {
                    text.wrappedValue = "\((value ?? -1) + 1)"
                }
                Spacer()
            }
        }
        .padding(.horizontal, 12)
        .padding(.vertical, 14)
        .frame(maxWidth: .infinity, maxHeight: .infinity)
        .background(side == .home ? Shuttl.sideHome : Shuttl.sideAway, in: RoundedRectangle(cornerRadius: ShuttlRadius.medium))
    }

    /// Where a side stands on the serve after the edit, as its tile shows it.
    private enum ServeMark { case serving, available, none }

    /// The board's white SERVE pill, saying who serves once the edit is saved. A
    /// side that could be handed the serve instead shows an outlined pill to tap;
    /// one that cannot shows nothing, held at zero opacity so both numbers stay level.
    @ViewBuilder
    private func serveToggle(_ mark: ServeMark, onServe: @escaping () -> Void) -> some View {
        let serving = mark == .serving
        Button(action: onServe) {
            Text("SERVES NEXT")
                .shuttlType(ShuttlType.labelMedium)
                .foregroundStyle(serving ? Color.black : Color.white.opacity(0.85))
                .padding(.horizontal, 8)
                .padding(.vertical, 4)
                .background(serving ? Color.white : Color.clear, in: RoundedRectangle(cornerRadius: ShuttlRadius.extraSmall))
                .overlay {
                    if !serving {
                        RoundedRectangle(cornerRadius: ShuttlRadius.extraSmall)
                            .strokeBorder(Color.white.opacity(0.6), lineWidth: 1)
                    }
                }
        }
        .buttonStyle(.plain)
        .disabled(mark != .available)
        .opacity(mark == .none ? 0 : 1)
        .accessibilityHidden(mark == .none)
        .accessibilityHint(mark == .available ? "Serve next" : "")
    }

    @ViewBuilder
    private func stepButton(_ symbol: String, _ label: String, enabled: Bool, action: @escaping () -> Void) -> some View {
        Button(action: action) {
            Image(systemName: symbol)
                .shuttlType(ShuttlType.headlineMedium)
                .foregroundStyle(.white)
                .frame(width: 48, height: 48)
                .background(Color.white.opacity(0.16), in: Circle())
        }
        .buttonStyle(.plain)
        .disabled(!enabled)
        .opacity(enabled ? 1 : 0.4)
        .accessibilityLabel(label)
    }
}
