import SwiftUI
import Shared

/// What "Add player" inside a picker hands to: `PlayersModel.create`. A failure
/// stays in the add sheet with the name typed, as on the Players screen.
typealias AddPlayer = (String, RacketArm?) async -> PlayerCreation

/// One end's or one side's player: a field-shaped control showing the chosen
/// player (their avatar and name), "Someone else" when `someoneElse` is
/// chosen, or "Choose a player". It opens a menu of `players` with the chosen
/// one ticked, then "Someone else" when `onSomeoneElse` is given, then "Add
/// player". `onClear` puts "Choose a player" at the top of the menu while a
/// player is chosen, to take them off again.
///
/// "Add player" opens the add sheet (`PlayerNameSheet`, with the playing hand)
/// and, once the player is created (`onAddPlayer`), picks them. Until the
/// players list carries the new player, the one just made is what the box
/// shows, so it never flashes "Choose a player".
///
/// Drawn as `ShuttlMenuField`, `ShuttlTextField`'s box, so a picker and a typed
/// name beside it read as one form. A native `Menu`, whose pop-up-button
/// semantics VoiceOver announces (Ruling 21); `accessibilityName` says which
/// end or side it is for, and its value is what is chosen. A disabled picker
/// dims to the field's 0.6.
///
/// The menu's rows carry no avatar (a menu row draws only text and a symbol)
/// and no symbol either, "Add player" included, where androidApp leads it with
/// a "+": a menu sets the text of a section with a symbol in it further right,
/// so "Someone else" and "Add player" read as a second column of text.
/// Mirrors androidApp's PlayerPicker.kt.
struct PlayerPicker: View {
    let selectedId: String?
    let players: [Player]
    let onPick: (Player) -> Void
    let onAddPlayer: AddPlayer
    let accessibilityName: String
    var onClear: (() -> Void)? = nil
    var someoneElse: Bool = false
    var onSomeoneElse: (() -> Void)? = nil

    @Environment(\.isEnabled) private var isEnabled
    @State private var adding = false
    /// The player "Add player" just made, until `players` carries them.
    @State private var created: Player? = nil

    private var selected: Player? {
        guard let selectedId else { return nil }
        return players.first { $0.id == selectedId } ?? (created?.id == selectedId ? created : nil)
    }

    private var shown: String {
        let copy = PlayersCopy.shared
        if let selected { return selected.name }
        return someoneElse ? copy.SOMEONE_ELSE : copy.CHOOSE_PLAYER
    }

    var body: some View {
        let copy = PlayersCopy.shared
        Menu {
            if let onClear, selected != nil {
                Button(copy.CHOOSE_PLAYER, action: onClear)
                Divider()
            }
            ForEach(players, id: \.id) { player in
                // A toggle, so the chosen one carries the menu's own tick.
                Toggle(player.name, isOn: Binding(
                    get: { player.id == selectedId },
                    set: { _ in onPick(player) }
                ))
            }
            if !players.isEmpty { Divider() }
            if let onSomeoneElse {
                Toggle(copy.SOMEONE_ELSE, isOn: Binding(
                    get: { someoneElse && selected == nil },
                    set: { _ in onSomeoneElse() }
                ))
            }
            Button(copy.PICKER_ADD_PLAYER) { adding = true }
        } label: {
            ShuttlMenuField(text: shown, dimmed: selected == nil && !someoneElse) { line in
                if let selected {
                    // The field's line at most, so the box keeps its height.
                    PlayerAvatar(name: selected.name, size: min(Self.avatarSize, line))
                }
            }
        }
        // The whole control, box and stroke too, as a disabled ShuttlTextField
        // dims: the two sit side by side.
        .opacity(isEnabled ? 1 : 0.6)
        .accessibilityLabel(accessibilityName)
        .accessibilityValue(shown)
        .sheet(isPresented: $adding) {
            PlayerNameSheet(
                title: copy.ADD_DIALOG_TITLE, initialName: "", initialArm: nil, showArm: true
            ) { name, arm in
                switch await onAddPlayer(name, arm) {
                case .created(let player):
                    created = player
                    onPick(player)
                    return nil
                case .failed(let message):
                    return message
                }
            }
        }
    }

    /// The avatar in the box: the switcher's less a little, so the box keeps a
    /// text field's height at the default text size.
    private static let avatarSize: CGFloat = 24
}
