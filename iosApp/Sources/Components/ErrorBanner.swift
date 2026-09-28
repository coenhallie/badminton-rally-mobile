import SwiftUI

/// A failure the coach needs to read: a solid `error` fill with `onError`
/// text and glyph, at `ShuttlRadius.small`. androidApp's ErrorBanner draws
/// the same thing; onError on error is 5.07:1 in both themes.
///
/// Draws no margin of its own. A caller places it at the page gutter or in a
/// padded slot: a rounded fill running into the screen edges reads as a
/// mistake, and a full-bleed fill at the top of a page also bled up behind a
/// transparent navigation bar.
struct ErrorBanner: View {
    let message: String

    var body: some View {
        HStack(alignment: .firstTextBaseline, spacing: 8) {
            Image(systemName: "exclamationmark.circle")
                .shuttlType(ShuttlType.bodySmall)
                .accessibilityHidden(true)
            Text(message)
                .shuttlType(ShuttlType.bodySmall)
                .frame(maxWidth: .infinity, alignment: .leading)
        }
        .foregroundStyle(Shuttl.onError)
        .padding(.horizontal, 12)
        .padding(.vertical, 10)
        .background(Shuttl.error, in: RoundedRectangle(cornerRadius: ShuttlRadius.small))
    }
}
