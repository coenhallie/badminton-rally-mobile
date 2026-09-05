import Shared
import SwiftUI

/// The coach's Analytics list: one section per group mirroring the drawer's own
/// grouping (local videos, owned matches, shared), so the same match shows up in
/// the same place on both screens. Port of Android's `AnalyticsScreen`.
///
/// Nothing here navigates on tap. Only a READY row opens anything on Android, and
/// iOS cannot produce READY until a track store exists, so an iOS detail screen
/// would have no entry point at all; making the other two states tappable to
/// compensate would just be a tap that leads somewhere unrelated.
struct AnalyticsListView: View {
    let rally: RallyApp
    let analyze: AnalyzeCoordinator
    /// Created and owned by `HomeView`, not here. `ClipListModel.start()` spawns
    /// `for await` loops that never return and hold the model alive, so a model
    /// created inside a pushed view would leak one full set of them on every
    /// push. Home makes exactly one, and `start()` is idempotent, so calling it
    /// from this view's own `.task` costs a coach who never opens Analytics
    /// nothing.
    let model: ClipListModel

    @State private var progressById: [String: AnalyzeProgress] = [:]
    /// Registered on this view rather than on Home. Two
    /// `navigationDestination(item:)` declared at the same depth of one stack
    /// replace each other: court marking driven from Home's binding would swap
    /// this screen out instead of stacking on it, and dismissing it would land
    /// the coach back on Home rather than on the list he was working through.
    /// Android settled the same question the same way.
    @State private var courtMarkingRoute: CourtMarkingRoute? = nil

    var body: some View {
        let rows = buildAnalyticsRows(
            localEntries: model.localEntries,
            ownedRows: model.ownedRows,
            sharedMatches: model.shared,
            progressByEntryId: progressById
        )
        let legend = analyticsLegend(for: rows)

        return Group {
            if rows.isEmpty {
                VStack {
                    Text("No matches yet.")
                        .shuttlType(ShuttlType.bodyMedium)
                        .foregroundStyle(Shuttl.textSecondary)
                }
                .frame(maxWidth: .infinity, maxHeight: .infinity)
                .background(Shuttl.bg)
            } else {
                list(rows: rows, legend: legend)
            }
        }
        .navigationTitle("ANALYTICS")
        .navigationBarTitleDisplayMode(.inline)
        .task { await model.start() }
        .task {
            for await map in analyze.progress {
                progressById = map
            }
        }
        .navigationDestination(item: $courtMarkingRoute) { route in
            CourtMarkingView(rally: rally, analyze: analyze, entryId: route.entryId)
        }
    }

    @ViewBuilder
    private func list(rows: [AnalyticsRow], legend: AnalyticsLegend) -> some View {
        List {
            if let text = legend.text {
                legendRow(text, showsDot: legend == .dot)
            }
            ForEach(AnalyticsGroup.allCases, id: \.self) { group in
                let groupRows = rows.filter { $0.group == group }
                if !groupRows.isEmpty {
                    Section {
                        ForEach(groupRows) { row in
                            rowView(row, showNotOnDeviceSubtitle: legend != .nothingOnThisPhone)
                                .listRowBackground(Shuttl.bg)
                        }
                    } header: { Shuttl.sectionLabel(group.label) }
                }
            }
        }
        .listStyle(.plain)
        // The list paints its own surface, so the token has to be asked for
        // twice: once for the scroll view behind the rows and once per row.
        .scrollContentBackground(.hidden)
        .background(Shuttl.bg)
    }

    @ViewBuilder
    private func legendRow(_ text: String, showsDot: Bool) -> some View {
        HStack(spacing: 8) {
            if showsDot {
                Circle().fill(Shuttl.accent).frame(width: 8, height: 8)
            }
            Text(text)
                .shuttlType(ShuttlType.bodySmall)
                .foregroundStyle(Shuttl.textTertiary)
        }
        .listRowBackground(Shuttl.bg)
        .listRowSeparator(.hidden)
    }

    @ViewBuilder
    private func rowView(_ row: AnalyticsRow, showNotOnDeviceSubtitle: Bool) -> some View {
        HStack(spacing: 12) {
            // The slot is reserved in every row, dot or no dot, so the titles of
            // a mixed list line up with each other rather than stepping in and
            // out by 20pt.
            Circle()
                .fill(Shuttl.accent)
                .frame(width: 8, height: 8)
                .opacity(row.state == .ready ? 1 : 0)

            VStack(alignment: .leading, spacing: 4) {
                Text(row.title)
                    .shuttlType(ShuttlType.titleMedium)
                    .foregroundStyle(Shuttl.text)
                    .lineLimit(1)
                Text(row.subtitle)
                    .shuttlType(ShuttlType.labelSmall)
                    .foregroundStyle(Shuttl.textSecondary)
                    .lineLimit(1)
                if row.state == .notOnDevice && showNotOnDeviceSubtitle {
                    Text("Not on this phone")
                        .shuttlType(ShuttlType.bodySmall)
                        .foregroundStyle(Shuttl.textTertiary)
                }
                switch row.affordance {
                case .ready:
                    EmptyView()
                case .inProgress(let phase):
                    Text(phase)
                        .shuttlType(ShuttlType.bodySmall)
                        .foregroundStyle(Shuttl.textSecondary)
                        .lineLimit(1)
                case .failed(let reason):
                    Text(reason)
                        .shuttlType(ShuttlType.bodySmall)
                        .foregroundStyle(Shuttl.error)
                        .lineLimit(2)
                }
            }

            Spacer()

            if row.state == .analysable {
                trailingControl(row)
            }
        }
        .padding(.vertical, 6)
    }

    @ViewBuilder
    private func trailingControl(_ row: AnalyticsRow) -> some View {
        switch row.affordance {
        case .ready:
            analysePill("Analyze", row: row)
        case .failed:
            // "Retry", not the drawer's "Re-analyze": this row already carries
            // the failure reason on the line above, exactly as a scored match's
            // row does, and that row says "Retry" too.
            analysePill("Retry", row: row)
        case .inProgress:
            // The pill gives way to a spinner while a run is in flight, which is
            // what the drawer's own local video row does in the same situation.
            ProgressView()
                .controlSize(.small)
        }
    }

    /// Same pill as the drawer's local video row, down to the padding: the two
    /// screens list the same videos and their one action must not look like two.
    private func analysePill(_ label: String, row: AnalyticsRow) -> some View {
        Button(label) { analyse(row) }
            .shuttlType(ShuttlType.labelMedium)
            .foregroundStyle(Shuttl.onAccent)
            .lineLimit(1)
            .fixedSize(horizontal: true, vertical: false)
            .layoutPriority(1)
            .padding(.horizontal, 12)
            .padding(.vertical, 6)
            .background(Shuttl.accent)
            .clipShape(Capsule())
            .buttonStyle(.borderless)
    }

    private func analyse(_ row: AnalyticsRow) {
        guard let entryId = row.entryId,
              let entry = model.localEntries.first(where: { $0.id == entryId }) else { return }
        switch analyseAction(for: entry) {
        case .resume(let id):
            analyze.retry(entryId: id)
        case .markCourt(let id):
            courtMarkingRoute = CourtMarkingRoute(entryId: id)
        }
    }
}
