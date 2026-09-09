import Shared
import SwiftUI

/// Where the player played from, rally by rally, on the court.
/// Port of Android's `BasePositionPanel`.
///
/// The median position of each rally, which the 2026-09-08 research found to be
/// the most trustworthy number the stored track supports: two pose models agree
/// on it to a centimetre or two. Drawn on the same court as the heatmap so the
/// two read together, then said in words against the two lines the near player
/// plays around.
///
/// The rally windows are the stored clips', which carry two seconds of pre-roll
/// and one and a half of post-roll around the detected rally. A median does not
/// notice: the player is standing at or near their base during both.
///
/// The track and the windows are resolved by `AnalyticsDetailView` and handed
/// in, rather than read here. On Android each panel resolves its own because
/// `remember` keys them to the entry; SwiftUI has no such key, and a panel
/// re-reading a megabyte of track on every redraw is the per-row file cost
/// `storedTrackIds` exists to keep off the Analytics list. It also means the
/// tab set and the panel behind it cannot disagree about which run they show.
struct BasePositionPanel: View {
    let source: HeatmapSource?
    let windows: [RallyWindow]

    /// The medians, held rather than computed in the body.
    ///
    /// `basePositions` sorts every sample in the match and then scans that sort
    /// once per rally - a couple of million comparisons on a long match. That is
    /// fine once per opening and wrong on every redraw, which is what a computed
    /// property would make it. `.task(id:)` below is androidApp's
    /// `remember(source, windows)`.
    @State private var bases: BasePositions? = nil
    /// Whether `bases` has been resolved at all, so an empty result and a result
    /// still being computed do not read the same.
    @State private var resolved = false

    var body: some View {
        Group {
            if let message {
                PanelMessage(text: message)
            } else if let bases {
                content(bases)
            } else {
                // The one frame between the panel appearing and the medians
                // landing. Deliberately blank rather than a spinner: the work is
                // milliseconds, and a spinner that flashes for one frame is more
                // noticeable than nothing at all.
                Color.clear.frame(height: 0)
            }
        }
        // Off the main actor, and awaited: `measure` is the couple of million
        // comparisons the property above describes, and a `.task` body with no
        // suspension point in it runs to completion on the main thread - which
        // blocked every open of this tab, and made the blank branch above
        // unreachable rather than one frame long. `SkeletonPanelModel.loadSkeleton`
        // reads its track the same way.
        .task(id: inputs) {
            let track = source
            let rallies = windows
            let measured = await Task.detached(priority: .userInitiated) {
                BasePositionPanel.measure(source: track, windows: rallies)
            }.value
            bases = measured
            resolved = true
        }
    }

    @ViewBuilder
    private func content(_ bases: BasePositions) -> some View {
        VStack(alignment: .leading, spacing: 0) {
            CourtBaseView(bases: bases)
            if let overall = bases.overall {
                Text("Whole match: \(BasePositionFormatKt.describeBase(p: overall))")
                    .shuttlType(ShuttlType.bodyMedium)
                    .foregroundStyle(Shuttl.text)
                    .padding(.horizontal, ShuttlGutter.page)
                    .padding(.top, 18)
            }
            Text(
                "The median position in each rally, from the ankles and the court marks. "
                    + "Left and right are the player's own, facing the net."
            )
            .shuttlType(ShuttlType.bodySmall)
            .foregroundStyle(Shuttl.textTertiary)
            .padding(.horizontal, ShuttlGutter.page)
            .padding(.vertical, 8)
            ForEach(bases.rallies, id: \.index) { rally in
                Text(BasePositionFormatKt.describeRally(base: rally))
                    .shuttlType(ShuttlType.bodySmall)
                    .foregroundStyle(Shuttl.textSecondary)
                    .padding(.horizontal, ShuttlGutter.page)
                    .padding(.vertical, 4)
            }
            Spacer(minLength: ShuttlGutter.page)
        }
        .frame(maxWidth: .infinity, alignment: .leading)
    }

    private var message: String? {
        // Held back until the medians land, so the last rung does not flash
        // "not found" over the frame before they do.
        baseMessage(source: source, windows: windows, bases: resolved ? bases : nil, measured: resolved)
    }

    /// What `bases` was measured from. Changing any of it re-measures; nothing
    /// else can.
    ///
    /// Flattened to numbers rather than holding the windows themselves: a
    /// `RallyWindow` is a Kotlin class reaching Swift through Objective-C, and
    /// keying a `.task` on its identity would tie a redraw to which side of the
    /// bridge the object came from.
    private struct Inputs: Equatable {
        let samples: Int
        let fps: Double
        let bounds: [Double]
    }

    private var inputs: Inputs {
        Inputs(
            samples: source?.track.samples.count ?? 0,
            fps: source?.fps ?? 0,
            bounds: windows.flatMap { [Double($0.index), $0.startSeconds, $0.endSeconds] }
        )
    }

    /// Static so the detached task above carries the two values it needs rather
    /// than the view, and `nonisolated` because a `View` is main-actor-isolated
    /// and everything it declares inherits that - which would put this straight
    /// back on the thread the task exists to keep it off (an error outright
    /// under the Swift 6 language mode).
    private nonisolated static func measure(source: HeatmapSource?, windows: [RallyWindow]) -> BasePositions? {
        guard let source else { return nil }
        return BasePositionKt.basePositions(
            track: source.track,
            fps: source.fps,
            windows: windows,
            minSeconds: BasePositionKt.MIN_RALLY_SECONDS
        )
    }
}

/// Why the base panel has nothing to draw, or nil when it has.
///
/// Four answers, kept apart because they ask four different things of the coach:
/// run it again, mark the court again, analyse for pose, or accept that this
/// match does not support the number. Android's `BasePositionPanel` has the same
/// ladder in the same order.
///
/// [measured] is whether [bases] has been computed at all. Without it a panel
/// one frame from its answer says the player was never found, which is a claim
/// about the match rather than about the frame.
func baseMessage(
    source: HeatmapSource?,
    windows: [RallyWindow],
    bases: BasePositions?,
    measured: Bool
) -> String? {
    guard let source else {
        return "This analysis is no longer loaded. Run it again to see where the player stood."
    }
    if source.track.samples.isEmpty { return whyEmpty(source.track) }
    if !windows.contains(where: { $0.isBounded }) {
        return "The rally windows for this analysis were not kept, so there is nothing to measure "
            + "per rally. Run the analysis again."
    }
    if measured, bases?.rallies.isEmpty ?? true {
        return "The player was not found for long enough in any rally to say where they stood."
    }
    return nil
}

/// The court with one dot per rally and a ring for the whole match.
///
/// Dots for rallies with bases centimetres apart land on top of each other,
/// and that is the picture: a player whose base does not move draws one spot.
private struct CourtBaseView: View {
    let bases: BasePositions

    /// Big enough to find on a 300pt court, small enough that two rallies a
    /// stride apart are still two dots.
    private static let dotRadius: CGFloat = 5
    /// The whole-match ring, drawn around the dots rather than over them.
    private static let ringRadius: CGFloat = 10
    private static let ringLineWidth: CGFloat = 2.5
    /// Between a marker's edge and the number beside it.
    private static let labelGap: CGFloat = 2

    var body: some View {
        CourtCard {
            Canvas { context, size in
                CourtHeatmapView.drawCourt(context: &context, size: size, marginM: CourtLayout.marginM)
                let dot = GraphicsContext.Shading.color(Shuttl.accent.opacity(0.8))
                for rally in bases.rallies {
                    let centre = CourtLayout.point(rally.position, in: size)
                    context.fill(Self.circle(at: centre, radius: Self.dotRadius), with: dot)
                }
                if let overall = bases.overall {
                    let centre = CourtLayout.point(overall, in: size)
                    context.stroke(
                        Self.circle(at: centre, radius: Self.ringRadius),
                        with: GraphicsContext.Shading.color(Shuttl.accent),
                        lineWidth: Self.ringLineWidth
                    )
                }
                // Numbers last, and clear of both circles, so neither the
                // whole-match marker nor the dot itself sits on the one label
                // that says which rally a dot is. `rallyLabelX` owns where
                // "clear" is; Android draws the same picture through the same
                // function.
                let ringCentre = bases.overall.map { CourtLayout.point($0, in: size) }
                for rally in bases.rallies {
                    let centre = CourtLayout.point(rally.position, in: size)
                    let label = Text("\(rally.index)")
                        .shuttlCanvasType(ShuttlType.labelSmall)
                        .foregroundStyle(Shuttl.text)
                    let x = BasePositionFormatKt.rallyLabelX(
                        dotX: Float(centre.x),
                        dotY: Float(centre.y),
                        dotRadius: Float(Self.dotRadius),
                        gap: Float(Self.labelGap),
                        ringX: Float(ringCentre?.x ?? 0),
                        ringY: Float(ringCentre?.y ?? 0),
                        // The stroke straddles the radius, so the outer edge is
                        // half a line width past it.
                        ringReach: ringCentre == nil ? 0 : Float(Self.ringRadius + Self.ringLineWidth / 2)
                    )
                    context.draw(
                        context.resolve(label),
                        at: CGPoint(x: CGFloat(x), y: centre.y),
                        anchor: .leading
                    )
                }
            }
            .accessibilityLabel(Self.spoken(bases))
        }
    }

    /// The picture in words, because a canvas of dots says nothing to a screen
    /// reader and the sentence under it only covers the whole match.
    private static func spoken(_ bases: BasePositions) -> String {
        guard let overall = bases.overall else { return "Base positions" }
        return "Base position over \(bases.rallies.count) rallies. "
            + "Whole match: \(BasePositionFormatKt.describeBase(p: overall))"
    }

    private static func circle(at centre: CGPoint, radius: CGFloat) -> Path {
        Path(ellipseIn: CGRect(
            x: centre.x - radius, y: centre.y - radius,
            width: radius * 2, height: radius * 2
        ))
    }
}
