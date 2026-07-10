# iOS App (Remote-Clips Parity) + Shared Versioning Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Ship a SwiftUI iOS app with remote-clips parity (sign-in, clip list, clip detail with annotations, match sharing) consuming the existing `:shared` KMP module, plus a single shared version source (`Config/Version.xcconfig`) read by both Android and iOS.

**Architecture:** Native SwiftUI over the existing shared repositories (PLAN.md "Approach A" — no shared UI). SKIE bridges Kotlin Flows to Swift AsyncSequence. New shared code is limited to: an `AuthState` mapping, user-facing error-message helpers promoted from `androidApp` to `commonMain` (so both platforms show identical copy), and a small `iosMain` Swift-interop layer (`createRallyApp`, `Result`-unwrapping wrappers).

**Tech Stack:** Kotlin 2.3.20, supabase-kt 3.5.0, SKIE 0.10.13, SwiftUI (iOS 17 min, `@Observable`), AVKit, XcodeGen 2.44.1 (one-shot generator; the generated `.xcodeproj` is committed and authoritative), GitHub Actions.

**Spec:** `docs/plans/2026-07-10-ios-app-design.md`

## Global Constraints

- Version source of truth: `Config/Version.xcconfig` at repo root with `MARKETING_VERSION = 0.2.0` and `CURRENT_PROJECT_VERSION = 9`. No other hardcoded version numbers anywhere.
- iOS minimum deployment target: iOS 17.0. Bundle id: `com.badmintontracker.ios`. Framework name: `Shared`.
- SKIE version: 0.10.13 (supports Kotlin 2.0.0–2.4.0; repo uses Kotlin 2.3.20).
- User-facing copy must match Android verbatim where a counterpart exists (exact strings are given in each task; note the `…` ellipsis character in "Signing in…").
- `iosApp/Config/Supabase.xcconfig` is gitignored; `iosApp/Config/Supabase.xcconfig.example` is committed.
- Design language: sharp corners (corner radius 0) everywhere except pill badges/chips (fully rounded), light theme default, Shuttl palette (hex values in Task 5).
- Do NOT commit unrelated dirty files — the working tree has other in-progress work. Every commit must `git add` only the files named in its task.
- Shell environment: run `sudo xcode-select -s /Applications/Xcode.app/Contents/Developer` once before Task 3 (the executor must ask the user to run this — it needs their password; suggest they type it with the `!` prefix in the prompt).
- Rally index is 1-based; display `"Rally #\(rallyIndex)"` with NO `+ 1`.
- Simulator install/launch recipe (any task that says "install + launch" means exactly this):
  ```bash
  export DEVICE="iPhone 16"   # or the first name from: xcrun simctl list devices available
  xcrun simctl boot "$DEVICE" 2>/dev/null; open -a Simulator
  xcodebuild -project iosApp/iosApp.xcodeproj -scheme iosApp \
    -destination "platform=iOS Simulator,name=$DEVICE" \
    -derivedDataPath iosApp/build/DerivedData build
  xcrun simctl install booted iosApp/build/DerivedData/Build/Products/Debug-iphonesimulator/iosApp.app
  xcrun simctl launch booted com.badmintontracker.ios
  ```
  `iosApp/build/` is Gradle-ignored already via the root `build` ignore; verify `.gitignore` covers it in Task 4.

---

### Task 1: Shared version file, read by Android

**Files:**
- Create: `Config/Version.xcconfig`
- Modify: `androidApp/build.gradle.kts` (defaultConfig block + top of file)
- Modify: `androidApp/src/main/java/com/badmintontracker/android/signin/SignInScreen.kt` (version footer)

**Interfaces:**
- Produces: `Config/Version.xcconfig` with keys `MARKETING_VERSION` (string, semver) and `CURRENT_PROJECT_VERSION` (int). Task 4's Xcode project and Task 10's CI check consume this exact file/format.
- Produces: Android `BuildConfig.VERSION_NAME == "0.2.0"`, `BuildConfig.VERSION_CODE == 9`.

- [ ] **Step 1: Create the version file**

`Config/Version.xcconfig`:
```
// Single source of truth for the app version on BOTH platforms.
// Android: parsed by androidApp/build.gradle.kts -> versionName/versionCode.
// iOS: included via iosApp/Config/AppConfig.xcconfig -> MARKETING_VERSION/CURRENT_PROJECT_VERSION.
// Release commits are tagged v<MARKETING_VERSION>; CI verifies the tag matches.
MARKETING_VERSION = 0.2.0
CURRENT_PROJECT_VERSION = 9
```

- [ ] **Step 2: Parse it in androidApp/build.gradle.kts**

Below the existing `localProperties` block at the top of `androidApp/build.gradle.kts`, add:

```kotlin
// Shared Android/iOS version source — see Config/Version.xcconfig.
val versionConfig: Map<String, String> = rootProject.file("Config/Version.xcconfig")
    .readLines()
    .filterNot { it.trimStart().startsWith("//") }
    .mapNotNull { line ->
        val parts = line.split("=", limit = 2)
        if (parts.size == 2) parts[0].trim() to parts[1].trim() else null
    }
    .toMap()
val sharedVersionName: String = versionConfig["MARKETING_VERSION"]
    ?: error("MARKETING_VERSION missing from Config/Version.xcconfig")
val sharedVersionCode: Int = versionConfig["CURRENT_PROJECT_VERSION"]?.toIntOrNull()
    ?: error("CURRENT_PROJECT_VERSION missing or not an Int in Config/Version.xcconfig")
```

Then in `defaultConfig`, replace the two hardcoded lines:
```kotlin
    versionCode = 8
    versionName = "0.1.7"
```
with:
```kotlin
    versionCode = sharedVersionCode
    versionName = sharedVersionName
```

- [ ] **Step 3: Add the version footer to the Android sign-in screen**

In `SignInScreen.kt`, directly after the closed-registration `Text` ("Registration is closed. …") add:

```kotlin
Spacer(Modifier.height(8.dp))
Text(
    text = "Version ${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})",
    style = MaterialTheme.typography.bodySmall,
    color = ShuttlTheme.extended.textTertiary,
    textAlign = TextAlign.Center,
)
```

Add the import `import com.badmintontracker.android.BuildConfig` if not already present (the file may already import it transitively — check the existing imports; `ClipListScreen.kt` shows the pattern).

- [ ] **Step 4: Verify the Android build picks up the shared version**

Run: `./gradlew :androidApp:assembleDebug -q && unzip -p androidApp/build/outputs/apk/debug/androidApp-debug.apk classes.dex > /dev/null && echo BUILD_OK`
Expected: `BUILD_OK` (build succeeds). Then confirm the parsed values fail loudly when absent:

Run: `mv Config/Version.xcconfig /tmp/v.tmp && (./gradlew :androidApp:assembleDebug -q 2>&1 | grep -o "MARKETING_VERSION missing" ; mv /tmp/v.tmp Config/Version.xcconfig)`
Expected: `MARKETING_VERSION missing` printed, then file restored.

- [ ] **Step 5: Run Android unit tests**

Run: `./gradlew :androidApp:testDebugUnitTest`
Expected: BUILD SUCCESSFUL, all tests pass.

- [ ] **Step 6: Commit**

```bash
git add Config/Version.xcconfig androidApp/build.gradle.kts androidApp/src/main/java/com/badmintontracker/android/signin/SignInScreen.kt
git commit -m "feat(version): single shared version source in Config/Version.xcconfig"
```

---

### Task 2: Shared commonMain additions — AuthState + unified error copy

**Files:**
- Create: `shared/src/commonMain/kotlin/com/badmintontracker/shared/repo/AuthState.kt`
- Create: `shared/src/commonMain/kotlin/com/badmintontracker/shared/auth/AuthErrorMessages.kt`
- Modify: `shared/src/commonMain/kotlin/com/badmintontracker/shared/RallyApp.kt`
- Modify: `shared/src/commonMain/kotlin/com/badmintontracker/shared/repo/ShareError.kt`
- Delete: `androidApp/src/main/java/com/badmintontracker/android/signin/AuthErrorMessages.kt`
- Modify: `androidApp/src/main/java/com/badmintontracker/android/signin/SignInViewModel.kt` (import change)
- Modify: `androidApp/src/main/java/com/badmintontracker/android/share/ShareSheetViewModel.kt` (use shared `userMessage()`)
- Test: `shared/src/commonTest/kotlin/com/badmintontracker/shared/repo/AuthStateTest.kt`
- Test: `shared/src/commonTest/kotlin/com/badmintontracker/shared/repo/ShareErrorMessageTest.kt`

**Interfaces:**
- Produces: `enum class AuthState { LOADING, AUTHENTICATED, UNAUTHENTICATED }` and `fun SessionStatus.toAuthState(): AuthState` in `com.badmintontracker.shared.repo`.
- Produces: `RallyApp.authState: Flow<AuthState>` — Task 4's Swift `RootView` iterates this via SKIE.
- Produces: `fun friendlyAuthError(e: Throwable): String` in `com.badmintontracker.shared.auth` (same body as the current Android copy).
- Produces: `fun ShareError?.userMessage(): String` in `com.badmintontracker.shared.repo`.

- [ ] **Step 1: Write failing tests**

`shared/src/commonTest/kotlin/com/badmintontracker/shared/repo/AuthStateTest.kt`:
```kotlin
package com.badmintontracker.shared.repo

import io.github.jan.supabase.auth.status.SessionStatus
import kotlin.test.Test
import kotlin.test.assertEquals

class AuthStateTest {
    @Test
    fun initializingMapsToLoading() {
        assertEquals(AuthState.LOADING, SessionStatus.Initializing.toAuthState())
    }

    @Test
    fun notAuthenticatedMapsToUnauthenticated() {
        assertEquals(AuthState.UNAUTHENTICATED, SessionStatus.NotAuthenticated(isSignOut = false).toAuthState())
    }
}
```
(Constructing `SessionStatus.Authenticated` needs a full `UserSession`; the two constructible cases above plus the `else` branch cover the mapping. If `NotAuthenticated`'s constructor differs in supabase-kt 3.5.0, check the library source and adjust — the test intent is fixed, the constructor call may vary.)

`shared/src/commonTest/kotlin/com/badmintontracker/shared/repo/ShareErrorMessageTest.kt`:
```kotlin
package com.badmintontracker.shared.repo

import kotlin.test.Test
import kotlin.test.assertEquals

class ShareErrorMessageTest {
    @Test
    fun mapsEachCaseToUserCopy() {
        assertEquals("You can only share matches you uploaded.", ShareError.NotOwner.userMessage())
        assertEquals("No Shuttl user found with that email.", ShareError.NoSuchUser.userMessage())
        assertEquals("You can't share a match with yourself.", ShareError.CannotShareSelf.userMessage())
        assertEquals("Could not share — please try again.", ShareError.Unknown(RuntimeException("x")).userMessage())
        assertEquals("Unknown error.", (null as ShareError?).userMessage())
    }
}
```

- [ ] **Step 2: Run tests to verify they fail**

Run: `./gradlew :shared:jvmTest --tests "com.badmintontracker.shared.repo.AuthStateTest" --tests "com.badmintontracker.shared.repo.ShareErrorMessageTest" 2>&1 | tail -5`
Expected: FAIL — unresolved references `toAuthState` / `userMessage` (compilation error counts as the failing state).

- [ ] **Step 3: Implement**

`shared/src/commonMain/kotlin/com/badmintontracker/shared/repo/AuthState.kt`:
```kotlin
package com.badmintontracker.shared.repo

import io.github.jan.supabase.auth.status.SessionStatus

/** Platform-friendly projection of Supabase's SessionStatus (easy to consume from Swift). */
enum class AuthState { LOADING, AUTHENTICATED, UNAUTHENTICATED }

fun SessionStatus.toAuthState(): AuthState = when (this) {
    is SessionStatus.Authenticated -> AuthState.AUTHENTICATED
    is SessionStatus.Initializing  -> AuthState.LOADING
    else                           -> AuthState.UNAUTHENTICATED
}
```

In `RallyApp.kt`, add after the existing `val` declarations (with imports `com.badmintontracker.shared.repo.AuthState`, `com.badmintontracker.shared.repo.toAuthState`, `kotlinx.coroutines.flow.Flow`, `kotlinx.coroutines.flow.map`):
```kotlin
    val authState: Flow<AuthState> = auth.sessionFlow.map { it.toAuthState() }
```

`shared/src/commonMain/kotlin/com/badmintontracker/shared/auth/AuthErrorMessages.kt` — move the Android file verbatim, changing only the package line:
```kotlin
package com.badmintontracker.shared.auth

import io.github.jan.supabase.auth.exception.AuthRestException
import io.github.jan.supabase.auth.exception.AuthErrorCode

fun friendlyAuthError(e: Throwable): String {
    if (e is AuthRestException) {
        return when (e.errorCode) {
            AuthErrorCode.InvalidCredentials    -> "Incorrect email or password."
            AuthErrorCode.EmailNotConfirmed     -> "Please confirm your email before signing in."
            AuthErrorCode.UserAlreadyExists     -> "An account with this email already exists."
            AuthErrorCode.WeakPassword          -> "Password is too weak. Try a longer one."
            AuthErrorCode.ValidationFailed      -> "Please check your email and password."
            AuthErrorCode.OverRequestRateLimit,
            AuthErrorCode.OverEmailSendRateLimit,
            AuthErrorCode.OverSmsSendRateLimit  -> "Too many attempts. Please wait a moment and try again."
            else                                -> "Sign-in failed. Please try again."
        }
    }
    return "Something went wrong. Please check your connection and try again."
}
```
Then delete `androidApp/src/main/java/com/badmintontracker/android/signin/AuthErrorMessages.kt` and change the reference in `SignInViewModel.kt` to `import com.badmintontracker.shared.auth.friendlyAuthError`.

Append to `shared/src/commonMain/kotlin/com/badmintontracker/shared/repo/ShareError.kt`:
```kotlin

/** User-facing copy for share failures — identical on Android and iOS. */
fun ShareError?.userMessage(): String = when (this) {
    ShareError.NotOwner        -> "You can only share matches you uploaded."
    ShareError.NoSuchUser      -> "No Shuttl user found with that email."
    ShareError.CannotShareSelf -> "You can't share a match with yourself."
    is ShareError.Unknown      -> "Could not share — please try again."
    null                       -> "Unknown error."
}
```
In `ShareSheetViewModel.kt`, delete the private `toMessage()` function and use the shared one: replace `(e as? ShareError).toMessage()` with `(e as? ShareError).userMessage()` and add `import com.badmintontracker.shared.repo.userMessage`. If `ShareSheetViewModelTest.kt` asserts on these strings, it should still pass unchanged (same copy); update imports there only if it referenced `toMessage` directly.

- [ ] **Step 4: Run tests to verify they pass**

Run: `./gradlew :shared:jvmTest :androidApp:testDebugUnitTest`
Expected: BUILD SUCCESSFUL, all shared + Android tests pass.

- [ ] **Step 5: Commit**

```bash
git add shared/src/commonMain/kotlin/com/badmintontracker/shared/repo/AuthState.kt \
        shared/src/commonMain/kotlin/com/badmintontracker/shared/auth/AuthErrorMessages.kt \
        shared/src/commonMain/kotlin/com/badmintontracker/shared/RallyApp.kt \
        shared/src/commonMain/kotlin/com/badmintontracker/shared/repo/ShareError.kt \
        shared/src/commonTest/kotlin/com/badmintontracker/shared/repo/AuthStateTest.kt \
        shared/src/commonTest/kotlin/com/badmintontracker/shared/repo/ShareErrorMessageTest.kt \
        androidApp/src/main/java/com/badmintontracker/android/signin/AuthErrorMessages.kt \
        androidApp/src/main/java/com/badmintontracker/android/signin/SignInViewModel.kt \
        androidApp/src/main/java/com/badmintontracker/android/share/ShareSheetViewModel.kt
git commit -m "feat(shared): AuthState mapping + unified auth/share error copy for both platforms"
```

---

### Task 3: SKIE plugin + iosMain Swift-interop layer

**Files:**
- Modify: `gradle/libs.versions.toml`
- Modify: `shared/build.gradle.kts`
- Create: `shared/src/iosMain/kotlin/com/badmintontracker/shared/RallyAppIos.kt`
- Create: `shared/src/iosMain/kotlin/com/badmintontracker/shared/SwiftInterop.kt`

**Interfaces:**
- Consumes: `friendlyAuthError` and `userMessage()` from Task 2.
- Produces (called from Swift as static members of `RallyAppIosKt` / `SwiftInteropKt`, receiver as first argument):
  - `createRallyApp(url: String, anonKey: String): RallyApp`
  - `signInEmailOrMessage(auth, email, password): String?` — null on success, user-facing message on failure
  - `signOutOrMessage(auth): String?`
  - `shareOrMessage(shares, videoId, email): String?`
  - `unshareOrMessage(shares, videoId, userId): String?`
  - `listSharesOrNull(shares, videoId): List<MatchShare>?`
  - `addAnnotationForSwift(annotations, clipId, timestampSeconds, body, kind): AddAnnotationOutcome` where `class AddAnnotationOutcome(val annotation: RallyAnnotation?, val errorMessage: String?)`
  - `deleteAnnotationOrMessage(annotations, id): String?`

**Prerequisite:** the executor must ask the user to run `! sudo xcode-select -s /Applications/Xcode.app/Contents/Developer` before this task's verification step, then confirm with `xcodebuild -version` (expected: `Xcode <version>` output, no error).

- [ ] **Step 1: Add SKIE to the version catalog and shared module**

In `gradle/libs.versions.toml` under `[versions]` add `skie = "0.10.13"`, and under `[plugins]` add:
```toml
skie = { id = "co.touchlab.skie", version.ref = "skie" }
```
In `shared/build.gradle.kts` plugins block add:
```kotlin
    alias(libs.plugins.skie)
```

- [ ] **Step 2: Create the iOS composition-root factory**

`shared/src/iosMain/kotlin/com/badmintontracker/shared/RallyAppIos.kt`:
```kotlin
package com.badmintontracker.shared

import com.russhwolf.settings.NSUserDefaultsSettings
import platform.Foundation.NSUserDefaults

/** iOS entry point: builds the app graph with NSUserDefaults-backed settings. */
fun createRallyApp(url: String, anonKey: String): RallyApp = RallyApp(
    config = SupabaseConfig(url = url, anonKey = anonKey),
    settings = NSUserDefaultsSettings(NSUserDefaults.standardUserDefaults),
)
```

- [ ] **Step 3: Create the Result-unwrapping wrappers**

`shared/src/iosMain/kotlin/com/badmintontracker/shared/SwiftInterop.kt`:
```kotlin
package com.badmintontracker.shared

import com.badmintontracker.shared.auth.friendlyAuthError
import com.badmintontracker.shared.model.AnnotationKind
import com.badmintontracker.shared.model.MatchShare
import com.badmintontracker.shared.model.RallyAnnotation
import com.badmintontracker.shared.repo.AnnotationsRepository
import com.badmintontracker.shared.repo.AuthRepository
import com.badmintontracker.shared.repo.ShareError
import com.badmintontracker.shared.repo.SharesRepository
import com.badmintontracker.shared.repo.userMessage

// kotlin.Result does not cross the ObjC bridge usefully; these wrappers return
// null on success and a ready-to-display message on failure.

suspend fun AuthRepository.signInEmailOrMessage(email: String, password: String): String? =
    signInEmail(email, password).exceptionOrNull()?.let(::friendlyAuthError)

suspend fun AuthRepository.signOutOrMessage(): String? =
    signOut().exceptionOrNull()?.let { it.message ?: "Sign-out failed." }

suspend fun SharesRepository.shareOrMessage(videoId: String, email: String): String? =
    share(videoId, email).exceptionOrNull()?.let { (it as? ShareError).userMessage() }

suspend fun SharesRepository.unshareOrMessage(videoId: String, userId: String): String? =
    unshare(videoId, userId).exceptionOrNull()?.let { "Couldn't remove access — please try again." }

suspend fun SharesRepository.listSharesOrNull(videoId: String): List<MatchShare>? =
    listShares(videoId).getOrNull()

class AddAnnotationOutcome(val annotation: RallyAnnotation?, val errorMessage: String?)

suspend fun AnnotationsRepository.addAnnotationForSwift(
    clipId: String,
    timestampSeconds: Float,
    body: String,
    kind: AnnotationKind?,
): AddAnnotationOutcome = add(clipId, timestampSeconds, body, kind).fold(
    onSuccess = { AddAnnotationOutcome(it, null) },
    onFailure = { AddAnnotationOutcome(null, it.message ?: "Couldn't add annotation") },
)

suspend fun AnnotationsRepository.deleteAnnotationOrMessage(id: String): String? =
    delete(id).exceptionOrNull()?.let { it.message ?: "Couldn't delete annotation" }
```
(These are thin `Result` adapters over already-tested repositories; they get no dedicated unit tests. They're exercised end-to-end by the Swift screens.)

- [ ] **Step 4: Verify the framework links with SKIE**

Run: `./gradlew :shared:linkDebugFrameworkIosSimulatorArm64`
Expected: BUILD SUCCESSFUL (first run downloads SKIE; takes a few minutes). Also run `./gradlew :shared:jvmTest` — expected: BUILD SUCCESSFUL (commonMain untouched by this task, sanity only).

- [ ] **Step 5: Commit**

```bash
git add gradle/libs.versions.toml shared/build.gradle.kts shared/src/iosMain
git commit -m "feat(shared): SKIE plugin + iosMain factory and Swift-interop wrappers"
```

---

### Task 4: iosApp Xcode project scaffold + app entry + splash

**Files:**
- Create: `iosApp/project.yml`
- Create: `iosApp/Config/AppConfig.xcconfig`
- Create: `iosApp/Config/Supabase.xcconfig.example`
- Create: `iosApp/Config/Supabase.xcconfig` (gitignored, real values from `local.properties`)
- Create: `iosApp/Sources/RallyIOSApp.swift`
- Create: `iosApp/Sources/RootView.swift`
- Create: `iosApp/Sources/SplashView.swift`
- Create: `iosApp/Tests/SmokeTests.swift`
- Create: `iosApp/iosApp.xcodeproj` (generated by `xcodegen`, committed)
- Modify: `.gitignore`

**Interfaces:**
- Consumes: `RallyAppIosKt.createRallyApp(url:anonKey:)`, `rally.authState` (SKIE `AsyncSequence` of `AuthState`) from Tasks 2–3.
- Produces: `RallyIOSApp` injecting `RallyApp` into views; `RootView(rally:)` switching Splash/SignIn/Main. Placeholder `SignInView`/`ClipListView` structs that Tasks 6–7 replace.

- [ ] **Step 1: Write the XcodeGen manifest**

`iosApp/project.yml`:
```yaml
name: iosApp
options:
  deploymentTarget:
    iOS: "17.0"
configFiles:
  Debug: Config/AppConfig.xcconfig
  Release: Config/AppConfig.xcconfig
targets:
  iosApp:
    type: application
    platform: iOS
    sources: [Sources]
    settings:
      base:
        PRODUCT_BUNDLE_IDENTIFIER: com.badmintontracker.ios
        FRAMEWORK_SEARCH_PATHS: "$(inherited) $(SRCROOT)/../shared/build/xcode-frameworks/$(CONFIGURATION)/$(SDK_NAME)"
        OTHER_LDFLAGS: "$(inherited) -framework Shared"
        ENABLE_USER_SCRIPT_SANDBOXING: "NO"
    info:
      path: Sources/Info.plist
      properties:
        CFBundleShortVersionString: "$(MARKETING_VERSION)"
        CFBundleVersion: "$(CURRENT_PROJECT_VERSION)"
        CFBundleDisplayName: Shuttl
        SUPABASE_URL: "$(SUPABASE_URL)"
        SUPABASE_ANON_KEY: "$(SUPABASE_ANON_KEY)"
        UILaunchScreen: {}
        UISupportedInterfaceOrientations: [UIInterfaceOrientationPortrait]
    preBuildScripts:
      - name: Compile Kotlin Framework
        basedOnDependencyAnalysis: false
        script: |
          if [ -z "${JAVA_HOME:-}" ]; then export JAVA_HOME=$(/usr/libexec/java_home); fi
          cd "$SRCROOT/.."
          ./gradlew :shared:embedAndSignAppleFrameworkForXcode
    scheme:
      testTargets: [iosAppTests]
  iosAppTests:
    type: bundle.unit-test
    platform: iOS
    sources: [Tests]
    dependencies:
      - target: iosApp
```

- [ ] **Step 2: Write the config files and gitignore entry**

`iosApp/Config/AppConfig.xcconfig`:
```
#include "../../Config/Version.xcconfig"
#include? "Supabase.xcconfig"
```
(The `#include?` is optional-include so CI builds without the secret file.)

`iosApp/Config/Supabase.xcconfig.example`:
```
// Copy to Supabase.xcconfig and fill in (values are in Supabase project settings).
// NOTE the $()/ trick: xcconfig treats // as a comment, even inside URLs.
SUPABASE_URL = https:/$()/YOUR-PROJECT.supabase.co
SUPABASE_ANON_KEY = YOUR-ANON-KEY
```

Create the real `iosApp/Config/Supabase.xcconfig` by copying the example and filling values from the repo root `local.properties` (`SUPABASE_URL`, `SUPABASE_ANON_KEY`) — remember to insert `$()` after `https:/` in the URL.

Append to `.gitignore`:
```
iosApp/Config/Supabase.xcconfig
```

- [ ] **Step 3: Write the app entry, root view, splash, and placeholders**

`iosApp/Sources/RallyIOSApp.swift`:
```swift
import SwiftUI
import Shared

@main
struct RallyIOSApp: App {
    let rally: RallyApp

    init() {
        let info = Bundle.main.infoDictionary
        rally = RallyAppIosKt.createRallyApp(
            url: info?["SUPABASE_URL"] as? String ?? "",
            anonKey: info?["SUPABASE_ANON_KEY"] as? String ?? ""
        )
    }

    var body: some Scene {
        WindowGroup {
            RootView(rally: rally)
        }
    }
}
```

`iosApp/Sources/RootView.swift`:
```swift
import SwiftUI
import Shared

struct RootView: View {
    let rally: RallyApp
    @State private var authState: AuthState? = nil

    var body: some View {
        Group {
            switch authState {
            case nil, .loading:
                SplashView()
            case .authenticated:
                NavigationStack { ClipListView(rally: rally) }
            case .unauthenticated:
                SignInView(rally: rally)
            case .some:
                SplashView()
            }
        }
        .task {
            for await state in rally.authState {
                authState = state
            }
        }
    }
}
```
(SKIE exposes `authState` as a typed AsyncSequence; if the compiler wants an explicit cast use `state as AuthState`. The redundant `case .some` silences exhaustiveness warnings for ObjC-bridged enums; drop it if the switch is already exhaustive.)

`iosApp/Sources/SplashView.swift`:
```swift
import SwiftUI

struct SplashView: View {
    var body: some View {
        VStack(spacing: 12) {
            Text("Rally Clips")
            ProgressView()
        }
        .frame(maxWidth: .infinity, maxHeight: .infinity)
    }
}
```

Placeholders (replaced in Tasks 6–7) — append to `RootView.swift`:
```swift
struct SignInView: View {
    let rally: RallyApp
    var body: some View { Text("Sign in — TODO Task 6") }
}

struct ClipListView: View {
    let rally: RallyApp
    var body: some View { Text("Clips — TODO Task 7") }
}
```

`iosApp/Tests/SmokeTests.swift`:
```swift
import XCTest
@testable import iosApp

final class SmokeTests: XCTestCase {
    func testTargetLinks() {
        XCTAssertTrue(true)
    }
}
```

- [ ] **Step 4: Generate and build the project**

Run: `cd iosApp && xcodegen generate && cd ..`
Expected: `Created project at .../iosApp/iosApp.xcodeproj`

Run: `xcodebuild -project iosApp/iosApp.xcodeproj -scheme iosApp -destination 'generic/platform=iOS Simulator' build 2>&1 | tail -5`
Expected: `** BUILD SUCCEEDED **` (first build compiles the Kotlin framework — several minutes).

- [ ] **Step 5: Run it in the simulator and verify the auth gate**

Run: `xcrun simctl list devices available | grep -m1 iPhone` to pick a device name, then boot + install + launch:
```bash
export DEVICE="iPhone 16"   # use whatever the list shows; later tasks reuse $DEVICE the same way
xcrun simctl boot "$DEVICE" 2>/dev/null; open -a Simulator
xcodebuild -project iosApp/iosApp.xcodeproj -scheme iosApp \
  -destination "platform=iOS Simulator,name=$DEVICE" \
  -derivedDataPath iosApp/build/DerivedData build
APP=iosApp/build/DerivedData/Build/Products/Debug-iphonesimulator/iosApp.app
xcrun simctl install booted "$APP" && xcrun simctl launch booted com.badmintontracker.ios
```
Expected: app launches, splash appears briefly, then the "Sign in — TODO Task 6" placeholder (no stored session). This proves: framework embedding, Supabase client construction, `authState` flow delivery.

- [ ] **Step 6: Commit**

```bash
git add iosApp/project.yml iosApp/Config/AppConfig.xcconfig iosApp/Config/Supabase.xcconfig.example \
        iosApp/Sources iosApp/Tests iosApp/iosApp.xcodeproj .gitignore
git commit -m "feat(ios): Xcode project scaffold, RallyApp bootstrap, auth-gated root view"
```

---

### Task 5: Shuttl theme + shared SwiftUI components

**Files:**
- Create: `iosApp/Sources/Theme/ShuttlTheme.swift`
- Create: `iosApp/Sources/Components/ErrorBanner.swift`
- Create: `iosApp/Sources/Components/ShuttlCard.swift`
- Create: `iosApp/Sources/Components/KindBadge.swift`
- Create: `iosApp/Sources/Components/PrimaryButtonStyle.swift`

**Interfaces:**
- Produces: `Shuttl` color namespace (`Shuttl.accent`, `.bg`, `.bgSecondary`, `.bgInput`, `.border`, `.textHeading`, `.text`, `.textSecondary`, `.textTertiary`, `.error`, …), `ErrorBanner(message:)`, `ShuttlCard { content }`, `KindBadge(kind:)`, `kindLabel(_ kind: AnnotationKind) -> String`, `PrimaryButtonStyle()`. All later Swift tasks consume these exact names.

- [ ] **Step 1: Theme tokens (ports of ShuttlColors.kt — light/dark adaptive)**

`iosApp/Sources/Theme/ShuttlTheme.swift`:
```swift
import SwiftUI

extension UIColor {
    convenience init(rgb: UInt32) {
        self.init(
            red: CGFloat((rgb >> 16) & 0xFF) / 255,
            green: CGFloat((rgb >> 8) & 0xFF) / 255,
            blue: CGFloat(rgb & 0xFF) / 255,
            alpha: 1
        )
    }
}

extension Color {
    init(rgb: UInt32) { self.init(UIColor(rgb: rgb)) }
    init(light: UInt32, dark: UInt32) {
        self.init(UIColor { trait in
            trait.userInterfaceStyle == .dark ? UIColor(rgb: dark) : UIColor(rgb: light)
        })
    }
}

/// Ports of androidApp ui/theme/ShuttlColors.kt (web tokens). Sharp corners everywhere
/// (ShuttlShapes.kt is all 0dp) except pill badges/chips.
enum Shuttl {
    static let bg              = Color(light: 0xFFFFFF, dark: 0x0D0D0D)
    static let bgSecondary     = Color(light: 0xF8F9FA, dark: 0x141414)
    static let bgTertiary      = Color(light: 0xF0F1F3, dark: 0x1A1A1A)
    static let bgInput         = Color(light: 0xF0F1F3, dark: 0x111111)
    static let border          = Color(light: 0xE0E0E0, dark: 0x222222)
    static let borderSecondary = Color(light: 0xD0D0D0, dark: 0x333333)
    static let textHeading     = Color(light: 0x0D0D0D, dark: 0xFFFFFF)
    static let text            = Color(light: 0x1A1A2E, dark: 0xE2E8F0)
    static let textSecondary   = Color(light: 0x555555, dark: 0x888888)
    static let textTertiary    = Color(light: 0x777777, dark: 0x666666)
    static let accent          = Color(light: 0x16A34A, dark: 0x22C55E)
    static let accentDark      = Color(light: 0x166534, dark: 0x16A34A)
    static let error           = Color(rgb: 0xEF4444)
    static let warning         = Color(rgb: 0xF59E0B)
    static let info            = Color(rgb: 0x3B82F6)

    /// Tiny uppercase tracked label — matches Android labelSmall (11sp, medium, 0.05em).
    static func sectionLabel(_ text: String) -> some View {
        Text(text.uppercased())
            .font(.system(size: 11, weight: .medium))
            .kerning(0.55)
            .foregroundStyle(Shuttl.textSecondary)
    }
}
```

- [ ] **Step 2: Components**

`iosApp/Sources/Components/ErrorBanner.swift`:
```swift
import SwiftUI

struct ErrorBanner: View {
    let message: String

    var body: some View {
        Text(message)
            .font(.footnote)
            .foregroundStyle(.white)
            .frame(maxWidth: .infinity, alignment: .leading)
            .padding(12)
            .background(Shuttl.error)
    }
}
```

`iosApp/Sources/Components/ShuttlCard.swift`:
```swift
import SwiftUI

struct ShuttlCard<Content: View>: View {
    @ViewBuilder let content: Content

    var body: some View {
        VStack(alignment: .leading, spacing: 0) { content }
            .padding(16)
            .frame(maxWidth: .infinity)
            .background(Shuttl.bgSecondary)
            .overlay(Rectangle().stroke(Shuttl.border, lineWidth: 1))
    }
}
```

`iosApp/Sources/Components/KindBadge.swift` (colors from AnnotationKindStyle.kt):
```swift
import SwiftUI
import Shared

func kindLabel(_ kind: AnnotationKind) -> String {
    switch kind {
    case .goodShot: return "Good shot"
    case .forcedError: return "Forced error"
    case .unforcedError: return "Unforced error"
    default: return ""
    }
}

struct KindBadge: View {
    let kind: AnnotationKind

    private var container: Color {
        switch kind {
        case .goodShot: return Color(rgb: 0x2E7D32)
        case .forcedError: return Color(rgb: 0xB26A00)
        case .unforcedError: return Color(rgb: 0xC62828)
        default: return Shuttl.bgTertiary
        }
    }
    private var onContainer: Color {
        kind == .forcedError ? .black : .white
    }

    var body: some View {
        Text(kindLabel(kind))
            .font(.system(size: 11, weight: .medium))
            .foregroundStyle(onContainer)
            .padding(.horizontal, 10)
            .padding(.vertical, 4)
            .background(Capsule().fill(container))
    }
}
```

`iosApp/Sources/Components/PrimaryButtonStyle.swift`:
```swift
import SwiftUI

struct PrimaryButtonStyle: ButtonStyle {
    func makeBody(configuration: Configuration) -> some View {
        configuration.label
            .font(.body.weight(.semibold))
            .foregroundStyle(.black)
            .frame(maxWidth: .infinity)
            .padding(.vertical, 14)
            .background(Shuttl.accent.opacity(configuration.isPressed ? 0.8 : 1))
    }
}
```

- [ ] **Step 3: Verify it compiles**

Run: `cd iosApp && xcodegen generate && cd .. && xcodebuild -project iosApp/iosApp.xcodeproj -scheme iosApp -destination 'generic/platform=iOS Simulator' build 2>&1 | tail -3`
Expected: `** BUILD SUCCEEDED **` (regenerate is needed because new files were added; xcodegen picks up the Sources tree).

- [ ] **Step 4: Commit**

```bash
git add iosApp/Sources/Theme iosApp/Sources/Components iosApp/iosApp.xcodeproj
git commit -m "feat(ios): Shuttl theme tokens and shared components"
```

---

### Task 6: Sign-in screen

**Files:**
- Create: `iosApp/Sources/SignIn/SignInView.swift`
- Modify: `iosApp/Sources/RootView.swift` (delete the placeholder `SignInView`)

**Interfaces:**
- Consumes: `SwiftInteropKt.signInEmailOrMessage(_:email:password:)` (Task 3), theme/components (Task 5).
- Produces: final `SignInView(rally:)`. Navigation on success is automatic — `RootView` reacts to `authState`.

- [ ] **Step 1: Implement the screen (copy is verbatim from SignInScreen.kt)**

`iosApp/Sources/SignIn/SignInView.swift`:
```swift
import SwiftUI
import Shared

struct SignInView: View {
    let rally: RallyApp
    @State private var email = ""
    @State private var password = ""
    @State private var isSubmitting = false
    @State private var error: String? = nil

    var body: some View {
        ScrollView {
            VStack(spacing: 0) {
                brand
                Spacer().frame(height: 8)
                Text("Sign in to continue")
                    .font(.footnote)
                    .foregroundStyle(Shuttl.textSecondary)
                Spacer().frame(height: 24)
                ShuttlCard {
                    VStack(spacing: 16) {
                        field("Email", text: $email)
                            .keyboardType(.emailAddress)
                            .textContentType(.username)
                        secureField("Password", text: $password)
                        Button(isSubmitting ? "Signing in…" : "Sign in") { submit() }
                            .buttonStyle(PrimaryButtonStyle())
                            .disabled(isSubmitting)
                        if let error {
                            ErrorBanner(message: error)
                        }
                    }
                }
                Spacer().frame(height: 24)
                Text("Registration is closed. Contact the admin if you need an account.")
                    .font(.footnote)
                    .foregroundStyle(Shuttl.textTertiary)
                    .multilineTextAlignment(.center)
                    .frame(maxWidth: 320)
                Spacer().frame(height: 8)
                Text(versionLabel)
                    .font(.footnote)
                    .foregroundStyle(Shuttl.textTertiary)
            }
            .frame(maxWidth: 400)
            .padding(.horizontal, 16)
            .padding(.top, 56)
            .frame(maxWidth: .infinity)
        }
        .background(Shuttl.bg)
    }

    private var brand: some View {
        HStack(spacing: 8) {
            Text("SHUTTL.")
                .font(.system(size: 24, weight: .bold))
                .kerning(-0.24)
                .foregroundStyle(Shuttl.textHeading)
            Text("BETA 2.0")
                .font(.system(size: 9, weight: .semibold))
                .kerning(0.5)
                .foregroundStyle(.black)
                .padding(.horizontal, 6)
                .padding(.vertical, 2)
                .background(LinearGradient(
                    colors: [Shuttl.accent, Shuttl.accentDark],
                    startPoint: .leading, endPoint: .trailing
                ))
        }
    }

    private func field(_ label: String, text: Binding<String>) -> some View {
        TextField(label, text: text)
            .textInputAutocapitalization(.never)
            .autocorrectionDisabled()
            .padding(12)
            .background(Shuttl.bgInput)
    }

    private func secureField(_ label: String, text: Binding<String>) -> some View {
        SecureField(label, text: text)
            .padding(12)
            .background(Shuttl.bgInput)
    }

    private var versionLabel: String {
        let v = Bundle.main.infoDictionary?["CFBundleShortVersionString"] as? String ?? "?"
        let b = Bundle.main.infoDictionary?["CFBundleVersion"] as? String ?? "?"
        return "Version \(v) (\(b))"
    }

    private func submit() {
        isSubmitting = true
        error = nil
        Task {
            do {
                let message = try await SwiftInteropKt.signInEmailOrMessage(
                    rally.auth, email: email, password: password
                )
                if let message { error = message }
            } catch {
                self.error = "Something went wrong. Please check your connection and try again."
            }
            isSubmitting = false
        }
    }
}
```
Delete the placeholder `SignInView` struct from `RootView.swift`.
(SKIE may also surface the wrapper as a member — `rally.auth.signInEmailOrMessage(email:password:)`; either call form is fine, prefer whichever compiles.)

- [ ] **Step 2: Build and verify sign-in end-to-end in the simulator**

Run: `cd iosApp && xcodegen generate && cd .. && xcodebuild -project iosApp/iosApp.xcodeproj -scheme iosApp -destination 'generic/platform=iOS Simulator' build 2>&1 | tail -3`
Expected: `** BUILD SUCCEEDED **`

Then install + launch on the booted simulator (commands from Task 4 Step 5) and verify:
1. Wrong password → red banner "Incorrect email or password."
2. Real credentials (ask the user for a test account if none are known) → view switches to the ClipList placeholder.
3. Relaunch the app → lands directly on ClipList placeholder (session persisted via NSUserDefaults).

- [ ] **Step 3: Commit**

```bash
git add iosApp/Sources/SignIn iosApp/Sources/RootView.swift iosApp/iosApp.xcodeproj
git commit -m "feat(ios): email sign-in screen with shared error copy and version footer"
```

---

### Task 7: Clip list + match clips

**Files:**
- Create: `iosApp/Sources/ClipList/MatchGrouping.swift`
- Create: `iosApp/Sources/ClipList/ClipListModel.swift`
- Create: `iosApp/Sources/ClipList/ClipListView.swift`
- Create: `iosApp/Sources/ClipList/MatchClipsView.swift`
- Modify: `iosApp/Sources/RootView.swift` (delete the placeholder `ClipListView`)
- Test: `iosApp/Tests/MatchGroupingTests.swift`

**Interfaces:**
- Consumes: `rally.clips.observeClips()` / `.refresh()`, `rally.shares.listReceived()`, `rally.media.signedThumbnailUrl(clip:)` / `signedClipUrl(clip:)`, `rally.auth.currentUserId()`, `SwiftInteropKt.signOutOrMessage(_:)`.
- Produces: `ClipInfo` (Swift value mirror of `RallyClip` — `id, videoId, ownerId, rallyIndex: Int32, createdAtMillis: Int64, title: String?, durationSeconds: Float, annotationCount: Int32`), `MatchSummary` (`videoId, rallyCount, latestCreatedAtMillis, coverClipId, isOwned, sharerEmail`), `MatchGrouping.matches(from:currentUserId:sharerByVideoId:) -> (owned: [MatchSummary], shared: [MatchSummary])`, `formatMatchDate(millis:) -> String` ("Jan 5, 2026" style), `ClipListView(rally:)`, `MatchClipsView(rally:videoId:)`. Task 9's share sheet is opened from `ClipListView` rows.

- [ ] **Step 1: Write the failing grouping tests**

`iosApp/Tests/MatchGroupingTests.swift`:
```swift
import XCTest
@testable import iosApp

final class MatchGroupingTests: XCTestCase {
    private func clip(
        id: String, videoId: String, owner: String = "me",
        rallyIndex: Int32, createdAt: Int64
    ) -> ClipInfo {
        ClipInfo(
            id: id, videoId: videoId, ownerId: owner, rallyIndex: rallyIndex,
            createdAtMillis: createdAt, title: nil, durationSeconds: 10,
            annotationCount: 0
        )
    }

    func testGroupsByVideoCoverIsMinRallyIndexSortedByLatestDesc() {
        let clips = [
            clip(id: "a", videoId: "v1", rallyIndex: 2, createdAt: 100),
            clip(id: "b", videoId: "v1", rallyIndex: 1, createdAt: 200),
            clip(id: "c", videoId: "v2", rallyIndex: 1, createdAt: 300),
        ]
        let result = MatchGrouping.matches(from: clips, currentUserId: "me", sharerByVideoId: [:])
        XCTAssertEqual(result.owned.map(\.videoId), ["v2", "v1"])   // latestCreatedAt desc
        XCTAssertEqual(result.owned[1].coverClipId, "b")            // min rallyIndex
        XCTAssertEqual(result.owned[1].rallyCount, 2)
        XCTAssertEqual(result.owned[1].latestCreatedAtMillis, 200)  // max createdAt
        XCTAssertTrue(result.shared.isEmpty)
    }

    func testPartitionsSharedMatchesWithSharerEmail() {
        let clips = [clip(id: "a", videoId: "v9", owner: "someone-else", rallyIndex: 1, createdAt: 50)]
        let result = MatchGrouping.matches(
            from: clips, currentUserId: "me", sharerByVideoId: ["v9": "coach@x.com"]
        )
        XCTAssertTrue(result.owned.isEmpty)
        XCTAssertEqual(result.shared.first?.sharerEmail, "coach@x.com")
        XCTAssertEqual(result.shared.first?.isOwned, false)
    }
}
```

- [ ] **Step 2: Run tests to verify they fail**

Boot a simulator (Task 4 Step 5), regenerate, then:
Run: `cd iosApp && xcodegen generate && cd .. && xcodebuild test -project iosApp/iosApp.xcodeproj -scheme iosApp -destination "platform=iOS Simulator,name=$DEVICE" 2>&1 | tail -10`
Expected: compile FAILURE — `ClipInfo`/`MatchGrouping` not defined.

- [ ] **Step 3: Implement grouping (port of ClipListViewModel.toMatches)**

`iosApp/Sources/ClipList/MatchGrouping.swift`:
```swift
import Foundation
import Shared

/// Swift value mirror of RallyClip so grouping is unit-testable without Kotlin construction.
struct ClipInfo: Equatable {
    let id: String
    let videoId: String
    let ownerId: String
    let rallyIndex: Int32
    let createdAtMillis: Int64
    let title: String?
    let durationSeconds: Float
    let annotationCount: Int32
}

extension ClipInfo {
    init(_ clip: RallyClip) {
        self.init(
            id: clip.id, videoId: clip.videoId, ownerId: clip.ownerId,
            rallyIndex: clip.rallyIndex, createdAtMillis: clip.createdAt.toEpochMilliseconds(),
            title: clip.title, durationSeconds: clip.durationSeconds,
            annotationCount: clip.annotationCount
        )
    }
}

struct MatchSummary: Equatable {
    let videoId: String
    let rallyCount: Int
    let latestCreatedAtMillis: Int64
    let coverClipId: String
    let isOwned: Bool
    let sharerEmail: String?
}

enum MatchGrouping {
    /// Port of ClipListViewModel.toMatches: group by videoId, cover = min rallyIndex,
    /// sort by latest createdAt desc, partition into owned/shared.
    static func matches(
        from clips: [ClipInfo],
        currentUserId: String?,
        sharerByVideoId: [String: String]
    ) -> (owned: [MatchSummary], shared: [MatchSummary]) {
        let all = Dictionary(grouping: clips, by: \.videoId)
            .map { videoId, list -> MatchSummary in
                let cover = list.min { $0.rallyIndex < $1.rallyIndex } ?? list[0]
                let owned = currentUserId != nil && cover.ownerId == currentUserId
                return MatchSummary(
                    videoId: videoId,
                    rallyCount: list.count,
                    latestCreatedAtMillis: list.map(\.createdAtMillis).max() ?? 0,
                    coverClipId: cover.id,
                    isOwned: owned,
                    sharerEmail: owned ? nil : sharerByVideoId[videoId]
                )
            }
            .sorted { $0.latestCreatedAtMillis > $1.latestCreatedAtMillis }
        return (all.filter(\.isOwned), all.filter { !$0.isOwned })
    }
}

private let matchDateFormatter: DateFormatter = {
    let f = DateFormatter()
    f.dateFormat = "MMM d, yyyy"   // "Jan 5, 2026" — matches Android formatDate
    return f
}()

func formatMatchDate(millis: Int64) -> String {
    matchDateFormatter.string(from: Date(timeIntervalSince1970: Double(millis) / 1000))
}
```

- [ ] **Step 4: Run tests to verify they pass**

Run: `xcodebuild test -project iosApp/iosApp.xcodeproj -scheme iosApp -destination "platform=iOS Simulator,name=$DEVICE" 2>&1 | tail -5`
Expected: `** TEST SUCCEEDED **`

- [ ] **Step 5: Implement the model and views**

`iosApp/Sources/ClipList/ClipListModel.swift`:
```swift
import Foundation
import Shared

@Observable @MainActor
final class ClipListModel {
    let rally: RallyApp
    private(set) var clips: [RallyClip] = []
    private(set) var owned: [MatchSummary] = []
    private(set) var shared: [MatchSummary] = []
    private(set) var thumbnailUrls: [String: URL] = [:]   // clipId -> signed URL
    var isRefreshing = false
    var error: String? = nil
    private var sharerByVideoId: [String: String] = [:]

    init(rally: RallyApp) { self.rally = rally }

    func start() async {
        Task { await refresh() }
        for await latest in rally.clips.observeClips() {
            clips = latest
            regroup()
        }
    }

    func refresh() async {
        isRefreshing = true
        error = nil
        do {
            try await rally.clips.refresh()
        } catch {
            self.error = "Couldn't refresh matches. Pull to try again."
        }
        // Soft failure: leave sharerByVideoId untouched, no user-facing error (matches Android).
        if let received = try? await rally.shares.listReceived() {
            sharerByVideoId = Dictionary(
                uniqueKeysWithValues: received.compactMap { r in
                    r.sharerEmail.map { (r.videoId, $0) }
                }
            )
        }
        regroup()
        isRefreshing = false
    }

    func signOut() async {
        _ = try? await SwiftInteropKt.signOutOrMessage(rally.auth)
    }

    func thumbnail(forCoverOf match: MatchSummary) async {
        guard thumbnailUrls[match.coverClipId] == nil,
              let cover = clips.first(where: { $0.id == match.coverClipId }) else { return }
        if let signed = try? await rally.media.signedThumbnailUrl(clip: cover),
           let url = URL(string: signed) {
            thumbnailUrls[match.coverClipId] = url
        }
    }

    private func regroup() {
        let infos = clips.map(ClipInfo.init)
        let result = MatchGrouping.matches(
            from: infos,
            currentUserId: rally.auth.currentUserId(),
            sharerByVideoId: sharerByVideoId
        )
        owned = result.owned
        shared = result.shared
    }
}
```

`iosApp/Sources/ClipList/ClipListView.swift`:
```swift
import SwiftUI
import Shared

struct ClipListView: View {
    let rally: RallyApp
    @State private var model: ClipListModel?
    @State private var shareTarget: MatchSummary? = nil

    var body: some View {
        Group {
            if let model {
                content(model)
            } else {
                SplashView()
            }
        }
        .navigationTitle("MATCHES")
        .navigationBarTitleDisplayMode(.inline)
        .toolbar {
            ToolbarItem(placement: .topBarTrailing) {
                if let model {
                    Menu {
                        Button("Sign out") { Task { await model.signOut() } }
                        Divider()
                        Text(versionLabel)
                    } label: {
                        Image(systemName: "ellipsis")
                    }
                }
            }
        }
        .task {
            if model == nil { model = ClipListModel(rally: rally) }
            await model?.start()
        }
        .sheet(item: $shareTarget) { match in
            ShareSheetView(rally: rally, videoId: match.videoId)
        }
    }

    @ViewBuilder
    private func content(_ model: ClipListModel) -> some View {
        List {
            if let error = model.error {
                ErrorBanner(message: error)
                    .listRowInsets(EdgeInsets())
            }
            if !model.owned.isEmpty {
                Section {
                    ForEach(model.owned, id: \.videoId) { match in
                        row(match, model: model)
                    }
                } header: { Shuttl.sectionLabel("My matches") }
            }
            if !model.shared.isEmpty {
                Section {
                    ForEach(model.shared, id: \.videoId) { match in
                        row(match, model: model)
                    }
                } header: { Shuttl.sectionLabel("Shared with me") }
            }
            if model.owned.isEmpty && model.shared.isEmpty && !model.isRefreshing {
                Text("No matches yet.")
                    .foregroundStyle(Shuttl.textSecondary)
            }
        }
        .listStyle(.plain)
        .refreshable { await model.refresh() }
        .navigationDestination(for: String.self) { videoId in
            MatchClipsView(rally: rally, videoId: videoId)
        }
    }

    private func row(_ match: MatchSummary, model: ClipListModel) -> some View {
        NavigationLink(value: match.videoId) {
            HStack(spacing: 12) {
                AsyncImage(url: model.thumbnailUrls[match.coverClipId]) { image in
                    image.resizable().aspectRatio(contentMode: .fill)
                } placeholder: {
                    Shuttl.bgTertiary
                }
                .frame(width: 96, height: 54)
                .clipped()
                .task { await model.thumbnail(forCoverOf: match) }

                VStack(alignment: .leading, spacing: 4) {
                    Text("Match · \(formatMatchDate(millis: match.latestCreatedAtMillis))")
                        .font(.body.weight(.medium))
                        .foregroundStyle(Shuttl.text)
                    Text("\(match.rallyCount) \(match.rallyCount == 1 ? "RALLY" : "RALLIES")")
                        .font(.system(size: 11, weight: .medium))
                        .kerning(0.55)
                        .foregroundStyle(Shuttl.textSecondary)
                    if let sharer = match.sharerEmail {
                        Text("Shared by \(sharer)")
                            .font(.footnote)
                            .foregroundStyle(Shuttl.textSecondary)
                            .lineLimit(1)
                    }
                }
                Spacer()
                if match.isOwned {
                    Button {
                        shareTarget = match
                    } label: {
                        Image(systemName: "square.and.arrow.up")
                    }
                    .buttonStyle(.borderless)
                }
            }
        }
    }

    private var versionLabel: String {
        let v = Bundle.main.infoDictionary?["CFBundleShortVersionString"] as? String ?? "?"
        let b = Bundle.main.infoDictionary?["CFBundleVersion"] as? String ?? "?"
        return "Version \(v) (\(b))"
    }
}

extension MatchSummary: Identifiable {
    var id: String { videoId }
}
```
Until Task 9 exists, add a temporary placeholder at the bottom of `ClipListView.swift` (Task 9 deletes it):
```swift
struct ShareSheetView: View {
    let rally: RallyApp
    let videoId: String
    var body: some View { Text("Share — TODO Task 9") }
}
```

`iosApp/Sources/ClipList/MatchClipsView.swift`:
```swift
import SwiftUI
import Shared

struct MatchClipsView: View {
    let rally: RallyApp
    let videoId: String
    @State private var clips: [RallyClip] = []

    private var title: String {
        guard let latest = clips.map({ $0.createdAt.toEpochMilliseconds() }).max() else {
            return "RALLIES"
        }
        return "MATCH · \(formatMatchDate(millis: latest).uppercased())"
    }

    var body: some View {
        List {
            if clips.isEmpty {
                Text("No rallies in this match.")
                    .foregroundStyle(Shuttl.textSecondary)
            }
            ForEach(clips, id: \.id) { clip in
                NavigationLink {
                    ClipDetailView(rally: rally, clipId: clip.id)
                } label: {
                    VStack(alignment: .leading, spacing: 4) {
                        Text(clip.title ?? "Rally #\(clip.rallyIndex)")
                            .font(.body.weight(.medium))
                            .foregroundStyle(Shuttl.text)
                        Text("\(clip.durationSeconds)S · \(clip.annotationCount) NOTES")
                            .font(.system(size: 11, weight: .medium))
                            .kerning(0.55)
                            .foregroundStyle(Shuttl.textSecondary)
                    }
                }
            }
        }
        .listStyle(.plain)
        .navigationTitle(title)
        .navigationBarTitleDisplayMode(.inline)
        .task {
            for await latest in rally.clips.observeClips() {
                clips = latest
                    .filter { $0.videoId == videoId }
                    .sorted { $0.rallyIndex < $1.rallyIndex }
            }
        }
    }
}
```
Add a temporary `ClipDetailView` placeholder at the bottom (Task 8 deletes it):
```swift
struct ClipDetailView: View {
    let rally: RallyApp
    let clipId: String
    var body: some View { Text("Detail — TODO Task 8") }
}
```
Delete the placeholder `ClipListView` from `RootView.swift`.

- [ ] **Step 6: Build, test, and verify in the simulator**

Run: `cd iosApp && xcodegen generate && cd .. && xcodebuild test -project iosApp/iosApp.xcodeproj -scheme iosApp -destination "platform=iOS Simulator,name=$DEVICE" 2>&1 | tail -5`
Expected: `** TEST SUCCEEDED **`

Install + launch (Task 4 Step 5 commands). Verify signed in: matches list loads with thumbnails, grouped sections, pull-to-refresh spins and completes, tapping a match shows its rallies sorted by rally index, "Sign out" in the menu returns to sign-in.

- [ ] **Step 7: Commit**

```bash
git add iosApp/Sources/ClipList iosApp/Sources/RootView.swift iosApp/Tests/MatchGroupingTests.swift iosApp/iosApp.xcodeproj
git commit -m "feat(ios): clip list with match grouping, thumbnails, match clips screen"
```

---

### Task 8: Clip detail — AVPlayer + annotations

**Files:**
- Create: `iosApp/Sources/ClipDetail/ClipDetailModel.swift`
- Create: `iosApp/Sources/ClipDetail/ClipDetailView.swift`
- Create: `iosApp/Sources/ClipDetail/AddAnnotationSheet.swift`
- Modify: `iosApp/Sources/ClipList/MatchClipsView.swift` (delete the placeholder `ClipDetailView`)

**Interfaces:**
- Consumes: `rally.clips.observeClips()` / `.refresh()`, `rally.media.signedClipUrl(clip:)`, `rally.annotations.list(clipId:)`, `SwiftInteropKt.addAnnotationForSwift(...)` / `deleteAnnotationOrMessage(...)`, `rally.auth.currentUserId()`, `KindBadge`/`kindLabel` (Task 5).
- Produces: `ClipDetailView(rally:clipId:)` — final, replaces the Task 7 placeholder.

- [ ] **Step 1: Implement the model (port of ClipDetailViewModel)**

`iosApp/Sources/ClipDetail/ClipDetailModel.swift`:
```swift
import AVKit
import Foundation
import Shared

@Observable @MainActor
final class ClipDetailModel {
    let rally: RallyApp
    let clipId: String
    private(set) var isLoading = true
    private(set) var clip: RallyClip? = nil
    private(set) var annotations: [RallyAnnotation] = []
    private(set) var player: AVPlayer? = nil
    private(set) var isOwner = false
    var error: String? = nil
    var actionError: String? = nil

    init(rally: RallyApp, clipId: String) {
        self.rally = rally
        self.clipId = clipId
    }

    func load() async {
        isLoading = true
        error = nil
        // Cache first, then one refresh — mirrors ClipDetailViewModel.load().
        var found = try? await firstCachedClip()
        if found == nil {
            do {
                try await rally.clips.refresh()
                found = try? await firstCachedClip()
            } catch {
                self.error = "Couldn't load clip: \(error.localizedDescription)"
            }
        }
        guard let clip = found else {
            if error == nil { error = "Clip not found" }
            isLoading = false
            return
        }
        self.clip = clip
        isOwner = clip.ownerId == rally.auth.currentUserId()

        if let rows = try? await rally.annotations.list(clipId: clipId) {
            annotations = rows   // server-sorted by timestamp ascending
        } else {
            actionError = "Couldn't load annotations"
        }
        await sign(clip: clip)
        isLoading = false
    }

    /// Re-sign the URL (used by load and by the manual Retry on player failure).
    func sign(clip: RallyClip) async {
        do {
            let signed = try await rally.media.signedClipUrl(clip: clip)
            if let url = URL(string: signed) {
                player = AVPlayer(url: url)
            } else {
                error = "Couldn't load video"
            }
        } catch {
            self.error = "Couldn't sign clip URL"
        }
    }

    func retry() async {
        error = nil
        if let clip { await sign(clip: clip) } else { await load() }
    }

    func seek(to seconds: Float) {
        player?.seek(to: CMTime(seconds: Double(seconds), preferredTimescale: 600))
    }

    func add(kind: AnnotationKind?, body: String) async {
        guard isOwner else { return }
        let trimmed = body.trimmingCharacters(in: .whitespacesAndNewlines)
        if trimmed.isEmpty && kind == nil { return }
        let ts = max(0, currentTimeSeconds())
        guard let outcome = try? await SwiftInteropKt.addAnnotationForSwift(
            rally.annotations, clipId: clipId, timestampSeconds: ts, body: trimmed, kind: kind
        ) else {
            actionError = "Couldn't add annotation"
            return
        }
        if let row = outcome.annotation {
            annotations = (annotations + [row]).sorted { $0.timestampSeconds < $1.timestampSeconds }
        } else {
            actionError = outcome.errorMessage
        }
    }

    func delete(id: String) async {
        guard isOwner else { return }
        if let message = try? await SwiftInteropKt.deleteAnnotationOrMessage(rally.annotations, id: id),
           let message {
            actionError = message
        } else {
            annotations = annotations.filter { $0.id != id }
        }
    }

    func currentTimeSeconds() -> Float {
        guard let time = player?.currentTime(), time.isNumeric else { return 0 }
        return Float(CMTimeGetSeconds(time))
    }

    private func firstCachedClip() async throws -> RallyClip? {
        for await clips in rally.clips.observeClips() {
            return clips.first { $0.id == clipId }   // take first emission only
        }
        return nil
    }
}
```

- [ ] **Step 2: Implement the views**

`iosApp/Sources/ClipDetail/ClipDetailView.swift`:
```swift
import AVKit
import SwiftUI
import Shared

struct ClipDetailView: View {
    let rally: RallyApp
    let clipId: String
    @State private var model: ClipDetailModel?
    @State private var showAddSheet = false
    @State private var deleteTarget: RallyAnnotation? = nil

    var body: some View {
        Group {
            if let model {
                content(model)
            } else {
                SplashView()
            }
        }
        .navigationBarTitleDisplayMode(.inline)
        .task {
            if model == nil {
                let m = ClipDetailModel(rally: rally, clipId: clipId)
                model = m
                await m.load()
            }
        }
    }

    @ViewBuilder
    private func content(_ model: ClipDetailModel) -> some View {
        VStack(spacing: 0) {
            VideoPlayer(player: model.player)
                .aspectRatio(16 / 9, contentMode: .fit)
                .background(Color.black)

            if let error = model.error {
                VStack(spacing: 8) {
                    ErrorBanner(message: error)
                    Button("Retry") { Task { await model.retry() } }
                        .buttonStyle(PrimaryButtonStyle())
                        .frame(maxWidth: 160)
                }
                .padding(16)
            }

            HStack {
                Text(model.clip.map { $0.title ?? "Rally #\($0.rallyIndex)" } ?? "")
                    .font(.title3.weight(.semibold))
                    .foregroundStyle(Shuttl.textHeading)
                Spacer()
                if model.isOwner {
                    Button {
                        showAddSheet = true
                    } label: {
                        Image(systemName: "plus")
                    }
                }
            }
            .padding(16)

            if let actionError = model.actionError {
                ErrorBanner(message: actionError)
            }

            List {
                ForEach(model.annotations, id: \.id) { annotation in
                    annotationRow(annotation, model: model)
                }
            }
            .listStyle(.plain)
        }
        .sheet(isPresented: $showAddSheet) {
            AddAnnotationSheet { kind, body in
                Task { await model.add(kind: kind, body: body) }
            }
            .presentationDetents([.medium])
        }
        .alert("Delete annotation?", isPresented: Binding(
            get: { deleteTarget != nil },
            set: { if !$0 { deleteTarget = nil } }
        )) {
            Button("Cancel", role: .cancel) { deleteTarget = nil }
            Button("Delete", role: .destructive) {
                if let target = deleteTarget {
                    Task { await model.delete(id: target.id) }
                }
                deleteTarget = nil
            }
        }
    }

    private func annotationRow(_ annotation: RallyAnnotation, model: ClipDetailModel) -> some View {
        HStack(spacing: 12) {
            Text(formatTimestamp(annotation.timestampSeconds))
                .font(.footnote.monospacedDigit())
                .foregroundStyle(Shuttl.textSecondary)
            if let kind = annotation.kind {
                KindBadge(kind: kind)
            }
            if !annotation.body.isEmpty {
                Text(annotation.body)
                    .font(.subheadline)
                    .foregroundStyle(Shuttl.text)
            }
            Spacer()
            if model.isOwner {
                Button {
                    deleteTarget = annotation
                } label: {
                    Image(systemName: "trash")
                }
                .buttonStyle(.borderless)
                .accessibilityLabel("Delete annotation")
            }
        }
        .contentShape(Rectangle())
        .onTapGesture { model.seek(to: annotation.timestampSeconds) }
    }
}

/// "m:ss" — matches Android formatTimestamp.
func formatTimestamp(_ seconds: Float) -> String {
    let total = Int(seconds)
    return String(format: "%d:%02d", total / 60, total % 60)
}
```

`iosApp/Sources/ClipDetail/AddAnnotationSheet.swift` (copy verbatim from AnnotationUi.kt):
```swift
import SwiftUI
import Shared

struct AddAnnotationSheet: View {
    let onAdd: (AnnotationKind?, String) -> Void
    @Environment(\.dismiss) private var dismiss
    @State private var kind: AnnotationKind? = nil
    @State private var body_ = ""

    private let kinds: [AnnotationKind] = [.goodShot, .forcedError, .unforcedError]

    var body: some View {
        VStack(alignment: .leading, spacing: 16) {
            Text("Add annotation")
                .font(.title2.weight(.semibold))
            HStack(spacing: 8) {
                ForEach(kinds, id: \.self) { k in
                    chip(k)
                }
            }
            TextField("Note (optional)", text: $body_, axis: .vertical)
                .padding(12)
                .background(Shuttl.bgInput)
            HStack {
                Spacer()
                Button("Cancel") { dismiss() }
                Button("Add") {
                    onAdd(kind, body_)
                    dismiss()
                }
                .disabled(kind == nil && body_.trimmingCharacters(in: .whitespaces).isEmpty)
            }
        }
        .padding(.horizontal, 24)
        .padding(.top, 24)
        .padding(.bottom, 16)
        .frame(maxHeight: .infinity, alignment: .top)
    }

    private func chip(_ k: AnnotationKind) -> some View {
        let selected = kind == k
        return Button {
            kind = selected ? nil : k   // tapping selected chip deselects
        } label: {
            Text(kindLabel(k))
                .font(.footnote)
                .padding(.horizontal, 12)
                .padding(.vertical, 6)
                .background(Capsule().fill(selected ? Shuttl.accent : Shuttl.bgTertiary))
                .foregroundStyle(selected ? .black : Shuttl.text)
        }
    }
}
```
Delete the placeholder `ClipDetailView` from `MatchClipsView.swift`.

- [ ] **Step 3: Build, test, and verify in the simulator**

Run: `cd iosApp && xcodegen generate && cd .. && xcodebuild test -project iosApp/iosApp.xcodeproj -scheme iosApp -destination "platform=iOS Simulator,name=$DEVICE" 2>&1 | tail -5`
Expected: `** TEST SUCCEEDED **`

Install + launch. Verify: tapping a rally opens the detail; video plays; annotation rows show timestamp + badge + body; tapping a row seeks the player; on an owned clip the + opens the sheet, chips toggle, Add inserts the row in timestamp order; delete asks "Delete annotation?" and removes the row; on a shared (non-owned) clip the + and trash buttons are absent.

- [ ] **Step 4: Commit**

```bash
git add iosApp/Sources/ClipDetail iosApp/Sources/ClipList/MatchClipsView.swift iosApp/iosApp.xcodeproj
git commit -m "feat(ios): clip detail with AVPlayer, annotations, add/delete flows"
```

---

### Task 9: Share sheet

**Files:**
- Create: `iosApp/Sources/Share/ShareSheetView.swift`
- Modify: `iosApp/Sources/ClipList/ClipListView.swift` (delete the placeholder `ShareSheetView`)

**Interfaces:**
- Consumes: `SwiftInteropKt.shareOrMessage(...)` / `unshareOrMessage(...)` / `listSharesOrNull(...)` (Task 3).
- Produces: final `ShareSheetView(rally:videoId:)` presented from clip-list rows.

- [ ] **Step 1: Implement (copy verbatim from ShareSheet.kt / ShareSheetViewModel.kt)**

`iosApp/Sources/Share/ShareSheetView.swift`:
```swift
import SwiftUI
import Shared

struct ShareSheetView: View {
    let rally: RallyApp
    let videoId: String
    @State private var email = ""
    @State private var recipients: [MatchShare] = []
    @State private var isBusy = false
    @State private var error: String? = nil

    var body: some View {
        VStack(alignment: .leading, spacing: 16) {
            Text("Share match")
                .font(.headline)
            TextField("Email", text: $email)
                .keyboardType(.emailAddress)
                .textInputAutocapitalization(.never)
                .autocorrectionDisabled()
                .padding(12)
                .background(Shuttl.bgInput)
            if let error {
                Text(error)
                    .font(.footnote)
                    .foregroundStyle(Shuttl.error)
            }
            HStack {
                Spacer()
                Button("Share") { share() }
                    .buttonStyle(PrimaryButtonStyle())
                    .frame(maxWidth: 120)
                    .disabled(isBusy || email.trimmingCharacters(in: .whitespaces).isEmpty)
            }
            Divider()
            Shuttl.sectionLabel("People with access")
            if recipients.isEmpty {
                Text("No one yet.")
                    .font(.subheadline)
                    .foregroundStyle(Shuttl.textSecondary)
            }
            ForEach(recipients, id: \.sharedWithUserId) { r in
                HStack {
                    Text(r.email ?? "Unknown user")
                        .frame(maxWidth: .infinity, alignment: .leading)
                    Button {
                        unshare(userId: r.sharedWithUserId)
                    } label: {
                        Image(systemName: "xmark")
                    }
                    .accessibilityLabel("Remove access")
                }
            }
            Spacer()
        }
        .padding(16)
        .presentationDetents([.medium])
        .task { await refresh() }
    }

    private func refresh() async {
        if let shares = try? await SwiftInteropKt.listSharesOrNull(rally.shares, videoId: videoId) {
            recipients = shares
        }
    }

    private func share() {
        let trimmed = email.trimmingCharacters(in: .whitespaces)
        guard !trimmed.isEmpty else { return }
        isBusy = true
        error = nil
        Task {
            do {
                let message = try await SwiftInteropKt.shareOrMessage(
                    rally.shares, videoId: videoId, email: trimmed
                )
                if let message {
                    error = message
                } else {
                    email = ""
                    await refresh()
                }
            } catch {
                self.error = "Could not share — please try again."
            }
            isBusy = false
        }
    }

    private func unshare(userId: String) {
        isBusy = true
        Task {
            do {
                let message = try await SwiftInteropKt.unshareOrMessage(
                    rally.shares, videoId: videoId, userId: userId
                )
                if let message { error = message } else { await refresh() }
            } catch {
                error = "Couldn't remove access — please try again."
            }
            isBusy = false
        }
    }
}
```
Delete the placeholder `ShareSheetView` from `ClipListView.swift`.

- [ ] **Step 2: Build, test, and verify in the simulator**

Run: `cd iosApp && xcodegen generate && cd .. && xcodebuild test -project iosApp/iosApp.xcodeproj -scheme iosApp -destination "platform=iOS Simulator,name=$DEVICE" 2>&1 | tail -5`
Expected: `** TEST SUCCEEDED **`

Install + launch. On an owned match, tap share: sheet opens, sharing to an unknown email shows "No Shuttl user found with that email.", sharing to your own email shows "You can't share a match with yourself.", sharing to a real second account adds them under "People with access", and ✕ removes them.

- [ ] **Step 3: Commit**

```bash
git add iosApp/Sources/Share iosApp/Sources/ClipList/ClipListView.swift iosApp/iosApp.xcodeproj
git commit -m "feat(ios): match share sheet with shared error copy"
```

---

### Task 10: CI jobs + changelog

**Files:**
- Modify: `.github/workflows/ci.yml`
- Modify: `CHANGELOG.md`

**Interfaces:**
- Consumes: `Config/Version.xcconfig` format (Task 1), `iosApp/iosApp.xcodeproj` + scheme `iosApp` (Task 4), `Supabase.xcconfig.example` (Task 4).

- [ ] **Step 1: Extend the workflow**

In `.github/workflows/ci.yml`, change the `on:` block to also fire on version tags:
```yaml
on:
  push:
    branches: [main]
    tags: ["v*"]
  pull_request:
```

Append two jobs (same indentation as the existing four):
```yaml
  ios-app:
    runs-on: macos-14
    steps:
      - uses: actions/checkout@v4
      - uses: actions/setup-java@v4
        with:
          distribution: temurin
          java-version: 17
      - uses: gradle/actions/setup-gradle@v4
      - run: cp iosApp/Config/Supabase.xcconfig.example iosApp/Config/Supabase.xcconfig
      - run: >
          xcodebuild test
          -project iosApp/iosApp.xcodeproj
          -scheme iosApp
          -destination 'platform=iOS Simulator,name=iPhone 15'
          CODE_SIGNING_ALLOWED=NO

  version-tag-check:
    if: startsWith(github.ref, 'refs/tags/v')
    runs-on: ubuntu-latest
    steps:
      - uses: actions/checkout@v4
      - name: Verify tag matches Config/Version.xcconfig
        run: |
          TAG="${GITHUB_REF_NAME#v}"
          FILE=$(sed -n 's/^MARKETING_VERSION *= *//p' Config/Version.xcconfig | tr -d '[:space:]')
          if [ "$TAG" != "$FILE" ]; then
            echo "::error::Tag v$TAG does not match MARKETING_VERSION=$FILE in Config/Version.xcconfig"
            exit 1
          fi
          echo "Tag v$TAG matches Version.xcconfig"
```
(If the macos-14 image's Xcode has no "iPhone 15" simulator, pick the first name from `xcrun simctl list devices available` — but iPhone 15 is present on macos-14/Xcode 15 images.)

- [ ] **Step 2: Validate the workflow syntax locally**

Run: `ruby -ryaml -e 'YAML.load_file(".github/workflows/ci.yml"); puts "YAML_OK"'`
Expected: `YAML_OK`

- [ ] **Step 3: Update the changelog**

Add under the `## [Unreleased]` heading in `CHANGELOG.md` (create the section if the working tree's in-progress edits changed it — do not touch unrelated pending entries):
```markdown
### Added
- iOS app (initial release, remote-clips parity): email sign-in, match list with
  thumbnails, rally clips, clip detail with AVPlayer and annotations, match sharing.
- Single shared version source `Config/Version.xcconfig` read by both Android and iOS;
  release tags `vX.Y.Z` are CI-verified against it. Version shown on both sign-in screens.
```

- [ ] **Step 4: Verify everything still builds and tests pass**

Run: `./gradlew :shared:jvmTest :androidApp:testDebugUnitTest :androidApp:assembleDebug && xcodebuild test -project iosApp/iosApp.xcodeproj -scheme iosApp -destination "platform=iOS Simulator,name=$DEVICE" 2>&1 | tail -3`
Expected: BUILD SUCCESSFUL for Gradle, `** TEST SUCCEEDED **` for Xcode.

- [ ] **Step 5: Commit**

```bash
git add .github/workflows/ci.yml CHANGELOG.md
git commit -m "ci: iOS app build+test job and version-tag consistency check"
```

---

## Verification checklist (end of plan)

1. `git tag v0.2.0-rc-test && git push` is NOT part of this plan — tagging happens when the user cuts a release. The CI check can be exercised on a branch by pushing a scratch tag if the user wants.
2. Both platforms from one commit report **Version 0.2.0 (9)** on their sign-in screens.
3. All six CI jobs green: shared-tests, ios-framework, android-tests, android-assemble, ios-app, (version-tag-check dormant until a tag).
4. Simulator walkthrough: sign in → matches list → match → rally → play, seek via annotation, add + delete annotation → share/unshare a match → sign out.

## Known interop watch-points (for the executor)

- SKIE surfaces Kotlin extension functions either as `SwiftInteropKt.foo(receiver, ...)` or as Swift members `receiver.foo(...)` — the plan's code uses the `SwiftInteropKt` static form; if the compiler complains, try the member form. Same for `RallyAppIosKt.createRallyApp`.
- `observeClips()` element type should arrive as `[RallyClip]`; if it arrives as `NSArray`, cast: `(latest as? [RallyClip]) ?? []`.
- `SessionStatus.NotAuthenticated`'s constructor signature in the Task 2 test may differ in supabase-kt 3.5.0 — check the library source, keep the test's intent.
- If `xcodebuild` cannot find Java when launched from Xcode GUI, the pre-build script's `/usr/libexec/java_home` fallback handles it; from the CLI the shell env is inherited.
