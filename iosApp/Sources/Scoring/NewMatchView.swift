import SwiftUI
import Shared

/// The match a coach creates before there is any video. Every rule it enforces
/// comes from `createMatchProblem`, which Android's form calls too - the two cannot
/// disagree about what a complete match is.
struct NewMatchView: View {
    let rally: RallyApp
    let onCreated: (String) -> Void

    @Environment(\.dismiss) private var dismiss
    @State private var title = ""
    @State private var doubles = false
    /// Always two per side; the second is ignored in singles rather than cleared,
    /// so toggling the format twice does not destroy what was typed.
    @State private var homePlayers = ["", ""]
    @State private var awayPlayers = ["", ""]
    @State private var rulesIndex = 0
    @State private var firstServer: Side = .home
    @State private var touched = false

    private var presets: [ScoringRules] { ScoringRules.companion.PRESETS }
    private var rules: ScoringRules { presets[rulesIndex] }

    private var submittedHome: [String] { Array(homePlayers.prefix(doubles ? 2 : 1)) }
    private var submittedAway: [String] { Array(awayPlayers.prefix(doubles ? 2 : 1)) }

    private var problem: String? {
        ScoreLogKt.createMatchProblem(
            title: title, homePlayers: submittedHome, awayPlayers: submittedAway, doubles: doubles
        )
    }

    var body: some View {
        Form {
            Section {
                TextField("Match name", text: $title)
                    .onChange(of: title) { touched = true }
            }

            Section("Format") {
                Picker("Format", selection: $doubles) {
                    Text("Singles").tag(false)
                    Text("Doubles").tag(true)
                }
                .pickerStyle(.segmented)
            }

            Section("Players") {
                TextField("Home player", text: $homePlayers[0]).onChange(of: homePlayers[0]) { touched = true }
                if doubles {
                    TextField("Home partner", text: $homePlayers[1])
                }
                TextField("Away player", text: $awayPlayers[0]).onChange(of: awayPlayers[0]) { touched = true }
                if doubles {
                    TextField("Away partner", text: $awayPlayers[1])
                }
            }

            Section("Scoring") {
                Picker("Scoring", selection: $rulesIndex) {
                    ForEach(presets.indices, id: \.self) { i in
                        Text(label(for: presets[i])).tag(i)
                    }
                }
                .pickerStyle(.segmented)
            }

            Section("Coin toss") {
                Picker("First serve", selection: $firstServer) {
                    Text("Home serves").tag(Side.home)
                    Text("Away serves").tag(Side.away)
                }
                .pickerStyle(.segmented)
            }

            if touched, let problem {
                Section {
                    // Shuttl.error rather than a literal red: it is the same
                    // token Android reaches for (colorScheme.error) for this line.
                    Text(problem).foregroundStyle(Shuttl.error).shuttlType(ShuttlType.bodySmall)
                }
            }
        }
        .navigationTitle("New match")
        .navigationBarTitleDisplayMode(.inline)
        .toolbar {
            // The chrome indicator, on every bar androidApp puts it on. See
            // `BackgroundWorkAction`.
            ToolbarItem(placement: .topBarTrailing) { BackgroundWorkAction() }
            ToolbarItem(placement: .topBarTrailing) {
                Button("Create") { create() }
                    .disabled(problem != nil)
            }
        }
    }

    private func create() {
        guard problem == nil else { return }
        let log = rally.scoreLogs.create(
            title: title,
            homePlayers: submittedHome,
            awayPlayers: submittedAway,
            rules: rules,
            setup: MatchSetup(
                doubles: doubles,
                firstServer: firstServer,
                homeStartsRight: .first,
                awayStartsRight: .first
            )
        )
        onCreated(log.id)
    }

    /// The presets are shared so both platforms offer the same rule sets; the
    /// wording is per-platform copy.
    private func label(for rules: ScoringRules) -> String {
        if rules.pointsToWin == 21 { return "21 points" }
        return rules.winBy == 1 ? "15 straight" : "15 points"
    }
}
