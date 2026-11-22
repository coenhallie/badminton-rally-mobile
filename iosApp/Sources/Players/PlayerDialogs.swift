import SwiftUI
import Shared

/// The playing-hand pills, in the order they are drawn: Right, Left, Not set.
let playerHandOptions: [RacketArm?] = [.right, .left, nil]

/// Add a player (`showArm`, with the playing-hand pills) or rename one (the
/// name alone). Save is offered only while the name can be saved, by the
/// shared `playerNameProblem`; the problem is said under the field once the
/// coach has typed, not on a sheet just opened.
///
/// `onSave` hands over the name as typed (the repository trims it) and the
/// hand, which is `initialArm` when the pills are not shown, and answers nil
/// once the save has landed or the sentence saying why it did not. The sheet
/// closes itself on nil. On a sentence it stays up and says it under the
/// field, so the coach keeps what they typed; typing again hides it. While
/// the save is in flight the field and Save are held, and the sheet cannot be
/// swiped away, so what is shown is always what was sent.
///
/// A sheet sized to its content rather than an alert: an alert cannot hold
/// the pills. Mirrors androidApp's PlayerNameDialog.
struct PlayerNameSheet: View {
    let title: String
    let showArm: Bool
    let initialArm: RacketArm?
    let onSave: (String, RacketArm?) async -> String?

    @Environment(\.dismiss) private var dismiss
    @State private var name: String
    @State private var edited = false
    @State private var armIndex: Int
    @State private var saving = false
    /// The last save's sentence, and the name it was about: typing past that
    /// name hides it, since it no longer describes what is in the field.
    @State private var failure: (message: String, name: String)? = nil
    @FocusState private var nameFocused: Bool

    private let nameCap = Int(MatchDetailsKt.MAX_PLAYER_NAME)

    init(
        title: String,
        initialName: String,
        initialArm: RacketArm?,
        showArm: Bool,
        onSave: @escaping (String, RacketArm?) async -> String?
    ) {
        self.title = title
        self.showArm = showArm
        self.initialArm = initialArm
        self.onSave = onSave
        _name = State(initialValue: initialName)
        _armIndex = State(initialValue: playerHandOptions.firstIndex(of: initialArm) ?? 2)
    }

    var body: some View {
        let problem = PlayerKt.playerNameProblem(name: name)
        let shownError = failure.flatMap { $0.name == name ? $0.message : nil } ?? (edited ? problem : nil)
        let copy = PlayersCopy.shared

        VStack(alignment: .leading, spacing: 16) {
            Text(title)
                .shuttlType(ShuttlType.headlineMedium)
                .foregroundStyle(Shuttl.textHeading)
                .accessibilityAddTraits(.isHeader)

            VStack(alignment: .leading, spacing: 6) {
                ShuttlTextField(copy.NAME_FIELD, text: $name)
                    .textContentType(.name)
                    .textInputAutocapitalization(.words)
                    .autocorrectionDisabled()
                    .submitLabel(.done)
                    .focused($nameFocused)
                    .onSubmit(save)
                    .disabled(saving)
                // Held to the cap as typed, as the match details editor holds
                // its names: the database refuses a longer one.
                .onChange(of: name) { _, new in
                    if new.sharedLength > nameCap { name = new.prefix(sharedLength: nameCap) }
                    edited = true
                }
                if let shownError {
                    Text(shownError)
                        .shuttlType(ShuttlType.bodySmall)
                        .foregroundStyle(Shuttl.error)
                        .fixedSize(horizontal: false, vertical: true)
                }
            }

            if showArm {
                VStack(alignment: .leading, spacing: 6) {
                    Shuttl.sectionLabel(copy.MENU_PLAYING_HAND)
                        .accessibilityHidden(true)
                    ShuttlPillTabs(
                        labels: playerHandOptions.map { copy.handOption(racketArm: $0) },
                        selectedIndex: armIndex,
                        onSelect: { armIndex = $0 },
                        accessibilityLabel: copy.MENU_PLAYING_HAND
                    )
                    .disabled(saving)
                }
            }

            HStack(spacing: 12) {
                // Held with Save while the save is in flight: a Cancel then
                // would close the sheet over a player that is still created.
                Button(copy.CANCEL) { dismiss() }
                    .buttonStyle(SecondaryPillButtonStyle())
                    .disabled(saving)
                Button(copy.SAVE, action: save)
                    .buttonStyle(PrimaryButtonStyle())
                    .disabled(problem != nil || saving)
            }
            .padding(.top, 8)
        }
        .padding(.horizontal, ShuttlGutter.page)
        .padding(.top, 24)
        .padding(.bottom, 16)
        .sizedToContentSheet(defaultHeight: showArm ? 320 : 240)
        .onAppear { nameFocused = true }
        .interactiveDismissDisabled(saving)
        .presentationDragIndicator(.visible)
        // Not bgSecondary, the sheets' usual fill: that is the pill track's
        // own value, which would leave the unselected pills floating on
        // nothing. See `ShuttlPalette.bgDialog`.
        .presentationBackground(Shuttl.bgDialog)
    }

    private func save() {
        guard PlayerKt.playerNameProblem(name: name) == nil, !saving else { return }
        saving = true
        failure = nil
        let sent = name
        let arm = showArm ? playerHandOptions[armIndex] : initialArm
        Task {
            let message = await onSave(sent, arm)
            saving = false
            if let message {
                failure = (message, sent)
            } else {
                dismiss()
            }
        }
    }
}

/// The playing hand of a player already made: Right, Left or Not set, with
/// Cancel and Save. Nothing typed to lose, so Save closes the sheet at once
/// and hands the hand over (`onSave`); a failure is said on the root toast.
///
/// A sheet sized to its content, like `PlayerNameSheet`, on the same fill.
/// Mirrors androidApp's PlayingHandDialog.
struct PlayingHandSheet: View {
    let onSave: (RacketArm?) -> Void

    @Environment(\.dismiss) private var dismiss
    @State private var armIndex: Int

    init(initialArm: RacketArm?, onSave: @escaping (RacketArm?) -> Void) {
        self.onSave = onSave
        _armIndex = State(initialValue: playerHandOptions.firstIndex(of: initialArm) ?? 2)
    }

    var body: some View {
        let copy = PlayersCopy.shared
        VStack(alignment: .leading, spacing: 16) {
            Text(copy.MENU_PLAYING_HAND)
                .shuttlType(ShuttlType.headlineMedium)
                .foregroundStyle(Shuttl.textHeading)
                .accessibilityAddTraits(.isHeader)

            ShuttlPillTabs(
                labels: playerHandOptions.map { copy.handOption(racketArm: $0) },
                selectedIndex: armIndex,
                onSelect: { armIndex = $0 },
                accessibilityLabel: copy.MENU_PLAYING_HAND
            )

            HStack(spacing: 12) {
                Button(copy.CANCEL) { dismiss() }
                    .buttonStyle(SecondaryPillButtonStyle())
                Button(copy.SAVE) {
                    onSave(playerHandOptions[armIndex])
                    dismiss()
                }
                .buttonStyle(PrimaryButtonStyle())
            }
            .padding(.top, 8)
        }
        .padding(.horizontal, ShuttlGutter.page)
        .padding(.top, 24)
        .padding(.bottom, 16)
        .sizedToContentSheet(defaultHeight: 240)
        .presentationDragIndicator(.visible)
        // See `PlayerNameSheet`: the sheets' usual fill is the pill track's.
        .presentationBackground(Shuttl.bgDialog)
    }
}

/// A sheet as tall as its content, wrapped in a scroll container so large
/// Dynamic Type never clips and seeded with a non-zero height to eliminate the
/// opening detent jump.
private struct SizedToContentSheet: ViewModifier {
    @State private var height: CGFloat

    init(defaultHeight: CGFloat = 280) {
        _height = State(initialValue: defaultHeight)
    }

    func body(content: Content) -> some View {
        ScrollView {
            content
                .fixedSize(horizontal: false, vertical: true)
                .onGeometryChange(for: CGFloat.self) { $0.size.height } action: { new in
                    if new > 0 { height = new }
                }
        }
        .scrollBounceBehavior(.basedOnSize)
        .scrollDismissesKeyboard(.interactively)
        .presentationDetents([.height(height)])
    }
}

extension View {
    /// See `SizedToContentSheet`. Shared by the player sheets and the attach
    /// question (`HomeEndPrompt`).
    func sizedToContentSheet(defaultHeight: CGFloat = 280) -> some View {
        modifier(SizedToContentSheet(defaultHeight: defaultHeight))
    }
}
