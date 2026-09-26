# Bottom Navigation Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Replace Home's hero, pills and side drawer with a three-tab bottom bar (Matches, Analytics, Settings) on Android and iOS, with the match list as the landing tab and an "Add match" button bottom-right.

**Architecture:** The tab bar lives inside the existing root destination on both platforms (`Route.Home` on Android, `HomeView`'s one `NavigationStack` root on iOS), so every pushed screen still covers it. A shared `HomeTab` enum, `isHomeFront` rule and `HomeCopy` strings keep the platforms in step. The list, Analytics list and their row code are reused unchanged in behaviour; only their chrome moves.

**Tech Stack:** Kotlin Multiplatform (commonMain + kotlin.test/kotest), Jetpack Compose Material 3, SwiftUI via SKIE, xcodegen.

**Spec:** `docs/plans/2026-09-25-bottom-navigation-design.md`. Read it first. The mock is `~/Desktop/badminton-design/home-navigation/home-navigation-options.html` (Option A frames). The spec wins where the mock differs ("Add match", not "New match").

## Global Constraints

- **Branch** `feat/bottom-navigation`, single working tree `/Users/coenhallie/Desktop/projects/badminton-rally-mobile`. Implementers run **one at a time**: Gradle's lock, the git index and the iOS build copy are shared.
- **iCloud.** The repo is iCloud-synced. If Gradle stalls or fails on `* 2.*` files: `for d in shared/build analysis/build androidApp/build; do find "$d" -name "* [0-9].*" -delete; done`; dataless build output: `find <module>/build -flags +dataless | wc -l`, delete that module's `build/test-results`/`build/reports` (or `build`) and rerun. An "Unresolved reference" in a file you did not touch may be an evicted source: `git ls-files | while read f; do [ -e "$f" ] || echo "$f"; done`, restore with `git show "HEAD:$f" > "$f"` only after checking `git diff HEAD --stat` shows the deletion is not someone's work. A stale 0-byte `.git/index.lock` with no git process running may be removed.
- **Android tests:** `./gradlew --offline :shared:jvmTest` and `./gradlew --offline :androidApp:testDebugUnitTest`. Judge by Gradle's own output (`BUILD SUCCESSFUL` / `BUILD FAILED`, `^e:` lines), never by a background task's exit code; end background commands with `echo "gradle exit ${pipestatus[1]}"`.
- **Android devices:** `adb devices` often lists Coen's phone too. Always `ANDROID_SERIAL=emulator-5554` for installs; never install a verification build on the phone. Emulator: `~/Library/Android/sdk/emulator/emulator -avd Pixel_9a -dns-server 8.8.8.8,1.1.1.1`.
- **iOS builds** run from a copy outside iCloud:
  ```bash
  export DEVELOPER_DIR=/Applications/Xcode.app/Contents/Developer
  R=/Users/coenhallie/Desktop/projects/badminton-rally-mobile
  C=~/Library/Caches/rally-ios-src
  rsync -a --delete --exclude .git --exclude build --exclude .gradle --exclude .kotlin --exclude .claude --exclude .superpowers --exclude .idea --exclude tools --exclude docs --exclude supabase/.temp --exclude iosApp/iosApp.xcodeproj "$R/" "$C/"
  mkdir -p "$C/tools/models/onnx" && cp "$R/tools/models/manifest.json" "$C/tools/models/" && cp "$R"/tools/models/onnx/{tracknet,badminton,posen}.fp16.onnx "$C/tools/models/onnx/"
  cd "$C" && xcodegen generate --spec iosApp/project.yml --project iosApp && xattr -cr iosApp/Sources iosApp/Tests iosApp/UITests iosApp/Resources iosApp/iosApp.xcodeproj 2>/dev/null
  xcodebuild test -project iosApp/iosApp.xcodeproj -scheme iosApp -destination "platform=iOS Simulator,name=iPhone 17 Pro" -derivedDataPath ~/Library/Caches/rally-ios-derived CODE_SIGNING_ALLOWED=NO 2>&1 | tee /tmp/ios-test.log | grep -E "Executed|error:|FAILED|TEST (SUCCEEDED|FAILED)"
  ```
  `iosAppTests` and `iosAppUITests` report separately; add both counts. `** TEST SUCCEEDED **` also prints for skipped UI tests: read `Test Case '...' (passed|skipped)` lines in `/tmp/ios-test.log`. When a Swift file is added or removed, also run `xcodegen generate --spec iosApp/project.yml --project iosApp` **in the real repo** and commit the regenerated `iosApp/iosApp.xcodeproj/project.pbxproj` (tracked, generated: never hand-edit), and confirm the new file's `.o` exists under `~/Library/Caches/rally-ios-derived` before trusting a green build.
- **SKIE:** Kotlin enums are Swift enums (`HomeTab.matches`, `.allCases`); top-level functions are `FileNameKt.fn(...)`; objects are `Thing.shared`; Kotlin default arguments do not reach Swift.
- **iOS lint tests** walk `Sources`: `TypographyAdoptionTests` fails any `.font(` outside `Theme/` (use `.shuttlType(ShuttlType.x)`); `SystemImageTests` checks every `Image(systemName: "literal")` resolves.
- **iOS deployment target is 17.** Use `TabView(selection:)` with `.tabItem { }` and `.tag(HomeTab.x)`, not the iOS 18 `Tab` API.
- **Copy:** no em dash in any string, comment or commit message. Sentence case. New strings live in `HomeCopy` (shared), never re-typed per platform. US spelling ("analyze").
- **Colour:** tokens only (`ShuttlTheme.extended.*` / `MaterialTheme.colorScheme.*` on Android, `Shuttl.*` on iOS). Material components get every colour and shape set explicitly. Light theme: `bgInput == bgTertiary` (`0xEDF0EE`), so never stack one on the other.
- **Commits:** conventional prefix, body says why. **No `Co-Authored-By` trailer, no "Generated with" footer.** Do not hand-edit `CHANGELOG.md`.
- **Comments:** this codebase explains *why*, generously, at decisions that look arbitrary. Match it. When you delete or move code, rewrite every comment that described the drawer, the hero pills or `Route.Analytics`; do not leave one that describes code that is gone.

## Review Focus

1. **A finished device run while the coach is typing in a list sheet or dialog** (details sheet, share sheet, delete confirmation, legend) must not switch tabs under them. Android: `nothingAbove` includes the list's overlay flag (Task 3/4). iOS: `isOnHome` still lists `HomeView`'s own sheets, and MatchesList's own sheets report upward (Task 6).
2. **A finished run while the coach is on the Settings or Analytics tab** does not switch tabs (`isHomeFront` is Matches-only). Tested in Task 1.
3. **Tab switch and back keeps the Matches filter and scroll position** (Android: `SaveableStateHolder` + hoisted `LazyListState`; iOS: `TabView` keeps both tabs mounted). Checked on device in Tasks 5 and 8.
4. **The scoring board shows no tab bar and no Add button**, and a tap on the lower right of the board scores rather than hitting a leftover button. Because the bar lives under the stack root, this should be free; it is checked by screenshot in Tasks 5 and 8.
5. **Sign-out from the Settings tab lands on sign-in**, and signing back in lands on Matches, not on Settings (the saved tab must not survive a sign-out on Android: `AuthGate` resets it on `TO_SIGN_IN`). Task 4.
6. **Android system Back on Analytics or Settings returns to Matches**, and only Back on Matches leaves the app. Task 4 (`BackHandler`), checked in Task 5.
7. **Opening the Analytics tab pulls fresh data on both platforms** (Android used to, by building a new view model per visit; iOS's `.task` says it matches that). Task 4 refreshes on selecting the tab.

---

## File structure

**Shared (new):** `shared/src/commonMain/kotlin/com/badmintontracker/shared/home/HomeTab.kt` (enum + `isHomeFront`), `.../home/HomeCopy.kt`. Tests: `shared/src/commonTest/kotlin/com/badmintontracker/shared/home/HomeTabTest.kt`, `HomeCopyTest.kt`.
**Shared (modified):** `shared/.../repo/AuthRepository.kt` (`currentUserEmail()`).

**Android (new):** `androidApp/.../home/HomeNavigationBar.kt`, `home/AddMatchButton.kt` (+ `addButtonExpanded`), `home/MatchesTab.kt` (top bar + list or empty hero), `home/MatchesEmptyState.kt`, `home/SettingsTab.kt`, `home/TabTopBar.kt`. Test: `androidApp/src/test/.../home/AddMatchButtonTest.kt`.
**Android (modified):** `home/HomeScreen.kt` (rewritten as the tab host), `AuthGate.kt`, `nav/Route.kt`, `cliplist/ClipListScreen.kt`, `analytics/AnalyticsScreen.kt`, `ui/icons/ShuttlIcons.kt`, `localanalysis/BackgroundWorkAction.kt` (comments only), tests' `FakeAuthRepository.kt` and the fake in `ClipListViewModelTest.kt`.
**Android (deleted):** `home/MatchesDrawer.kt`.

**iOS (new):** `iosApp/Sources/Home/SettingsView.swift`, `Home/AddMatchButton.swift`, `Home/MatchesTabView.swift`, `Home/TabHeader.swift`.
**iOS (modified):** `Home/HomeView.swift`, `ClipList/ClipListView.swift`, `Analytics/AnalyticsListView.swift`, the four `iosApp/UITests/*.swift`.
**iOS (deleted):** `Home/MatchesDrawer.swift`, `Home/DrawerDragMath.swift`, `Tests/DrawerDragMathTests.swift`.

---

### Task 1: Shared tab model, copy, and the signed-in email

**Files:**
- Create: `shared/src/commonMain/kotlin/com/badmintontracker/shared/home/HomeTab.kt`
- Create: `shared/src/commonMain/kotlin/com/badmintontracker/shared/home/HomeCopy.kt`
- Create: `shared/src/commonTest/kotlin/com/badmintontracker/shared/home/HomeTabTest.kt`
- Create: `shared/src/commonTest/kotlin/com/badmintontracker/shared/home/HomeCopyTest.kt`
- Modify: `shared/src/commonMain/kotlin/com/badmintontracker/shared/repo/AuthRepository.kt`
- Modify: `androidApp/src/test/java/com/badmintontracker/android/testing/FakeAuthRepository.kt`, and the `AuthRepository` fake inside `androidApp/src/test/java/com/badmintontracker/android/cliplist/ClipListViewModelTest.kt` (find it with `grep -n "AuthRepository" .../ClipListViewModelTest.kt`)

**Interfaces:**
- Produces: `enum class HomeTab { MATCHES, ANALYTICS, SETTINGS }` with `companion object { val DEFAULT = MATCHES }`; `fun isHomeFront(tab: HomeTab, nothingAbove: Boolean): Boolean` (top level in `HomeTab.kt`, Swift: `HomeTabKt.isHomeFront(tab:nothingAbove:)`); `object HomeCopy` (constants below); `AuthRepository.currentUserEmail(): String?`.

- [ ] **Step 1: Write the failing tests**

`HomeTabTest.kt`:
```kotlin
package com.badmintontracker.shared.home

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class HomeTabTest {
    @Test fun the_app_opens_on_matches() {
        assertEquals(HomeTab.MATCHES, HomeTab.DEFAULT)
    }

    @Test fun only_the_matches_tab_with_nothing_above_it_is_the_front() {
        assertTrue(isHomeFront(HomeTab.MATCHES, nothingAbove = true))
    }

    @Test fun something_above_the_tabs_is_never_the_front() {
        HomeTab.entries.forEach { assertFalse(isHomeFront(it, nothingAbove = false), "$it") }
    }

    // Review focus 2: a coach in Settings or already in Analytics is not
    // pulled anywhere by a run that lands.
    @Test fun analytics_and_settings_are_never_the_front() {
        assertFalse(isHomeFront(HomeTab.ANALYTICS, nothingAbove = true))
        assertFalse(isHomeFront(HomeTab.SETTINGS, nothingAbove = true))
    }
}
```

`HomeCopyTest.kt`:
```kotlin
package com.badmintontracker.shared.home

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse

class HomeCopyTest {
    private val all = listOf(
        HomeCopy.TAB_MATCHES, HomeCopy.TAB_ANALYTICS, HomeCopy.TAB_SETTINGS,
        HomeCopy.ADD_MATCH, HomeCopy.EMPTY_BODY, HomeCopy.ANALYTICS_EMPTY_TITLE,
        HomeCopy.ANALYTICS_EMPTY_BODY, HomeCopy.SETTINGS_LABELS, HomeCopy.SETTINGS_LABELS_DETAIL,
        HomeCopy.SETTINGS_DARK_THEME, HomeCopy.SETTINGS_SIGNED_IN_AS, HomeCopy.SETTINGS_SIGN_OUT,
        HomeCopy.version("0.8.0", 25),
    )

    @Test fun no_em_dash_anywhere() {
        all.forEach { assertFalse('\u2014' in it, it) }
    }

    @Test fun the_version_line_names_name_and_code() {
        assertEquals("Version 0.8.0 (25)", HomeCopy.version("0.8.0", 25))
    }

    // The add sheet lists "New match" as one of its own three options; a
    // button named after one of them would read as a shortcut to it.
    @Test fun the_add_button_is_not_named_after_a_sheet_option() {
        assertEquals("Add match", HomeCopy.ADD_MATCH)
    }
}
```

- [ ] **Step 2: Run to verify they fail**

Run: `./gradlew --offline :shared:jvmTest --tests "com.badmintontracker.shared.home.*"`
Expected: compilation FAILS (`Unresolved reference: HomeTab`, `HomeCopy`).

- [ ] **Step 3: Implement**

`HomeTab.kt`:
```kotlin
package com.badmintontracker.shared.home

/**
 * The three places the bottom bar goes. Shared so both clients name, order and
 * default the tabs the same way; each draws its own bar.
 *
 * The bar lives INSIDE the root destination on both platforms, not around the
 * navigation stack: every pushed screen (the scoring board above all) covers it
 * with no per-screen code. See docs/plans/2026-09-25-bottom-navigation-design.md.
 */
enum class HomeTab {
    MATCHES, ANALYTICS, SETTINGS;

    companion object {
        /** The landing tab. A fresh sign-in lands here, not on the last tab used. */
        val DEFAULT = MATCHES
    }
}

/**
 * What each platform passes as `announceFinishedRuns`'s `onHome`.
 *
 * Matches only: that is the tab the coach watches a run on (its row says
 * "Analyzing 42%"), so a run landing there moves them to Analytics to see it.
 * From Settings it would pull them out of something unrelated, and on
 * Analytics they are already there. [nothingAbove] is each platform's own
 * "no screen pushed, no sheet or dialog open over the tabs".
 */
fun isHomeFront(tab: HomeTab, nothingAbove: Boolean): Boolean =
    nothingAbove && tab == HomeTab.MATCHES
```

`HomeCopy.kt`:
```kotlin
package com.badmintontracker.shared.home

/**
 * Every string the tab host, the Settings tab and the empty Matches tab draw.
 * One place for both clients, the way MatchListCopy holds the list's.
 */
object HomeCopy {
    const val TAB_MATCHES = "Matches"
    const val TAB_ANALYTICS = "Analytics"
    const val TAB_SETTINGS = "Settings"

    /**
     * Not "New match", which the mock drew: the sheet this opens lists "New
     * match" as the first of its three options, and a button named after one
     * of them reads as a shortcut to it.
     */
    const val ADD_MATCH = "Add match"

    /** Under the hero on an empty Matches tab. Names what the Add button does. */
    const val EMPTY_BODY =
        "Score a match courtside, or record or import a video to cut it into rallies. Your matches will show up here."

    const val ANALYTICS_EMPTY_TITLE = "No matches yet"
    const val ANALYTICS_EMPTY_BODY = "Add a match on the Matches tab and it will be listed here, ready to analyze."

    const val SETTINGS_LABELS = "Labels"
    const val SETTINGS_LABELS_DETAIL = "Tags you add to rallies"
    const val SETTINGS_DARK_THEME = "Dark theme"
    const val SETTINGS_SIGNED_IN_AS = "Signed in as"
    const val SETTINGS_SIGN_OUT = "Sign out"

    fun version(name: String, code: Int): String = "Version $name ($code)"
}
```

`AuthRepository.kt`: add to the interface, directly under `currentUserId()`:
```kotlin
    /** The signed-in account's email, for Settings. Null while signed out or if the session has none. */
    fun currentUserEmail(): String?
```
and to `AuthRepositoryImpl`, under its `currentUserId()`:
```kotlin
    override fun currentUserEmail(): String? = client.auth.currentUserOrNull()?.email
```
Then add `override fun currentUserEmail(): String? = null` (or a settable property if the fake already has one for the user id; match its style) to `FakeAuthRepository.kt` and to the fake in `ClipListViewModelTest.kt`. There are no Swift conformers (`grep -rn "AuthRepository" iosApp` finds none); confirm that again before moving on.

- [ ] **Step 4: Run to verify they pass**

Run: `./gradlew --offline :shared:jvmTest --tests "com.badmintontracker.shared.home.*"` then `./gradlew --offline :shared:jvmTest :androidApp:testDebugUnitTest`
Expected: `BUILD SUCCESSFUL`; the new tests pass; nothing else changed.

- [ ] **Step 5: Commit**

```bash
git add shared/src androidApp/src/test
git commit -m "feat(shared): the home tabs, their copy, and the signed-in email" -m "One enum and one front-of-home rule both clients follow, so a finished run switches tabs on the same condition on each phone."
```

---

### Task 2: Android list becomes tab content

The list stops assuming it lives in a drawer. HomeScreen still hosts the drawer after this task (Task 4 replaces it), so pass the new parameters from there.

**Files:**
- Modify: `androidApp/src/main/java/com/badmintontracker/android/cliplist/ClipListScreen.kt`
- Create: `androidApp/src/main/java/com/badmintontracker/android/home/MatchesEmptyState.kt`
- Create: `androidApp/src/main/java/com/badmintontracker/android/home/AddMatchButton.kt`
- Test: `androidApp/src/test/java/com/badmintontracker/android/home/AddMatchButtonTest.kt`
- Modify: `androidApp/src/main/java/com/badmintontracker/android/home/HomeScreen.kt` (only to pass the new parameters)

**Interfaces:**
- Consumes: `HomeCopy.EMPTY_BODY`, `HomeCopy.ADD_MATCH` (Task 1).
- Produces:
  - `ClipListScreen(...)` gains `listState: LazyListState`, `snackbarHostState: SnackbarHostState`, `onOverlayChange: (Boolean) -> Unit = {}`, `heroPaused: Boolean = false`, `contentPadding: PaddingValues = PaddingValues()`; loses `onAutoDetailsClosed` and `onAddMatch`.
  - `@Composable fun MatchesEmptyState(isPaused: Boolean, modifier: Modifier = Modifier)`.
  - `fun addButtonExpanded(firstVisibleItemIndex: Int): Boolean`.
  - `@Composable fun AddMatchButton(expanded: Boolean, onClick: () -> Unit, modifier: Modifier = Modifier)`.

- [ ] **Step 1: Write the failing test**

`AddMatchButtonTest.kt`:
```kotlin
package com.badmintontracker.android.home

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AddMatchButtonTest {
    // The filter row is always item 0, so the label shows until the first
    // match row has scrolled under the top bar.
    @Test fun labelled_at_the_top_of_the_list() {
        assertTrue(addButtonExpanded(firstVisibleItemIndex = 0))
    }

    @Test fun collapses_once_the_list_is_scrolled() {
        assertFalse(addButtonExpanded(firstVisibleItemIndex = 1))
        assertFalse(addButtonExpanded(firstVisibleItemIndex = 12))
    }
}
```

- [ ] **Step 2: Run to verify it fails**

Run: `./gradlew --offline :androidApp:testDebugUnitTest --tests "com.badmintontracker.android.home.AddMatchButtonTest"`
Expected: FAIL, `Unresolved reference: addButtonExpanded`.

- [ ] **Step 3: Implement `AddMatchButton.kt`**

```kotlin
package com.badmintontracker.android.home

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import com.badmintontracker.android.ui.theme.ShuttlRadius
import com.badmintontracker.shared.home.HomeCopy

/**
 * Labelled while the list is at its top, "+" once it is scrolled, so the
 * button covers less of the rows (and their menus) while the coach is reading
 * them. Item 0 is always the filter row.
 */
fun addButtonExpanded(firstVisibleItemIndex: Int): Boolean = firstVisibleItemIndex == 0

/**
 * The Matches tab's bottom-right "Add match". Opens the same three-option
 * sheet the Home pill used to.
 *
 * Every colour and the shape are set here rather than left to Material: the
 * default FAB is a 16dp-cornered primaryContainer square, and the mock's is an
 * accent pill with the near-black onAccent text the rest of the app's primary
 * buttons carry.
 */
@Composable
fun AddMatchButton(expanded: Boolean, onClick: () -> Unit, modifier: Modifier = Modifier) {
    ExtendedFloatingActionButton(
        text = { Text(HomeCopy.ADD_MATCH, style = MaterialTheme.typography.titleMedium) },
        icon = { Icon(Icons.Default.Add, contentDescription = null) },
        expanded = expanded,
        onClick = onClick,
        shape = RoundedCornerShape(ShuttlRadius.pill),
        containerColor = MaterialTheme.colorScheme.primary,
        contentColor = MaterialTheme.colorScheme.onPrimary,
        modifier = modifier,
    )
}
```
Check `ShuttlRadius` is an `object` exposing `pill` (`ui/theme/ShuttlRadius.kt:20`); adjust the import if it is named differently. The collapsed button keeps `HomeCopy.ADD_MATCH` as its accessible name because `ExtendedFloatingActionButton` keeps the text in semantics; confirm with TalkBack or `adb shell uiautomator dump` in Task 5.

- [ ] **Step 4: Implement `MatchesEmptyState.kt`**

```kotlin
package com.badmintontracker.android.home

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.badmintontracker.shared.home.HomeCopy

/**
 * An empty Matches tab: the old Home hero, then one line saying what the Add
 * button does. It still tells a new coach what the app is for, and gets out of
 * the way once there is a match to list.
 *
 * No button of its own: the Add button is on screen, and two controls doing
 * one thing is noise.
 */
@Composable
fun MatchesEmptyState(isPaused: Boolean, modifier: Modifier = Modifier) {
    Column(modifier.fillMaxWidth().padding(horizontal = 24.dp)) {
        Spacer(Modifier.height(72.dp))
        HeroTickerView(isPaused = isPaused)
        Spacer(Modifier.height(22.dp))
        Text(
            HomeCopy.EMPTY_BODY,
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.widthIn(max = 320.dp),
        )
    }
}
```

- [ ] **Step 5: Change `ClipListScreen.kt`**

1. Parameters: remove `onAutoDetailsClosed` and `onAddMatch` (and their kdoc). Add, after `onAutoDetailsShown`:
```kotlin
    /** Hoisted to the tab host: the Add button reads it, and it must outlive a tab switch. */
    listState: LazyListState,
    /** The tab host's one host, so a snackbar sits above the bar and the Add button, not under them. */
    snackbarHostState: SnackbarHostState,
    /**
     * Whether any sheet or dialog this list owns is open. The tab host folds it
     * into "nothing above the tabs", which gates a finished run switching tabs:
     * without it a run could switch tabs under a half-typed details sheet.
     */
    onOverlayChange: (Boolean) -> Unit = {},
    /** The empty state's hero ticker stops while the tab is not on screen. */
    heroPaused: Boolean = false,
    /** Room at the bottom so the last row and its menu scroll clear of the bar and the Add button. */
    contentPadding: PaddingValues = PaddingValues(),
```
2. Delete the local `val snackbarHostState = remember { SnackbarHostState() }`; the `LaunchedEffect(state.error)` keeps using `snackbarHostState` (now the parameter).
3. After the `resultEntry` declaration, report overlays:
```kotlin
    val overlayOpen = sheetVideoId != null || detailsTarget != null || legendOpen ||
        choiceTarget != null || deleteTarget != null || removeTarget != null || resultEntry != null
    LaunchedEffect(overlayOpen) { onOverlayChange(overlayOpen) }
```
4. Replace the empty-state branch body (the `ShuttlEmptyState(...)` with its "Add new match" button and the comment above it) with:
```kotlin
                Box(
                    Modifier.fillMaxSize().verticalScroll(rememberScrollState()),
                ) {
                    MatchesEmptyState(isPaused = heroPaused)
                }
```
   keeping the existing comment about `verticalScroll` and pull to refresh. Top-aligned, not centred: the hero sits at a fixed offset so the cycling line does not shift the layout.
5. `LazyColumn(state = listState, ...)`, and its `contentPadding` becomes:
```kotlin
                    contentPadding = PaddingValues(
                        top = DrawerList.listTopInset,
                        bottom = DrawerList.sideMargin + contentPadding.calculateBottomPadding(),
                    ),
```
6. Delete the `SnackbarHost(snackbarHostState, modifier = Modifier.align(Alignment.BottomCenter))` line, and rewrite the "No Scaffold" comment above the `Box`: the tab host owns the Scaffold, the bar, the Add button and the one snackbar host; this is tab content.
7. In the `detailsTarget` block, `close()` becomes just `detailsTarget = null` (there is no drawer to open; the coach is already looking at the new row).
8. Remove imports that are now unused (`ShuttlEmptyState`, `ShuttlIcons` if unused, `Icons.Default.Add`, `ShuttlButton` if unused, `SnackbarHost`).

- [ ] **Step 6: Keep `HomeScreen.kt` compiling**

In the `ClipListScreen(...)` call inside the drawer, remove `onAutoDetailsClosed = ...` and `onAddMatch = ...` and add:
```kotlin
                        listState = rememberLazyListState(),
                        snackbarHostState = snackbarHostState,
```
This is temporary; Task 4 rewrites the file.

- [ ] **Step 7: Run tests**

Run: `./gradlew --offline :androidApp:testDebugUnitTest` then `./gradlew --offline :androidApp:assembleDebug 2>&1 | grep -E "^e:|BUILD (SUCCESSFUL|FAILED)"`
Expected: `BUILD SUCCESSFUL` for both; `AddMatchButtonTest` passes; `RowGesturesTest`, `ClipListViewModelTest`, `AnalyticsRowsTest` unchanged and green.

- [ ] **Step 8: Commit**

```bash
git add androidApp/src
git commit -m "refactor(android): the match list takes its state and chrome from its host" -m "Hoisted list state, one snackbar host, an overlay flag and the hero as its empty state, so the list can sit in a tab under a bottom bar."
```

---

### Task 3: Android Analytics and Settings as tab content

**Files:**
- Modify: `androidApp/src/main/java/com/badmintontracker/android/analytics/AnalyticsScreen.kt`
- Create: `androidApp/src/main/java/com/badmintontracker/android/home/SettingsTab.kt`
- Create: `androidApp/src/main/java/com/badmintontracker/android/home/TabTopBar.kt`
- Modify: `androidApp/src/main/java/com/badmintontracker/android/ui/icons/ShuttlIcons.kt`

**Interfaces:**
- Consumes: `HomeCopy` (Task 1).
- Produces:
  - `@Composable fun AnalyticsTab(rows: List<AnalyticsRow>, onOpenDetail: (AnalyticsRow) -> Unit, onAnalyse: (AnalyticsRow) -> Unit, contentPadding: PaddingValues)` in `AnalyticsScreen.kt` (the old `AnalyticsScreen` stays as a thin wrapper until Task 4 deletes it with `Route.Analytics`).
  - `@Composable fun TabTopBar(title: String, actions: @Composable RowScope.() -> Unit = {})`.
  - `@Composable fun SettingsTab(themeMode: ThemeMode, onToggleTheme: () -> Unit, email: String?, onLabels: () -> Unit, onSignOut: () -> Unit, contentPadding: PaddingValues)`.
  - `ShuttlIcons.Rows`, `ShuttlIcons.SlidersHorizontal`.

- [ ] **Step 1: Icons**

In `ShuttlIcons.kt`, next to `ChartColumn`, add (lucide-static `rows-2` and `sliders-horizontal`, drawn with the existing `buildLucideSvg`):
```kotlin
    /** The Matches tab: two stacked rows, lucide `rows-2`. */
    val Rows: ImageVector by lazy {
        buildLucideSvg("ShuttlRows", "M5 3h14a2 2 0 0 1 2 2v14a2 2 0 0 1-2 2H5a2 2 0 0 1-2-2V5a2 2 0 0 1 2-2z", "M3 12h18")
    }
    /** The Settings tab, lucide `sliders-horizontal`: the gear above is the video card's. */
    val SlidersHorizontal: ImageVector by lazy {
        buildLucideSvg(
            "ShuttlSlidersHorizontal",
            "M21 4h-7", "M10 4H3", "M21 12h-9", "M8 12H3", "M21 20h-5", "M12 20H3",
            "M14 2v4", "M8 10v4", "M16 18v4",
        )
    }
```

- [ ] **Step 2: `TabTopBar.kt`**

```kotlin
package com.badmintontracker.android.home

import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

/**
 * A tab's own title bar: the tab's name at headline size, as the drawer's
 * header drew "Matches", and the actions (the background-work indicator) at
 * the end. Not a TopAppBar: a tab root has no navigation icon, and a 28sp
 * headline does not fit TopAppBar's title slot.
 *
 * Draws under the status bar itself because the tab host's Scaffold hands its
 * content no top inset (see HomeScreen).
 */
@Composable
fun TabTopBar(title: String, actions: @Composable RowScope.() -> Unit = {}) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .statusBarsPadding()
            .height(64.dp)
            .padding(start = 24.dp, end = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            title,
            style = MaterialTheme.typography.headlineLarge,
            color = MaterialTheme.colorScheme.onBackground,
            modifier = Modifier.weight(1f),
        )
        actions()
    }
}
```
(`WindowInsets` import only if you use it; remove if not.)

- [ ] **Step 3: `AnalyticsScreen.kt`**

1. Extract the body of `AnalyticsScreen`'s `Scaffold` content into a new public composable:
```kotlin
/**
 * The Analytics tab: the list of matches with what each one offers. A tab
 * root, so no back arrow; the tab host draws the bar under it.
 */
@Composable
fun AnalyticsTab(
    rows: List<AnalyticsRow>,
    onOpenDetail: (AnalyticsRow) -> Unit,
    onAnalyse: (AnalyticsRow) -> Unit,
    contentPadding: PaddingValues,
) {
    val legend = analyticsLegend(rows)
    Column(Modifier.fillMaxSize()) {
        TabTopBar(HomeCopy.TAB_ANALYTICS) { BackgroundWorkAction() }
        if (rows.isEmpty()) {
            Box(Modifier.fillMaxSize().padding(bottom = contentPadding.calculateBottomPadding()), contentAlignment = Alignment.Center) {
                // No action here: Add lives on the Matches tab, which the body names.
                ShuttlEmptyState(
                    icon = ShuttlIcons.ChartColumn,
                    title = HomeCopy.ANALYTICS_EMPTY_TITLE,
                    body = HomeCopy.ANALYTICS_EMPTY_BODY,
                )
            }
            return@Column
        }
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(bottom = GUTTER + contentPadding.calculateBottomPadding()),
        ) {
            // ... the existing "explainer" item and the group loop, unchanged ...
        }
    }
}
```
   The `"headline"` item ("Pick a match") is deleted: the tab title does its job, as in the mock. Keep the `explainer` item and the groups exactly as they are.
2. Reduce the old `AnalyticsScreen(rows, onOpenDetail, onAnalyse, onBack)` to a wrapper that Task 4 deletes:
```kotlin
@Composable
fun AnalyticsScreen(rows: List<AnalyticsRow>, onOpenDetail: (AnalyticsRow) -> Unit, onAnalyse: (AnalyticsRow) -> Unit, onBack: () -> Unit) {
    Surface(color = MaterialTheme.colorScheme.background) {
        AnalyticsTab(rows, onOpenDetail, onAnalyse, WindowInsets.navigationBars.asPaddingValues())
    }
}
```
   (`onBack` is unused for the one task this wrapper lives; the system back gesture still pops.)
3. Drop now-unused imports (`TopAppBar`, `ArrowBack`, `Scaffold`, `ExperimentalMaterial3Api` if unused).

- [ ] **Step 4: `SettingsTab.kt`**

```kotlin
package com.badmintontracker.android.home

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.badmintontracker.android.BuildConfig
import com.badmintontracker.android.ui.icons.ShuttlIcons
import com.badmintontracker.android.ui.theme.ShuttlRadius
import com.badmintontracker.android.ui.theme.ShuttlTheme
import com.badmintontracker.shared.home.HomeCopy
import com.badmintontracker.shared.prefs.ThemeMode

/**
 * Everything Home's overflow menu and the drawer's footer used to hold, in one
 * place: the version line used to be drawn twice, from two string templates.
 * The sign-in screen keeps its own theme toggle; it is outside the tabs.
 */
@Composable
fun SettingsTab(
    themeMode: ThemeMode,
    onToggleTheme: () -> Unit,
    email: String?,
    onLabels: () -> Unit,
    onSignOut: () -> Unit,
    contentPadding: PaddingValues,
) {
    Column(Modifier.fillMaxSize()) {
        // The indicator on every tab's bar, as on every other screen's.
        TabTopBar(HomeCopy.TAB_SETTINGS) { BackgroundWorkAction() }
        Column(
            Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp)
                .padding(top = 8.dp, bottom = 16.dp + contentPadding.calculateBottomPadding()),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            SettingsCard {
                SettingsRow(onClick = onLabels) {
                    Column(Modifier.weight(1f)) {
                        Text(HomeCopy.SETTINGS_LABELS, style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onBackground)
                        Text(HomeCopy.SETTINGS_LABELS_DETAIL, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    Icon(ShuttlIcons.ChevronRight, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                SettingsRow(onClick = onToggleTheme) {
                    Text(HomeCopy.SETTINGS_DARK_THEME, style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onBackground, modifier = Modifier.weight(1f))
                    Switch(
                        checked = themeMode == ThemeMode.DARK,
                        // The row is the target; a second handler on the switch
                        // would toggle twice on a tap that lands on it.
                        onCheckedChange = null,
                        colors = SwitchDefaults.colors(
                            checkedTrackColor = MaterialTheme.colorScheme.primary,
                            checkedThumbColor = MaterialTheme.colorScheme.onPrimary,
                            checkedBorderColor = MaterialTheme.colorScheme.primary,
                            uncheckedTrackColor = ShuttlTheme.extended.bgTertiary,
                            uncheckedThumbColor = MaterialTheme.colorScheme.onSurfaceVariant,
                            uncheckedBorderColor = MaterialTheme.colorScheme.outline,
                        ),
                    )
                }
            }
            SettingsCard {
                if (email != null) {
                    Column(Modifier.fillMaxWidth().heightIn(min = 56.dp).padding(horizontal = 18.dp, vertical = 10.dp), verticalArrangement = Arrangement.Center) {
                        Text(HomeCopy.SETTINGS_SIGNED_IN_AS, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Text(email, style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onBackground)
                    }
                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                }
                SettingsRow(onClick = onSignOut) {
                    Text(HomeCopy.SETTINGS_SIGN_OUT, style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.error)
                }
            }
            Text(
                HomeCopy.version(BuildConfig.VERSION_NAME, BuildConfig.VERSION_CODE),
                style = MaterialTheme.typography.bodySmall,
                color = ShuttlTheme.extended.textTertiary,
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth().padding(top = 6.dp),
            )
        }
    }
}

/** bgSecondary on the page's bg: never bgInput on bgTertiary, which are one colour in light. */
@Composable
private fun SettingsCard(content: @Composable () -> Unit) {
    Column(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(ShuttlRadius.large))
            .background(MaterialTheme.colorScheme.surfaceVariant),
    ) { content() }
}

@Composable
private fun SettingsRow(onClick: () -> Unit, content: @Composable androidx.compose.foundation.layout.RowScope.() -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .heightIn(min = 56.dp)
            .clickable(role = Role.Button, onClick = onClick)
            .padding(horizontal = 18.dp),
        verticalAlignment = Alignment.CenterVertically,
        content = content,
    )
}
```
If `ShuttlIcons` has no `ChevronRight`, add one next to `ChevronUp`/`ChevronDown` in the same style: `val ChevronRight: ImageVector by lazy { buildStroked("ShuttlChevronRight") { moveTo(5f, 3f); lineTo(9f, 7f); lineTo(5f, 11f) } }`. If `ShuttlTheme.extended` is reached differently (check `ui/theme/Theme.kt`), follow the existing call sites (`ShuttlTheme.extended.textTertiary` is used in `AnalyticsScreen.kt`).

- [ ] **Step 5: Build and run tests**

Run: `./gradlew --offline :androidApp:testDebugUnitTest` and `./gradlew --offline :androidApp:assembleDebug 2>&1 | grep -E "^e:|^w:.*(SettingsTab|TabTopBar|AnalyticsScreen)|BUILD (SUCCESSFUL|FAILED)"`
Expected: `BUILD SUCCESSFUL`, no warnings in the touched files.

- [ ] **Step 6: Commit**

```bash
git add androidApp/src
git commit -m "feat(android): Analytics and Settings as tab content" -m "Analytics loses its back arrow and headline; Settings gathers what the Home menu and the drawer footer both held."
```

---

### Task 4: Android tab host, routing, and the drawer's removal

**Files:**
- Rewrite: `androidApp/src/main/java/com/badmintontracker/android/home/HomeScreen.kt`
- Create: `androidApp/src/main/java/com/badmintontracker/android/home/HomeNavigationBar.kt`
- Create: `androidApp/src/main/java/com/badmintontracker/android/home/MatchesTab.kt`
- Modify: `androidApp/src/main/java/com/badmintontracker/android/AuthGate.kt`
- Modify: `androidApp/src/main/java/com/badmintontracker/android/nav/Route.kt`
- Modify: `androidApp/src/main/java/com/badmintontracker/android/analytics/AnalyticsScreen.kt` (delete the wrapper)
- Modify: `androidApp/src/main/java/com/badmintontracker/android/localanalysis/BackgroundWorkAction.kt` (comments)
- Delete: `androidApp/src/main/java/com/badmintontracker/android/home/MatchesDrawer.kt`

**Interfaces:**
- Consumes: Tasks 1 to 3.
- Produces: `HomeScreen(selectedTab: HomeTab, onSelectTab: (HomeTab) -> Unit, onOverlayChange: (Boolean) -> Unit, ...)`, `HomeNavigationBar(selected: HomeTab, onSelect: (HomeTab) -> Unit)`.

- [ ] **Step 1: `HomeNavigationBar.kt`**

```kotlin
package com.badmintontracker.android.home

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import com.badmintontracker.android.ui.icons.ShuttlIcons
import com.badmintontracker.android.ui.theme.ShuttlTheme
import com.badmintontracker.shared.home.HomeCopy
import com.badmintontracker.shared.home.HomeTab

/**
 * The bottom bar. Every colour is pinned: Material's defaults are
 * surfaceContainer for the bar and secondaryContainer for the indicator, and
 * this app's scheme maps neither to what the mock draws. The indicator is the
 * same green tint the list's analysis chips use, so "selected" and "has this"
 * read as one family.
 */
@Composable
fun HomeNavigationBar(selected: HomeTab, onSelect: (HomeTab) -> Unit) {
    val border = MaterialTheme.colorScheme.outlineVariant
    NavigationBar(
        containerColor = MaterialTheme.colorScheme.surfaceVariant,
        tonalElevation = 0.dp,
        modifier = Modifier.fillMaxWidth().drawBehind {
            drawLine(border, Offset(0f, 0f), Offset(size.width, 0f), strokeWidth = 1.dp.toPx())
        },
    ) {
        HomeTab.entries.forEach { tab ->
            NavigationBarItem(
                selected = tab == selected,
                onClick = { onSelect(tab) },
                icon = { Icon(tab.icon, contentDescription = null) },
                label = { Text(tab.label, style = MaterialTheme.typography.labelMedium) },
                colors = NavigationBarItemDefaults.colors(
                    indicatorColor = ShuttlTheme.extended.analysisChip,
                    selectedIconColor = ShuttlTheme.extended.analysisGlyph,
                    selectedTextColor = MaterialTheme.colorScheme.onBackground,
                    unselectedIconColor = MaterialTheme.colorScheme.onSurfaceVariant,
                    unselectedTextColor = MaterialTheme.colorScheme.onSurfaceVariant,
                ),
            )
        }
    }
}

private val HomeTab.label: String
    get() = when (this) {
        HomeTab.MATCHES -> HomeCopy.TAB_MATCHES
        HomeTab.ANALYTICS -> HomeCopy.TAB_ANALYTICS
        HomeTab.SETTINGS -> HomeCopy.TAB_SETTINGS
    }

private val HomeTab.icon: ImageVector
    get() = when (this) {
        HomeTab.MATCHES -> ShuttlIcons.Rows
        HomeTab.ANALYTICS -> ShuttlIcons.ChartColumn
        HomeTab.SETTINGS -> ShuttlIcons.SlidersHorizontal
    }
```
If `labelMedium` in this app's typography is not 12sp medium, use the role that is (check `ui/theme/ShuttlType.kt`); the mock's label is 12px/500.

- [ ] **Step 2: `MatchesTab.kt`**

```kotlin
package com.badmintontracker.android.home

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import com.badmintontracker.android.localanalysis.BackgroundWorkAction
import com.badmintontracker.shared.home.HomeCopy

/**
 * The landing tab: its title bar over the match list. The list is the one the
 * drawer used to hold, full width now; everything it does (filters, legend,
 * swipe, menus, pull to refresh, "Shared with me") is unchanged.
 */
@Composable
fun MatchesTab(list: @Composable () -> Unit) {
    Column(Modifier.fillMaxSize()) {
        TabTopBar(HomeCopy.TAB_MATCHES) { BackgroundWorkAction() }
        list()
    }
}
```

- [ ] **Step 3: Rewrite `HomeScreen.kt`**

Replace the whole file. Signature and body:
```kotlin
/**
 * The tab host: the bottom bar, the Add button, and the one snackbar host,
 * over whichever tab is selected.
 *
 * It is the root destination (Route.Home) and the bar lives INSIDE it, so every
 * other route still pushes over the whole screen, bar included: the scoring
 * board needs no code to hide it. The selected tab is owned by AuthGate, above
 * the NavHost, because the finished-run watcher there needs to read it.
 *
 * Mirrors iosApp's HomeView.swift.
 */
@Composable
fun HomeScreen(
    selectedTab: HomeTab,
    onSelectTab: (HomeTab) -> Unit,
    /** Any sheet or dialog over the tabs: the add sheet here, or one the list owns. */
    onOverlayChange: (Boolean) -> Unit,
    vm: ClipListViewModel,
    shares: SharesRepository,
    media: MediaRepository,
    themePrefs: ThemePreferenceRepository,
    email: String?,
    /**
     * The Analytics tab's content, given the bottom padding. A slot built by
     * AuthGate so the progress flows it collects are read only while that tab
     * is composed: collected here, every progress tick would recompose the
     * whole tab host, on Matches and Settings too.
     */
    analyticsContent: @Composable (PaddingValues) -> Unit,
    onMatchClick: (videoId: String) -> Unit,
    onScoreMatchClick: (scoreLogId: String) -> Unit,
    onNewMatch: () -> Unit,
    onRecord: () -> Unit,
    onImport: () -> Unit,
    onLabels: () -> Unit,
    onOpenHeatmap: (heatmapId: String) -> Unit,
    onOpenLocalClips: (entryId: String) -> Unit,
    onLocalClick: (entryId: String) -> Unit,
    onAnalyze: (entryId: String) -> Unit,
    onMarkCourt: (entryId: String) -> Unit,
    onRemoveFromPhone: (entryId: String) -> Unit,
    onLocalResultSeen: (entryId: String) -> Unit,
    onLocalDetailsSaved: (id: String, title: String, description: String) -> Unit,
    autoDetailsEntryId: String?,
    onAutoDetailsShown: () -> Unit,
    intakeError: String?,
    onIntakeErrorShown: () -> Unit,
) {
    val themeMode by themePrefs.mode.collectAsStateWithLifecycle()
    var addSheetOpen by remember { mutableStateOf(false) }
    var listOverlayOpen by remember { mutableStateOf(false) }
    val snackbarHostState = remember { SnackbarHostState() }
    // Hoisted here, not inside the list: it has to outlive a switch to another
    // tab (SaveableStateProvider below keeps rememberSaveable state, not this),
    // and the Add button reads it to decide whether to show its label.
    val listState = rememberLazyListState()
    val tabs = rememberSaveableStateHolder()

    LaunchedEffect(addSheetOpen, listOverlayOpen) { onOverlayChange(addSheetOpen || listOverlayOpen) }

    // Back on Analytics or Settings returns to Matches; only Back on Matches
    // leaves the app. Without this, Back from any tab fell through to the
    // NavHost, which has nothing under Route.Home, and closed the app.
    BackHandler(enabled = selectedTab != HomeTab.MATCHES) { onSelectTab(HomeTab.MATCHES) }

    // Opening Analytics pulls, as every visit to Route.Analytics used to (it
    // built a fresh ClipListViewModel, whose init refreshes). iOS's Analytics
    // `.task` refreshes on each appearance for the same reason.
    LaunchedEffect(selectedTab) { if (selectedTab == HomeTab.ANALYTICS) vm.refresh() }

    // Record and import failures. Add is only on the Matches tab, so this is
    // where they come from.
    LaunchedEffect(intakeError) {
        val err = intakeError ?: return@LaunchedEffect
        snackbarHostState.showSnackbar(err)
        onIntakeErrorShown()
    }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        // Each tab draws its own title bar under the status bar (TabTopBar), so
        // the Scaffold hands content the bottom inset only; the bar's own
        // insets are NavigationBar's.
        contentWindowInsets = WindowInsets(0),
        bottomBar = { HomeNavigationBar(selected = selectedTab, onSelect = onSelectTab) },
        floatingActionButton = {
            if (selectedTab == HomeTab.MATCHES) {
                val expanded by remember { derivedStateOf { addButtonExpanded(listState.firstVisibleItemIndex) } }
                AddMatchButton(expanded = expanded, onClick = { addSheetOpen = true })
            }
        },
        snackbarHost = { SnackbarHost(snackbarHostState) },
    ) { padding ->
        // Room for the Add button above the bar: the last row and its menu must
        // scroll clear of both.
        val bottom = PaddingValues(bottom = padding.calculateBottomPadding() + 88.dp)
        Box(Modifier.fillMaxSize()) {
            tabs.SaveableStateProvider(selectedTab.name) {
                when (selectedTab) {
                    HomeTab.MATCHES -> MatchesTab {
                        ClipListScreen(
                            vm = vm, shares = shares, media = media,
                            onMatchClick = onMatchClick,
                            onScoreMatchClick = onScoreMatchClick,
                            onLocalClick = onLocalClick,
                            onAnalyze = onAnalyze,
                            onMarkCourt = onMarkCourt,
                            onOpenHeatmap = onOpenHeatmap,
                            onOpenLocalClips = onOpenLocalClips,
                            onRemoveFromPhone = onRemoveFromPhone,
                            onLocalResultSeen = onLocalResultSeen,
                            onLocalDetailsSaved = onLocalDetailsSaved,
                            autoDetailsEntryId = autoDetailsEntryId,
                            onAutoDetailsShown = onAutoDetailsShown,
                            listState = listState,
                            snackbarHostState = snackbarHostState,
                            onOverlayChange = { listOverlayOpen = it },
                            heroPaused = addSheetOpen,
                            contentPadding = bottom,
                        )
                    }
                    HomeTab.ANALYTICS -> analyticsContent(PaddingValues(bottom = padding.calculateBottomPadding()))
                    HomeTab.SETTINGS -> SettingsTab(
                        themeMode = themeMode,
                        onToggleTheme = themePrefs::toggle,
                        email = email,
                        onLabels = onLabels,
                        onSignOut = vm::signOut,
                        contentPadding = PaddingValues(bottom = padding.calculateBottomPadding()),
                    )
                }
            }
        }
    }

    if (addSheetOpen) {
        AddMatchSheet(
            onNewMatch = { addSheetOpen = false; onNewMatch() },
            onRecord = { addSheetOpen = false; onRecord() },
            onImport = { addSheetOpen = false; onImport() },
            onDismiss = { addSheetOpen = false },
        )
    }
}
```
Notes for the implementer:
- A list sheet or dialog that closes because its tab left composition must also report `false`, or `listOverlayOpen` sticks. Reset it where the tab changes: `LaunchedEffect(selectedTab) { if (selectedTab != HomeTab.MATCHES) listOverlayOpen = false }`. (Tabs cannot be switched by hand while a modal is open; this covers the auto switch never happening in that state and any future path.)
- The Scaffold's `padding` from `contentWindowInsets = WindowInsets(0)` still includes the bottom bar's height; do not add `navigationBarsPadding` on top.
- The 88dp is the 56dp button plus its 16dp margin plus 16dp air; name it in a `private val` with a comment rather than a bare literal.
- Remove every import the old file used and the new one does not.

- [ ] **Step 4: `AuthGate.kt`**

1. Replace `var pendingDrawerOpen by remember { mutableStateOf(false) }` (and its comment) with:
```kotlin
            // The selected tab, owned here rather than by HomeScreen: the
            // finished-run watcher below reads it, and the background-work
            // indicator sets it from any screen. Saveable, so rotation and process
            // death keep it; reset on sign-out below, so a fresh sign-in lands on
            // Matches.
            var homeTab by rememberSaveable { mutableStateOf(HomeTab.DEFAULT) }
            // Any sheet or dialog over the tabs, reported by HomeScreen. Part of
            // "nothing above": see isHomeFront.
            var homeOverlayOpen by remember { mutableStateOf(false) }
```
2. In `LaunchedEffect(auth)`, in the `AuthRedirect.TO_SIGN_IN` branch, add `homeTab = HomeTab.DEFAULT` before navigating (Review focus 5). Do the same in `SignInScreen`'s `onSignedIn` is not needed; the reset on the way out covers it.
3. In the finished-run collector, replace the `onHome = ...` argument and the navigate:
```kotlin
                        val announcement = announceFinishedRuns(
                            finished = finished,
                            seen = seen,
                            onHome = isHomeFront(
                                tab = homeTab,
                                nothingAbove = !homeOverlayOpen && nav.currentBackStackEntry
                                    ?.destination?.hasRoute<Route.Home>() == true,
                            ),
                        )
                        seen = announcement.seen
                        if (announcement.openAnalytics) homeTab = HomeTab.ANALYTICS
```
   Reading `homeTab` and `homeOverlayOpen` inside the coroutine reads their current values (they are snapshot state); no `rememberUpdatedState` needed. Rewrite the long comment above the collector where it says "takes the coach to the Analytics list" and "pass through Home" to describe the tab switch; keep its reasoning about the seen-set and why it lives above the NavHost.
4. `LocalBackgroundWorkClick`: replace the body with:
```kotlin
                    {
                        // popUpTo plus singleTop: the indicator is reachable from
                        // any screen, and without both, tapping it repeatedly would
                        // stack Home screens. The run it points at is a row on the
                        // Matches tab, so arriving selects that tab too.
                        nav.navigate(Route.Home) {
                            popUpTo(Route.Home)
                            launchSingleTop = true
                        }
                        homeTab = HomeTab.MATCHES
                    }
```
5. In `composable<Route.Home>`: keep the `clipListVm` and `localVm` construction. Move the analytics row building and `onAnalyse` logic from `composable<Route.Analytics>` into it (one `ClipListViewModel` now serves both tabs), and call the new `HomeScreen`. The row building goes inside the `analyticsContent` slot, never at `Route.Home` scope: AuthGate's own comments explain why a progress-tick read there recomposes too much. `fun analyze` is a local function; if Kotlin will not take `::analyze` for a local function in your version, use a `val analyze: (String) -> Unit = { ... }` lambda instead:
```kotlin
                    // Both the list's Analyze and the Analytics tab's Analyze go
                    // through this one decision: resume a failed cloud run whose
                    // court points are saved, otherwise mark the court.
                    fun analyze(entryId: String) {
                        localVideos.get(entryId)?.let { entry ->
                            if (canResumeFailedAnalysis(entry)) localVm.retry(entry.id)
                            else nav.navigate(Route.CourtMarking(entry.id))
                        }
                    }
                    HomeScreen(
                        selectedTab = homeTab,
                        onSelectTab = { homeTab = it },
                        onOverlayChange = { homeOverlayOpen = it },
                        vm = clipListVm,
                        shares = rally.shares,
                        media = rally.media,
                        themePrefs = themePrefs,
                        email = rally.auth.currentUserEmail(),
                        analyticsContent = { bottom ->
                            // Collected inside the slot, so only while the tab is
                            // on screen: see HomeScreen's analyticsContent.
                            val clipListState by clipListVm.state.collectAsStateWithLifecycle()
                            val liveAnalysisStates by localAnalysis.state.collectAsStateWithLifecycle()
                            val cloudProgress by coordinator.progress.collectAsStateWithLifecycle()
                            AnalyticsTab(
                                rows = buildAnalyticsRows(
                                    matchList = clipListState.matchList,
                                    localEntries = clipListState.localEntries,
                                    liveAnalysisStates = liveAnalysisStates,
                                    progressByEntryId = cloudProgress,
                                ),
                                onOpenDetail = { row -> row.entryId?.let { nav.navigate(Route.AnalyticsDetail(it, row.title)) } },
                                onAnalyse = { row -> row.entryId?.let(::analyze) },
                                contentPadding = bottom,
                            )
                        },
                        onMatchClick = { nav.navigate(Route.Match(videoId = it)) },
                        onScoreMatchClick = { nav.navigate(Route.Match(scoreLogId = it)) },
                        onNewMatch = { nav.navigate(Route.NewMatch) },
                        onRecord = { intake.record(null) },
                        onImport = { intake.import(null) },
                        onLabels = { nav.navigate(Route.Labels) },
                        onOpenHeatmap = { nav.navigate(Route.Heatmap(it)) },
                        onOpenLocalClips = { nav.navigate(Route.LocalClips(it)) },
                        onLocalClick = { nav.navigate(Route.LocalPlayer(it)) },
                        onAnalyze = ::analyze,
                        onMarkCourt = { entryId -> nav.navigate(Route.CourtMarking(entryId)) },
                        onRemoveFromPhone = localVm::remove,
                        onLocalResultSeen = localVm::acknowledgeResult,
                        onLocalDetailsSaved = localVm::setDetails,
                        autoDetailsEntryId = autoDetailsEntryId,
                        onAutoDetailsShown = { autoDetailsEntryId = null },
                        intakeError = intakeError,
                        onIntakeErrorShown = { intakeError = null },
                    )
```
   Keep the long comment that explained the resume-vs-court-marking guard (it lived in the Analytics block) above `fun analyze`. `localVideos.get(id)` is what the drawer's `onAnalyze` used; the Analytics block looked the entry up in `localEntries`. Use `localVideos.get` for both, as the list did.
6. Delete the whole `composable<Route.Analytics> { ... }` block.
7. If `rally.auth` is not the `AuthRepository` (check `RallyApp`), use whatever `ClipListViewModel` receives as `rally.auth`.
8. Scoring finished (`popUpTo(Route.Home)`) is unchanged; confirm it still compiles.

- [ ] **Step 5: `Route.kt`, `AnalyticsScreen.kt`, `BackgroundWorkAction.kt`, delete the drawer**

- Remove `Route.Analytics` and its kdoc from `Route.kt`. Rewrite the `Route.Heatmap` kdoc that mentions "the analysis banner" and "the drawer's own heatmap affordance": it is what a row's "Player heatmap" menu item opens.
- Delete the `AnalyticsScreen(...)` wrapper added in Task 3.
- `BackgroundWorkAction.kt`: rewrite comments that say "Home, drawer open"; "Go to matches" now selects the Matches tab. The button label stays.
- `git rm androidApp/src/main/java/com/badmintontracker/android/home/MatchesDrawer.kt`.
- `grep -rn "drawer\|Drawer" androidApp/src/main --include=*.kt` and fix every comment that now describes something gone. `DrawerList` and `DrawerSectionLabel` are the list's own metrics and label; keep the names, but a comment saying the list "sits in the drawer" gets rewritten.

- [ ] **Step 6: Build and run all Android tests**

Run: `./gradlew --offline :shared:jvmTest :androidApp:testDebugUnitTest` and `./gradlew --offline :androidApp:assembleDebug 2>&1 | grep -E "^e:|BUILD (SUCCESSFUL|FAILED)"`
Expected: `BUILD SUCCESSFUL`; `AuthNavigationTest` unchanged and green (Route.Home is still the start destination).

- [ ] **Step 7: Commit**

```bash
git add -A androidApp/src
git commit -m "feat(android): a bottom bar with Matches, Analytics and Settings replaces Home and the drawer" -m "The bar lives inside Route.Home so every pushed screen still covers it. One ClipListViewModel serves both list tabs. A finished run switches to Analytics only from the Matches tab with nothing open over it."
```

---

### Task 5: Android on the emulator, against the mock

**Files:** screenshots under `docs/screenshots/` (new files only), fixes wherever the screenshots show a defect.

- [ ] **Step 1: Device and session**

Start `Pixel_9a` with the DNS flags (Global Constraints). Check the session: `adb -s emulator-5554 shell run-as com.badmintontracker.android cat shared_prefs/rally.xml | grep -o 'name="[a-z_]*"' | sort -u`. If there is no session key, stop and report: Coen has to sign in by hand. Do not look for credentials.

- [ ] **Step 2: Install**

`ANDROID_SERIAL=emulator-5554 ./gradlew --offline :androidApp:installDebug 2>&1 | grep -E "^e:|BUILD (SUCCESSFUL|FAILED)|Installed"`. If it fails on storage, `adb -s emulator-5554 shell rm -f /data/local/tmp/*.apk` and retry once; otherwise report.

- [ ] **Step 3: Capture, one screen at a time**

Use `e() { adb -s emulator-5554 "$@"; }`. For each shot: navigate with `e shell input tap x y`, capture with `e exec-out screencap -p > /tmp/bn-<name>.png`, **Read the image**, and only then go on. Never chain taps blind. Theme is switched from the Settings tab's "Dark theme" row. Shots, in dark and light:
- `matches` (top of list, button labelled), `matches-scrolled` (button collapsed to "+", last row and its menu clear of the button and bar), `add-sheet`, `analytics`, `settings`, `scoring` (open a match's board; no bar, no button).
- Back check (Review focus 6): on Settings, `e shell input keyevent 4` returns to Matches; on Matches it leaves the app. Relaunch after.
- Tab switch check (Review focus 3): pick "On this phone" (if the chips show), scroll down, go to Settings, come back: filter and scroll position kept.
- Empty state: only if the account can show it without deleting anything; otherwise say it was not captured.

- [ ] **Step 4: Compare against the mock and fix**

Open the mock's Option A frames next to each capture. Be picky: the bar's colours and indicator, the button's pill shape and colours (no Material lavender or purple anywhere), label and icon sizes, the title bar's height and baseline, bottom padding, the light theme's surfaces. Fix defects in code, rebuild, recapture that shot. Report anything you judge a deliberate difference.

- [ ] **Step 5: Save and commit**

Copy the final captures to `docs/screenshots/2026-09-25-android-bottom-nav-<name>-<theme>.png` (Read each again after copying).
```bash
git add docs/screenshots androidApp/src
git commit -m "docs: emulator screenshots of the Android bottom bar" -m "<one line per fix made in step 4, if any>"
```

---

### Task 6: iOS tab pieces (Settings, Add button, tab header, list and Analytics as tab content)

**Files:**
- Create: `iosApp/Sources/Home/SettingsView.swift`, `iosApp/Sources/Home/AddMatchButton.swift`, `iosApp/Sources/Home/TabHeader.swift`, `iosApp/Sources/Home/MatchesTabView.swift`
- Modify: `iosApp/Sources/ClipList/ClipListView.swift` (`MatchesList`)
- Modify: `iosApp/Sources/Analytics/AnalyticsListView.swift`
- Modify: `iosApp/Sources/Home/HomeView.swift` (only what keeps it compiling; Task 7 rewrites it)

**Interfaces:**
- Consumes: `HomeCopy`, `HomeTab`, `HomeTabKt.isHomeFront(tab:nothingAbove:)`, `AuthRepository.currentUserEmail()` (Task 1).
- Produces:
  - `struct TabHeader<Trailing: View>: View { init(_ title: String, @ViewBuilder trailing: () -> Trailing) }` plus an `init(_ title: String)` with no trailing view.
  - `struct AddMatchButton: View { let action: () -> Void }`.
  - `struct SettingsView: View { let rally: RallyApp; let onLabels: () -> Void }`.
  - `struct MatchesTabView: View` (header, intake error banner, `MatchesList`).
  - `MatchesList` loses `onAddMatch`, gains `heroPaused: Bool` and `onOverlayChange: (Bool) -> Void`.
  - `AnalyticsListView` gains `onCourtMarking: (CourtMarkingRoute) -> Void` and `onDetail: (AnalyticsDetailRoute) -> Void`, loses its own two `navigationDestination`s and its navigation title.

- [ ] **Step 1: `TabHeader.swift`**

```swift
import SwiftUI

/// A tab's own title: the tab's name at headline size, as the drawer's header
/// drew "Matches", with the background-work indicator trailing. Not a
/// navigation bar title: the stack's bar is hidden over the tabs, because the
/// tab bar lives inside the stack's root (see HomeView).
struct TabHeader<Trailing: View>: View {
    let title: String
    let trailing: Trailing

    init(_ title: String, @ViewBuilder trailing: () -> Trailing) {
        self.title = title
        self.trailing = trailing()
    }

    var body: some View {
        HStack {
            Text(title)
                .shuttlType(ShuttlType.headlineLarge)
                .foregroundStyle(Shuttl.textHeading)
            Spacer()
            trailing
        }
        .padding(.horizontal, 24)
        .padding(.top, 12)
        .padding(.bottom, 8)
    }
}

extension TabHeader where Trailing == EmptyView {
    init(_ title: String) { self.init(title) { EmptyView() } }
}
```

- [ ] **Step 2: `AddMatchButton.swift`**

```swift
import SwiftUI
import Shared

/// The Matches tab's round "Add match", bottom right, above the tab bar. Fixed
/// size: resizing on scroll needs a scroll-position API iOS 17 does not have
/// cleanly. Opens the same three-option sheet the Home pill used to.
struct AddMatchButton: View {
    let action: () -> Void

    var body: some View {
        Button(action: action) {
            Image(systemName: "plus")
                .shuttlType(ShuttlType.headlineMedium)
                .foregroundStyle(Shuttl.onAccent)
                .frame(width: 56, height: 56)
                .background(Shuttl.accent, in: Circle())
                .shadow(color: .black.opacity(0.35), radius: 12, y: 6)
        }
        .buttonStyle(.plain)
        .accessibilityLabel(HomeCopy.shared.ADD_MATCH)
    }
}
```

- [ ] **Step 3: `SettingsView.swift`**

```swift
import SwiftUI
import Shared

/// Everything Home's overflow menu and the drawer's footer used to hold, in one
/// place: the version line used to be drawn twice. The sign-in screen keeps its
/// own theme toggle; it is outside the tabs.
struct SettingsView: View {
    let rally: RallyApp
    let onLabels: () -> Void

    @State private var themeMode: ThemeMode = .light

    var body: some View {
        VStack(spacing: 0) {
            // The indicator on every tab's header, as on every other screen's bar.
            TabHeader(HomeCopy.shared.TAB_SETTINGS) { BackgroundWorkAction() }
            ScrollView {
                VStack(spacing: 12) {
                    card {
                        Button(action: onLabels) {
                            HStack {
                                VStack(alignment: .leading, spacing: 2) {
                                    Text(HomeCopy.shared.SETTINGS_LABELS)
                                        .shuttlType(ShuttlType.bodyLarge)
                                        .foregroundStyle(Shuttl.textHeading)
                                    Text(HomeCopy.shared.SETTINGS_LABELS_DETAIL)
                                        .shuttlType(ShuttlType.bodySmall)
                                        .foregroundStyle(Shuttl.textSecondary)
                                }
                                Spacer()
                                Image(systemName: "chevron.right")
                                    .shuttlType(ShuttlType.bodySmall)
                                    .foregroundStyle(Shuttl.textSecondary)
                            }
                            .settingsRow()
                        }
                        .buttonStyle(.plain)
                        .accessibilityLabel(HomeCopy.shared.SETTINGS_LABELS)
                        Divider().overlay(Shuttl.border)
                        Toggle(isOn: Binding(
                            get: { themeMode == .dark },
                            set: { _ in rally.themePrefs.toggle() }
                        )) {
                            Text(HomeCopy.shared.SETTINGS_DARK_THEME)
                                .shuttlType(ShuttlType.bodyLarge)
                                .foregroundStyle(Shuttl.textHeading)
                        }
                        .tint(Shuttl.accent)
                        .settingsRow()
                    }
                    card {
                        if let email = rally.auth.currentUserEmail() {
                            VStack(alignment: .leading, spacing: 2) {
                                Text(HomeCopy.shared.SETTINGS_SIGNED_IN_AS)
                                    .shuttlType(ShuttlType.bodySmall)
                                    .foregroundStyle(Shuttl.textSecondary)
                                Text(email)
                                    .shuttlType(ShuttlType.bodyLarge)
                                    .foregroundStyle(Shuttl.textHeading)
                            }
                            .frame(maxWidth: .infinity, alignment: .leading)
                            .settingsRow()
                            Divider().overlay(Shuttl.border)
                        }
                        Button {
                            Task { _ = try? await SwiftInteropKt.signOutOrMessage(rally.auth) }
                        } label: {
                            Text(HomeCopy.shared.SETTINGS_SIGN_OUT)
                                .shuttlType(ShuttlType.bodyLarge)
                                .foregroundStyle(Shuttl.error)
                                .frame(maxWidth: .infinity, alignment: .leading)
                                .settingsRow()
                        }
                        .buttonStyle(.plain)
                    }
                    Text(versionLabel())
                        .shuttlType(ShuttlType.bodySmall)
                        .foregroundStyle(Shuttl.textTertiary)
                        .padding(.top, 6)
                }
                .padding(.horizontal, 16)
                .padding(.top, 8)
                .padding(.bottom, 24)
            }
        }
        .background(Shuttl.bg)
        .task {
            for await mode in rally.themePrefs.mode { themeMode = mode }
        }
    }

    /// bgSecondary on the page's bg: never bgInput on bgTertiary, one colour in light.
    private func card<Content: View>(@ViewBuilder _ content: () -> Content) -> some View {
        VStack(spacing: 0) { content() }
            .background(Shuttl.bgSecondary, in: RoundedRectangle(cornerRadius: 20, style: .continuous))
    }
}

private extension View {
    func settingsRow() -> some View {
        padding(.horizontal, 18).frame(minHeight: 56).contentShape(Rectangle())
    }
}
```
Check `versionLabel()` (`Components/VersionLabel.swift`) produces the same "Version x (y)" as `HomeCopy.version`; if it builds its own string, change it to call `HomeCopy.shared.version(name:code:)` so the two platforms cannot drift. Check the radius constant: use `ShuttlRadius.large` if `Theme/ShuttlRadius.swift` defines it.

- [ ] **Step 4: `MatchesList` in `ClipListView.swift`**

1. Remove `onAddMatch` and its doc. Add:
```swift
    /// The empty state's hero ticker stops while this tab is not on screen or
    /// something is pushed over it.
    let heroPaused: Bool
    /// Whether any sheet or dialog this list owns is open. HomeView folds it
    /// into "nothing above the tabs", which gates a finished run switching tabs.
    let onOverlayChange: (Bool) -> Void
```
2. Report overlays: in `listBody`, add
```swift
        .onChange(of: overlayOpen, initial: true) { _, open in onOverlayChange(open) }
```
   with
```swift
    private var overlayOpen: Bool {
        shareTarget != nil || detailsTarget != nil || legendOpen || resultEntry != nil
            || choiceKey != nil || deleteKey != nil || removeKey != nil
    }
```
3. Replace the `.overlay { if mine.isEmpty ... ShuttlEmptyState ... }` content with the hero:
```swift
            if mine.isEmpty && shared.isEmpty && !model.isRefreshing {
                // The old Home hero, then what the Add button does. No button of
                // its own: the Add button is on screen, and two controls doing one
                // thing is noise. Top aligned so the cycling line never moves it.
                VStack(alignment: .leading, spacing: 22) {
                    HeroTickerView(isPaused: heroPaused)
                    Text(HomeCopy.shared.EMPTY_BODY)
                        .shuttlType(ShuttlType.bodyLarge)
                        .foregroundStyle(Shuttl.textSecondary)
                        .frame(maxWidth: 320, alignment: .leading)
                }
                .padding(.horizontal, 24)
                .padding(.top, 72)
                .frame(maxWidth: .infinity, maxHeight: .infinity, alignment: .topLeading)
            }
```
4. The list supplies its own background now (the drawer panel used to): replace `.scrollContentBackground(.hidden)`'s comment and add `.background(Shuttl.bg)` after it. Add bottom room so the last row clears the Add button and the tab bar: `.safeAreaPadding(.bottom, 72)` on the `List`.
5. Rewrite every doc comment in the file that says "drawer" or "Home's add sheet" to describe the tab.

- [ ] **Step 5: `MatchesTabView.swift`**

```swift
import SwiftUI
import Shared

/// The landing tab: its title, record and import errors, and the match list.
/// The errors show here because Add, which starts a record or import, is only
/// on this tab. The list is the one the drawer used to hold, full width now.
struct MatchesTabView: View {
    let rally: RallyApp
    let analyze: AnalyzeCoordinator
    let intake: LocalVideoIntake
    let model: ClipListModel
    let heroPaused: Bool
    let onMatchTap: (MatchRoute) -> Void
    let onCourtMarking: (CourtMarkingRoute) -> Void
    let onLocalPlayer: (LocalPlayerRoute) -> Void
    let onLocalClips: (LocalClipsRoute) -> Void
    let onAnalyticsDetail: (AnalyticsDetailRoute) -> Void
    let onOverlayChange: (Bool) -> Void
    let onAdd: () -> Void

    var body: some View {
        VStack(spacing: 0) {
            TabHeader(HomeCopy.shared.TAB_MATCHES) { BackgroundWorkAction() }
            if let error = intake.error {
                ErrorBanner(message: error)
            }
            MatchesList(
                rally: rally, analyze: analyze, intake: intake, model: model,
                onMatchTap: onMatchTap, onCourtMarking: onCourtMarking,
                onLocalPlayer: onLocalPlayer, onLocalClips: onLocalClips,
                onAnalyticsDetail: onAnalyticsDetail,
                heroPaused: heroPaused, onOverlayChange: onOverlayChange
            )
        }
        .background(Shuttl.bg)
        .overlay(alignment: .bottomTrailing) {
            AddMatchButton(action: onAdd)
                .padding(.trailing, 16)
                .padding(.bottom, 16)
        }
    }
}
```
Match `MatchesList`'s real parameter order when calling it.

- [ ] **Step 6: `AnalyticsListView.swift`**

1. Add `let onCourtMarking: (CourtMarkingRoute) -> Void` and `let onDetail: (AnalyticsDetailRoute) -> Void`. Delete `@State detailRoute`, `@State courtMarkingRoute`, their doc comments and the two `.navigationDestination`s. Where the file assigned `detailRoute = ...` (row tap) call `onDetail(...)`; where it assigned `courtMarkingRoute = ...` (`analyse`) call `onCourtMarking(...)`. As a tab root it sits at the same depth as the Matches tab, so HomeView's own routes present it one deeper, as they do for the list.
2. Remove `.navigationTitle`, `.navigationBarTitleDisplayMode`, `.shuttlNavigationBarBackground()` and the `.toolbar { BackgroundWorkAction }`. Wrap the body in `VStack(spacing: 0) { TabHeader(HomeCopy.shared.TAB_ANALYTICS) { BackgroundWorkAction() }; <existing Group> }.background(Shuttl.bg)`.
3. Delete the "Pick a match" row from `list(rows:legend:)`; keep the legend row (give it the top inset the headline had).
4. Empty state copy: `HomeCopy.shared.ANALYTICS_EMPTY_TITLE` / `ANALYTICS_EMPTY_BODY`; rewrite the comment above it.
5. Add `.safeAreaPadding(.bottom, 16)` on the list so the last row clears the tab bar.
6. Keep `.task { await model.start(); await model.refresh() }`, and rewrite its comment: it now runs each time the tab is shown. `start()` is idempotent (see `ClipListModel.start`); confirm by reading it, and say so in the comment.

- [ ] **Step 7: Keep `HomeView.swift` compiling**

Pass `heroPaused: drawerOpen, onOverlayChange: { _ in }` to `MatchesList` and delete its `onAddMatch:` argument; pass `onCourtMarking: { courtMarkingRoute = $0 }, onDetail: { analyticsDetailRoute = $0 }` to `AnalyticsListView`. (The pushed-Analytics depth issue this reintroduces lives for one task; Task 7 replaces the push with a tab.)

- [ ] **Step 8: Regenerate the project, build and test**

Run `xcodegen generate --spec iosApp/project.yml --project iosApp` in the real repo, then the iOS build recipe. Expected: both suites run, `iosAppTests` all green, `TypographyAdoptionTests` and `SystemImageTests` green over the new files. Confirm `SettingsView.o`, `AddMatchButton.o`, `TabHeader.o`, `MatchesTabView.o` exist under `~/Library/Caches/rally-ios-derived`.

- [ ] **Step 9: Commit**

```bash
git add iosApp/Sources iosApp/iosApp.xcodeproj/project.pbxproj
git commit -m "feat(ios): Settings, the Add button, and the list and Analytics as tab content" -m "The list takes its background and hero from itself, reports its sheets upward, and Analytics reports its pushes to HomeView instead of registering its own."
```

---

### Task 7: iOS tab host, routing, the drawer's removal, UI tests

**Files:**
- Modify: `iosApp/Sources/Home/HomeView.swift`
- Delete: `iosApp/Sources/Home/MatchesDrawer.swift`, `iosApp/Sources/Home/DrawerDragMath.swift`, `iosApp/Tests/DrawerDragMathTests.swift`
- Modify: `iosApp/UITests/ScoringBoardUITests.swift`, `VideoMatchDetailsUITests.swift`, `ReplayPlayerUITests.swift`, `LabelSwatchGridUITests.swift`

**Interfaces:**
- Consumes: Task 6's views, `HomeTab`, `HomeTabKt.isHomeFront`.

- [ ] **Step 1: `HomeView` state**

Delete `drawerOpen`, `showAnalytics`, `themeMode` (Settings owns its own now) and the `.task` that fed `themeMode`. Add:
```swift
    /// The selected tab. The bar lives inside this stack's root, so every push
    /// covers it (the scoring board needs no code to hide it), and one stack
    /// keeps `CreateFlowDestination`'s single binding and the one mounted
    /// parent the match-list feed and the run watcher rely on.
    @State private var selectedTab: HomeTab = .matches
    /// Any sheet or dialog MatchesList has open over the tabs.
    @State private var listOverlayOpen = false
```

- [ ] **Step 2: `HomeView.body`**

Replace the `ZStack { homeScreen; MatchesDrawer ... }` with:
```swift
            TabView(selection: $selectedTab) {
                MatchesTabView(
                    rally: rally, analyze: analyze, intake: intake, model: listModel,
                    heroPaused: selectedTab != .matches || !isOnHome,
                    onMatchTap: { matchRoute = $0 },
                    onCourtMarking: { courtMarkingRoute = $0 },
                    onLocalPlayer: { localPlayerRoute = $0 },
                    onLocalClips: { localClipsRoute = $0 },
                    onAnalyticsDetail: { analyticsDetailRoute = $0 },
                    onOverlayChange: { listOverlayOpen = $0 },
                    onAdd: { showAddSheet = true }
                )
                .tabItem { Label(HomeCopy.shared.TAB_MATCHES, systemImage: "rectangle.split.1x2") }
                .tag(HomeTab.matches)

                AnalyticsListView(
                    rally: rally, analyze: analyze, model: listModel, localAnalysis: localAnalysis,
                    onCourtMarking: { courtMarkingRoute = $0 },
                    onDetail: { analyticsDetailRoute = $0 }
                )
                .tabItem { Label(HomeCopy.shared.TAB_ANALYTICS, systemImage: "chart.bar") }
                .tag(HomeTab.analytics)

                SettingsView(rally: rally, onLabels: { showLabels = true })
                    .tabItem { Label(HomeCopy.shared.TAB_SETTINGS, systemImage: "slider.horizontal.3") }
                    .tag(HomeTab.settings)
            }
            .tint(Shuttl.analysisGlyph)
            .toolbar(.hidden, for: .navigationBar)
```
- The `.toolbar(.hidden, for: .navigationBar)` goes on the `TabView` itself, not inside the tabs.
- Delete the `.navigationDestination(isPresented: $showAnalytics)` registration and its comment.
- The `analyticsDetailRoute` registration passes `fallbackTitle: route.title` now that Analytics rows push it too.
- `.sheet(item: $detailsTarget, onDismiss:)`: the body becomes `selectedTab = .matches` with a rewritten comment (the new row is on the Matches tab; the Add button that started this is only on that tab, so this normally changes nothing, and it keeps the promise that the named video is on screen).
- Delete `homeScreen`, `topBar`, `bottomControls`, `edgeOpenDrag`, `HomePillButtonStyle`, `signOut()` (Settings signs out itself).
- The tab bar's own label font: in `HomeView.init`, `if let font = UIFont(name: "Archivo-Medium", size: 10) { UITabBarItem.appearance().setTitleTextAttributes([.font: font], for: .normal); ... .selected }` (never a force unwrap), kept only if the simulator shows iOS honouring it; if iOS 26's bar ignores it, remove it and say so in the report.

- [ ] **Step 3: Routing**

- `isOnHome` becomes:
```swift
    /// Whether nothing covers the tabs: no push, no sheet, no list dialog.
    /// Every route and sheet this view owns is listed; a new one must be added,
    /// or a finished run switches tabs under it. The tab itself is checked by
    /// `isHomeFront`, shared with androidApp.
    private var nothingAbove: Bool {
        !showLabels && !showNewMatch && !showAddSheet && !showRecorder && !showImporter
            && courtMarkingRoute == nil && matchRoute == nil && localPlayerRoute == nil
            && localClipsRoute == nil && analyticsDetailRoute == nil
            && createFlowTarget == nil && detailsTarget == nil && !listOverlayOpen
    }
```
  (rename `isOnHome` to `nothingAbove` everywhere, including `heroPaused` above: `selectedTab != .matches || !nothingAbove`).
- `announce(cloudFinished:)`: `onHome: HomeTabKt.isHomeFront(tab: selectedTab, nothingAbove: nothingAbove)`, and `if announcement.openAnalytics { selectedTab = .analytics }`. Rewrite its doc comment.
- `goToMatches()`: drop `showAnalytics = false` and the drawer line; end with `selectedTab = .matches`. Rewrite its doc comment (it was merged with `announce`'s; give each its own).
- Rewrite the file-level doc comment on `HomeView` (front door, drawer) to describe the tab host.

- [ ] **Step 4: Delete the drawer**

```bash
git rm iosApp/Sources/Home/MatchesDrawer.swift iosApp/Sources/Home/DrawerDragMath.swift iosApp/Tests/DrawerDragMathTests.swift
grep -rn "drawer\|Drawer" iosApp/Sources iosApp/Tests
```
Fix every comment that describes the drawer as current. `DrawerList` / `DrawerSectionLabel` / `drawerListRow` are the list's metrics; keep the names.

- [ ] **Step 5: UI tests**

The signed-in gate becomes the tab bar: `app.tabBars.buttons["Settings"].firstMatch.waitForExistence(timeout: 15)` (it only exists behind sign-in). Update each file's comment to say why the gate is on a control the test does not otherwise need.
- `ScoringBoardUITests.createMatch`: tap `app.buttons["Add match"].firstMatch`, then `"New match"` in the sheet (unchanged). `deleteMatch`: after `"Back"`, the board returns to the tab host on Matches; drop the `"Open matches"` tap and its comment.
- `VideoMatchDetailsUITests`, `ReplayPlayerUITests`: drop `"Open matches"`; the list is on screen at launch. In `ReplayPlayerUITests` keep the `isHittable` wait but rewrite its comment (no drawer slides in now).
- `LabelSwatchGridUITests.openLabelsScreen`: tap `app.tabBars.buttons["Settings"]`, then `app.buttons["Labels"]`.

- [ ] **Step 6: Regenerate, build, test**

`xcodegen generate --spec iosApp/project.yml --project iosApp` in the real repo, then the iOS build recipe. Expected: `iosAppTests` green with `DrawerDragMathTests` gone; read `/tmp/ios-test.log` for each UI test's `passed` or `skipped` and report which. A skip means the simulator is signed out; that is expected unless Coen has signed in, and is handed over in Task 9.

- [ ] **Step 7: Commit**

```bash
git add -A iosApp
git commit -m "feat(ios): a tab bar with Matches, Analytics and Settings replaces Home and the drawer" -m "The TabView sits at the stack's root so pushes cover it and CreateFlowDestination keeps its one binding. A finished run switches to Analytics only from Matches with nothing open. UI tests gate on the tab bar instead of the removed menu."
```

---

### Task 8: iOS on the simulator, against the mock

**Files:** screenshots under `docs/screenshots/` (new files only); fixes where the screenshots show a defect.

- [ ] **Step 1: Session**

The iPhone 17 Pro simulator (`25A81330-906B-42F9-A880-716162614A46`) may hold a session Coen signed in by hand. Launch the app built in Task 7 and screenshot once. If it shows sign-in, use the no-credentials route: temporarily point `RootView`'s `.unauthenticated` branch at `HomeView` built from the real `RallyApp` (lists will be empty, which still shows the bar, Settings, Analytics' empty state and the Matches hero), capture, then **revert the harness before committing** (`git diff iosApp/Sources/RootView.swift` must be empty at commit time). Never drive the Simulator with cliclick or AppleScript.

- [ ] **Step 2: Capture**

Reach screens with a throwaway XCUITest that taps tabs and holds each screen (`Thread.sleep(forTimeInterval: 8)`), run it in the background, and poll `xcrun simctl io <udid> screenshot /tmp/bn-ios-<n>.png` (delete the target first: simctl refuses to overwrite) until the test ends. Delete the throwaway test and regenerate the project afterwards.

In the same throwaway test, assert the UI tests' new gate resolves: `XCTAssertTrue(app.tabBars.buttons["Settings"].firstMatch.waitForExistence(timeout: 15))`. If the iOS 26 floating bar does not expose itself as `tabBars`, the four UI tests would skip forever even when signed in: find the query that does resolve (`app.buttons["Settings"]` with the tab trait, for example), fix the four tests to use it, and say so. Also confirm the three tab icons (`rectangle.split.1x2`, `chart.bar`, `slider.horizontal.3`) render: `SystemImageTests` does not check `Label(_, systemImage:)`. Theme: the Settings tab's switch. Shots, dark and light: Matches, Add sheet, Analytics, Settings, and the empty Matches hero if the harness shows it; the scoring board if signed in.

- [ ] **Step 3: Compare against the mock and fix**

Same bar as Task 5: iOS 26 floating bar's tint, the Add button's place relative to the bar (beside it if it fits, else above it; report which), header sizes, list background (no system grey behind rows), light theme surfaces. Fix, rebuild, recapture.

- [ ] **Step 4: Save and commit**

Copy finals to `docs/screenshots/2026-09-25-ios-bottom-nav-<name>-<theme>.png`, Read each again, then:
```bash
git add docs/screenshots iosApp
git commit -m "docs: simulator screenshots of the iOS tab bar" -m "<fixes made in step 3, if any>"
```

---

### Task 9: Docs, final review, and the device handover

- [ ] **Step 1: Docs**

- `docs/plans/2026-09-03-home-and-analytics-redesign-design.md`: under its Status line add `**Superseded in part:** phase 2 (Home and drawer) by 2026-09-25-bottom-navigation-design.md.`
- `docs/plans/2026-09-25-bottom-navigation-design.md`: set Status to implemented on `feat/bottom-navigation`, and add a "What shipped differently" section listing any deviation found in Tasks 2 to 8 (e.g. the iOS Add button's final position, tab label font).
- Commit: `docs: mark the Home and drawer design superseded, and note what shipped`.

- [ ] **Step 2: Whole-branch review**

Dispatch a final reviewer over `git diff 289f5cc..HEAD` (the spec commits come before it) with the spec and this plan. Fix what it confirms.

- [ ] **Step 3: Hand the device checks to Coen (do not close these out)**

Report as open, for Coen on a real phone:
- Android (Galaxy S23): the bar and gesture-bar insets; the Add button collapsing on scroll; a device run finishing while on Matches switches to Analytics, and while on Settings does not; "Go to matches" from the indicator's sheet lands on Matches from a pushed screen.
- iPhone: the iOS 26 floating bar and the Add button beside or above it; the four UI tests run signed in (`passed`, not `skipped`).
- Sign out from Settings, sign back in: lands on Matches.
