import SwiftUI

/// What a list shows when it has nothing to list: a glyph in a disc, a short
/// title, one line saying how the list gets filled, and (usually) the control
/// that fills it. An empty screen has to teach the action itself rather than
/// describe where else to find it - the Labels screen's empty state set that
/// rule and this is the same idea, given a shape.
///
/// The disc is the same surface-plus-hairline pairing as `ShuttlCard`, rounded
/// because it holds a glyph and not content. Mirrors androidApp's
/// ShuttlEmptyState.kt measure for measure.
struct ShuttlEmptyState<Action: View>: View {
    let systemImage: String
    let title: String
    let message: String
    @ViewBuilder let action: () -> Action

    var body: some View {
        VStack(spacing: 0) {
            ZStack {
                Circle().fill(Shuttl.bgSecondary)
                Circle().stroke(Shuttl.border, lineWidth: 1)
                Image(systemName: systemImage)
                    // Android draws its 24-unit glyph at 28dp; SF Symbols size
                    // by point size, and 26 with a regular weight lands the
                    // trophy at the same visual height inside the 64pt disc.
                    .font(.system(size: 26, weight: .regular))
                    .foregroundStyle(Shuttl.textSecondary)
            }
            .frame(width: 64, height: 64)
            Text(title)
                .shuttlType(ShuttlType.titleLarge)
                .foregroundStyle(Shuttl.textHeading)
                .multilineTextAlignment(.center)
                .padding(.top, 20)
            Text(message)
                .shuttlType(ShuttlType.bodyMedium)
                .foregroundStyle(Shuttl.textSecondary)
                .multilineTextAlignment(.center)
                .padding(.top, 6)
            action()
                .padding(.top, 20)
        }
        .padding(.horizontal, 32)
        .padding(.vertical, 24)
        .frame(maxWidth: .infinity, maxHeight: .infinity)
    }
}
