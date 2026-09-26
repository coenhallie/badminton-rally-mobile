# Design: bottom navigation, with the match list as the landing page

**Date:** 2026-09-25
**Status:** implemented on `feat/bottom-navigation` (2026-09-26); real-phone checks open
**Asked for:** "We can list recent matches on the landing page instead of just showing
3 buttons (two big buttons and one link your matches). The add match button can be on
the bottom right corner." Plus: can a bottom navigation bar carry Analytics and
creating a match?
**Visual mock:** `~/Desktop/badminton-design/home-navigation/home-navigation-options.html`
(Option A frames; a portable copy sits next to it).
**Supersedes:** the Home and drawer parts of `2026-09-03-home-and-analytics-redesign-design.md`
(phase 2). The token layer (phase 1) and the Analytics row model (phase 3) stand.
**Builds on:** `2026-09-25-unified-match-list-design.md`. Its list moves; nothing in it
changes.

The app opens on a front door with a hero line, two pill buttons and a link to a
drawer. The coach's own matches are one tap away and the app's most used surface is
hidden. This moves the match list to the landing page, puts Analytics and Settings in
a three-tab bottom bar, and puts Add in the bottom-right corner.

---

## 1. What exists today

Verified by reading the code.

### 1.1 Android

- One `NavHost` in `AuthGate.kt:268-761`. The authenticated start destination is
  `Route.Home` (`nav/AuthNavigation.kt:11-13`, pinned by `AuthNavigationTest.kt`).
- `Route.Home` renders `HomeScreen` (`home/HomeScreen.kt:78-386`): a
  `ModalNavigationDrawer` around a `Scaffold`. The top bar holds the hamburger, the
  wordmark, `BackgroundWorkAction()` and an overflow menu (Labels, Sign out, theme
  toggle, version; `:272-302`). The body is the hero ticker and two pills; "Add new
  match" opens `AddMatchSheet`, "Analytics" navigates to `Route.Analytics`.
- The drawer shell is `home/MatchesDrawer.kt` (header, close, footer with Labels,
  Sign out and the version drawn a second time). The list inside it is
  `cliplist/ClipListScreen.kt`, which deliberately has no `Scaffold` of its own
  (`:270-275`) and owns its `LazyColumn` state privately.
- `Route.Analytics` renders `AnalyticsScreen` (`analytics/AnalyticsScreen.kt:129`)
  with its own `Scaffold`, a back arrow and `BackgroundWorkAction()`. It builds its
  own `ClipListViewModel` instance (`AuthGate.kt:340-359`).
- The background-work sheet's "Go to matches" calls `LocalBackgroundWorkClick`,
  which navigates to `Route.Home` and sets `pendingDrawerOpen` (`AuthGate.kt:150,
  253-266`).
- A finished on-device run navigates to `Route.Analytics` only while the coach is
  on `Route.Home` (`AuthGate.kt:233-242`, rule in shared `announceFinishedRuns`,
  `RunAnnouncement.kt:40-47`).
- Scoring finishing pops to `Route.Home` and pushes the match (`AuthGate.kt:450-456`).
- No "analysis banner" exists on Home. The earlier design named one; what shipped
  is the app-bar indicator plus its sheet.

### 1.2 iOS

- `RootView` shows `HomeView` inside `BackgroundWorkScope` when authenticated.
- `HomeView` (`Home/HomeView.swift`) owns one `NavigationStack` (`:109`) with every
  destination registered on it as a push: court marking, labels, analytics, match,
  new match, the create flow, local player, local clips, analytics detail
  (`:133-205`). Scoring, court marking and the player are pushes, not covers.
- `CreateFlowDestination` (`:4-28`) folds "new match -> board -> match page" into one
  binding. Its doc comment records an on-device blank-destination race between a pop
  on one binding and a push on another. The fix is the single binding.
- `.feedsMatchList(...)`, the finished-runs task, and `backgroundWorkClick` sit on
  that stack (`:212-240`) because it is the one view that stays mounted under every
  push. `ClipListModel` is created once in `HomeView.init` and shared by the drawer
  list and `AnalyticsListView`.
- The drawer is a hand-built overlay (`MatchesDrawer.swift`, `DrawerDragMath.swift`,
  tested by `DrawerDragMathTests.swift`). The list inside is `MatchesList` in
  `ClipList/ClipListView.swift`, which relies on the drawer panel for its background
  (`:190-192`).
- `AnalyticsListView` registers its own court-marking and detail destinations one
  level deeper than Home's, because it is itself pushed (`AnalyticsListView.swift:25-35,
  93-104`).
- `isOnHome` (`HomeView.swift:347-360`) feeds `announceFinishedRuns`.
- Four UI tests enter through the overflow menu (`"Menu"`) and the hamburger
  (`"Open matches"`), and `XCTSkip` when those are missing
  (`ScoringBoardUITests.swift:135-147,204`, `VideoMatchDetailsUITests.swift:20-23`,
  `ReplayPlayerUITests.swift:19-22`, `LabelSwatchGridUITests.swift:68-73`). Removing
  the controls would turn them into silent skips, not failures.

### 1.3 Shared

- `HeroTicker` (`shared/.../home/HeroTicker.kt`) holds the hero copy and the index
  rule, tested by `HeroTickerTest`.
- `MatchListCopy` holds every list string. Home and drawer strings ("Add new match",
  "Sign out", the version line) are literals on each platform.
- `ThemeMode` is `LIGHT` / `DARK` only; there is no follow-system mode.
- `AuthRepository` has `currentUserId()` but nothing that returns the email.

## 2. The design

### 2.1 Structure

The tab bar lives **inside** the existing root destination on both platforms, not
around the navigation stack.

- **Android:** `Route.Home` stays the start destination and becomes the tab host.
  Its `Scaffold` carries the `NavigationBar` and the Add button. Every other route
  (match, scoring, court marking, player, labels, analytics detail, heatmap, local
  clips, new match) still pushes onto the one `NavHost`, so it covers the bar.
- **iOS:** `HomeView`'s one `NavigationStack` stays. Its root content becomes a
  `TabView`. Every destination stays registered on the stack as today, so a push
  covers the whole `TabView`, bar included.

Why this and not a stack per tab: every full-screen flow (the scoring board in
particular) hides the bar with no per-screen code; `CreateFlowDestination`,
the match-list feed and the finished-run watcher keep the one always-mounted parent
they were built around; and Android and iOS stay one shape. The cost is that a tab
does not keep its own push history across a tab switch. Nothing in this app needs
that: every push is a full-screen task the coach finishes and comes back from.

### 2.2 Tabs

A shared enum, `HomeTab { MATCHES, ANALYTICS, SETTINGS }`, in
`shared/.../home/HomeTab.kt`, and one `HomeCopy` object for every string this
design adds or moves, so the platforms cannot drift.

| Tab | Icon | Content |
| --- | --- | --- |
| Matches (default) | two stacked rows | the unified list, full width |
| Analytics | three bars | today's analytics list, as a tab root |
| Settings | sliders | Labels, dark theme switch, signed-in email, sign out, version |

The selected tab survives rotation and process death on Android (`rememberSaveable`)
and is `@State` on iOS.

**Who owns it.** On Android the selected tab is hoisted to `AuthGate`, above the
`NavHost`, because the finished-run watcher there needs it. `HomeScreen` receives it
and reports changes. On iOS it is `@State` on `HomeView`, next to the routes.

**Tab state survives a switch.** On Android each tab's content sits in a
`SaveableStateHolder` keyed by tab, and the Matches list's `LazyListState` is hoisted
to the tab host (the Add button reads it too). Leaving Matches for Settings and
coming back keeps the filter and the scroll position. (A sheet or dialog open in the
list cannot be left by hand: it is modal over the bar. The one automatic switch, a
finished run, is gated on no overlay being open; see 2.8.) On iOS both tabs stay
mounted inside `TabView`, which gives the same result.

### 2.3 Matches tab

- Top bar: the title "Matches" (headline style, as the drawer header was) and
  `BackgroundWorkAction` on the right. No hamburger, no overflow menu, no wordmark.
- Record and import errors ("Camera is not available", "Couldn't import the
  video") show here, since Add is only on this tab: iOS moves `ErrorBanner(intake.error)`
  from above the hero to the top of this tab; Android's intake snackbar moves to the
  tab host's one `SnackbarHost`.
- Body: the existing list component, unchanged in behaviour: filter chips, legend,
  rows, swipe, menus, dialogs, pull to refresh, "Shared with me". It gains the
  screen's own background and bottom padding so the last row clears the bar and the
  Add button.
- **Empty state** (no own and no shared matches, not refreshing): the hero moves
  here. `HeroTicker.leadLine` over the rotating phrase, then one line of body copy
  from `HomeCopy`. The list's old empty state and its "Add new match" button go: the
  Add button is on screen, and two controls doing one thing is noise. The ticker
  pauses while the tab is not selected, while anything is pushed, and under Reduce
  Motion, as today.

### 2.4 The Add button

- Label **"Add match"**. The mock said "New match", but the sheet it opens lists
  "New match" as one of three options, and a button named after one of its own
  options reads wrong. "Add match" is also the boss's wording.
- Shown on the Matches tab only.
- **Android:** an `ExtendedFloatingActionButton` in the accent fill with `onAccent`
  content, pill-shaped, bottom-right above the bar. Expanded while the list is at
  its top, collapsed to the "+" once it is scrolled. What shipped is
  `addButtonExpanded(index: Int)`, a pure function of the first visible index
  alone, not `addButtonExpanded(index, offset)` as drafted here; see section 6. It
  needs the list's `LazyListState`, which `ClipListScreen` starts accepting as a
  parameter.
- **iOS:** a 56pt round accent button with a "+" glyph, bottom-right above the tab
  bar, accessibility label "Add match". It does not change size; there is no
  iOS 17 scroll-position API worth taking on for it. The mock drew it beside the
  iOS 26 floating bar; whether that fits next to a three-tab bar is checked on the
  simulator, and if it does not, it floats above, as on Android. It did not fit;
  see section 6 for the measured clearance.
- Both open the existing `AddMatchSheet` unchanged.

### 2.5 Analytics tab

- `AnalyticsScreen` / `AnalyticsListView` lose the back button and become tab
  content, titled "Analytics", with `BackgroundWorkAction`. The "Pick a match"
  headline goes (the tab title does its job, as in the mock); the legend line stays.
- They read the same list model instance as the Matches tab. On Android that means
  one `ClipListViewModel` and one `LocalVideoListViewModel` per `Route.Home` entry,
  not two; on iOS it already is the same instance.
- `Route.Analytics` is removed on Android. iOS's `showAnalytics` push is removed.
- **iOS:** `AnalyticsListView`'s own court-marking and detail registrations move to
  `HomeView`'s existing `courtMarkingRoute` and `analyticsDetailRoute`, reported
  upward by callback as `MatchesList` already does. Registered at the root depth
  twice, they would collide.
- The empty-state copy that says "Add a match on Home" is rewritten to point at the
  Matches tab.

### 2.6 Settings tab

Two cards, in this order: Labels (pushes Labels) and a "Dark theme" switch bound to
`ThemePreferenceRepository`; then "Signed in as <email>" and "Sign out". The version
line sits below. This replaces the Home overflow menu and the drawer footer, so the
version string is drawn in one place.

`AuthRepository` gains `currentUserEmail(): String?`, read from the same
`currentUserOrNull()` as `currentUserId()`. When it is null the "Signed in as" row is
not drawn.

The sign-in screen's own theme toggle is untouched.

### 2.7 Routing that used to name Home or the drawer

| Today | Becomes |
| --- | --- |
| "Go to matches" (background-work sheet): go Home, open drawer | pop to the tab host, select Matches |
| Finished device run while on Home: open Analytics | while the tab host is on screen with nothing pushed or presented **and** Matches is selected: select Analytics. The shared rule (`announceFinishedRuns`) is unchanged. What each platform passes as `onHome` comes from one shared function, `HomeTab.isHomeFront(tab, nothingAbove)` |
| Scoring finished: pop to Home, push match | unchanged; Home is now the tab host |
| Android `pendingDrawerOpen` | removed: `AuthGate` owns the selected tab, so "Go to matches" pops to `Route.Home` and sets it |
| Android `onAutoDetailsClosed` (after a new import's details sheet): open the drawer so the new row shows | nothing to do; the coach is already on the Matches tab looking at the row. The parameter goes |
| iOS `goToMatches()` | clears routes and sheets as today, selects Matches |

### 2.8 "Nothing above" on Android

`isHomeFront` needs to know that nothing covers the tab host. On Android that is: the
current back-stack entry is `Route.Home`, the Add sheet is closed, and no sheet or
dialog of the list is open. The last one is private state in `ClipListScreen` today
(share, details, legend, delete confirmations, analyze result). It starts reporting
one `onOverlayChange(open: Boolean)` upward. Without it a finished run could switch
tabs under a half-typed details sheet.

On iOS `isOnHome` already lists every sheet and route `HomeView` owns; it gains
`selectedTab` and loses the entries that are deleted.

### 2.9 Android layout rules

- One outer `Scaffold` in the tab host owns the `NavigationBar`, the Add button and
  the one `SnackbarHost`. `ClipListScreen`'s own bottom-centred snackbar host moves
  up into it, or snackbars land under the bar.
- Tab content has no `Scaffold` of its own. `contentWindowInsets = WindowInsets(0)`
  shipped as drafted, but is not the whole story: since it also zeroes the
  horizontal insets, the tab host's content additionally takes the horizontal
  safe-drawing insets on its own `Box`, so landscape and a side gesture cutout do
  not eat into content; see section 6. `AnalyticsScreen` keeping its own
  `Scaffold` would pad the bottom twice.
- Every colour and shape is set explicitly, not left to Material roles: bar container
  `bgSecondary` with a top `border` line, indicator `glyphBg`-style accent tint,
  selected icon `analysisGlyph`, selected label `textHeading`, unselected `textSecondary`;
  the Add button `accent` fill, `onAccent` content and the pill shape (Material's
  default FAB has 16dp corners). The unified-list work already found Material's default
  lavender on menus; this is the same trap.
- Light theme: `bgInput` and `bgTertiary` are the same colour, so no surface in the tab
  host stacks one on the other.

### 2.10 What is deleted

- Android: `home/MatchesDrawer.kt`, the `ModalNavigationDrawer` and its width math,
  the hero and pills on Home, the overflow menu, `Route.Analytics`.
- iOS: `Home/MatchesDrawer.swift`, `Home/DrawerDragMath.swift`,
  `Tests/DrawerDragMathTests.swift`, the hamburger, the overflow menu, the pills.
- Comments that describe the drawer or Home's pills are rewritten, not left behind.

## 3. Decisions

| Question | Chosen | Rejected, and why |
| --- | --- | --- |
| Where the bar lives | Inside the root destination; pushes cover it | A stack per tab: splits the one always-mounted parent the create flow, feed and run watcher depend on, and needs per-screen code to hide the bar on the board |
| Tabs | Matches, Analytics, Settings | Two tabs plus an avatar: under Material's three-destination floor; a centre "+" tab (Option B): a tab that opens a sheet, and needs a custom bar on iOS 26 |
| Add button label | "Add match" | "New match" (mock): the same words as the first option in the sheet it opens |
| Android Add behaviour | Extended at the top, "+" once scrolled | Always extended: covers row menus. Always "+": loses the label |
| iOS Add button | Round "+", fixed size | Resizing on scroll: needs a scroll-position API iOS 17 does not have cleanly |
| Empty list | Hero on the Matches tab, no second Add | Keep the list's "Add new match" empty-state button: duplicates the Add button |
| Auto-open Analytics on a finished run | Only from the Matches tab root | From any tab: would pull a coach out of Settings mid-task. Never: loses the behaviour the run watcher exists for |
| Signed-in email | Shown, via a new `AuthRepository` accessor | Omitted: a coach with two accounts has no way to tell which one they are in |
| Theme control | A switch, still two-valued | A System / Light / Dark picker: a new mode nobody asked for |

## 4. Deliberately not in this pass

- Cross-match analytics (trends, a season heatmap). The Analytics tab ships with
  today's list; what it grows into is its own design.
- A follow-system theme mode.
- Changing `AddMatchSheet` (the mock's icons and descriptions were marked as a
  suggestion).
- Per-tab push history.
- Re-selecting the current tab to scroll its list to the top.

## 5. Verification

- Shared unit: `isHomeFront` over every tab, with and without something above.
- Android unit: `addButtonExpanded`, `AuthNavigationTest` unchanged and green, the
  existing `ClipListViewModel`, `RowGesturesTest` and `AnalyticsRowsTest` suites
  unchanged and green.
- iOS unit: the suites green after `DrawerDragMathTests` is deleted;
  `TypographyAdoptionTests` and `SystemImageTests` clean over the new files.
- iOS UI tests: all four re-pointed at the tabs (Settings -> Labels; Add match ->
  New match; the Matches tab for delete), run, and confirmed to execute, not skip.
- Screens, light and dark, both platforms: Matches with rows, Matches scrolled
  (Android button collapsed, last row clear), Add sheet, Analytics, Settings, empty
  Matches, scoring board with no bar showing. Compared against the mock. What was
  actually captured, and what is still open, is in section 6.
- On a real phone: the Android gesture bar and the bar's insets, the iOS 26 floating
  bar, and a finished device run switching tabs only from Matches.

## 6. What shipped differently

Decided during implementation (`docs/plans/2026-09-25-bottom-navigation-plan.md`
plus review rulings):

- **Android's `addButtonExpanded` takes the first visible index only, not
  `(index, offset)` as 2.4 drafted.** Item 0 in the list is the filter row, which
  gives a stable collapse point with no pixel threshold to tune.
- **The Android Add button's icon carries "Add match" in both states**, not just
  while collapsed. Material 3 1.3.1 clears the extended label's own semantics, so
  a label-only expanded state would leave the button with no accessible name.
- **Tab bodies under the title bar use `Modifier.weight(1f)` in a `Column`,
  not `fillMaxSize()` as the plan had it.** `fillMaxSize()` on a sibling below a
  fixed-height `TabTopBar` overflows the column; `weight(1f)` gives it the
  remaining space instead.
- **The Settings switch's off state draws a background track with an
  onSurfaceVariant outline**, rather than the `bgTertiary` track the plan
  specified. `bgTertiary` measured 1.07:1 against the card in light theme; the
  onSurfaceVariant outline clears 3:1 in both themes.
- **Android resets the saved tab to Matches on arrival after sign-in** (the
  `TO_HOME` redirect and `SignIn`'s `onSignedIn`), not on sign-out (the
  `TO_SIGN_IN` redirect) as the plan had it. Resetting on sign-out flashes the
  list during the sign-out crossfade, and arrival also covers process death
  followed by an invalid session, which the sign-out path misses.
- **The tab host's content additionally takes the horizontal safe-drawing
  insets on its own `Box`.** `contentWindowInsets = WindowInsets(0)` shipped as
  2.9 drafted, but since it zeroes every inset, including the horizontal ones,
  a `windowInsetsPadding(WindowInsets.safeDrawing.only(Horizontal))` was added
  on top so a landscape or gesture-nav side inset does not eat into content;
  see the correction in 2.9.
- **Android system Back on Analytics or Settings returns to Matches**; only
  Back on Matches leaves the app. Not in this design's scope in section 2, but
  specified in the implementation plan's review focus and shipped as written.
- **Opening the Analytics tab refreshes the list on Android**, via
  `LaunchedEffect(selectedTab)`, matching iOS's per-appearance `.task`. This
  keeps the behaviour section 1.1 describes today, where every visit to
  `Route.Analytics` built a fresh `ClipListViewModel` whose init refreshed; the
  tab host now shares one view model instance, so the refresh has to be made
  explicit instead.
- **Both hero tickers reserve the tallest phrase's height** (except under Reduce
  Motion on iOS, where only the first phrase ever shows), so the empty-state
  body copy never jumps as the phrase cycles.
- **iOS's Analytics tab no longer awaits `start()` before `refresh()` inside the
  same `.task`.** The plan's `.task { await model.start(); await model.refresh() }`
  never actually refreshed: `ClipListModel.start()` does not return while the
  view is on screen, so the awaited `refresh()` after it never ran. It now spawns
  `refresh()` as its own unstructured child task and awaits `start()` separately:
  `.task { Task { await model.refresh() }; await model.start() }`.
- **The iOS Add button floats above the centred iOS 26 floating tab bar**, 16pt
  clear of it, not beside it as the mock drew and 2.4 conditioned on a fit
  check. The system leaves 65pt to the bar's right on the iPhone 17 Pro
  simulator; the button needs 72pt, so it does not fit beside the bar.
- **iOS tab labels use the system font.** iOS 26's floating bar ignores
  `UITabBarItem` appearance fonts, so `ShuttlType` cannot reach them. Only the
  selected tab's SF Symbol is filled; the rest stay outlined.
- **Tab icons are lucide (Android) and SF Symbols (iOS)**, not the mock's
  hand-drawn glyphs. Both apps already draw every icon from one of these two
  sets, and the mock's SVGs were illustrative.
- **Verification is incomplete, and asymmetric across platforms.** The
  Android emulator's session was revoked by the server
  (`refresh_token_already_used`) before Task 5's captures, so its screenshots
  were taken signed out through a temporary, uncommitted harness: cloud rows,
  the collapsed Add button and the tab-switch check are still open on Android
  for a signed-in pass. iOS's captures were taken signed in, with the Settings
  email row redacted. Neither platform's scoring-board screen was captured; it
  needs a match created on the account first. Open for Coen on a real phone.
