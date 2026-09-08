import SwiftUI

/// The mock's tab switcher: a pill-shaped track holding one pill per choice, the
/// chosen one filled with the heading colour and lettered in the page colour, the
/// rest lettered in the secondary text colour.
///
/// This replaces `.pickerStyle(.segmented)` on the screens built from the mock.
/// That control is UIKit's, and it draws a bordered segment in the system font on
/// a system fill - it cannot take the type scale or the tokens, so it reads as a
/// piece of another app wherever it sits. Every pill takes an equal share of the
/// track, so labels of different lengths do not make the pills ragged.
///
/// `labels` and `selectedIndex` rather than a generic over the caller's enum: the
/// callers keep their own type and pass its labels, which keeps this file free of
/// them. Port of androidApp's `ShuttlPillTabs.kt`.
struct ShuttlPillTabs: View {
    let labels: [String]
    let selectedIndex: Int
    let onSelect: (Int) -> Void
    /// Read to VoiceOver as the group's name, since the pills replace a `Picker`
    /// that carried one.
    var accessibilityLabel: String? = nil

    var body: some View {
        HStack(spacing: Metrics.gap) {
            ForEach(Array(labels.enumerated()), id: \.offset) { index, label in
                let selected = index == selectedIndex
                Button {
                    onSelect(index)
                } label: {
                    Text(label)
                        .shuttlType(ShuttlType.labelMedium)
                        // The mock letters an unchosen pill in `#5d6462`, which is
                        // `textMuted`. Not used here, and Android made the same
                        // call: that token is documented for the hero line only
                        // and drops below its own 3:1 floor on a raised surface,
                        // which this track is.
                        .foregroundStyle(selected ? Shuttl.bg : Shuttl.textSecondary)
                        .multilineTextAlignment(.center)
                        .frame(maxWidth: .infinity)
                        .padding(.horizontal, Metrics.tabPaddingH)
                        .padding(.vertical, Metrics.tabPaddingV)
                        .background(selected ? Shuttl.textHeading : Shuttl.bgSecondary)
                        .clipShape(Capsule())
                        .contentShape(Capsule())
                }
                .buttonStyle(.plain)
                .accessibilityAddTraits(selected ? [.isButton, .isSelected] : .isButton)
            }
        }
        .padding(Metrics.trackPadding)
        .frame(maxWidth: .infinity)
        .background(Shuttl.bgSecondary)
        .clipShape(Capsule())
        .accessibilityElement(children: .contain)
        .accessibilityLabel(accessibilityLabel ?? "")
    }

    /// Mirrors androidApp's `ShuttlPillTabs.kt` number for number.
    private enum Metrics {
        static let trackPadding: CGFloat = 4
        static let gap: CGFloat = 4
        static let tabPaddingH: CGFloat = 8
        static let tabPaddingV: CGFloat = 11
    }
}
