# Home and Analytics Redesign - Design

**Date:** 2026-09-03
**Status:** draft
**Builds on:** the Android client (`AuthGate`, `ClipListScreen`, `ui/theme/*`) and the iOS client (`RootView`, `ClipListView`, `Theme/ShuttlTheme.swift`).
**Reference mock:** `~/Desktop/badminton-design/Rally Home.dc.html` (Home, drawer, Analytics). No other screen has a mock.

## Goal

Replace the app's front door. Today both clients open straight onto a scrolling
list of matches. The mock opens onto a near-empty screen: a rotating line of copy
saying what the product does, and two buttons. The list of matches moves into a
drawer reached by the hamburger, or by a swipe from the left edge.

The mock also carries a different visual language from the one the apps ship
today, so this is two changes that have to land together: a new token layer, and
a new screen built on it.

## Reference: what the mock specifies

- **Palette:** near-black surfaces (`#0B0C0D` page, `#121415` cards, `#161819`
  raised), a single bright green accent `#3EE27C` carrying near-black text
  `#06210F`, and three greys of text (`#F2F4F3`, `#8D938F`, `#5D6462`).
- **Shape:** 999px pill buttons, 20px cards, 16px drawer rows, 14px panels. This
  is a wholesale reversal of the current "sharp corners everywhere" rule.
- **Type:** Archivo, weights 400 to 700, with tight negative tracking on display
  sizes (-0.035em at 40px down to -0.01em at 15px).
- **Home:** wordmark and hamburger at the top, a two-line hero where the second
  line cycles through four phrases, two full-width pill buttons pinned to the
  bottom, and a hint line under them.
- **Drawer:** 330px wide, "On this phone" and "My matches" sections.
- **Analytics:** a match picker with per-row availability dots, then a Heatmap /
  Skeleton tab pair over a court panel and stat tiles.

## What the mock does not settle

Five gaps between the mock and the code, and the decision taken for each.

**1. Analytics rows have three states, not two.** Heatmap availability is
`localAnalysis.storedTrack(entryId)`, keyed to a video file physically on the
phone, and analysis starts from court marking on that file. Cloud-only and shared
matches (`ClipListModel.shared`, carrying `sharerEmail`) have no local entry and
can never acquire one. Offering "tap to analyse" on those rows would promise
something that cannot run. The list therefore distinguishes:

| State | Meaning | Row affordance |
| --- | --- | --- |
| `ready` | a stored track exists | availability dots, opens the detail |
| `analysable` | local entry, no track yet | "Analyse" pill, goes to court marking |
| `notOnDevice` | cloud or shared, no local file | no dots, no action, subtext says so |

**2. The left edge is the system back gesture on Android.** On gesture
navigation the system wins the outer edge strip, so `ModalNavigationDrawer`'s
swipe-to-open is unreliable there. The hamburger is the primary affordance on
both platforms; swipe is a bonus where the platform allows it. The mock's hint
copy "Swipe right for your matches" ships on iOS only. Android shows "Your
matches", tappable, opening the same drawer.

**3. Light mode cannot be a recolour of the mock.** `#3EE27C` on white is 1.6:1
and fails every contrast threshold, which is why `LightAccent` is `#16A34A`
today. Light mode keeps a darker green and will not look like the mock. What
does carry over exactly is the mock's own pattern of near-black text on a green
fill, which passes comfortably in both themes.

**4. Archivo is not bundled.** There are no font files in the repo. This is real
work on both sides (iOS bundle plus `UIAppFonts` in `Info.plist`; Android
`res/font` plus a `FontFamily`), not a token edit. Archivo is SIL OFL 1.1, so
bundling is permitted.

**5. iOS has no on-device analysis.** `AnalyzeCoordinatorIos` is the cloud
upload path. The local inference engine, the pose runner, and `storedTrack` live
under `:androidApp` only, by the deliberate sequencing in
`docs/plans/2026-08-31-on-device-analysis-pipeline-design.md` (Stage 2 is iOS,
after Android). Until that lands, every Analytics row on iPhone is
`notOnDevice`. The screen still ships on iOS with that honest empty state, so
the structure is in place and Stage 2's arrival is a data change rather than a
screen build.

## Scope

| Topic | Decision |
| --- | --- |
| Platforms | iOS and Android together, in lockstep |
| Token depth | Values only: palette, type, shape scale swapped at the token layer. Existing screen layouts inherit the new look and are otherwise untouched |
| Per-screen redesign | Out of scope. Match, ClipDetail, Scoring, Labels, SignIn and CourtMarking have no mocks; redesigning them would be invention |
| Light mode | Kept. A derived, accessible light palette, reviewed separately from the dark one |
| Wordmark | Stays "SHUTTL.". The mock's "Rally" is treated as placeholder |
| Add button | Opens a sheet offering New match / Record video / Import video. No capability lost |
| Drawer contents | On this phone, My matches, Shared with me, plus an account footer (Labels, Sign out, version) |
| Home overflow | An ellipsis menu on Home holds Labels, Sign out, the theme toggle and the version string. Deliberately the same set as the drawer footer, so neither route is a dead end |
| Analytics on iOS | Ships, with every row `notOnDevice` until pipeline Stage 2 |
| Web app | Not changed. Mobile tokens fork from `badminton-tracker/src/app.css`; web catches up later |
| Out of scope | iOS on-device inference, the Skeleton renderer's host screen, launcher icons, splash |

## Phases

Each phase is its own implementation plan under this one design.

1. **Design system.** New palette, Archivo, shape scale, on both platforms. No
   layout moves. Verified by screenshotting every existing screen in light and
   dark on both platforms.
2. **Home, drawer, and navigation ownership.** The new front door, and the move
   of the navigation graph off the clip list. Ships together with phase 1.
3. **Analytics.** List with the three row states, and the detail with its tab
   pair. Android wires its existing `CourtHeatmapView`; iOS gets the same
   structure with the empty state.

Phase 4, the iOS renderers, is not scheduled here. It is downstream of pipeline
Stage 2 and belongs to that design.

## Phase 1: design system

### Files

```
androidApp/src/main/java/com/badmintontracker/android/ui/theme/
  ShuttlColors.kt   new light and dark values; extended palette unchanged in shape
  ShuttlShapes.kt   rounded scale replaces the all-0dp scale
  ShuttlType.kt     Archivo family, new display sizes and tracking
  ShuttlRadius.kt   NEW: the pill constant, which M3 Shapes has no slot for
androidApp/src/main/res/font/
  archivo_regular.ttf, archivo_medium.ttf, archivo_semibold.ttf, archivo_bold.ttf

iosApp/Sources/Theme/
  ShuttlTheme.swift   new colour values
  ShuttlType.swift    NEW: Archivo font styles mirroring ShuttlType.kt
  ShuttlRadius.swift  NEW: the shape scale
iosApp/Resources/Fonts/
  Archivo-Regular.ttf, Archivo-Medium.ttf, Archivo-SemiBold.ttf, Archivo-Bold.ttf
iosApp/Sources/Info.plist   UIAppFonts entries
```

### Palette

Dark is taken from the mock. Light is derived to hold the same roles at
accessible contrast; it is not a recolour.

| Token | Dark | Light | Notes |
| --- | --- | --- | --- |
| `bg` | `#0B0C0D` | `#FFFFFF` | |
| `bgSecondary` | `#121415` | `#F5F7F6` | cards, tab track |
| `bgTertiary` | `#161819` | `#EDF0EE` | secondary button, drawer rows |
| `bgInput` | `#0E0F10` | `#EDF0EE` | |
| `border` | `#1A1D1E` | `#E3E6E4` | |
| `borderSecondary` | `#22262A` | `#CED3D0` | |
| `textHeading` | `#F2F4F3` | `#0B0C0D` | |
| `text` | `#F2F4F3` | `#16191A` | |
| `textSecondary` | `#8D938F` | `#545C58` | 6.9:1 on light bg |
| `textTertiary` | `#5D6462` | `#6E7672` | 4.7:1 on light bg |
| `accent` | `#3EE27C` | `#16A34A` | fill only |
| `onAccent` | `#06210F` | `#04240F` | near-black on green, both themes |
| `accentDark` | `#22C55E` | `#15803D` | accent as text, and pressed states |
| `sideHome` | `#14532D` | `#15803D` | unchanged; see the existing rationale |
| `sideAway` | `#1E3A8A` | `#1D4ED8` | unchanged |
| `error` / `warning` / `info` | unchanged | unchanged | |

The accent is a fill colour, never a text colour. Accent-coloured text uses
`accentDark`, which clears 4.5:1 in both themes; `accent` on light bg does not.

### Shape

`ShuttlShapes` stops being all-zero:

| Slot | Radius | Used by |
| --- | --- | --- |
| `pill` | 999 | buttons, chips, badges, tab pills |
| `extraLarge` | 28 | sheets |
| `large` | 20 | cards, stat tiles, analytics rows |
| `medium` | 16 | drawer rows |
| `small` | 12 | inputs |
| `extraSmall` | 8 | thumbnails |

`pill` is a separate constant because M3's `Shapes` has no slot for it and
`ShuttlRadius` on iOS mirrors the same names.

Both `ShuttlShapes.kt` and `ShuttlTheme.swift` carry doc comments asserting
"sharp corners everywhere". Those are rewritten, not left to contradict the code.

### Type

Archivo throughout. Sizes and tracking mirror the mock.

| Role | Size / weight / tracking | Mock source |
| --- | --- | --- |
| `display` | 40 / 500 / -0.035em, line height 1.08 | hero |
| `headlineLarge` | 28 / 500 / -0.03em | "Pick a match" |
| `headlineMedium` | 22 / 500 / -0.02em | drawer title |
| `statNumber` | 26 / 500 / -0.03em | stat tiles |
| `titleLarge` | 16 / 600 / -0.01em | button label, row title |
| `titleMedium` | 15 / 600 / -0.01em | screen title |
| `bodyLarge` | 16 / 400 | |
| `bodyMedium` | 14 / 400 | drawer row title |
| `bodySmall` | 12 / 400 | row subtitle |
| `labelSmall` | 11 / 500 / +0.05em, uppercase | unchanged role |

### Verification

Every existing screen is screenshotted in light and dark on both platforms
before and after. A screen whose layout breaks under rounded corners or a
different font metric is fixed in place; it is not redesigned.

Before starting, grep both clients for hardcoded colours and radii that bypass
the token layer, since those are what the swap will miss.

## Phase 2: Home, drawer, and navigation ownership

### Home layout

Top bar, in reading order: hamburger on the left, where the drawer comes from
and where the swipe starts; the "SHUTTL." wordmark; an ellipsis menu on the
right holding Labels, Sign out, the theme toggle and the version string. This
deviates from the mock, which puts the hamburger on the right, because a
hamburger opposite the edge it opens from reads as an unrelated control. The
theme toggle moves from a visible top-bar icon on Android into that menu, which
costs it some discoverability and buys a top bar with two controls instead of
four.

Body: the hero sits at a fixed offset from the top, not centred, so the line
does not shift as phrases of different lengths cycle through it. First line
"Your game," in `textHeading`; second line is the ticker, in `textTertiary`.

Bottom, pinned: the primary pill button "Add new match" in `accent` with
`onAccent` text and a plus glyph; below it the secondary pill "Analytics" in
`bgTertiary`; below that the hint line, tappable, opening the drawer. Both
buttons are 60pt tall and full width inside a 24pt gutter.

### Hero ticker

Four phrases, from the mock verbatim: "clipped rally by rally.", "mapped as
heatmaps.", "tracked as skeletons.", "annotated and shared." They live in one
named constant per platform so copy can change without touching layout.

2600ms dwell, 380ms transition. Outgoing phrase fades out and rises 12pt;
incoming fades in and rises from 14pt. The mock's blur is dropped: per-frame
text blur is expensive on both platforms and Compose's blur needs API 31.

The ticker stops when the screen is not visible and when the drawer is open.
Under Reduce Motion (`accessibilityReduceMotion` on iOS, animator duration scale
of zero on Android) it shows the first phrase and does not cycle. The index
advance is a pure function so it can be unit tested without a running animation,
following the precedent of `FrameStepMath` and `CourtTapMath`.

### Drawer

Width `min(330, 86% of screen width)`. Header "Matches" with a close control.
Sections in order: On this phone, My matches, Shared with me. Rows are the
existing ones, moved not rewritten, so swipe-to-delete, the confirmation
dialogs, thumbnails and progress all behave exactly as today. A footer pinned to
the bottom holds Labels, Sign out and the version string.

**iOS gestures.** SwiftUI has no drawer primitive, so this is an overlay plus a
`DragGesture`: a 26pt left-edge zone on Home to open, 70pt or a velocity
threshold to commit. Closing is by the header control, a scrim tap, or a drag
starting on the scrim. A close-drag is deliberately not accepted from inside the
list body, because the rows carry `.swipeActions` and a horizontal drag there
would be ambiguous. The open threshold is a pure function, unit tested.

**Android gestures.** `ModalNavigationDrawer` with gestures enabled, understood
as a bonus rather than the route in. See gap 2 above.

### Navigation ownership

`ClipListView` and `ClipListScreen` own the whole navigation graph today, not
just a list. Moving their body into a drawer means those registrations need a
new owner.

On iOS, `HomeView` becomes the `NavigationStack` root and takes over
`MatchRoute`, `CourtMarkingRoute`, `LabelsView`, `LocalPlayerRoute` and
`createFlowTarget`. `ClipListView`'s body becomes `MatchesDrawerContent`, which
renders rows and reports taps upward, holding no navigation state.

`CreateFlowDestination` moves verbatim, doc comment included. It records a
confirmed on-device failure where a pop on one binding racing a push on another
left a destination blank for the full length of a thirty second wait; the
single-binding shape is the fix and must survive the move intact.

On Android, `Route.Home` is added and becomes the authenticated start
destination. `Route.ClipList` is removed. `LocalBackgroundWorkClick` currently
navigates to `Route.ClipList` with `popUpTo` plus `launchSingleTop`; it becomes
a navigation to `Route.Home` with the same guards, opening the drawer on
arrival.

`LocalAnalysisBanner`, which sits above the clip list today so an in-flight run
is visible on return, moves to Home for the same reason. It is more visible
there, not less.

### Files

```
androidApp/src/main/java/com/badmintontracker/android/home/
  HomeScreen.kt, HeroTicker.kt, MatchesDrawer.kt, AddMatchSheet.kt
androidApp/src/main/java/com/badmintontracker/android/nav/Route.kt   Route.Home

iosApp/Sources/Home/
  HomeView.swift, HeroTicker.swift, MatchesDrawer.swift, AddMatchSheet.swift,
  DrawerDragMath.swift
```

## Phase 3: Analytics

### Row state

The classification is a pure function in `shared/commonMain` so both clients and
their tests agree on it:

```kotlin
enum class AnalyticsRowState { READY, ANALYSABLE, NOT_ON_DEVICE }

fun analyticsRowState(hasLocalEntry: Boolean, hasTrack: Boolean): AnalyticsRowState
```

Android supplies `hasTrack` from `localAnalysis.storedTrack(id) != null`. iOS
supplies `false` until pipeline Stage 2, which is what makes every iOS row
`NOT_ON_DEVICE` without any iOS-specific branch in the UI.

### List

Every match, in the same three groups the drawer uses. A legend at the top says
what the dots mean. A `READY` row shows a green dot for heatmap and a grey one
for skeleton, and opens the detail. An `ANALYSABLE` row shows an "Analyse" pill
going to court marking. A `NOT_ON_DEVICE` row is inert, with a subtitle saying
the video is not on this phone.

When every row is `NOT_ON_DEVICE`, which is the whole iOS list today, the screen
leads with a single explanatory line rather than repeating it per row.

### Detail

A pill tab pair over the content, showing only the tabs that have something
behind them. Android's Heatmap tab hosts the existing `CourtHeatmapView` and
keeps its current behaviour, including the "no longer loaded" message after a
process death.

The Skeleton tab does not ship in this phase on either platform.
`SkeletonOverlay` exists on Android but has no host screen and no caller, and
giving it one is a separate piece of product work, not a redesign.

`Route.Heatmap(entryId)` is kept and reached from Analytics as well as from the
banner, rather than being replaced by a new route.

### Files

```
androidApp/src/main/java/com/badmintontracker/android/analytics/
  AnalyticsScreen.kt, AnalyticsDetailScreen.kt
androidApp/.../nav/Route.kt   Route.Analytics
iosApp/Sources/Analytics/
  AnalyticsListView.swift, AnalyticsDetailView.swift
shared/src/commonMain/kotlin/com/badmintontracker/shared/analytics/
  AnalyticsRowState.kt
```

## Testing

| Level | What |
| --- | --- |
| Shared unit | `analyticsRowState` across all input combinations |
| iOS unit | hero index advance, drawer open threshold, reduce-motion path |
| Android unit | the same three, plus existing ViewModel suites staying green |
| Visual | every screen, light and dark, both platforms, before and after phase 1 |
| Manual | drawer open and close by every route on both platforms; swipe-to-delete inside the drawer on iOS; Android edge swipe under gesture navigation and under three-button navigation |

The existing suites are the regression net for phase 2: `ClipListViewModelTest`,
`MatchModelTests` and the create-and-finish flow must pass unchanged after the
navigation graph moves. Any test that needs editing to pass is a signal that
behaviour moved when it should not have.

## Risks

- **Hardcoded values bypassing the token layer** are what the phase 1 swap will
  miss. Grepping for literal colours and radii before starting is cheaper than
  finding them in screenshots afterwards.
- **The iOS drawer drag versus row swipe actions.** Mitigated by refusing the
  close-drag inside the list body, but it needs on-device confirmation, not a
  simulator check.
- **Font metric changes** shift every layout slightly. Archivo is not
  metric-compatible with either system font, so tight rows may reflow.
- **Analytics on iOS ships empty** and stays empty until pipeline Stage 2. This
  is accepted knowingly; the empty state has to be written so it reads as "not
  yet on iPhone" rather than as a broken screen.
