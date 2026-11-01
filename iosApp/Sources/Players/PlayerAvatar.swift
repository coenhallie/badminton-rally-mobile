import SwiftUI
import Shared

/// A player's initials on a disc in the home side's colour: `rowSize` on a
/// row, `profileSize` on the profile. Hidden from VoiceOver, since the name is
/// always beside it.
///
/// The letters scale with the disc rather than with the text size (the mock's
/// 15 on 40, 21 on 60): two letters at a large text size would overrun a disc
/// whose size is fixed. Mirrors androidApp's PlayerAvatar.kt.
struct PlayerAvatar: View {
    /// A player's row.
    static let rowSize: CGFloat = 40
    /// A player's profile.
    static let profileSize: CGFloat = 60

    let name: String
    let size: CGFloat

    var body: some View {
        Text(PlayerKt.initials(name: name))
            .shuttlFixedType(letters)
            .foregroundStyle(Shuttl.playerInitials)
            .lineLimit(1)
            .frame(width: size, height: size)
            .background(Shuttl.sideHome, in: Circle())
            .accessibilityHidden(true)
    }

    /// titleMedium's face in bold, at the mock's letter size for this disc.
    private var letters: ShuttlType.Role {
        ShuttlType.Role(
            name: "playerAvatar",
            size: size * Self.lettersPerDisc,
            weight: .bold,
            trackingEm: ShuttlType.titleMedium.trackingEm,
            lineHeightMultiple: 1
        )
    }

    /// The mock's letter size against its disc: 15 on 40, 21 on 60.
    private static let lettersPerDisc: CGFloat = 0.36
}
