# Design: one match page for everything about a match, and no Analytics tab

**Date:** 2026-09-26
**Status:** draft for review. The direction, the toggles, dropping the bar, the
Match section and the announcement were agreed in conversation; the rest is new
in this document
**Asked for:** "Investigate how we can better distinguish between matches and the
analytics page. I think for users it's hard to know where to go for the clipped
rallies and the other analytics like skeleton, heatmap and base." Then, choosing
from the options: "points, rallies and all other analytics are combined on one
page, not all visible at the same time, but selectable using the toggles. Then we
can remove the 'analytics' navigation tab."
**Supersedes:** the Analytics tab and the tab bar of
`2026-09-25-bottom-navigation-design.md` (sections 2.1, 2.2, 2.5, 2.7 and 2.8 of
it). Its list-as-landing-page, Add button, Settings contents and empty state stand.
**Builds on:** `2026-09-25-unified-match-list-design.md` (the row's status line
becomes the page's table of contents, 2.3).
**Followed by:** `2026-09-11-on-device-rallies-as-matches-design.md`, still a
proposal. It turns the interim device-rally list of 2.6 into real rally rows.

The app has two tabs that draw the same list of the same matches, in the same
order, with the same status chips. What differs is only where a tap goes, and
nothing on screen says so. Neither destination has everything a match has: the
match page has points and cloud rallies but no heatmap, base or skeleton; the
analysis screen has those but no rallies; a device run's rallies are behind a
row menu item on a screen of plain buttons. This makes the match page the one
place a match lives, with its parts behind toggles, and removes the tab.

---

## 1. What exists today

Verified by reading the code on branch `feat/simplify-new-match` (PR #7, open).

### 1.1 Two tabs, one list

- The Analytics tab's rows are built from the same shared `MatchList` the
  Matches tab draws, "the same rows, in the same order"
  (`analytics/AnalyticsRows.kt`, `buildAnalyticsRows` and its doc comment), and
  each row draws the same `MatchStatusLine` (`analytics/AnalyticsScreen.kt`,
  `AnalyticsRowItem`). iOS is the same (`Analytics/AnalyticsRows.swift`,
  `AnalyticsListView.swift`).
- Screenshots `docs/screenshots/2026-09-25-ios-bottom-nav-matches-light.png` and
  `...-analytics-light.png` show the result. "cloudia" has a rallies chip on
  Analytics but no dot and no button, and a tap does nothing, under the legend
  "Analyzed on this phone - tap to view" (`AnalyticsScreen.kt:219`). "Oh Jip"
  reads "found no rallies" with Retry on Matches, and READY with a dot on
  Analytics.

### 1.2 Where each result lives

| Result | Matches tab | Analytics tab |
| --- | --- | --- |
| Score, points | row tap: match page, Points facet | none |
| Cloud rallies | row tap: match page, Rallies facet | none; the row is inert |
| Device-cut rallies | row menu "Clips on this phone (N)": `Route.LocalClips`, a `Column` of `TextButton`s (`AuthGate.kt:619-663`) | none |
| Heatmap | row menu "Player heatmap" | READY row tap: `AnalyticsDetailScreen`, Heatmap |
| Base, skeleton | Android: none. iOS: the same menu item opens the full detail | READY row tap: Base, Skeleton |
| Full recording | row tap, phone-only video only: `LocalPlayerScreen` | none |
| Analyze | row button | row button |

Evidence: the row tap is `ClipListScreen.kt:190-199` (`open`); the menu is
`ClipListScreen.kt:226-247` (`menuFor`); a row opens the local player exactly
when it has an entry and no cloud video (`MatchListBuilder.kt:236`,
`opensLocalPlayer = entry != null && videoId == null`). `MatchScreen.kt`,
`LocalPlayerScreen.kt` and `MatchView.swift` have no route to the heatmap, base
or skeleton.

The "Player heatmap" menu item already differs by platform: Android opens
`Route.Heatmap`, the heatmap alone (`AuthGate.kt:410`, `:675-701`); iOS opens
`AnalyticsDetailRoute`, all three panels (`ClipListView.swift:336-338`).

### 1.3 The match page

- `MatchScreen.kt:81` has a private `Facet { Points, Rallies }`. The selector is
  drawn only when both exist (`:350-361`). The choice is `rememberSaveable` and
  falls back when its facet goes away (`:178-199`). iOS ports it
  (`MatchView.swift:20-21`, `:145-155`).
- It is keyed by `scoreLogId` or `videoId` (`nav/Route.kt`, `Route.Match`). A
  video only on this phone has neither, which is why it opens a separate screen.
- Its top bar holds up to six actions: background work, sort (Rallies only),
  share, add video, the overflow (Edit details, Export as text, Change video,
  Remove video) and a theme toggle (`MatchScreen.kt:206-322`).
- It never shows the full recording. `LocalPlayerScreen` does (`:76`), with
  timestamped local notes, label chips and an Analyze button (`:193`).

### 1.4 The analysis screen

`AnalyticsDetailScreen` (`analytics/AnalyticsDetailScreen.kt:102`) shows
`ShuttlPillTabs` over `availablePanels(...)` (shared,
`analytics/AnalyticsPanel.kt:30`) when more than one panel has content, else a
"HEATMAP" label. It resolves rally windows from cloud clips or device clips,
whichever run the track came from. iOS: `AnalyticsDetailView.swift:120`.

### 1.5 The row's chips

`MatchListBuilder.kt:160-171` (`analysesFor`): RALLIES when there are cloud or
device clips; HEATMAP when an id is in `storedTrackIds` (that id is
`heatmapId`); SKELETON only with a local entry and a stored skeleton. SCORE is
added for a started score log (`:255`). The where-glyph is the phone when there
is a local entry.

### 1.6 The finished-run announcement

`announceFinishedRuns` (shared, `localvideo/RunAnnouncement.kt:42`) returns
`openAnalytics`, true when a run is new and `onHome`, which is
`isHomeFront(tab, nothingAbove)` (`home/HomeTab.kt:29`), true only for the
Matches tab with nothing above. Android switches the tab (`AuthGate.kt:284-290`);
iOS does the same from `HomeView.swift` (`announcedRuns`, `:98-100`, `:358`).
iOS has no snackbar or toast component; its transient messages are the inline
`ErrorBanner`.

---

## 2. The design

### 2.1 Home without a bar

- The Matches list is the authenticated root screen, on both platforms. There is
  no bottom bar and no tab state.
- Its top bar: the title "Matches", `BackgroundWorkAction`, and a gear button,
  accessibility label "Settings", which pushes Settings.
- The Add button stays bottom-right, with the behaviour the bottom-navigation
  design gave it (Android `addButtonExpanded`; iOS round and fixed size). With no
  bar under it, it sits at the standard inset above the gesture area.
- Settings keeps its contents and order (Labels, Dark theme, Signed in as, Sign
  out, version) and becomes a pushed page with a back button: Android
  `Route.Settings`, iOS a destination on `HomeView`'s one `NavigationStack`.
- Android system Back on the list leaves the app, as on any root screen.

### 2.2 The match page's sections

A top row of up to four sections, in this order: **Points | Match | Rallies |
Analysis**. A section is drawn only when it has content. With one section there
is no toggle row, as today.

| Section | Present when | Body |
| --- | --- | --- |
| Points | the match has a score log | today's Points facet |
| Match | the recording is on this phone (a local entry) | the full recording with its timestamped notes, from `LocalPlayerScreen`; Analyze while unanalysed |
| Rallies | cloud clips, else device clips | cloud: today's Rallies facet. Device: see 2.6 |
| Analysis | a stored track, or a stored skeleton with the file here | a second row, **Heatmap \| Base \| Skeleton**, over `availablePanels` exactly as today, drawn only with more than one panel |

"Match", not "Video": every rally is a video too, and Match against Rallies
reads as the whole against its parts. "Full match" was considered and rejected
for width: four equal pills get about 73dp each on a 360dp screen, and it wraps
at large font scales.

### 2.3 One shared rule decides the sections

New in `shared/.../matchpage/MatchPageSections.kt`, so the platforms cannot
disagree:

```kotlin
enum class MatchPageSection(val label: String) {
    POINTS("Points"), MATCH("Match"), RALLIES("Rallies"), ANALYSIS("Analysis"),
}

fun matchPageSections(item: MatchListItem): List<MatchPageSection>
fun defaultSection(sections: List<MatchPageSection>): MatchPageSection
fun shownSection(chosen: MatchPageSection?, sections: List<MatchPageSection>): MatchPageSection
```

It reads the same `MatchListItem` fields the row's chips come from, so the chips
on a row predict the sections of its page:

| On the row | On the page |
| --- | --- |
| # (score) | Points |
| phone glyph | Match |
| film (rallies) | Rallies |
| flame or figure (heatmap, skeleton) | Analysis |

One deliberate exception: a score log nobody has started shows no # chip but
its page has Points, because Points is where scoring starts.

The page finds its item in the shared `MatchList` by any of its three ids. When
none matches, it shows the existing "This match is no longer on this phone."
state.

### 2.4 Which section opens, and what it remembers

- `defaultSection`: Points if present, else Rallies, else Analysis, else Match. A
  scored match opens where it does today, a filmed one on its rallies, a
  just-imported video on Match with its Analyze button.
- The coach's choice, and the Analysis sub-panel, are saved state (Android
  `rememberSaveable`, iOS `@State`): they survive rotation and a return from any
  pushed page, as the facet does today.
- `shownSection` falls back to `defaultSection` when the chosen section has gone
  (the file removed, a re-analysis clearing clips), as `MatchScreen.kt:194-199`
  does for two facets.

### 2.5 Opening the page

- Every row tap opens the match page. `Route.Match` (and iOS's match route) gains
  `entryId`, so a video only on this phone can be opened, and `section`, an
  optional initial section used by the announcement (2.8).
- The row menu loses "Player heatmap" and "Clips on this phone (N)". It keeps
  Share, Edit details, Analyze again, Remove video from this phone, and Delete or
  Remove from my list.

### 2.6 Each section's body

- **Points:** unchanged.
- **Match:** `LocalPlayerScreen`'s body without its `Scaffold`: video card,
  transport bar, label chips, notes, the storage note. Analyze is a primary
  button under the player, under the rule `AuthGate.kt:600-612` uses today
  (resume a failed cloud run with saved court points, else court marking).
- **Rallies, cloud:** today's facet, unchanged.
- **Rallies, device clips, until 09-11 lands:** one row per stored clip with the
  rally number and its length, opening `LocalClipPlayerDialog` as today. Styled
  as the cloud rally rows' container and type, without thumbnails or note
  counts, which device clips do not have. No sort control, since there are no
  notes to sort by. 09-11 replaces this with real rally rows. When a match has
  both, cloud clips win, which is the rule the row's rally count already follows
  (`MatchListBuilder.kt:271`).
- **Analysis:** `AnalyticsDetailScreen`'s body without its `Scaffold`, keyed by
  the item's `heatmapId` (the id the menu item passes today), falling back to the
  entry id.

### 2.7 The top bar and actions

| Action | Where |
| --- | --- |
| Back, title, background work, Share (own matches), overflow | top bar, as today |
| Add video (a scored match with no video) | top bar, as today; it only shows when little else does |
| Sort (Rally order, Most notes) | a compact control at the top of the Rallies section |
| Analyze | under the player in Match |
| Add note | next to the player in Match |
| Analyze again | new overflow item, same condition as the row's (`canAnalyzeAgain`) |
| Theme toggle | removed from this page; Settings has it |
| Running, failed, needs court marking | `AttachStatusBanner`, above the toggles, as today |

### 2.8 When a run finishes

- `announceFinishedRuns` returns the newly finished ids instead of
  `openAnalytics`. The seen-set rule is unchanged.
- `onHome` becomes "the list is the front screen with nothing above it". Android
  keeps its conditions from the bottom-navigation design section 6 (the
  back-stack entry, RESUMED, no list overlay, no pending naming sheet); iOS keeps
  `isOnHome` minus `selectedTab`.
- One new run: "<title> is analyzed", with a **View** action that pushes its
  match page on Analysis, or on `defaultSection` if the run produced no track.
  Several at once: "<n> matches are analyzed", no action; the rows already show
  it. The strings live in shared `MatchListCopy`.
- Android: the existing `SnackbarHost`. iOS: a new toast pinned above the Add
  button, auto-dismissing after four seconds and posting a VoiceOver
  announcement, since iOS has no transient component today.
- The app does not navigate on its own any more. Switching tabs changed a list;
  pushing a page would change the screen, which is a bigger jump than the old
  behaviour.

### 2.9 Players and orientation

Only the section on screen is composed. Leaving Match or Skeleton pauses and
releases its player, so two players never run at once. Landscape goes
fullscreen only while Match is showing, as `LocalPlayerScreen` does today.

### 2.10 Shared and cloud-only matches

These follow from 2.3 with no special cases:

- Shared with me: Rallies, and Analysis if a track is on this phone. No Match,
  no Share, no overflow.
- Own match whose file was removed from this phone: Rallies, and Analysis if the
  track was kept; Skeleton drops out of the second row (`availablePanels`).

### 2.11 What is deleted

- Shared: `home/HomeTab.kt` and `HomeTabTest`; `HomeCopy.TAB_*` and
  `ANALYTICS_EMPTY_*`. `analytics/AnalyticsRowState.kt` stays only if something
  still needs it once the list is gone: both platforms' `AnalysisFiles` read it
  today (`LocalVideoRepository.kt` only names it in a comment), and the plan
  decides whether they move to `MatchListItem`'s own fields.
- Android: `home/HomeNavigationBar.kt`, `analytics/AnalyticsScreen.kt`,
  `analytics/AnalyticsRows.kt` and `AnalyticsRowsTest`; the tab plumbing in
  `AuthGate.kt` (hoisted `homeTab`, the `SaveableStateHolder`, the Analytics
  refresh `LaunchedEffect`, tab-aware Back); `Route.LocalPlayer`,
  `Route.LocalClips`, `Route.Heatmap`, `Route.AnalyticsDetail`. The bodies of
  `LocalPlayerScreen` and `AnalyticsDetailScreen` move into section composables
  under `match/`; `CloudAnalysisPanelsTest` moves with them.
- iOS: the `TabView` in `HomeView.swift`, `Analytics/AnalyticsListView.swift`,
  `Analytics/AnalyticsRows.swift` and `AnalyticsRowsTests`,
  `LocalAnalysis/LocalClipsView.swift`, `LocalVideo/LocalPlayerRoute.swift`, the
  local-clips and analytics-detail routes. `LocalPlayerView` and
  `AnalyticsDetailView` bodies move into section views under `Match/`.
- Comments that describe tabs, the Analytics list or the old routes are
  rewritten, not left behind (`MatchListFeed.swift:11`, `ClipListView.swift:20`
  among them).

---

## 3. Decisions

| Question | Chosen | Rejected, and why |
| --- | --- | --- |
| Where a match's results live | One match page, sections behind toggles | Two tabs with cross-links: a match keeps two homes and the two lists keep disagreeing about its state |
| Five sections, one toggle | Two levels: Points, Match, Rallies, Analysis, then Heatmap, Base, Skeleton | One row of five: about 60dp a pill on a small phone, wraps at large fonts. Scrolling chips: hides sections past the edge. A vertical list: pushes content down |
| The Analytics tab | Removed | Cross-match analytics: a new product with no design yet. An analysis queue: overlaps the "Not analyzed" filter |
| The bar with two destinations left | No bar; Settings behind a gear | A Matches and Settings bar: under Material's three-destination floor, and permanent space for a rarely used page |
| The full recording | A Match section | Keep the separate player: one kind of row would still open a different kind of page, and a cloud-analysed match with the file here would still have no way to its full recording |
| Section label | "Match" | "Video": rallies are videos too. "Full match": wraps at large fonts |
| Who decides the sections | One shared function over `MatchListItem` | Per-platform facet logic: the two pages drifted once already (the "Player heatmap" item, 1.2) |
| A finished run | A snackbar or toast with View | Navigate by itself: bigger than the old tab switch. Say nothing: loses the announcement the watcher exists for |
| Device clips before 09-11 | The existing clip list, inline in Rallies | Keep the row menu item until 09-11: leaves a second way in. Do 09-11 first: makes the navigation fix wait on the largest open design |
| The pipeline-comparison screen `LocalClipsView` was written as | Gone as a product screen | Keeping it reachable for comparing pipelines: a developer need, which belongs in a debug build if it is still wanted |

## 4. Deliberately not in this pass

- Device-run rallies as real rally rows, with thumbnails, notes and the rally
  page: `2026-09-11-on-device-rallies-as-matches-design.md`.
- Analytics across matches (trends, a season heatmap). If it comes, it is its own
  design and its own place in the app.
- A Match section for a match whose file is not on this phone.
- Tapping a row's chip to open that section.
- Moving Delete or "Remove video from this phone" onto the page.
- A follow-system theme mode.

## 5. Sequencing

A new branch, `feat/match-page-hub`, off `feat/simplify-new-match`, with its own
PR stacked on PR #7. PR #7 is 122 commits and green; folding this in would make
it larger and reopen review of work that stands. The tab bar has not reached
users, so it can merge ahead of this without anyone seeing a tab come and go,
provided no build is released between the two merges.

Inside the branch, shared first (the section rule, the announcement, the copy),
then Android, then iOS, each platform ending with its screenshots. 09-11 comes
after this and only changes the body of the device Rallies section.

## 6. Verification

- Shared unit: `matchPageSections`, `defaultSection` and `shownSection` over
  every combination of the four facts; for every item `MatchListBuilderTest`
  already builds, the sections agree with the chips as 2.3's table says (the
  unstarted score log as the one exception); `announceFinishedRuns` in its new
  shape, including the seen-set rule's existing cases.
- Android unit: the existing suites green once `AnalyticsRowsTest` and the tab
  tests are removed; `CloudAnalysisPanelsTest` green where it moved.
  `AuthNavigationTest` unchanged.
- iOS unit: green, with `TypographyAdoptionTests` and `SystemImageTests` clean
  over the new files.
- iOS UI tests: the four that enter through `app.tabBars.buttons["Settings"]`
  (`LabelSwatchGridUITests.swift:69`, `ReplayPlayerUITests.swift:22`,
  `ScoringBoardUITests.swift:139`, `VideoMatchDetailsUITests.swift:23`)
  re-pointed at the gear button, run, and confirmed to execute, not skip.
- Screens, light and dark, both platforms, compared pixel by pixel against each
  other: the list with the gear; Settings; the match page for a just-imported
  video (Match only, Analyze), a video analysed on the phone (Match, Rallies,
  Analysis with the second row), a scored, filmed and analysed match (four pills
  plus the second row, at 360dp and at the largest font scale), a cloud-only
  match and a shared one; the device-clip Rallies list; the announcement on each
  platform; landscape on Match and on Skeleton.
- On a real phone, handed to Coen: a finished run's announcement and its View,
  a player released when switching sections, and rotation on Match and
  Skeleton.
