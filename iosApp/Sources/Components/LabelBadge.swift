import SwiftUI
import Shared

/// A label's pill. `colorKey` nil, or naming a swatch this build does not know,
/// renders the neutral chip rather than nothing: the name is the information, the
/// colour is decoration.
struct LabelBadge: View {
    let name: String
    let colorKey: String?

    private var swatch: LabelColor? { LabelColor.companion.from(key: colorKey) }
    private var container: Color {
        swatch.map { Color(rgb: UInt32($0.background & 0xFFFFFF)) } ?? Shuttl.bgTertiary
    }
    private var onContainer: Color {
        swatch.map { Color(rgb: UInt32($0.foreground & 0xFFFFFF)) } ?? Shuttl.text
    }

    var body: some View {
        // A blank name renders nothing at all rather than an empty pill, matching
        // androidApp's LabelBadge: the invariant lives here, not at each call site.
        if !name.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty {
            Text(name)
                .font(.system(size: 11, weight: .medium))
                .foregroundStyle(onContainer)
                .padding(.horizontal, 10)
                .padding(.vertical, 4)
                .background(Capsule().fill(container))
        }
    }
}
