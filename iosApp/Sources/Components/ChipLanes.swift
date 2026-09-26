import Shared
import SwiftUI

/// Chips dealt into at most two lanes by the shared `LabelLanes`, for a strip
/// that scrolls sideways once the wider lane outgrows the row. Where the lanes
/// break is the shared code's decision, so the replay page's label strip is the
/// same strip on both platforms.
///
/// `ChipFlow` wraps to as many lines as it needs and is the right layout when the
/// container should grow; this one is for a strip whose height the page must be
/// able to plan around.
struct ChipLanes: Layout {
    var spacing: CGFloat = 8
    /// The width the strip has before it scrolls. A horizontal `ScrollView`
    /// proposes no width, so the host measures it and passes it in.
    let available: CGFloat

    func sizeThatFits(proposal: ProposedViewSize, subviews: Subviews, cache: inout ()) -> CGSize {
        let fit = fit(subviews)
        let laneHeight = subviews.map { $0.sizeThatFits(.unspecified).height }.max() ?? 0
        let lanes = CGFloat(fit.lanes)
        return CGSize(
            // At least the row: centred under the transport while it fits.
            width: max(CGFloat(fit.width), available),
            height: lanes * laneHeight + max(lanes - 1, 0) * spacing
        )
    }

    func placeSubviews(
        in bounds: CGRect,
        proposal: ProposedViewSize,
        subviews: Subviews,
        cache: inout ()
    ) {
        let fit = fit(subviews)
        let laneHeight = subviews.map { $0.sizeThatFits(.unspecified).height }.max() ?? 0
        let lead = bounds.minX + max(0, (bounds.width - CGFloat(fit.width)) / 2)
        for (index, placement) in fit.placements.enumerated() {
            let size = subviews[index].sizeThatFits(.unspecified)
            subviews[index].place(
                at: CGPoint(
                    x: lead + CGFloat(placement.x),
                    y: bounds.minY + CGFloat(placement.lane) * (laneHeight + spacing)
                ),
                anchor: .topLeading,
                proposal: ProposedViewSize(size)
            )
        }
    }

    private func fit(_ subviews: Subviews) -> LaneFit {
        LabelLanes.shared.fit(
            widths: subviews.map { KotlinFloat(float: Float($0.sizeThatFits(.unspecified).width)) },
            spacing: Float(spacing),
            availableWidth: Float(available)
        )
    }
}
