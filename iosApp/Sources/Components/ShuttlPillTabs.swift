import SwiftUI
import UIKit

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
/// A label wraps between words, never inside one. Where a pill is too narrow for
/// its longest word (the match page's four sections at a large text size on a
/// narrow phone broke "Analysis" into "Analys / is" on Android), the pills first
/// give up some of their side padding, and only if that is not enough is every
/// label in the row set a step smaller, by the one factor the tightest word
/// needs, so the row keeps one size. `minimumScaleFactor` alone would shrink
/// "Analysis" and leave "Points" beside it at full size: a ragged row. Padding
/// first because a smaller label is visible against the row stacked under it (the
/// Analysis section's panels); a few points of air inside a pill is not. See
/// `pillPadding` and `pillLabelScale`.
///
/// `secondary` is the row that belongs to another one above it: the match page's
/// Analysis panels (Heatmap | Base | Skeleton) under its sections, and the Near |
/// Far toggle inside a panel. Shorter pills and a smaller label, labelSmall's
/// size and weight, in the same colours: three rows of one size stacked in the
/// Analysis section read as three peers, with nothing to say the second was
/// Analysis's own.
///
/// A primary row shrinks its labels on a narrow phone at a large text size, and a
/// secondary one under it could then come out the larger of the two. A row with
/// `setsCeiling` publishes its drawn label size as a preference; a view carrying
/// `.pillLabelCeilingHost()` collects it and hands it down through the
/// environment, and every secondary row inside that view draws no larger. The
/// match page wires it once: its section row takes `setsCeiling: true`, and the
/// stack holding that row and the section under it takes
/// `.pillLabelCeilingHost()`. A preference rather than a shared object the row
/// writes into: it goes with the row when the row goes (one section left, a
/// pop), and comes back with it, with no last write to go stale.
///
/// `labels` and `selectedIndex` rather than a generic over the caller's enum: the
/// callers keep their own type and pass its labels, which keeps this file free of
/// them. Port of androidApp's `ShuttlPillTabs.kt`, behaviour for behaviour.
struct ShuttlPillTabs: View {
    let labels: [String]
    let selectedIndex: Int
    let onSelect: (Int) -> Void
    /// Read to VoiceOver as the group's name, since the pills replace a `Picker`
    /// that carried one.
    var accessibilityLabel: String? = nil
    var secondary: Bool = false
    /// Whether this row sets the ceiling for the secondary rows under the
    /// nearest `.pillLabelCeilingHost()`. For a primary row; a secondary row
    /// that set it would be capping itself.
    var setsCeiling: Bool = false

    @Environment(\.dynamicTypeSize) private var dynamicTypeSize
    @Environment(\.pillLabelCeiling) private var ceiling
    /// The track's width, measured, since the fit depends on it. Zero until the
    /// first layout, which draws at full size rather than flashing at the floor.
    @State private var trackWidth: CGFloat = 0

    var body: some View {
        let fit = fit
        HStack(spacing: Metrics.gap) {
            ForEach(Array(labels.enumerated()), id: \.offset) { index, label in
                let selected = index == selectedIndex
                Button {
                    onSelect(index)
                } label: {
                    Text(label)
                        .shuttlType(fit.role)
                        // At most a line per word, and a hair of shrink past that:
                        // the fit measured every word to fit its pill, so this only
                        // ever takes up the fraction of a point where SwiftUI's
                        // layout and UIKit's measure disagree. Lifted past the floor,
                        // where no size fits and Android's rule holds: a break
                        // inside the word is the lesser harm than an unreadable
                        // label or a truncated one.
                        .lineLimit(fit.fits ? max(1, Self.words(label).count) : nil)
                        .minimumScaleFactor(fit.fits ? 0.95 : 1)
                        // The mock letters an unchosen pill in `#5d6462`, which is
                        // `textMuted`. Not used here, and Android made the same
                        // call: that token is documented for the hero line only
                        // and drops below its own 3:1 floor on a raised surface,
                        // which this track is.
                        .foregroundStyle(selected ? Shuttl.bg : Shuttl.textSecondary)
                        .multilineTextAlignment(.center)
                        .frame(maxWidth: .infinity)
                        .padding(.horizontal, fit.padding)
                        .padding(.vertical, secondary ? Metrics.secondaryPaddingV : Metrics.tabPaddingV)
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
        .onGeometryChange(for: CGFloat.self, of: { $0.size.width }) { trackWidth = $0 }
        // Infinity is no ceiling, and what every row that does not set one
        // contributes: the key keeps the smallest.
        .preference(
            key: PillLabelCeilingPreference.self,
            value: setsCeiling && !secondary ? fit.labelPoints : .infinity
        )
        .accessibilityElement(children: .contain)
        .accessibilityLabel(accessibilityLabel ?? "")
    }

    /// The role before any fitting. The secondary one is labelSmall's size and
    /// weight, untracked: its tracking is for the uppercase labels it was made for.
    /// 11pt against the primary's 13pt, the step Android settled on: at 12pt the
    /// row under a primary shrunk to keep "Analysis" whole came out the larger.
    private var baseRole: ShuttlType.Role {
        secondary ? Self.secondaryRole : ShuttlType.labelMedium
    }

    private static let secondaryRole = ShuttlType.Role(
        name: "labelSmall untracked",
        size: ShuttlType.labelSmall.size,
        weight: ShuttlType.labelSmall.weight,
        trackingEm: 0,
        lineHeightMultiple: ShuttlType.labelSmall.lineHeightMultiple
    )

    /// What this row draws at, for the width it has.
    private var fit: PillFit {
        let base = baseRole
        let traits = UITraitCollection(preferredContentSizeCategory: UIContentSizeCategory(dynamicTypeSize))
        // The size a role at this base size actually draws at: `.custom(_:size:)`
        // scales with Dynamic Type relative to the body style, so the measure
        // has to as well.
        func points(_ size: CGFloat) -> CGFloat {
            UIFontMetrics(forTextStyle: .body).scaledValue(for: size, compatibleWith: traits)
        }
        func widestWord(_ scale: CGFloat) -> CGFloat {
            let size = base.size * scale
            let drawn = points(size)
            let font = UIFont(name: base.weight.rawValue, size: drawn) ?? .systemFont(ofSize: drawn)
            // Tracking is not scaled by Dynamic Type: `.kerning` takes points.
            let attributes: [NSAttributedString.Key: Any] = [.font: font, .kern: size * base.trackingEm]
            return labels.flatMap(Self.words).map {
                ceil(($0 as NSString).size(withAttributes: attributes).width)
            }.max() ?? 0
        }
        guard trackWidth > 0, !labels.isEmpty else {
            return PillFit(role: base, padding: Metrics.tabPaddingH, labelPoints: points(base.size), fits: true)
        }
        let count = CGFloat(labels.count)
        // Each pill's width: the track less its padding and the gaps, shared
        // equally. Half a point under that, for the rounding the stack does when
        // it hands out the widths.
        let pillWidth = (trackWidth - Metrics.trackPadding * 2 - Metrics.gap * (count - 1)) / count - 0.5
        let padding = pillPadding(
            pillWidth: pillWidth,
            widestWord: widestWord(1),
            min: Metrics.tabPaddingHMin,
            max: Metrics.tabPaddingH
        )
        let limit = pillWidth - padding * 2
        // Only a secondary row is held under a ceiling; reading it from the
        // environment is what redraws the row when the primary above it
        // changes size.
        let scale = pillRowScale(
            widthLimit: limit,
            widestWord: widestWord,
            ceiling: secondary ? ceiling : .infinity,
            labelSize: { points(base.size * $0) }
        )
        return PillFit(
            role: scale < 1 ? base.at(size: base.size * scale) : base,
            padding: padding,
            labelPoints: points(base.size * scale),
            fits: widestWord(scale) <= limit
        )
    }

    private static func words(_ label: String) -> [String] {
        label.split(separator: " ").map(String.init)
    }

    /// Mirrors androidApp's `ShuttlPillTabs.kt` number for number.
    private enum Metrics {
        static let trackPadding: CGFloat = 4
        static let gap: CGFloat = 4
        static let tabPaddingH: CGFloat = 8
        /// The least air either side of a label. Below this the label's first and
        /// last letters crowd the capsule's curve (Android tried 2dp, and
        /// "Analysis" all but touched it). Past this point the label shrinks
        /// instead.
        static let tabPaddingHMin: CGFloat = 6
        static let tabPaddingV: CGFloat = 11
        /// A secondary pill's: with its smaller label, 8pt shorter than a primary.
        static let secondaryPaddingV: CGFloat = 7
    }
}

/// What a row draws at: its label role, its pills' side padding, the label's
/// drawn size in points (what a ceiling-setting row publishes), and whether every word fits
/// its pill at that size.
private struct PillFit {
    let role: ShuttlType.Role
    let padding: CGFloat
    let labelPoints: CGFloat
    let fits: Bool
}

/// The label size, in points as drawn, of the row that sets the ceiling. The
/// smallest wins, so a row that sets none contributes infinity.
struct PillLabelCeilingPreference: PreferenceKey {
    static let defaultValue: CGFloat = .infinity
    static func reduce(value: inout CGFloat, nextValue: () -> CGFloat) {
        value = min(value, nextValue())
    }
}

private struct PillLabelCeilingKey: EnvironmentKey {
    static let defaultValue: CGFloat = .infinity
}

extension EnvironmentValues {
    /// The most, in points as drawn, a secondary pill row's label may be.
    /// Infinity, no ceiling, outside a `.pillLabelCeilingHost()` or before its
    /// row has drawn.
    var pillLabelCeiling: CGFloat {
        get { self[PillLabelCeilingKey.self] }
        set { self[PillLabelCeilingKey.self] = newValue }
    }
}

extension View {
    /// Collects the ceiling a `setsCeiling` pill row inside this view publishes
    /// and hands it to every secondary pill row inside it. Goes on the view that
    /// holds both: on the match page, the stack of the section row and the
    /// section under it.
    func pillLabelCeilingHost() -> some View {
        modifier(PillLabelCeilingHost())
    }
}

private struct PillLabelCeilingHost: ViewModifier {
    @State private var ceiling: CGFloat = .infinity

    func body(content: Content) -> some View {
        content
            .environment(\.pillLabelCeiling, ceiling)
            .onPreferenceChange(PillLabelCeilingPreference.self) { ceiling = $0 }
    }
}

/// A pill's side padding: `max` while the widest word fits inside it, else
/// whatever is left either side of that word, down to `min`.
func pillPadding(pillWidth: CGFloat, widestWord: CGFloat, min: CGFloat, max: CGFloat) -> CGFloat {
    Swift.min(Swift.max((pillWidth - widestWord) / 2, min), max)
}

/// The least a pill label is set at, as a factor of its role. Below this a label
/// is too small to read, and a break inside the word is the lesser harm.
let minPillLabelScale: CGFloat = 0.75

/// The largest factor, at most 1, at which `measure` (a width, or a size, at that
/// factor) comes to no more than `limit`. Checked against `measure` again at each
/// guess rather than assumed linear: text measure rounds, and Android found its
/// own scaling non-linear, so a proportional guess can leave a word a hair too
/// wide. Floored at `minPillLabelScale`.
func pillLabelScale(limit: CGFloat, measure: (CGFloat) -> CGFloat) -> CGFloat {
    var scale: CGFloat = 1
    for _ in 0..<8 {
        let size = measure(scale)
        if size <= limit || size <= 0 || scale <= minPillLabelScale { return scale }
        // A hair under the proportional guess, so a guess that lands exactly on
        // the limit is not rounded back over it by the measure.
        scale = min(max(scale * limit / size * 0.995, minPillLabelScale), 1)
    }
    return scale
}

/// A row's one factor: the largest at which its widest word fits `widthLimit`,
/// held further so its label size, as drawn, comes to no more than `ceiling`.
/// `widestWord` and `labelSize` take the factor. The two limits are fitted
/// apart because they measure different things (a word's width, a font's size).
func pillRowScale(
    widthLimit: CGFloat,
    widestWord: (CGFloat) -> CGFloat,
    ceiling: CGFloat,
    labelSize: (CGFloat) -> CGFloat
) -> CGFloat {
    let fit = pillLabelScale(limit: widthLimit, measure: widestWord)
    guard ceiling.isFinite else { return fit }
    return min(fit, pillLabelScale(limit: ceiling, measure: labelSize))
}
