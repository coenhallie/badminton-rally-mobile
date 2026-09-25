import SwiftUI
import Shared

/// The match a coach creates before there is any video. It asks only for what the
/// fold cannot guess - how long a game is and how many sets - and the name arrives
/// filled in with the time and, where the phone will say, the place, so the form can
/// be submitted the moment it opens. Every rule it enforces comes from
/// `createMatchProblem`, which Android's form calls too.
struct NewMatchView: View {
    let rally: RallyApp
    let onCreated: (String) -> Void

    @Environment(\.dismiss) private var dismiss
    /// Read once: the name says when the form was opened, not when the place came back.
    @State private var openedAtMs: Int64
    @State private var title: String
    /// Once the user has typed a name it is theirs, and a place arriving late must
    /// not overwrite it. Also what holds the error back until anyone has typed.
    @State private var titleEdited = false
    @State private var points: GamePoints = .companion.DEFAULT
    @State private var sets: MatchSets = .companion.DEFAULT
    @State private var places = PlaceNames()

    init(rally: RallyApp, onCreated: @escaping (String) -> Void) {
        self.rally = rally
        self.onCreated = onCreated
        // Filled in here rather than in `.task`, so the form never draws a frame
        // with an empty name and a disabled Create.
        let now = Int64(Date().timeIntervalSince1970 * 1000)
        _openedAtMs = State(initialValue: now)
        _title = State(initialValue: DefaultMatchTitleKt.defaultMatchTitle(epochMs: now, place: nil))
    }

    private var problem: String? { ScoreLogKt.createMatchProblem(title: title) }

    /// Writes to `title` from the form itself, which `titleEdited` must not count.
    private var typedTitle: Binding<String> {
        Binding(get: { title }, set: { title = $0; titleEdited = true })
    }

    var body: some View {
        Form {
            Section {
                TextField("Match name", text: typedTitle)
            }

            Section("Points") {
                Picker("Points", selection: $points) {
                    ForEach(GamePoints.allCases, id: \.self) { Text("\($0.pointsToWin)").tag($0) }
                }
                .pickerStyle(.segmented)
            }

            Section("Sets") {
                Picker("Sets", selection: $sets) {
                    ForEach(MatchSets.allCases, id: \.self) { Text("\($0.sets)").tag($0) }
                }
                .pickerStyle(.segmented)
            }

            if titleEdited, let problem {
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
        .task {
            guard let place = await places.current(), !titleEdited else { return }
            title = DefaultMatchTitleKt.defaultMatchTitle(epochMs: openedAtMs, place: place)
        }
    }

    private func create() {
        guard problem == nil else { return }
        let log = rally.scoreLogs.create(
            title: title,
            rules: ScoringRules.companion.of(points: points, sets: sets),
            // Home, because the form no longer asks: the board lets it be switched
            // until the first point is scored.
            setup: MatchSetup(firstServer: .home)
        )
        onCreated(log.id)
    }
}
