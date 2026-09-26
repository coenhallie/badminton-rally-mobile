import SwiftUI
import Shared

/// The match page's Points section: the score header, the tag tally and one row
/// per point, newest first. Lifted from `ScoreMatchView`: same header, same tally
/// gate, same newest-first point list. The Score/Resume button is omitted rather
/// than disabled once there is nothing left to score - undo lives on the board
/// itself, not on this page.
///
/// Every row sits in the page's 24pt gutter, as every other section does: at the
/// list's own inset, switching from Points to Match moved the page's left edge.
/// The vertical insets are androidApp's `PointsFacet`'s, the header's top being
/// `MatchPageLayout.rowInset` like a rally row's, which the page's gap under the
/// section row counts on.
struct PointsFacet: View {
    let log: ScoreLog
    let card: ScoreMatchCard?
    let tally: ScoreTagSummary
    let points: [ScoredPoint]
    /// `MatchView` pushes the board itself now, rather than this being a bare
    /// `NavigationLink(value:)` resolved by an ambient destination - the match
    /// page it lands back on after a finish must be this exact instance, not a
    /// freshly pushed one, and only the pusher can guarantee that.
    let onScore: () -> Void

    var body: some View {
        Section {
            VStack(alignment: .leading, spacing: 4) {
                Text(card?.scoreLine ?? "")
                    .shuttlType(ShuttlType.headlineMedium)
                    .foregroundStyle(Shuttl.text)
                // The legend for the score above it: which number is whose. Always
                // drawn, unlike the list row's players line, so it falls back to
                // Home vs Away.
                Text("\(log.sideName(side: .home)) vs \(log.sideName(side: .away))")
                    .shuttlType(ShuttlType.bodyLarge)
                    .foregroundStyle(Shuttl.text)
                Text([card?.statusLine, log.format?.label, rulesSummary(log.rules)].compactMap { $0 }.joined(separator: " · "))
                    .shuttlType(ShuttlType.bodySmall)
                    .foregroundStyle(Shuttl.textSecondary)
                // Absent rather than disabled on a finished match: there is
                // nothing left to score, and undo lives on the board itself.
                if log.status == .live {
                    Button(action: onScore) {
                        Text(points.isEmpty ? "Score" : "Resume scoring")
                            .shuttlType(ShuttlType.titleLarge)
                            .foregroundStyle(Shuttl.onAccent)
                            .padding(.horizontal, 20)
                            .padding(.vertical, 10)
                            .background(Shuttl.accent)
                            .clipShape(Capsule())
                    }
                    .buttonStyle(.plain)
                    .padding(.top, 8)
                }
            }
            .pointsListRow(top: MatchPageLayout.rowInset, bottom: 16)
        }

        // How the match was tagged courtside, in the rally page's visual language
        // so the two summaries read as the same thing.
        if !tally.isEmpty {
            Section {
                VStack(alignment: .leading, spacing: 8) {
                    Text(tally.taggedPointCount == 1
                         ? "1 rally tagged"
                         : "\(tally.taggedPointCount) rallies tagged")
                        .shuttlType(ShuttlType.titleMedium)
                        .foregroundStyle(Shuttl.text)
                    HStack(spacing: 12) {
                        ForEach(tally.labels, id: \.name) { label in
                            HStack(spacing: 4) {
                                LabelBadge(name: label.name, colorKey: label.colorKey)
                                Text("\(label.count)")
                                    .shuttlType(ShuttlType.bodySmall)
                                    .foregroundStyle(Shuttl.textSecondary)
                            }
                        }
                    }
                }
                .pointsListRow(top: 12, bottom: 12)
            }
        }

        if points.isEmpty {
            // No action of its own: the Score button in the header above is
            // the one that fills this list.
            Section {
                ShuttlEmptyState(
                    systemImage: "list.number",
                    title: "No points yet",
                    message: "Points are listed here as you score the match."
                ) { EmptyView() }
                .listRowInsets(EdgeInsets())
                .listRowSeparator(.hidden)
                .listSectionSeparator(.hidden, edges: .top)
                .listRowBackground(Color.clear)
            }
        } else {
            // Newest first: courtside, the rally you care about is the last one.
            Section {
                ForEach(points.reversed(), id: \.ordinal) { point in
                    pointRow(point)
                        .pointsListRow(top: 10, bottom: 10)
                }
            }
        }
    }

    @ViewBuilder
    private func pointRow(_ point: ScoredPoint) -> some View {
        VStack(alignment: .leading, spacing: 6) {
            HStack(spacing: 12) {
                Text("\(point.scoreAfter.home)-\(point.scoreAfter.away)")
                    .shuttlType(ShuttlType.titleMedium)
                    .foregroundStyle(Shuttl.text)
                Text(log.sideName(side: point.wonBy))
                    .shuttlType(ShuttlType.bodyLarge)
                    .foregroundStyle(Shuttl.textSecondary)
            }
            if !point.tags.isEmpty {
                HStack(spacing: 6) {
                    ForEach(point.tags, id: \.labelName) { tag in
                        LabelBadge(name: tag.labelName, colorKey: tag.labelColor)
                    }
                }
            }
            if let comment = point.comment, !comment.trimmingCharacters(in: .whitespaces).isEmpty {
                Text(comment).shuttlType(ShuttlType.bodySmall).foregroundStyle(Shuttl.textSecondary)
            }
        }
        .frame(maxWidth: .infinity, alignment: .leading)
    }

    /// "21 points, best of 3". Rules are shared; this sentence is iOS copy.
    private func rulesSummary(_ rules: ScoringRules) -> String {
        let games = rules.gamesToWin == 1 ? "single game" : "best of \(rules.gamesToWin * 2 - 1)"
        return "\(rules.pointsToWin) points, \(games)"
    }
}

private extension View {
    /// A Points row's list chrome: the page's gutter either side, the given
    /// insets above and below, a separator in the border token rather than the
    /// system grey, no rule above a section's first row, and no fill of its
    /// own, as `.rallyListRow()` does for a rally.
    func pointsListRow(top: CGFloat, bottom: CGFloat) -> some View {
        listRowInsets(EdgeInsets(top: top, leading: ShuttlGutter.page, bottom: bottom, trailing: ShuttlGutter.page))
            .listRowSeparatorTint(Shuttl.border)
            .listSectionSeparator(.hidden, edges: .top)
            .listRowBackground(Color.clear)
    }
}
