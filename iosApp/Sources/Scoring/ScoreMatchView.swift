import SwiftUI
import Shared

/// One scored match, folded from the store's flow so a rename or a point scored
/// elsewhere redraws this page without a refresh.
@Observable @MainActor
final class ScoreMatchModel {
    let rally: RallyApp
    let scoreLogId: String
    /// Nil when the match is not on this device - removed from the list while this
    /// page was open, or a cold start before the cache has loaded.
    private(set) var log: ScoreLog? = nil
    private(set) var match: MatchState? = nil
    private(set) var card: ScoreMatchCard? = nil

    init(rally: RallyApp, scoreLogId: String) {
        self.rally = rally
        self.scoreLogId = scoreLogId
    }

    func start() async {
        for await logs in rally.scoreLogs.logs {
            let found = logs.first { $0.id == scoreLogId }
            log = found
            match = found?.state()
            card = found.map { ScoreMatchCardKt.buildScoreMatchCard(log: $0) }
        }
    }
}

struct ScoreMatchView: View {
    let rally: RallyApp
    let scoreLogId: String
    @State private var model: ScoreMatchModel?

    var body: some View {
        Group {
            if let model, let log = model.log {
                content(model, log)
            } else if model?.log == nil && model != nil {
                // Removed from the list while this page was open.
                Text("This match is no longer on this phone.")
                    .foregroundStyle(Shuttl.textSecondary)
            } else {
                SplashView()
            }
        }
        .navigationTitle(model?.log?.title ?? "Match")
        .navigationBarTitleDisplayMode(.inline)
        .task {
            let m = ScoreMatchModel(rally: rally, scoreLogId: scoreLogId)
            model = m
            await m.start()
        }
    }

    @ViewBuilder
    private func content(_ model: ScoreMatchModel, _ log: ScoreLog) -> some View {
        let points = model.match?.points ?? []
        List {
            Section {
                VStack(alignment: .leading, spacing: 4) {
                    Text(model.card?.scoreLine ?? "")
                        .font(.system(size: 28, weight: .semibold))
                        .foregroundStyle(Shuttl.text)
                    Text(model.card?.playersLine ?? "")
                        .font(.body)
                        .foregroundStyle(Shuttl.text)
                    Text("\(model.card?.statusLine ?? "") · \(rulesSummary(log.rules))")
                        .font(.footnote)
                        .foregroundStyle(Shuttl.textSecondary)
                }
                .padding(.vertical, 4)
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
                        pointRow(point, log: log)
                    }
                }
            }
        }
        .listStyle(.plain)
    }

    @ViewBuilder
    private func pointRow(_ point: ScoredPoint, log: ScoreLog) -> some View {
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
