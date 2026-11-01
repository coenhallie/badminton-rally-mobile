import SwiftUI
import Shared

/// "Who's playing", for a match born as a video: One player | Both players,
/// then the court as the camera sees it, Far above Near (section 4.3 of the
/// player profiles design).
///
/// In One mode the player's end is a picker and the other end a typed
/// opponent, never a second picker: a profile chosen there would drop the name
/// typed for it, so the player is moved to the other end with Swap ends
/// instead. In Both mode both ends are pickers, each offering every player but
/// the one at the other end. Every picker also offers "Someone else", which
/// shows the end's typed name under it, so no typed name is ever out of sight
/// (Ruling 17). When the player is at the far end, a line says the phone's own
/// analysis follows only the near one.
///
/// Only renders `draft` and writes each tap back as the next draft, through
/// the shared EndsDraft functions, which hold every rule about which end keeps
/// whom. Singles only, as EndsDraft is: a caller never shows this for a
/// doubles match.
///
/// `uploading` shows the section as it stands, read-only, with the sentence
/// saying it can be changed once the upload finishes.
///
/// Mirrors androidApp's WhoIsPlayingSection.kt.
struct WhoIsPlayingSection: View {
    @Binding var draft: EndsDraft
    let players: [Player]
    let onAddPlayer: AddPlayer
    var uploading: Bool = false

    private let nameCap = Int(MatchDetailsKt.MAX_PLAYER_NAME)

    var body: some View {
        let copy = PlayersCopy.shared
        VStack(alignment: .leading, spacing: 12) {
            Shuttl.sectionLabel(copy.WHOS_PLAYING_TITLE)
                .accessibilityAddTraits(.isHeader)
            ShuttlPillTabs(
                labels: [copy.ONE_PLAYER, copy.BOTH_PLAYERS],
                selectedIndex: draft.tracked == .one ? 0 : 1,
                onSelect: { draft = draft.withTracked(t: $0 == 0 ? .one : .both) },
                accessibilityLabel: copy.WHOS_PLAYING_TITLE,
                enabled: !uploading
            )
            court
                .disabled(uploading)
            if draft.tracked == .one {
                HStack {
                    Spacer(minLength: 0)
                    Button {
                        draft = draft.swapped()
                    } label: {
                        Label(copy.SWAP_ENDS, systemImage: "arrow.up.arrow.down")
                            .shuttlType(ShuttlType.labelMedium)
                            // `accentDark`, the palette's accent for lettering:
                            // `accent` is a fill.
                            .foregroundStyle(Shuttl.accentDark)
                    }
                    .buttonStyle(.plain)
                    .contentShape(MinimumTargetShape())
                    .disabled(uploading)
                    .opacity(uploading ? 0.6 : 1)
                }
            }
            if draft.showsDeviceLine() { PlayersNote(text: copy.DEVICE_ANALYSIS_LINE) }
            if uploading { PlayersNote(text: copy.UPLOAD_IN_PROGRESS) }
        }
    }

    /// The court card: Far over Near with a dashed net between, each end's
    /// label at its left, in a column as wide as the wider label so the two
    /// controls start at one x at any text size. A label sits on its control's
    /// own text line: a picker's name (its first line, with any name field
    /// under it) or a typed opponent's text (its last, under the field's label).
    private var court: some View {
        Grid(alignment: .leading, horizontalSpacing: 12, verticalSpacing: 0) {
            half(.far)
            // The net: dashed, as the mock draws it, across the card's width.
            Net()
                .stroke(Shuttl.textTertiary, style: StrokeStyle(lineWidth: Self.netStroke, dash: [6, 4]))
                .frame(height: Self.netStroke)
                .padding(.horizontal, -Self.inset)
                .accessibilityHidden(true)
            half(.near)
        }
        .padding(.horizontal, Self.inset)
        // textTertiary, the input boxes' own stroke, rather than Android's
        // outline (borderSecondary): on the dark dialog fill that was about
        // 1.1:1, and the card and its net all but vanished.
        .overlay(
            RoundedRectangle(cornerRadius: ShuttlRadius.small)
                .strokeBorder(Shuttl.textTertiary, lineWidth: 1)
        )
    }

    private func half(_ end: CourtSide) -> some View {
        let field = draft.field(end: end)
        return GridRow(alignment: field == .opponent ? .lastTextBaseline : .firstTextBaseline) {
            Text(end == .near ? PlayersCopy.shared.NEAR : PlayersCopy.shared.FAR)
                .shuttlType(ShuttlType.labelMedium)
                .foregroundStyle(Shuttl.textSecondary)
                .lineLimit(1)
                .fixedSize()
                // The control is named after the end, so the tag is not read
                // a second time.
                .accessibilityHidden(true)
            control(end, field)
                .frame(maxWidth: .infinity, alignment: .leading)
        }
        .padding(.vertical, Self.inset)
    }

    /// One end, as the draft says it draws (`EndsDraft.field`): One mode's
    /// other end is the typed opponent; every other end is a picker of the
    /// players, "Someone else" and "Add player", with the end's typed name
    /// under it once "Someone else" is its value.
    @ViewBuilder
    private func control(_ end: CourtSide, _ field: EndField) -> some View {
        let copy = PlayersCopy.shared
        let near = end == .near
        let endName = near ? copy.NEAR : copy.FAR
        let name = Binding(
            get: { near ? draft.nearName : draft.farName },
            set: { draft = draft.withName(end: end, name: String($0.prefix(nameCap))) }
        )
        if field == .opponent {
            ShuttlTextField(copy.OPPONENT_FIELD, text: name, maxLength: nameCap)
                .textInputAutocapitalization(.words)
                .autocorrectionDisabled()
        } else {
            let otherId = near ? draft.farPlayerId : draft.nearPlayerId
            VStack(alignment: .leading, spacing: 8) {
                PlayerPicker(
                    selectedId: near ? draft.nearPlayerId : draft.farPlayerId,
                    // One player cannot stand at both ends; in One mode the other
                    // end holds no profile, so this hides nobody there.
                    players: players.filter { $0.id != otherId },
                    onPick: { draft = draft.withPlayer(end: end, playerId: $0.id) },
                    onAddPlayer: onAddPlayer,
                    accessibilityName: endName,
                    onClear: { draft = draft.withPlayer(end: end, playerId: nil) },
                    someoneElse: field == .someoneElse,
                    onSomeoneElse: { draft = draft.withSomeoneElse(end: end) }
                )
                if field == .someoneElse {
                    ShuttlTextField(copy.NAME_FIELD, text: name, maxLength: nameCap)
                        .textInputAutocapitalization(.words)
                        .autocorrectionDisabled()
                }
            }
        }
    }

    private static let inset: CGFloat = 12
    private static let netStroke: CGFloat = 2
}

/// A who's-playing editor's quiet line: the device line, the doubles note, the
/// upload sentence.
struct PlayersNote: View {
    let text: String

    var body: some View {
        Text(text)
            .shuttlType(ShuttlType.bodySmall)
            .foregroundStyle(Shuttl.textSecondary)
            .fixedSize(horizontal: false, vertical: true)
    }
}

/// The net across the court card: a horizontal line through the middle.
private struct Net: Shape {
    func path(in rect: CGRect) -> Path {
        var path = Path()
        path.move(to: CGPoint(x: rect.minX, y: rect.midY))
        path.addLine(to: CGPoint(x: rect.maxX, y: rect.midY))
        return path
    }
}
