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
    /// Registered on this view rather than on Home, so court marking stacks ON
    /// Analytics instead of replacing it.
    ///
    /// The rule is about PRESENTATION depth, not registration: Home declares
    /// seven `navigationDestination`s side by side and they all work. What
    /// cannot happen is two of them presenting at the same depth. Court marking
    /// driven from Home's binding would present at the depth Analytics already
    /// occupies, swapping it out and landing the coach back on Home rather than
    /// on the list he was working through. Declared here it presents one deeper.
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
                // No action here: matches are added on Home, and this screen's
                // only way there is its back button, which the body names.
                ShuttlEmptyState(
                    systemImage: "chart.bar",
                    title: "No matches yet",
                    message: "Add a match on Home and it will be listed here, ready to analyse."
                ) { EmptyView() }
                .background(Shuttl.bg)
            } else {
                list(rows: rows, legend: legend)
            }
        }
        .navigationTitle("ANALYTICS")
        .navigationBarTitleDisplayMode(.inline)
        .task {
            await model.start()
            // Android builds a fresh ClipListViewModel per entry to
            // Route.Analytics and its init refreshes, so opening Analytics there
            // always pulls. Here start() is latched after the first call, so
            // without this an entry made no network call at all: a cloud run that
            // finished, or a match a colleague shared, while the app was
            // backgrounded would simply not be on the list, and the only pull to
            // refresh in the app is the drawer's.
            await model.refresh()
        }
        .task {
            for await map in analyze.progress {
                progressById = map
            }
        }
        .refreshable { await model.refresh() }
        .navigationDestination(item: $courtMarkingRoute) { route in
            CourtMarkingView(rally: rally, analyze: analyze, entryId: route.entryId)
        }
    }

    @ViewBuilder
    private func list(rows: [AnalyticsRow], legend: AnalyticsLegend) -> some View {
        List {
            if let text = legend.text {
                legendRow(text, showsDot: legend.showsDot)
            }
            ForEach(AnalyticsGroup.allCases, id: \.self) { group in
                let groupRows = rows.filter { $0.group == group }
                if !groupRows.isEmpty {
                    Section {
                        ForEach(groupRows) { row in
                            rowView(row, showNotOnDeviceSubtitle: legend.showsNotOnDeviceSubtitle)
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
            // A spinner, as the drawer's local video row shows in the same
            // situation - but not bare, as that row shows it. There the spinner
            // is followed by a menu and a chevron that hold the width; here it is
            // the last thing in the row, so a bare ~16pt indicator in place of a
            // ~75pt pill would visibly pull the row's right edge inwards the
            // moment a run starts. Reserving the pill's own minimum keeps the
            // edge still. Android argues the same way in AnalyticsScreen.kt.
            ProgressView()
                .controlSize(.small)
                .frame(minWidth: analysePillMinWidth)
        }
    }

    /// The width an in-flight spinner reserves so the row's trailing edge does not
    /// move when a pill is replaced by one. Measured from the shorter of the two
    /// labels ("Retry" at labelMedium) plus this pill's 12pt horizontal padding;
    /// a longer label still grows the slot, which is the pre-existing behaviour
    /// between "Analyze" and "Retry" and not something this reserves against.
    private var analysePillMinWidth: CGFloat { 64 }

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
