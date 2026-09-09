import Shared
import SwiftUI

/// The coach's Analytics list: one section per group mirroring the drawer's own
/// grouping (local videos, owned matches, shared), so the same match shows up in
/// the same place on both screens. Port of Android's `AnalyticsScreen`.
///
/// A ready row opens its analysis and the other two do not, as on Android. The
/// two that do not are not inert for want of a modifier: an analysable row's one
/// action is the pill it already carries, and a match that is not on this phone
/// has nothing behind it to open.
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
    /// The on-device pipeline, or nil in a build with no models staged. Its
    /// absence is what keeps every row's control honest: with no runner there is
    /// no track store, so nothing can be ready.
    let localAnalysis: LocalAnalysisRunner?

    @State private var progressById: [String: AnalyzeProgress] = [:]
    /// The entries with a stored track, read once per appearance rather than
    /// per row. `PlayerTrackStore.has` is a small header read and a list of
    /// forty matches would otherwise do forty of them on every redraw.
    @State private var storedTrackIds: Set<String> = []
    @State private var detailRoute: AnalyticsDetailRoute? = nil
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
            progressByEntryId: progressById,
            storedTrackIds: storedTrackIds,
            deviceStates: localAnalysis?.states ?? [:]
        )
        let legend = analyticsLegend(for: rows)

        return Group {
            if rows.isEmpty {
                // No action here: matches are added on Home, and this screen's
                // only way there is its back button, which the body names.
                ShuttlEmptyState(
                    systemImage: "chart.bar",
                    title: "No matches yet",
                    message: "Add a match on Home and it will be listed here, ready to analyze."
                ) { EmptyView() }
                .background(Shuttl.bg)
            } else {
                list(rows: rows, legend: legend)
            }
        }
        .navigationTitle("Analytics")
        .navigationBarTitleDisplayMode(.inline)
        .shuttlNavigationBarBackground()
        .toolbar {
            // The chrome indicator, on every bar androidApp puts it on. See
            // `BackgroundWorkAction`.
            ToolbarItem(placement: .topBarTrailing) { BackgroundWorkAction() }
        }
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
            // Once on appearance, and not only from the onChange below: that
            // fires on a CHANGE, and by the time this view appears the entries
            // are usually already loaded, so the first render read an empty set
            // and every analysed match showed as never analysed.
            await refreshStoredTracks(model.localEntries.map(\.id))
        }
        .task {
            for await map in analyze.progress {
                progressById = map
            }
        }
        .refreshable { await model.refresh() }
        .onChange(of: model.localEntries.map(\.id)) { _, ids in
            Task { await refreshStoredTracks(ids) }
        }
        // Also when a run settles: a track appears on disk without the entry
        // list moving, so nothing above would notice.
        //
        // Keyed on which runs have settled rather than on the state map itself.
        // The map changes on every progress callback - thirty times a second on
        // a fast video - and each firing stats one file per row, which is the
        // forty reads per redraw that `storedTrackIds` exists to avoid. A set of
        // ids moves exactly on the transitions that can write a track.
        .onChange(of: settledRuns) { _, _ in
            Task { await refreshStoredTracks(model.localEntries.map(\.id)) }
        }
        .navigationDestination(item: $courtMarkingRoute) { route in
            CourtMarkingView(
                rally: rally, analyze: analyze,
                localAnalysis: localAnalysis, entryId: route.entryId
            )
        }
        .navigationDestination(item: $detailRoute) { route in
            AnalyticsDetailView(
                rally: rally, localAnalysis: localAnalysis, entryId: route.entryId
            )
        }
    }

    @ViewBuilder
    private func list(rows: [AnalyticsRow], legend: AnalyticsLegend) -> some View {
        List {
            // The mock's headline over the list, then the one line saying what
            // the rows offer.
            Text("Pick a match")
                .shuttlType(ShuttlType.headlineLarge)
                .foregroundStyle(Shuttl.textHeading)
                .frame(maxWidth: .infinity, alignment: .leading)
                .analyticsListRow(top: AnalyticsList.headlineTop)

            if let text = legend.text {
                legendRow(text, showsDot: legend.showsDot)
                    .analyticsListRow(top: AnalyticsList.legendGap, bottom: AnalyticsList.legendGap)
            }

            ForEach(AnalyticsGroup.allCases, id: \.self) { group in
                let groupRows = rows.filter { $0.group == group }
                if !groupRows.isEmpty {
                    // Sentence case at bodySmall, the same label the drawer's
                    // own sections carry (`DrawerSectionLabel`) - NOT
                    // `Shuttl.sectionLabel`, the uppercase tracked `labelSmall`
                    // this screen used to draw. The mock has "On this phone" and
                    // "My matches" as plain 12px secondary text and carries no
                    // uppercase anywhere; the uppercase was a Material holdover
                    // from before the redesign, and it followed the layout
                    // across to iPhone where it never belonged at all.
                    //
                    // A plain row carrying its own top inset, NOT a `Section`
                    // header: `.plain` pins a header to the top of the viewport
                    // while its own rows scroll away underneath it, and the
                    // mock's labels scroll with their rows.
                    Text(group.label)
                        .shuttlType(ShuttlType.bodySmall)
                        .foregroundStyle(Shuttl.textSecondary)
                        .frame(maxWidth: .infinity, alignment: .leading)
                        .analyticsListRow(
                            top: AnalyticsList.sectionTop,
                            bottom: AnalyticsList.sectionBottom
                        )
                    ForEach(groupRows) { row in
                        rowView(row, showNotOnDeviceSubtitle: legend.showsNotOnDeviceSubtitle)
                            .analyticsListRow(bottom: AnalyticsList.cardGap)
                    }
                }
            }
        }
        .listStyle(.plain)
        // Every gap on this screen is stated in `analyticsListRow`, so the row
        // heights have to come from the content alone. `List`'s own 44pt floor
        // would sit under the two short rows - the legend line and a section
        // label - and quietly add height that no number here asks for, which is
        // exactly how a ported layout ends up looser than the one it copies.
        .environment(\.defaultMinListRowHeight, 0)
        // The list paints its own surface, so the token has to be asked for
        // twice: once for the scroll view behind the rows and once per row
        // (`analyticsListRow` clears the row fill so the card is what shows).
        .scrollContentBackground(.hidden)
        .background(Shuttl.bg)
        // The gutter again at the foot, so the last card does not sit against
        // the home indicator. Android's LazyColumn spends its contentPadding
        // the same way.
        .contentMargins(.bottom, AnalyticsList.gutter, for: .scrollContent)
    }

    @ViewBuilder
    private func legendRow(_ text: String, showsDot: Bool) -> some View {
        HStack(spacing: AnalyticsList.dotGap) {
            if showsDot {
                Circle()
                    .fill(Shuttl.accent)
                    .frame(width: AnalyticsList.dot, height: AnalyticsList.dot)
            }
            Text(text)
                .shuttlType(ShuttlType.bodySmall)
                .foregroundStyle(Shuttl.textTertiary)
        }
        .frame(maxWidth: .infinity, alignment: .leading)
    }

    /// One match on the mock's card: title over subtitle, and on the right the
    /// availability dot for a ready row, the Analyze pill for an analysable one,
    /// nothing for a match that is not on this phone.
    ///
    /// A ready row opens its analysis; the other two do not, matching Android.
    @ViewBuilder
    private func rowView(_ row: AnalyticsRow, showNotOnDeviceSubtitle: Bool) -> some View {
        HStack(spacing: 0) {
            VStack(alignment: .leading, spacing: 0) {
                Text(row.title)
                    .shuttlType(ShuttlType.titleLarge)
                    .foregroundStyle(Shuttl.textHeading)
                    .lineLimit(1)
                // bodySmall, not the labelSmall this row used before the
                // redesign, while the string stays uppercased at the call site
                // in AnalyticsRows.swift. That pairing looks like a mistake and
                // is not: it is Android's own, and labelSmall's +0.05em tracking
                // on an already-uppercase line reads as a caption rather than as
                // a subtitle.
                Text(row.subtitle)
                    .shuttlType(ShuttlType.bodySmall)
                    .foregroundStyle(Shuttl.textTertiary)
                    .lineLimit(1)
                    .padding(.top, AnalyticsList.subtitleGap)
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
                case .paused(let reason):
                    // Secondary, not the error colour: nothing went wrong.
                    Text(reason)
                        .shuttlType(ShuttlType.bodySmall)
                        .foregroundStyle(Shuttl.textSecondary)
                        .lineLimit(2)
                case .failed(let reason):
                    Text(reason)
                        .shuttlType(ShuttlType.bodySmall)
                        .foregroundStyle(Shuttl.error)
                        .lineLimit(2)
                }
            }
            .frame(maxWidth: .infinity, alignment: .leading)

            // On the right, as Android has it. The old flat list reserved a slot
            // on the LEFT so titles lined up across a mixed list; a card starts
            // its content at the same x whatever the row carries, so there is
            // nothing left to reserve against.
            if row.state == .ready {
                Circle()
                    .fill(Shuttl.accent)
                    .frame(width: AnalyticsList.dot, height: AnalyticsList.dot)
                    .padding(.leading, AnalyticsList.trailingGap)
            }
            if row.state == .analysable {
                trailingControl(row)
                    .padding(.leading, AnalyticsList.trailingGap)
            }
        }
        .padding(.horizontal, AnalyticsList.cardPaddingH)
        .padding(.vertical, AnalyticsList.cardPaddingV)
        .background(
            Shuttl.bgSecondary,
            in: RoundedRectangle(cornerRadius: ShuttlRadius.large)
        )
        // contentShape before the gesture, or the gaps between the title and
        // the dot are not part of the target and the card feels unreliable.
        .contentShape(RoundedRectangle(cornerRadius: ShuttlRadius.large))
        .onTapGesture {
            guard row.state == .ready, let entryId = row.entryId else { return }
            detailRoute = AnalyticsDetailRoute(entryId: entryId)
        }
        .accessibilityAddTraits(row.state == .ready ? .isButton : [])
    }

    @ViewBuilder
    private func trailingControl(_ row: AnalyticsRow) -> some View {
        switch row.affordance {
        case .ready:
            analysePill("Analyze", row: row, loading: false)
        case .paused:
            // Inert, and not "Resume": nothing was cancelled, so there is
            // nothing to restart. The pass picks up by itself once the app is
            // back in the foreground - which is where a coach has to be to read
            // this at all - and a button here would route through
            // `analyseAction`, which knows nothing about device runs and would
            // send him back to mark a court he has already marked. The line
            // above the pill says why it stopped.
            analysePill("Paused", row: row, loading: false, enabled: false)
        case .failed:
            // "Retry", not the drawer's "Re-analyze": this row already carries
            // the failure reason on the line above, exactly as a scored match's
            // row does, and that row says "Retry" too.
            analysePill("Retry", row: row, loading: false)
        case .inProgress:
            // The spinner goes INSIDE the pill, as Android's ShuttlButton draws
            // it, rather than replacing the pill with a bare indicator. A bare
            // one collapsed the slot from a pill to about 16pt and pulled the
            // row's trailing edge inwards the moment a run started; the label
            // stays put and the ring plus its gap is all that is added.
            analysePill("Analyze", row: row, loading: true)
        }
    }

    /// The same pill the drawer's local video row uses, down to the padding: the
    /// two screens list the same videos and their one action must not look like
    /// two.
    private func analysePill(
        _ label: String, row: AnalyticsRow, loading: Bool, enabled: Bool = true
    ) -> some View {
        Button {
            analyse(row)
        } label: {
            HStack(spacing: AnalyticsList.dotGap) {
                if loading {
                    ProgressView()
                        .progressViewStyle(.circular)
                        .controlSize(.mini)
                        .tint(Shuttl.onAccent)
                        .frame(width: AnalyticsList.spinner, height: AnalyticsList.spinner)
                }
                Text(label)
                    .shuttlType(ShuttlType.labelMedium)
                    .foregroundStyle(Shuttl.onAccent)
                    .lineLimit(1)
            }
            .fixedSize(horizontal: true, vertical: false)
            .padding(.horizontal, 12)
            .padding(.vertical, 6)
            .background(Shuttl.accent)
            .clipShape(Capsule())
            // Android dims the whole control while it is inert, rather than the
            // label alone. One opacity for both ways of being inert, so a
            // spinning pill and a paused one read as the same kind of thing.
            .opacity(loading || !enabled ? 0.5 : 1)
        }
        // Borderless, or the List makes the whole card tappable and every tap
        // anywhere on the row starts an analysis.
        .buttonStyle(.borderless)
        .disabled(loading || !enabled)
        .layoutPriority(1)
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

/// The Analytics list's own metrics, from the `Rally Analysis` mock.
///
/// Deliberately NOT `DrawerList`'s, even though the drawer lists the same videos
/// one tap away and both are inset cards in a `List`. Those rows carry a
/// thumbnail and sit tighter (16 by 14, a 6 gap); an Analytics row is text and
/// one control at a larger size, and the mock gives it its own numbers. What the
/// two screens share is the technique, not the scale.
///
/// Mirrors androidApp's AnalyticsScreen.kt number for number.
private enum AnalyticsList {
    /// The page gutter the mock lays every card in.
    static let gutter: CGFloat = 24
    static let cardPaddingH: CGFloat = 20
    static let cardPaddingV: CGFloat = 18
    /// Between two cards.
    static let cardGap: CGFloat = 8
    static let headlineTop: CGFloat = 16
    /// Above and below the one explanatory line.
    static let legendGap: CGFloat = 10
    static let sectionTop: CGFloat = 18
    static let sectionBottom: CGFloat = 10
    /// A row's title to its subtitle.
    static let subtitleGap: CGFloat = 4
    static let dot: CGFloat = 8
    /// The dot to the line it explains, and the pill's ring to its label.
    static let dotGap: CGFloat = 8
    /// A row's text to the dot or pill on its right.
    static let trailingGap: CGFloat = 16
    /// Android sizes its in-pill ring at 14dp; `.mini` alone is close but not
    /// fixed, and a ring that changes size with the control metrics would change
    /// the pill's height with it.
    static let spinner: CGFloat = 14
}

private extension View {
    /// Strips the styling `List` gives a row so the mock's inset card can be
    /// drawn in its place: no separator, no system fill over the page's own
    /// background, and the gutter and gaps stated as insets rather than as one
    /// uniform spacing between rows - this list wants different gaps above a
    /// section label, above the headline and between two cards.
    ///
    /// The same technique `DrawerList.drawerListRow` uses, with this screen's
    /// own numbers.
    func analyticsListRow(top: CGFloat = 0, bottom: CGFloat = 0) -> some View {
        listRowInsets(EdgeInsets(
            top: top,
            leading: AnalyticsList.gutter,
            bottom: bottom,
            trailing: AnalyticsList.gutter
        ))
        .listRowBackground(Color.clear)
        .listRowSeparator(.hidden)
    }
}


/// Which analysis the detail screen should open.
struct AnalyticsDetailRoute: Hashable {
    let entryId: String
}

private extension AnalyticsListView {
    /// The runs that have stopped, whichever way they stopped.
    ///
    /// The runner's own set, rather than one rebuilt from `states` here: this
    /// list already redraws on every progress callback to move its rows, and
    /// that scan ran on each of those redraws.
    var settledRuns: Set<String> { localAnalysis?.settledRuns ?? [] }

    /// Re-reads which entries have a track this build can draw.
    ///
    /// One header read per entry, and off the main actor: rare - the list
    /// appearing, and a run settling - is a reason not to do it often, not a
    /// reason to do it on the main thread, and `storedTrackIds` is
    /// `nonisolated` so it need not be. The list redraws when the set lands,
    /// one hop later. What must not happen is calling it per row or per
    /// progress tick; see `settledRuns`.
    func refreshStoredTracks(_ ids: [String]) async {
        guard let localAnalysis else {
            storedTrackIds = []
            return
        }
        let runner = localAnalysis
        storedTrackIds = await Task.detached(priority: .userInitiated) {
            runner.storedTrackIds(among: ids)
        }.value
    }
}
