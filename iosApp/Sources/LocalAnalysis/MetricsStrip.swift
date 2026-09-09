import Shared
import SwiftUI

/// The per-frame measurements under the skeleton video, as stat tiles.
///
/// Two layouts of the same tiles. Collapsed, they are pages of four, two rows of
/// two, swiped a page at a time, so the video, the tiles and the graph fit a
/// phone together and a coach watching one number sees it and the frame at once.
/// Expanded, they are the whole two-column grid, with the racket-arm control,
/// the caption that says once what every angle tile means, and the file's own
/// facts, none of which is read per frame. Either way every measurement is
/// present and a tap selects it for the graph and the overlay; absent is a dash
/// so the layout never jumps and a stale number is never left on screen.
///
/// Port of androidApp's `MetricsStrip`.
struct MetricsStrip: View {
    let metrics: PoseMetrics?
    let hasCourt: Bool
    let racketArm: RacketArm?
    let onRacketArm: (RacketArm?) -> Void
    let selected: MetricKind
    let onSelect: (MetricKind) -> Void
    let expanded: Bool
    let onExpanded: (Bool) -> Void
    let detail: String

    /// Which page the collapsed pager is on. Held here rather than derived from
    /// `selected`, so a swipe to a page is not undone by the tile that stays
    /// selected on the page it came from.
    @State private var page = 0

    private var kinds: [MetricKind] {
        MetricsFormatKt.visibleKinds(hasCourt: hasCourt, racketArm: racketArm)
    }

    var body: some View {
        if expanded { grid } else { pager }
    }

    // MARK: - Collapsed

    private var pages: [[MetricKind]] {
        stride(from: 0, to: kinds.count, by: Metrics.tilesPerPage).map {
            Array(kinds[$0..<min($0 + Metrics.tilesPerPage, kinds.count)])
        }
    }

    private var pager: some View {
        let pages = self.pages
        return VStack(spacing: 0) {
            TabView(selection: $page) {
                ForEach(Array(pages.enumerated()), id: \.offset) { index, kinds in
                    tilePage(kinds).tag(index)
                }
            }
            .tabViewStyle(.page(indexDisplayMode: .never))
            .frame(height: Metrics.pageHeight)
            .padding(.horizontal, ShuttlGutter.page)
            // The dots say there is more, which the mock's single grid never had
            // to; the chevron opens the whole grid. One row for both, so the
            // strip costs the graph under it as little height as it can.
            HStack {
                Spacer()
                if pages.count > 1 { PageDots(count: pages.count, current: page) }
                Spacer()
            }
            .overlay(alignment: .trailing) {
                Button { onExpanded(true) } label: {
                    Image(systemName: "chevron.down")
                        .foregroundStyle(Shuttl.textSecondary)
                        .frame(width: 44, height: 44)
                }
                .buttonStyle(.borderless)
                .accessibilityLabel("Show all measurements")
            }
            .padding(.horizontal, ShuttlGutter.page)
        }
        // Turn to the selected tile's page when something other than a tap on it
        // selected it - the racket-arm control hiding the chosen kind, or a
        // saved choice on opening - and leave the pager alone otherwise, since a
        // page that turns under a tap is a page that cannot be aimed at.
        .onChange(of: selected) { _, _ in turnToSelected() }
        .onChange(of: kinds) { _, _ in turnToSelected() }
        .onAppear { turnToSelected() }
    }

    /// One page of the collapsed strip: two rows of two tiles, always two rows.
    ///
    /// A short last page keeps its empty slots rather than collapsing, because
    /// the pager takes one height for every page: a one-tile page would be half
    /// as tall, and the graph under it would jump up on the swipe to it and back
    /// down on the swipe away.
    private func tilePage(_ pageKinds: [MetricKind]) -> some View {
        VStack(spacing: 8) {
            ForEach(0..<(Metrics.tilesPerPage / 2), id: \.self) { row in
                HStack(spacing: 8) {
                    ForEach(0..<2, id: \.self) { column in
                        let slot = row * 2 + column
                        if slot < pageKinds.count {
                            tile(pageKinds[slot])
                        } else {
                            Color.clear.frame(maxWidth: .infinity)
                        }
                    }
                }
            }
        }
    }

    // MARK: - Expanded

    private var grid: some View {
        VStack(alignment: .leading, spacing: 0) {
            HStack {
                Text("All measurements")
                    .shuttlType(ShuttlType.bodySmall)
                    .foregroundStyle(Shuttl.textTertiary)
                Spacer()
                Button { onExpanded(false) } label: {
                    Image(systemName: "chevron.up")
                        .foregroundStyle(Shuttl.textSecondary)
                        .frame(width: 44, height: 44, alignment: .trailing)
                }
                .buttonStyle(.borderless)
                .accessibilityLabel("Show one row of measurements")
            }
            // Two to a row, each taking half, as the mock lays them out. An odd
            // last tile gets an empty partner so it keeps its half rather than
            // stretching across the whole row and reading as a different kind of
            // tile.
            let rows = stride(from: 0, to: kinds.count, by: 2).map { Array(kinds[$0..<min($0 + 2, kinds.count)]) }
            VStack(spacing: 8) {
                ForEach(Array(rows.enumerated()), id: \.offset) { _, row in
                    HStack(spacing: 8) {
                        ForEach(row, id: \.self) { tile($0) }
                        if row.count == 1 { Color.clear.frame(maxWidth: .infinity) }
                    }
                }
            }
            // The caption and the control get a row each. Side by side they fit
            // a phone only by touching: the caption ends on the pixel the first
            // segment starts.
            Text("Angles as seen by the camera")
                .shuttlType(ShuttlType.bodySmall)
                .foregroundStyle(Shuttl.textTertiary)
                .padding(.top, 16)
            ShuttlPillTabs(
                labels: Metrics.racketArms.map(\.label),
                selectedIndex: Metrics.racketArms.firstIndex { $0.arm == racketArm } ?? 0,
                onSelect: { onRacketArm(Metrics.racketArms[$0].arm) }
            )
            .padding(.top, 8)
            Text(detail)
                .shuttlType(ShuttlType.bodySmall)
                .foregroundStyle(Shuttl.textTertiary)
                .padding(.top, 12)
        }
        .frame(maxWidth: .infinity, alignment: .leading)
        .padding(.horizontal, ShuttlGutter.page)
    }

    // MARK: - Pieces

    private func tile(_ kind: MetricKind) -> some View {
        let text = MetricsFormatKt.metricText(kind: kind, value: metrics.flatMap { kind.of(m: $0) })
        return ShuttlStatTile(
            value: text.value,
            unit: text.unit,
            label: MetricsFormatKt.metricLabel(kind: kind, racketArm: racketArm),
            labelAbove: true,
            selected: kind == selected,
            onTap: { onSelect(kind) }
        )
    }

    private func turnToSelected() {
        guard let index = kinds.firstIndex(of: selected) else { return }
        let target = index / Metrics.tilesPerPage
        if page != target { withAnimation { page = target } }
    }

    private enum Metrics {
        /// Tiles per page of the collapsed strip: two rows of two.
        static let tilesPerPage = 4
        /// Two tiles and the gap between them. `TabView` will not size itself to
        /// its pages, so the height is stated; it is the same on every page
        /// because a short page keeps its empty slots.
        static let pageHeight: CGFloat = 176
        static let racketArms: [(arm: RacketArm?, label: String)] = [
            (.left, "Left arm"), (.right, "Right arm"), (nil, "Both"),
        ]
    }
}

/// One dot per page, the current one in the accent.
private struct PageDots: View {
    let count: Int
    let current: Int

    var body: some View {
        HStack(spacing: 6) {
            ForEach(0..<count, id: \.self) { index in
                Circle()
                    .fill(index == current ? Shuttl.accent : Shuttl.borderSecondary)
                    .frame(width: 6, height: 6)
            }
        }
        .accessibilityHidden(true)
    }
}
