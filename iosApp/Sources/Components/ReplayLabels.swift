import Shared
import SwiftUI

/// One label, as a thing to tap while the video plays: a tap tags the moment on
/// screen, with no sheet in between. Drawn in the label's own swatch, as the
/// scoring board's chips are, so the chip and the badge it becomes share a colour.
///
/// Port of androidApp's `QuickTagChip`.
private struct QuickTagChip: View {
    let label: AnnotationLabel
    var compact = false
    let onTap: () -> Void

    var body: some View {
        let swatch = LabelColor.companion.from(key: label.colorKey)
        Button(action: onTap) {
            Text(label.name)
                // Smaller in the floating window: every point of it is a point of
                // court the coach cannot see.
                .shuttlType(compact ? ShuttlType.labelMedium : ShuttlType.titleMedium)
                .lineLimit(1)
                .foregroundStyle(swatch.map { Color(rgb: UInt32($0.foreground & 0xFFFFFF)) } ?? Shuttl.text)
                .padding(.horizontal, compact ? 10 : 12)
                .padding(.vertical, compact ? 6 : 8)
                .background(swatch.map { Color(rgb: UInt32($0.background & 0xFFFFFF)) } ?? Shuttl.bgTertiary)
                .clipShape(Capsule())
        }
        .buttonStyle(.plain)
        .accessibilityHint("Tags this moment")
    }
}

/// "Tagged Good shot at 0:12": what both the row and the window say after a tap.
func quickTagConfirmation(_ label: AnnotationLabel, positionMs: Int64) -> String {
    "Tagged \(label.name) at \(LocalVideoLogic.formatDuration(ms: max(0, positionMs)))"
}

/// The labels under the transport, for tagging without leaving the video: the
/// same note the "+" sheet makes, minus the sheet. The chips sit in at most two
/// lanes that scroll sideways once they are full, the compromise `LabelLanes`
/// explains: the 2026-08-30 label scope design records what one scrolling line
/// costs, and unlimited wrapping made an account with many clip labels pay for
/// them in video and notes on every rally.
///
/// Absent rather than empty when there is nothing to tap, so a viewer of somebody
/// else's match does not get a hint over nothing.
///
/// Port of androidApp's `ReplayLabelRow`.
struct ReplayLabelRow: View {
    let labels: [AnnotationLabel]
    let message: String?
    let onTag: (AnnotationLabel) -> Void

    /// The page gutter the strip rests in, and scrolls out of.
    private static let gutter: CGFloat = 24
    @State private var width: CGFloat = 0

    var body: some View {
        if !labels.isEmpty {
            VStack(spacing: 8) {
                // The gutter is inside the scroll, so the strip rests at the page
                // margin and the last chip clears it at the far end.
                ScrollView(.horizontal) {
                    ChipLanes(spacing: 8, available: max(0, width - 2 * Self.gutter)) {
                        ForEach(labels, id: \.id) { label in
                            QuickTagChip(label: label, onTap: { onTag(label) })
                        }
                    }
                    .padding(.horizontal, Self.gutter)
                }
                .scrollIndicators(.hidden)
                .onGeometryChange(for: CGFloat.self, of: { $0.size.width }) { width = $0 }
                // One line that is always there: what a tap does, until a tap has
                // happened, then what it did. Swapping text in a held line rather
                // than adding one keeps the notes underneath from jumping.
                Text(message ?? "Tap a label to tag this moment")
                    .shuttlType(ShuttlType.bodySmall)
                    .foregroundStyle(Shuttl.textSecondary)
                    .lineLimit(1)
                    .padding(.horizontal, Self.gutter)
            }
            .frame(maxWidth: .infinity)
        }
    }
}

/// The same chips over a fullscreen video, in a window the coach can move: there
/// is no "under the transport" in fullscreen, and wherever a fixed panel went it
/// would sit on somebody's half of the court.
///
/// Dragged by its title bar only, so a chip is always a tap. Where it is left is
/// remembered as a `PanelAnchor`, a fraction of its travel, so it comes back on
/// screen when the screen or the chips change size. All of the arithmetic is the
/// shared `FloatingPanelMath`, which is what Android's window uses too.
///
/// `message` is the last thing that happened, said inside the window: fullscreen
/// covers every other surface an error or a confirmation could appear on.
///
/// Port of androidApp's `FloatingLabelPanel`.
struct FloatingLabelPanel: View {
    let labels: [AnnotationLabel]
    /// The size of the area this floats in.
    let area: CGSize
    let anchor: PanelAnchor
    let onAnchorChange: (PanelAnchor) -> Void
    let onTag: (AnnotationLabel) -> Void
    let message: String?

    @State private var collapsed = false
    @State private var size: CGSize = .zero
    /// Non-nil only while a finger is dragging; until then the anchor places it.
    @State private var dragged: PanelPosition?
    @State private var dragOrigin: PanelPosition?
    /// Where the window was when it was folded or unfolded. Folding changes its
    /// height and so its travel; left to the anchor, the title bar would jump out
    /// from under the finger that tapped it. Held until the new size is measured.
    @State private var held: PanelPosition?

    private func clamped(_ position: PanelPosition) -> PanelPosition {
        FloatingPanelMath.shared.clamp(
            position: position,
            panelWidth: Float(size.width), panelHeight: Float(size.height),
            areaWidth: Float(area.width), areaHeight: Float(area.height)
        )
    }

    private var anchored: PanelPosition {
        FloatingPanelMath.shared.position(
            anchor: anchor,
            panelWidth: Float(size.width), panelHeight: Float(size.height),
            areaWidth: Float(area.width), areaHeight: Float(area.height)
        )
    }

    private func anchor(for position: PanelPosition, size: CGSize) -> PanelAnchor {
        FloatingPanelMath.shared.anchor(
            position: position,
            panelWidth: Float(size.width), panelHeight: Float(size.height),
            areaWidth: Float(area.width), areaHeight: Float(area.height)
        )
    }

    var body: some View {
        let position = clamped(dragged ?? held ?? anchored)
        VStack(alignment: .leading, spacing: 0) {
            titleBar(at: position)
            if !collapsed {
                // Capped and scrollable, so a long palette cannot grow the window
                // past the screen it has to stay inside.
                ScrollView {
                    ChipFlow(spacing: 6) {
                        ForEach(labels, id: \.id) { label in
                            QuickTagChip(label: label, compact: true, onTap: { onTag(label) })
                        }
                    }
                }
                .frame(maxHeight: PanelMetrics.maxChipsHeight)
                .fixedSize(horizontal: false, vertical: true)
                .padding(.horizontal, 12)
                .padding(.bottom, 12)
                // Always there while the window is open, blank until there is
                // something to say: a line that came and went would change the
                // window's height on every tag, and with it where it sits.
                Text(message ?? " ")
                    .shuttlType(ShuttlType.labelSmall)
                    .foregroundStyle(ShuttlVideoOverlay.text)
                    .lineLimit(1)
                    .padding(.horizontal, 12)
                    .padding(.bottom, 10)
            }
        }
        .frame(width: PanelMetrics.width, alignment: .leading)
        .background(ShuttlVideoOverlay.scrim)
        .clipShape(RoundedRectangle(cornerRadius: ShuttlRadius.large))
        .onGeometryChange(for: CGSize.self, of: { $0.size }) { measured in
            size = measured
            if let kept = held {
                onAnchorChange(anchor(for: kept, size: measured))
            }
        }
        // Let go only once the anchor that replaced it has arrived, or there is a
        // frame in between drawn from the old anchor at the new size.
        .onChange(of: anchor) { _, _ in held = nil }
        .offset(x: CGFloat(position.x), y: CGFloat(position.y))
        .frame(maxWidth: .infinity, maxHeight: .infinity, alignment: .topLeading)
    }

    private func titleBar(at position: PanelPosition) -> some View {
        HStack(spacing: 8) {
            Image(systemName: "circle.grid.2x2.fill")
                .shuttlType(ShuttlType.labelSmall)
                .foregroundStyle(ShuttlVideoOverlay.text.opacity(0.6))
            Text("Labels")
                .shuttlType(ShuttlType.labelMedium)
                .foregroundStyle(ShuttlVideoOverlay.text)
            Spacer()
            Button {
                held = position
                collapsed.toggle()
            } label: {
                Image(systemName: collapsed ? "chevron.down" : "chevron.up")
                    .shuttlType(ShuttlType.labelMedium)
                    .foregroundStyle(ShuttlVideoOverlay.text)
                    .frame(width: 40, height: 40)
                    .contentShape(Rectangle())
            }
            .buttonStyle(.plain)
            .accessibilityLabel(collapsed ? "Show labels" : "Hide labels")
        }
        .padding(.leading, 12)
        .contentShape(Rectangle())
        .gesture(
            // In the area's own coordinates, not the window's: the window moves
            // under the finger, and a translation measured against a moving view
            // feeds back into itself.
            DragGesture(minimumDistance: 4, coordinateSpace: .named(PanelMetrics.areaSpace))
                .onChanged { value in
                    let origin = dragOrigin ?? position
                    dragOrigin = origin
                    dragged = clamped(PanelPosition(
                        x: origin.x + Float(value.translation.width),
                        y: origin.y + Float(value.translation.height)
                    ))
                }
                .onEnded { _ in
                    if let dragged { onAnchorChange(anchor(for: dragged, size: size)) }
                    dragged = nil
                    dragOrigin = nil
                }
        )
        .accessibilityElement(children: .contain)
        .accessibilityLabel("Labels window. Drag to move.")
    }
}

enum PanelMetrics {
    static let width: CGFloat = 236
    /// Four rows of compact chips and the gaps between them, so the cut falls
    /// between rows rather than through one.
    static let maxChipsHeight: CGFloat = 122
    /// The video card's chips: their 12pt inset, their height, and 8pt of air.
    static let chipClearance: CGFloat = 44
    /// The fullscreen transport: its 24pt bottom inset, the 60pt play circle, and
    /// 12pt of air.
    static let transportClearance: CGFloat = 96
    static let areaSpace = "replay-label-area"
}
