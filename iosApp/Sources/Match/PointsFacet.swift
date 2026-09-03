import SwiftUI
import Shared

/// The points half of a match: the score header, the tag tally and one row per
/// point, newest first. Lifted from `ScoreMatchView` unchanged: same header, same
/// tally gate, same newest-first point list. The Score/Resume button is omitted
/// rather than disabled once there is nothing left to score - undo lives on the
/// board itself, not on this page.
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
                    .font(.system(size: 28, weight: .semibold))
                    .foregroundStyle(Shuttl.text)
                Text(card?.playersLine ?? "")
                    .font(.body)
                    .foregroundStyle(Shuttl.text)
                Text("\(card?.statusLine ?? "") · \(rulesSummary(log.rules))")
                    .font(.footnote)
                    .foregroundStyle(Shuttl.textSecondary)
                // Absent rather than disabled on a finished match: there is
                // nothing left to score, and undo lives on the board itself.
                if log.status == .live {
                    Button(action: onScore) {
                        Text(points.isEmpty ? "Score" : "Resume scoring")
                            .font(.headline)
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
            .padding(.vertical, 4)
        }

        // How the match was tagged courtside, in the rally page's visual language
        // so the two summaries read as the same thing.
        if !tally.isEmpty {
            Section {
                VStack(alignment: .leading, spacing: 8) {
                    Text(tally.taggedPointCount == 1
                         ? "1 rally tagged"
                         : "\(tally.taggedPointCount) rallies tagged")
                        .font(.subheadline.weight(.semibold))
                        .foregroundStyle(Shuttl.text)
                    HStack(spacing: 12) {
                        ForEach(tally.labels, id: \.name) { label in
                            HStack(spacing: 4) {
                                LabelBadge(name: label.name, colorKey: label.colorKey)
                                Text("\(label.count)")
                                    .font(.caption)
                                    .foregroundStyle(Shuttl.textSecondary)
                            }
                        }
                    }
                }
                .padding(.vertical, 4)
            }
        }

        if points.isEmpty {
            Section {
                Text("No points scored yet.")
                    .foregroundStyle(Shuttl.textSecondary)
            }
        } else {
            // Newest first: courtside, the rally you care about is the last one.
            Section {
                ForEach(points.reversed(), id: \.ordinal) { point in
                    pointRow(point)
                }
            }
        }
    }

    @ViewBuilder
    private func pointRow(_ point: ScoredPoint) -> some View {
        VStack(alignment: .leading, spacing: 6) {
            HStack(spacing: 12) {
                Text("\(point.scoreAfter.home)-\(point.scoreAfter.away)")
                    .font(.body.weight(.medium))
                    .foregroundStyle(Shuttl.text)
                Text(point.wonBy == .home
                     ? ScoreMatchCardKt.sideLabel(players: log.homePlayers)
                     : ScoreMatchCardKt.sideLabel(players: log.awayPlayers))
                    .font(.body)
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
                Text(comment).font(.footnote).foregroundStyle(Shuttl.textSecondary)
            }
        }
        .padding(.vertical, 2)
    }

    /// "21 points, best of 3". Rules are shared; this sentence is iOS copy.
    private func rulesSummary(_ rules: ScoringRules) -> String {
        let games = rules.gamesToWin == 1 ? "single game" : "best of \(rules.gamesToWin * 2 - 1)"
        return "\(rules.pointsToWin) points, \(games)"
    }
}
