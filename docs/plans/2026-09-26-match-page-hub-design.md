# Design: one match page for everything about a match, and no Analytics tab

**Date:** 2026-09-26
**Status:** implemented on `feat/match-page-hub` (2026-09-27); real-phone checks
and an account with cloud matches still open. Where the build differs from
section 2, section 7 says so and why
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
| Running, failed, needs court marking | `StatusBanner`, above the toggles: a scored match's list row, its status and its action, drawn again (7.4) |

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

---

## 7. What shipped differently

Recorded 2026-09-27 from the build on `feat/match-page-hub`. Every place the code
differs from section 2, with the reason. Android paths are under
`androidApp/.../android/`, iOS under `iosApp/Sources/`, shared under
`shared/.../shared/`.

### 7.1 Sections, and what the page remembers (2.3, 2.4, 2.8)

- **The first section shown counts as chosen.** Once a section is on screen it
  is pinned as the coach's choice (`match/MatchScreen.kt`,
  `LaunchedEffect(section)`; `Match/MatchView.swift`,
  `.onChange(of: section, initial: true)`). Without the pin, a video opened on
  Match alone jumped to Rallies when its run landed and released the player
  mid-watch, and 2.8 says the app does not change the screen on its own.
  `shownSection` still falls back when the pinned section goes.
- **The missing-match state waits until the list knows.** The page shows
  `MatchPageCopy.MISSING` once the shared list has finished a refresh
  (`refreshGeneration > 0`, failed refreshes included), or earlier when a lookup
  that needs no network (the score log, the local entry) comes back empty. Until
  then the bar shows only Back. iOS also checks its own direct lookup
  (`foundDirectly` in `MatchView.swift`), because a match created under a push
  is missing from the list until the page's own `start()` delivers. Before this,
  a cloud match whose row had gone drew a blank page.
- **The page reads the list's state instead of loading its own.** Android takes
  the Home entry's `ClipListViewModel` and does not refresh on open. On iOS a
  push cancels the list's `.task`, so `MatchView` runs `ClipListModel.start()`
  itself; a temporary log line showed the cancel. The two overlap only during
  the push transition, and both write the same values.
- **The announcement's strings live in shared `MatchPageCopy`**, with the page's
  other strings, not in `MatchListCopy`.

### 7.2 The toggle rows (2.2)

- **The Analysis panel row and every Near/Far row use a secondary, compact pill
  style.** At one size, the section row, the panel row and Near/Far read as
  peers, and nothing tied the second row to Analysis. `secondary = true` on
  `ShuttlPillTabs` (`ui/components/ShuttlPillTabs.kt`,
  `Components/ShuttlPillTabs.swift`) is 8dp/pt shorter with an 11sp/pt
  labelSmall label, on the same tokens. A secondary row never draws its labels
  larger than the section row above it. Android passes a `PillLabelCeiling`
  from the section row; iOS publishes it as a preference (`setsCeiling: true`,
  `.pillLabelCeilingHost()`).
- **Equal-width pills shrink to a 0.75 floor, then wrap.** A pill does not break
  inside a word while it can avoid it. First its side padding gives way from 8
  to 6, then the whole row's labels shrink by one factor, down to 0.75
  (`PillLabelScaleTest`, `ShuttlPillTabsTests`). Past the floor the label wraps:
  "Analysis" breaks at font scale 2.0 on a 360dp Android screen and at AX5 on
  iOS. Proportional widths would avoid it but give up equal shares; that was
  ruled out, and the wrap at the largest accessibility sizes is the accepted
  cost.

### 7.3 The section bodies (2.6, 2.7, 2.9)

- **Device rallies are drawn as the cloud rally rows**: full-bleed divided rows,
  "Rally #N" over the length, with no thumbnail and no note count
  (`match/DeviceRalliesSection.kt`; `Match/DeviceRalliesSection.swift` through
  `Match/RallyRow.swift`). The plan had drawn cards. "#N" matches the cloud
  rows' `clipRowTitle`, and the clip player's title uses the same format ("Rally
  #1 · 24.1s"). On iOS the clip opens in a sheet, not a dialog.
- **One 24dp/pt gutter for every section, Points and cloud Rallies included**,
  and one 16dp/pt gap under the section row (`MatchPageGutter`,
  `MatchSectionGap`, `MatchRowInset` in `MatchScreen.kt`; `MatchPageLayout` and
  `ShuttlGutter.page` on iOS). 2.6 kept Points and cloud Rallies unchanged at
  16dp, but then the page's left edge moved 8dp between toggles.
- **One rally length format, "25.2s"**: shared `matchlist/RallyLength.kt`
  (`rallyLengthText`), used by device and cloud rows on both platforms. Cloud
  rows printed raw seconds ("25.1727S"). Tenths rather than "0:25", because
  rallies run from seconds to about a minute and the tenths tell two short ones
  apart.
- **The sort control sits inside Rallies**, above the first rally
  (`RallySortRow` in `MatchScreen.kt` and `MatchView.swift`), and only when
  there is more than one rally. It is a borderless text button at the row's
  trailing edge: a sort glyph and the current order in labelMedium, secondary
  text colour, in a 48dp (Android) or 44pt (iOS) target, opening a menu of the two orders with a
  check (Android's dropdown) or the system picker menu (iOS). Its screen-reader
  label carries the current order ("Sort rallies, Rally order") from the
  existing strings.
- **Analyze sits under the transport bar**, before the label chips (iOS:
  `ShuttlPlayer`'s `primaryAction` slot). **Add note is a compact "+ Add note"
  pill in the notes header** on both platforms, not a control beside the
  player.
- **The Match section in landscape, after Back.** Back from fullscreen in
  landscape left the portrait column in the landscape height, and the video
  card covered the section row. Outside fullscreen in landscape the card now
  fits the available height (`clipdetail/VideoCard.kt`, `maxHeight`) and the
  section scrolls as one (`match/MatchVideoSection.kt`). The iOS app is
  portrait-only outside the fullscreen cover, so there the exit button returns
  to the portrait page.
- **iOS lays out a player and its notes with one shared container**,
  `Components/PlayerNotesLayout.swift`, used by the Match section and the rally
  page (`ClipDetail/ClipDetailView.swift`). It pins the player over the notes
  and switches to one scroll when the notes would get less than 88pt, which
  happened at accessibility sizes and on 667pt screens. The player stays in the
  same place in both layouts, so switching does not rebuild it.
- **iOS keeps a paused video where it was on entering fullscreen.** Attaching a
  paused player to the fullscreen controller snapped it to the nearest keyframe,
  0:00 on a short clip. `Components/PlayerSurface.swift` seeks it back exactly
  while paused.
- **Smaller fixes found while checking the screens**, none of which changes the
  design:
  - Android's row overflow button is a 48dp target whose glyph lines up with
    the header's gear and info icons (`cliplist/MatchListRow.kt`).
  - iOS pills take taps across 44pt vertically (`Theme/ShuttlTarget.swift`).
    Analysis takes the page's 16pt gap inside its scroll view, because UIKit
    does not hit-test outside a scroll view's bounds.
  - iOS row rules run edge to edge under rallies and points, as Android's do
    (`fullBleedListSeparator` in `Match/RallyRow.swift`).
  - iOS draws no Live Text button over a paused video
    (`allowsVideoFrameAnalysis = false` in `PlayerSurface.swift`).
  - The iOS clip sheet's Close is lettered in `accentDark`, with a 44pt
    target.

### 7.4 The status banner (2.7)

- **The banner is `StatusBanner`, and it shows the list row's status.** For a
  scored match with a status line (shared `showsStatusBanner`), it shows the
  row's own `statusText`, in the row's colour, and the row's action
  (`MatchScreen.kt`, `MatchView.swift`): running, failed, court not marked, and
  also "Not analyzed" with Analyze for a scored match whose court is marked but
  has no results. The old `AttachStatusBanner` derived its status again from
  the score log alone, could not see device runs, and disagreed with the row.
  The text is bodyMedium on both platforms, a step up from the row's bodySmall:
  alone at the top of the page, beside the button's 13-unit label, bodySmall
  read as the least important thing there (compared on the simulator). iOS's
  action button takes taps across 44pt (`MinimumTargetShape`).
- **"Court not marked" after an on-device run is fixed.** Shared `attachStatus`
  asked for the court for every entry at `LOCAL`. A device run never leaves
  `LOCAL`, so a scored match analyzed on the phone said "Video added, court not
  marked" forever. It now asks only while the entry has no court points, and a
  device run or its results win over that status in `scoreItem`
  (`scoring/AttachStatus.kt`, `matchlist/MatchListBuilder.kt`). iOS now saves the
  court points on a device run, as Android already did (shared
  `LocalVideoRepository.setKeypoints`, called from
  `CourtMarking/CourtMarkingView.swift`).
- **The page offers no video removal while a device run holds the file.** The
  overflow's Change video and Remove video follow the row's rule (shared
  `pageOffersVideoRemoval`). The page used to offer them mid-run.
- **A scored match never offers "Analyze again".** 2.7 gives the page's overflow
  item the row's condition, `canAnalyzeAgain`, and `scoreItem` sets that to
  false for every scored match (`matchlist/MatchListBuilder.kt`). So neither the
  row nor the page of a scored match can re-run its analysis, even after a
  device run that found nothing. Left as is: whether re-running should replace
  a scored match's results is a product decision for Coen (8).

### 7.5 When a run finishes (2.8)

- **A run that found nothing is not announced.** A device run counts as finished
  only when its own result has clips, a track or a saved skeleton
  (`LocalAnalysisState.Done.foundSomething`; `DeviceRunOutputTest`,
  `DeviceRunOutputTests`). Judging from the list row instead could lose a run
  for good when the row lagged behind it. Cloud runs are not filtered: a cloud
  run that finds no rallies fails instead of finishing.
- **Runs landing together are one message** ("2 matches are analyzed", no
  View). On iOS, a run landing while the toast shows is merged into it and the
  toast is rebuilt (`Home/RunToastUpdate.swift`). On Android, runs are merged
  only while they are waiting to be shown (`runSnackbarUpdate` in
  `home/RunSnackbarUpdate.kt`, called from `AuthGate.kt`, and pinned by
  `RunSnackbarUpdateTest`). Once a snackbar is up, the next run's snackbar
  queues behind it.
- **View reads the row again when tapped**, on both platforms, so a cloud track
  that landed after the run still opens on Analysis.
- **Android.** The snackbar is shown from the screen's coroutine scope
  (`home/HomeScreen.kt`), not from an effect keyed on the announced ids: clearing
  the ids re-keyed that effect and cancelled the snackbar while it showed. Its
  colours are set: `textHeading` surface, `bg` text, and the other theme's
  `accentDark` for View. Material's defaults had drawn it purple-grey with a
  lavender action. With View it is `SnackbarDuration.Long`: ten seconds, or
  longer if the user has set Android's accessibility timeout ("Time to take
  action") higher. Unlike the iOS toast, it does not stay up indefinitely for
  a screen reader on Android 10 and later.
- **iOS.** `Components/ShuttlToast.swift` is a rounded rectangle
  (`ShuttlRadius.medium`, continuous corners) in the 24pt gutter, 16pt above
  the Add button, at least 48pt tall. Its text is bodyMedium in `bg` on a
  `textHeading` surface and wraps to two lines. View is labelMedium in
  `Shuttl.inverseAccent`, the other theme's `accentDark`, in a 44pt target;
  `accent` there was 1.5:1 in dark. Its side margins are the page gutter, where
  Android's snackbar keeps Material's 12dp. It posts a VoiceOver announcement.
  While VoiceOver runs it stays until dismissed or View, instead of leaving
  after four seconds. Announcing also needs the app in the foreground
  (`scenePhase == .active`), as Android needs RESUMED.

### 7.6 What was deleted (2.11)

- `AnalyticsRowState` went entirely. Once the Analytics list was gone only
  comments named it, and both `AnalysisFiles` now describe the case in words.
- Also gone: `MatchListItem.opensLocalPlayer`, `MatchListCopy.MENU_HEATMAP` and
  `menuDeviceClips`, `RunAnnouncement.openAnalytics`, and `HomeCopy.TAB_*` and
  `ANALYTICS_EMPTY_*`. `TAB_MATCHES` became `HomeCopy.MATCHES_TITLE`.

## 8. Verification, as run

- **Unit tests** at the end of the branch, after the final review's fixes:
  shared 774, androidApp 319, iOS 315 (one skipped: `ThroughputMeasurementTests`
  needs its build flag), no failures.
- **iOS UI tests**: 7 run. The three suites re-pointed at the gear pass
  (`LabelSwatchGridUITests`, `ReplayPlayerUITests`, `ScoringBoardUITests`).
  `VideoMatchDetailsUITests` gets past the gear and then skips, because the
  simulator account has no cloud match ("cloudia"), so section 6's "not skip"
  holds for three of the four.
- **Screens**, light and dark, in `docs/screenshots/2026-09-26-android-*`
  (Pixel 9a emulator) and `docs/screenshots/2026-09-26-ios-*` (iPhone 17 Pro
  simulator): the list, Settings, a video-only match, an analyzed match, a
  scored match with and without Analysis showing (Android at font scale 1.3 on
  360dp; iOS at the default size, XXXL and AX5), device rallies with the clip
  player open, fullscreen landscape, the page after leaving fullscreen, and the
  run message with where its View lands.
  - Android's `match-cloud-*` shows a cloud-only match with the sort control.
    iOS has no cloud frame.
  - The run messages came from real device runs on both platforms. Android's
    `match-analysed-*` and dark `run-snackbar-view` used a track planted from
    the cloud copy of the same footage, because a device pose run cannot finish
    on the 2GB emulator. The iOS frames used only real runs.
  - **Re-taken after the final review, on 77b1220.** The first iOS set was
    taken before the "court not marked" fix (7.4), and its scored match showed
    "Video added, court not marked" with Mark court over its results. These
    were taken again from new real device runs: iOS `list-*`,
    `match-scored-{light,dark}`, `match-scored-xxxl-{light,dark}`,
    `match-scored-ax5-dark`, `match-scored-analysis-{light,dark}`,
    `run-snackbar-{light,dark}` and `run-snackbar-view-light`; and Android
    `run-snackbar-light`, for its 16dp corners. Both toasts now name 28.mp4.
    The light one came from a clips-only "Analyze again", which dropped the
    earlier run's skeleton, so 28.mp4 shows no skeleton chip there and its
    Analysis opens on Heatmap and Base. Android's `run-snackbar-dark` still
    shows the snackbar's earlier 8dp corners. iOS
    `run-snackbar-view-dark` is from the first set; it shows a video-only
    match, which the fix does not touch.
  - **The banner's "Not analyzed / Analyze"**, light, in
    `2026-09-26-{android,ios}-match-banner-not-analyzed-light.png`. A court is
    saved only when a run starts, so the state cannot be made by marking alone.
    Each frame is a scored match whose device run was interrupted: the app
    force-stopped (Android) or terminated (iOS) seconds into the run, then
    opened again. The run is gone, the court stays marked, and the row and the
    banner offer Analyze.
- **Open, needs an account with cloud matches**: the iOS cloud Rallies rows,
  their label strip and details row, and the sort control; a shared match on
  either platform; `VideoMatchDetailsUITests` running rather than skipping.
- **Open, needs a real phone (Android and iPhone)**:
  - a real finished run's snackbar or toast, and its View;
  - rotating on Match (position kept) and on Skeleton; no Skeleton landscape
    frame was taken on either platform;
  - switching sections releasing the player, with no audio from a section left
    behind (iOS now pauses the Match section's player when the section goes;
    on the simulator a playing video still played on entering fullscreen);
  - a phone-analyzed video's Rallies taking the new rallies when "Analyze
    again" lands while it shows (the read is keyed on the run settling; a re-run
    on the same footage gives the same rallies, so the simulator and emulator
    could not show the difference);
  - the banner reading "Not analyzed / Analyze" on a scored match whose device
    run was interrupted;
  - the gesture bar and the Add button's clearance;
  - on Android, the keyboard over the list: the emulator's hardware keyboard
    showed no inset;
  - on iOS, a signed-in 667pt phone.
- **Open, awaiting Coen's decision**: whether a scored match gets "Analyze
  again", and so whether re-running replaces its results (7.4).
- **Known, pre-existing, not changed here** (reported to Coen):
  - "Remove video from this phone" on a match only on this phone leaves its
    player track on disk, while the confirmation says the heatmap goes with
    it. The match leaves the list, so the track is orphaned. Both platforms.
  - Accent green used as text on white is about 3:1 in light, under AA: text
    buttons (dialog Cancel and Remove, the naming sheet's Skip, the clip
    dialog's Close on Android) take the accent fill colour. App-wide.
