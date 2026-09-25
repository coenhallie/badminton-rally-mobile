import Shared
import SwiftUI

/// The coach's Analytics tab: the same rows the Matches tab lists, in the
/// same order, one row per match whether its video is on this phone, in the
/// cloud or both - "Shared with me" is the only section either screen still
/// labels. Port of Android's `AnalyticsScreen`.
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
    /// Reported upward instead of pushed from here: as a tab root this view
    /// sits at the same depth as the Matches tab, so `HomeView`'s own
    /// `navigationDestination`s present both of these one deeper, the same way
    /// it already does for every route `MatchesList` reports.
    let onCourtMarking: (CourtMarkingRoute) -> Void
    /// The same shape as `onCourtMarking`, for the row menu's heatmap and a
    /// ready row's tap.
    let onDetail: (AnalyticsDetailRoute) -> Void

    @State private var progressById: [String: AnalyzeProgress] = [:]

    var body: some View {
        let rows = buildAnalyticsRows(
            matchList: model.matchList,
            localEntries: model.localEntries,
            progressByEntryId: progressById,
            deviceStates: localAnalysis?.states ?? [:]
        )
        let legend = analyticsLegend(for: rows)

        return VStack(spacing: 0) {
            TabHeader(HomeCopy.shared.TAB_ANALYTICS) { BackgroundWorkAction() }
            Group {
                if rows.isEmpty {
                    // No action here: matches are added on the Matches tab,
                    // which the body names directly.
                    ShuttlEmptyState(
                        systemImage: "chart.bar",
                        title: HomeCopy.shared.ANALYTICS_EMPTY_TITLE,
                        message: HomeCopy.shared.ANALYTICS_EMPTY_BODY
                    ) { EmptyView() }
                } else {
                    list(rows: rows, legend: legend)
                }
            }
        }
        .background(Shuttl.bg)
        .task {
            await model.start()
            // Android used to build a fresh ClipListViewModel per entry to its
            // Analytics screen and its init refreshed, so opening it there
            // always pulled. Here `model` is the one instance HomeView owns
            // for the whole app, and `start()` is idempotent (see its own doc
            // comment: repeated calls only restart its five observing loops,
            // latched behind `initialRefreshStarted` so the one-time network
            // refresh it kicks off cannot fire twice) - so without the
            // explicit refresh() below, reopening this tab after a cloud run
            // finished, or after a colleague shared a match, while it was off
            // screen would pull nothing at all. This task runs again every
            // time the tab reappears.
            await model.refresh()
        }
        // Device runs and stored results are fed into `model` once, from
        // `HomeView`'s own `NavigationStack` - the common parent of this
        // screen and the Matches tab, which stays mounted the whole time
        // either is on screen. This screen used to keep its own copy of the
        // same two probes (`storedTrackIds`, `refreshStoredTracks`, keyed on
        // `trackCandidateIds` and `settledRuns`); that copy is gone, not
        // moved here, so the two screens do not each run it at once. See
        // `MatchListFeed`.
        .task {
            for await map in analyze.progress {
                progressById = map
            }
        }
        .refreshable { await model.refresh() }
    }

    @ViewBuilder
    private func list(rows: [AnalyticsRow], legend: AnalyticsLegend) -> some View {
        List {
            // Carries the top inset the mock's own headline used to: the
            // tab's title is drawn once, in `TabHeader`, above this list now,
            // so the legend (or the first group's rows, if there is no
            // legend) is what sits at the list's own top.
            if let text = legend.text {
                legendRow(text, showsDot: legend.showsDot)
                    .analyticsListRow(top: AnalyticsList.headlineTop, bottom: AnalyticsList.legendGap)
            }

            ForEach(AnalyticsGroup.allCases, id: \.self) { group in
                let groupRows = rows.filter { $0.group == group }
                if !groupRows.isEmpty {
                    // Sentence case at bodySmall, the same label the Matches tab's
                    // own "Shared with me" section carries
                    // (`DrawerSectionLabel`/`MatchListCopy.SHARED_SECTION`) -
                    // NOT `Shuttl.sectionLabel`, the uppercase tracked
                    // `labelSmall` this screen used to draw before the redesign
                    // dropped uppercase everywhere.
                    //
                    // A plain row carrying its own top inset, NOT a `Section`
                    // header: `.plain` pins a header to the top of the viewport
                    // while its own rows scroll away underneath it, and the
                    // mock's labels scroll with their rows.
                    if let label = group.label {
                        Text(label)
                            .shuttlType(ShuttlType.bodySmall)
                            .foregroundStyle(Shuttl.textSecondary)
                            .frame(maxWidth: .infinity, alignment: .leading)
                            .analyticsListRow(
                                top: AnalyticsList.sectionTop,
                                bottom: AnalyticsList.sectionBottom
                            )
                    }
                    ForEach(groupRows) { row in
                        rowView(row)
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
        // Extra room below the page gutter above, so the last card clears the
        // tab bar rather than sitting flush against it.
        .safeAreaPadding(.bottom, 16)
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

    /// One match on the mock's card: title over subtitle, the players line and
    /// the status line (as the Matches tab draws them), and on the right the
    /// availability dot for a ready row or the Analyze pill for an analysable
    /// one. A match that is not on this phone draws neither: its status line
    /// already says where the video is, and for a scored match with no video at
    /// all "Not on this phone" was simply wrong, so this screen no longer draws
    /// it per row - see `analyticsLegend`, which still explains it once for the
    /// whole list.
    ///
    /// A ready row opens its analysis; the other two do not, matching Android.
    @ViewBuilder
    private func rowView(_ row: AnalyticsRow) -> some View {
        HStack(spacing: 0) {
            VStack(alignment: .leading, spacing: 0) {
                Text(row.title)
                    .shuttlType(ShuttlType.titleLarge)
                    .foregroundStyle(Shuttl.textHeading)
                    .lineLimit(1)
                Text(row.subtitle)
                    .shuttlType(ShuttlType.bodySmall)
                    .foregroundStyle(Shuttl.textTertiary)
                    .lineLimit(1)
                    .padding(.top, AnalyticsList.subtitleGap)
                // Absent until somebody is named, the same rule the Matches tab's own
                // row follows: "Home vs Away" on every row would say nothing.
                if let players = row.item.playersLine {
                    Text(players)
                        .shuttlType(ShuttlType.bodySmall)
                        .foregroundStyle(Shuttl.textTertiary)
                        .lineLimit(1)
                        .padding(.top, AnalyticsList.subtitleGap)
                }
                if row.item.hasStatusLine {
                    MatchStatusLine(item: row.item)
                        .padding(.top, AnalyticsList.subtitleGap)
                }
                // No "Ready"/"In progress" line here: the status line above
                // already reports the very run this affordance was computed
                // from (`item.action`/`item.statusText`), so repeating it below
                // would say the same thing twice in two different wordings.
                // Failed is the one case the status line cannot speak for -
                // `MatchStatusLine` never shows an error string, on purpose -
                // so this stays the row's only account of *why* it failed.
                if row.state == .analysable, case .failed(let reason) = row.affordance {
                    Text(reason)
                        .shuttlType(ShuttlType.bodySmall)
                        .foregroundStyle(Shuttl.error)
                        .lineLimit(2)
                        .padding(.top, AnalyticsList.subtitleGap)
                }
            }
            .frame(maxWidth: .infinity, alignment: .leading)

            // On the right, as Android has it. The old flat list reserved a slot
            // on the LEFT so titles lined up across a mixed list; a card starts
            // its content at the same x whatever the row carries, so there is
            // nothing left to reserve against.
            if AnalyticsRowStateKt.opensAnalytics(state: row.state) {
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
            guard AnalyticsRowStateKt.opensAnalytics(state: row.state),
                  let entryId = row.entryId else { return }
            onDetail(AnalyticsDetailRoute(entryId: entryId, title: row.title))
        }
        .accessibilityAddTraits(AnalyticsRowStateKt.opensAnalytics(state: row.state) ? .isButton : [])
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
            // send him back to mark a court he has already marked. The status
            // line above says why it stopped.
            analysePill("Paused", row: row, loading: false, enabled: false)
        case .failed:
            // "Retry", not the Matches tab's "Re-analyze": this row already carries
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

    /// The same pill the Matches tab's row uses, down to the padding: the two
    /// screens list the same videos and their one action must not look like
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
            onCourtMarking(CourtMarkingRoute(entryId: id))
        }
    }
}

/// The Analytics list's own metrics, from the `Rally Analysis` mock.
///
/// Deliberately NOT `DrawerList`'s, even though the Matches tab lists the same videos
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
    /// A row's title to its subtitle, and each line under it to the one above.
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
    /// The list's own name for this match, carried so the bar can head the
    /// screen with it.
    ///
    /// Needed because a cloud-analysed match has no local entry to read a title
    /// from - the run retires it - and heading that screen "Analysis" while the
    /// row one tap back says "Thu League" reads as two matches.
    var title: String? = nil
}
