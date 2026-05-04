# Android UI Implementation Plan

> **For Claude:** REQUIRED SUB-SKILL: Use superpowers:executing-plans to implement this plan task-by-task.

**Goal:** Ship the first Android client. Sign in (email + Google), browse clips, watch a clip with annotations as a list. Read-only consumption — no add/edit/delete from mobile in this plan.

**Architecture:** Plain `:androidApp` Android module on `:shared`. Three screens, three ViewModels (one per `NavBackStackEntry`), manual DI via `Application` + `viewModelFactory`, type-safe Nav Compose routes, Material 3, Media3 ExoPlayer. ViewModel unit tests only — no Compose UI tests, no Robolectric.

**Tech Stack:** Kotlin 2.3.20, AGP 8.10.1, Compose BOM (latest stable), Activity/Lifecycle/Navigation Compose, Media3 ExoPlayer, Coil 3, multiplatform-settings (already in `:shared`).

**Reference design:** `docs/plans/2026-05-04-android-ui-design.md`.

**Reference foundation:** the `:shared` module on `main` exposes `RallyApp(config, settings, httpEngine?)` with `auth: AuthRepository`, `clips: ClipsRepository`, `annotations: AnnotationsRepository`, `media: MediaRepository`. Don't re-read those interfaces here — open the source if needed.

> **Note on versions.** The library versions below are best guesses for compatibility with Kotlin 2.3.20 / AGP 8.10.1 as of 2026-05-04. If a transitive dep forces a newer version (as supabase-kt did during the foundation), bump and document in the commit message — don't fight it.

---

## Phase 0 — Module bootstrap

### Task 1: Re-add `:androidApp` and create the directory

**Files:**
- Modify: `settings.gradle.kts`
- Create: `androidApp/build.gradle.kts` (placeholder so Gradle can resolve)

**Step 1: Edit `settings.gradle.kts`**

Append `include(":androidApp")` after `include(":shared")`.

**Step 2: Write a placeholder `androidApp/build.gradle.kts`**

```kotlin
plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
}

android {
    namespace = "com.badmintontracker.android"
    compileSdk = 35
    defaultConfig {
        applicationId = "com.badmintontracker.android"
        minSdk = 26
        targetSdk = 35
        versionCode = 1
        versionName = "0.1.0"
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }
}

dependencies {
    implementation(project(":shared"))
}
```

This will fail until `kotlin-android` is added to the version catalog (Task 2). That's expected — Tasks 1–3 form a unit; we'll verify after Task 3.

**Step 3: Commit**

```bash
git add settings.gradle.kts androidApp/build.gradle.kts
git commit -m "chore(androidApp): scaffold module"
```

---

### Task 2: Android UI version catalog entries

**Files:**
- Modify: `gradle/libs.versions.toml`

**Step 1: Add versions**

```toml
[versions]
# ...existing...
activity-compose       = "1.10.0"
compose-bom            = "2025.01.00"
lifecycle              = "2.9.0"
navigation-compose     = "2.8.5"
media3                 = "1.5.1"
coil                   = "3.0.4"
```

**Step 2: Add libraries**

```toml
[libraries]
# ...existing...
androidx-activity-compose       = { module = "androidx.activity:activity-compose",                       version.ref = "activity-compose" }
androidx-compose-bom            = { module = "androidx.compose:compose-bom",                             version.ref = "compose-bom" }
androidx-compose-ui             = { module = "androidx.compose.ui:ui" }
androidx-compose-ui-tooling     = { module = "androidx.compose.ui:ui-tooling" }
androidx-compose-ui-tooling-preview = { module = "androidx.compose.ui:ui-tooling-preview" }
androidx-compose-material3      = { module = "androidx.compose.material3:material3" }
androidx-lifecycle-viewmodel-compose = { module = "androidx.lifecycle:lifecycle-viewmodel-compose",      version.ref = "lifecycle" }
androidx-lifecycle-runtime-compose   = { module = "androidx.lifecycle:lifecycle-runtime-compose",        version.ref = "lifecycle" }
androidx-navigation-compose     = { module = "androidx.navigation:navigation-compose",                   version.ref = "navigation-compose" }
androidx-media3-exoplayer       = { module = "androidx.media3:media3-exoplayer",                          version.ref = "media3" }
androidx-media3-ui              = { module = "androidx.media3:media3-ui",                                 version.ref = "media3" }
androidx-media3-common          = { module = "androidx.media3:media3-common",                             version.ref = "media3" }
coil-compose                    = { module = "io.coil-kt.coil3:coil-compose",                             version.ref = "coil" }
coil-network-okhttp             = { module = "io.coil-kt.coil3:coil-network-okhttp",                      version.ref = "coil" }
```

**Step 3: Add the `kotlin-android` plugin alias**

```toml
[plugins]
# ...existing...
kotlin-android = { id = "org.jetbrains.kotlin.android", version.ref = "kotlin" }
```

**Step 4: Commit**

```bash
git add gradle/libs.versions.toml
git commit -m "chore: android UI version catalog entries"
```

---

### Task 3: Full `androidApp/build.gradle.kts`

**Files:**
- Modify: `androidApp/build.gradle.kts`

**Step 1: Replace the placeholder**

```kotlin
import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.serialization)
}

val localProperties = Properties().apply {
    val f = rootProject.file("local.properties")
    if (f.exists()) f.inputStream().use { load(it) }
}
val supabaseUrl     = (localProperties["SUPABASE_URL"] as? String).orEmpty()
val supabaseAnonKey = (localProperties["SUPABASE_ANON_KEY"] as? String).orEmpty()

android {
    namespace = "com.badmintontracker.android"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.badmintontracker.android"
        minSdk = 26
        targetSdk = 35
        versionCode = 1
        versionName = "0.1.0"

        buildConfigField("String", "SUPABASE_URL",      "\"$supabaseUrl\"")
        buildConfigField("String", "SUPABASE_ANON_KEY", "\"$supabaseAnonKey\"")
    }

    buildFeatures {
        compose     = true
        buildConfig = true
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }

    buildTypes {
        release {
            isMinifyEnabled = false
        }
    }
}

dependencies {
    implementation(project(":shared"))

    implementation(libs.androidx.activity.compose)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.compose.material3)
    debugImplementation(libs.androidx.compose.ui.tooling)

    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.navigation.compose)

    implementation(libs.androidx.media3.exoplayer)
    implementation(libs.androidx.media3.ui)
    implementation(libs.androidx.media3.common)

    implementation(libs.coil.compose)
    implementation(libs.coil.network.okhttp)

    // multiplatform-settings android target (transitive via :shared, but explicit for clarity)
    implementation(libs.settings)

    testImplementation(kotlin("test"))
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.turbine)
    testImplementation(libs.kotest.assertions)
}
```

**Step 2: Verify Gradle resolves the module**

Run: `./gradlew :androidApp:tasks` (no actual build, just configuration).
Expected: lists tasks like `assembleDebug`, `test`, etc. No errors.

**Step 3: Commit**

```bash
git add androidApp/build.gradle.kts
git commit -m "chore(androidApp): full gradle config with Compose, Nav, Media3, Coil"
```

---

## Phase 1 — App init + theme

### Task 4: Manifest, `Application`, MainActivity skeleton

**Files:**
- Create: `androidApp/src/main/AndroidManifest.xml`
- Create: `androidApp/src/main/java/com/badmintontracker/android/RallyAndroidApp.kt`
- Create: `androidApp/src/main/java/com/badmintontracker/android/MainActivity.kt`
- Create: `androidApp/src/main/res/values/strings.xml`

**Step 1: `strings.xml`**

```xml
<resources>
    <string name="app_name">Rally Clips</string>
</resources>
```

**Step 2: `AndroidManifest.xml`**

```xml
<?xml version="1.0" encoding="utf-8"?>
<manifest xmlns:android="http://schemas.android.com/apk/res/android">

    <uses-permission android:name="android.permission.INTERNET"/>

    <application
        android:name=".RallyAndroidApp"
        android:label="@string/app_name"
        android:allowBackup="true"
        android:supportsRtl="true">

        <activity
            android:name=".MainActivity"
            android:exported="true"
            android:theme="@android:style/Theme.Material.Light.NoActionBar">

            <intent-filter>
                <action android:name="android.intent.action.MAIN"/>
                <category android:name="android.intent.category.LAUNCHER"/>
            </intent-filter>

            <!-- Supabase OAuth callback. Wired in Task 16. -->
        </activity>
    </application>
</manifest>
```

**Step 3: `RallyAndroidApp.kt` (skeleton; real init in Task 6)**

```kotlin
package com.badmintontracker.android

import android.app.Application

class RallyAndroidApp : Application() {
    override fun onCreate() {
        super.onCreate()
    }
}
```

**Step 4: `MainActivity.kt` (renders "Hello Rally")**

```kotlin
package com.badmintontracker.android

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MaterialTheme {
                Surface(modifier = Modifier.fillMaxSize()) {
                    Text("Hello Rally")
                }
            }
        }
    }
}
```

**Step 5: Verify it assembles**

Run: `./gradlew :androidApp:assembleDebug`
Expected: BUILD SUCCESSFUL. APK appears at `androidApp/build/outputs/apk/debug/`.

**Step 6: Commit**

```bash
git add androidApp/src/main
git commit -m "feat(androidApp): manifest, application, mainactivity skeleton"
```

---

### Task 5: BuildConfig wiring + `local.properties.example`

**Files:**
- Create: `local.properties.example` (committed; the real `local.properties` is gitignored)

**Step 1: Write `local.properties.example`**

```
# Copy this file to `local.properties` and fill in your Supabase project values.
SUPABASE_URL=https://<your-project>.supabase.co
SUPABASE_ANON_KEY=<your-anon-key>
```

**Step 2: Verify BuildConfig fields are reachable**

Add a temporary log line inside `MainActivity.onCreate`:

```kotlin
android.util.Log.d("Rally", "url=${BuildConfig.SUPABASE_URL.take(20)}…")
```

Run: `./gradlew :androidApp:assembleDebug`
Expected: BUILD SUCCESSFUL. (We're not running it; just confirming the symbol resolves.)

Remove the log line before committing.

**Step 3: Commit**

```bash
git add local.properties.example
git commit -m "docs(androidApp): local.properties example with supabase fields"
```

---

### Task 6: Initialize `RallyApp` in `Application` + Material 3 theme

**Files:**
- Modify: `androidApp/src/main/java/com/badmintontracker/android/RallyAndroidApp.kt`
- Create: `androidApp/src/main/java/com/badmintontracker/android/ui/theme/Theme.kt`
- Create: `androidApp/src/main/java/com/badmintontracker/android/RallyAppHolder.kt`
- Modify: `androidApp/src/main/java/com/badmintontracker/android/MainActivity.kt`

**Step 1: Initialize `RallyApp` in `RallyAndroidApp`**

```kotlin
package com.badmintontracker.android

import android.app.Application
import com.badmintontracker.shared.RallyApp
import com.badmintontracker.shared.SupabaseConfig
import com.russhwolf.settings.SharedPreferencesSettings

class RallyAndroidApp : Application() {

    lateinit var rally: RallyApp
        private set

    override fun onCreate() {
        super.onCreate()
        rally = RallyApp(
            config   = SupabaseConfig(BuildConfig.SUPABASE_URL, BuildConfig.SUPABASE_ANON_KEY),
            settings = SharedPreferencesSettings(getSharedPreferences("rally", MODE_PRIVATE)),
        )
    }
}
```

> If `SharedPreferencesSettings(...)` constructor signature differs in `multiplatform-settings 1.2.0`, swap to the documented form (e.g. `SharedPreferencesSettings.Factory(this).create("rally")`). Adjust during execution.

**Step 2: `RallyAppHolder.kt` — composable accessor**

```kotlin
package com.badmintontracker.android

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import com.badmintontracker.shared.RallyApp

@Composable
fun rememberRallyApp(): RallyApp {
    val ctx = LocalContext.current.applicationContext
    return remember(ctx) { (ctx as RallyAndroidApp).rally }
}
```

**Step 3: Material 3 theme**

```kotlin
package com.badmintontracker.android.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable

@Composable
fun RallyTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit,
) {
    MaterialTheme(
        colorScheme = if (darkTheme) darkColorScheme() else lightColorScheme(),
        content     = content,
    )
}
```

**Step 4: Update `MainActivity.kt`**

```kotlin
package com.badmintontracker.android

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.ui.Modifier
import com.badmintontracker.android.ui.theme.RallyTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            RallyTheme {
                Surface(modifier = Modifier.fillMaxSize()) {
                    Text("Hello Rally")
                }
            }
        }
    }
}
```

**Step 5: Verify**

Run: `./gradlew :androidApp:assembleDebug`
Expected: BUILD SUCCESSFUL.

**Step 6: Commit**

```bash
git add androidApp/src/main
git commit -m "feat(androidApp): RallyApp init + Material 3 theme"
```

---

## Phase 2 — Navigation + AuthGate

### Task 7: `Route` sealed interface

**Files:**
- Create: `androidApp/src/main/java/com/badmintontracker/android/nav/Route.kt`

**Step 1: Write it**

```kotlin
package com.badmintontracker.android.nav

import kotlinx.serialization.Serializable

sealed interface Route {
    @Serializable data object SignIn   : Route
    @Serializable data object ClipList : Route
    @Serializable data class  ClipDetail(val clipId: String) : Route
}
```

**Step 2: Verify it compiles**

Run: `./gradlew :androidApp:compileDebugKotlin`
Expected: BUILD SUCCESSFUL.

**Step 3: Commit**

```bash
git add androidApp/src/main/java/com/badmintontracker/android/nav/Route.kt
git commit -m "feat(androidApp): type-safe nav routes"
```

---

### Task 8: `AuthGate` + `Splash` + skeleton `NavHost`

**Files:**
- Create: `androidApp/src/main/java/com/badmintontracker/android/AuthGate.kt`
- Modify: `androidApp/src/main/java/com/badmintontracker/android/MainActivity.kt`

**Step 1: `AuthGate.kt`**

```kotlin
package com.badmintontracker.android

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import com.badmintontracker.android.nav.Route
import com.badmintontracker.shared.RallyApp
import io.github.jan.supabase.auth.status.SessionStatus

@Composable
fun AuthGate(rally: RallyApp) {
    val session by rally.auth.sessionFlow.collectAsStateWithLifecycle(initialValue = null)

    when (val s = session) {
        null -> Splash()
        else -> {
            val nav = rememberNavController()
            val start: Route = if (s is SessionStatus.Authenticated) Route.ClipList else Route.SignIn

            // Watch for silent expiry: if we're authenticated -> not, pop back to SignIn.
            LaunchedEffect(s) {
                if (s is SessionStatus.NotAuthenticated &&
                    nav.currentDestination?.route?.contains("ClipList") == true) {
                    nav.navigate(Route.SignIn) {
                        popUpTo(Route.ClipList) { inclusive = true }
                    }
                }
            }

            NavHost(navController = nav, startDestination = start) {
                composable<Route.SignIn> {
                    Text("SignIn (placeholder — Task 11)")
                }
                composable<Route.ClipList> {
                    Text("ClipList (placeholder — Task 13)")
                }
                composable<Route.ClipDetail> { entry ->
                    val args = entry.toRoute<Route.ClipDetail>()
                    Text("ClipDetail (placeholder — Task 15) clipId=${args.clipId}")
                }
            }
        }
    }
}

@Composable
private fun Splash() {
    Column(
        modifier = Modifier.fillMaxSize(),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Text("Rally Clips")
        CircularProgressIndicator()
    }
}
```

> `entry.toRoute<Route.ClipDetail>()` is from `androidx.navigation:navigation-compose 2.8+`. Import: `import androidx.navigation.toRoute`.

**Step 2: Update `MainActivity.kt` to render `AuthGate`**

```kotlin
override fun onCreate(savedInstanceState: Bundle?) {
    super.onCreate(savedInstanceState)
    setContent {
        RallyTheme {
            Surface(modifier = Modifier.fillMaxSize()) {
                AuthGate(rally = (application as RallyAndroidApp).rally)
            }
        }
    }
}
```

**Step 3: Verify it assembles**

Run: `./gradlew :androidApp:assembleDebug`
Expected: BUILD SUCCESSFUL. Launching shows Splash briefly, then the SignIn placeholder text (no session yet).

**Step 4: Commit**

```bash
git add androidApp/src/main
git commit -m "feat(androidApp): AuthGate with Splash + NavHost skeleton"
```

---

## Phase 3 — Test infrastructure

### Task 9: Handwritten fakes for the four shared repositories

**Files:**
- Create: `androidApp/src/test/java/com/badmintontracker/android/testing/FakeAuthRepository.kt`
- Create: `androidApp/src/test/java/com/badmintontracker/android/testing/FakeClipsRepository.kt`
- Create: `androidApp/src/test/java/com/badmintontracker/android/testing/FakeAnnotationsRepository.kt`
- Create: `androidApp/src/test/java/com/badmintontracker/android/testing/FakeMediaRepository.kt`

**Step 1: `FakeAuthRepository`**

```kotlin
package com.badmintontracker.android.testing

import com.badmintontracker.shared.repo.AuthRepository
import io.github.jan.supabase.auth.status.SessionStatus
import kotlinx.coroutines.flow.MutableStateFlow

class FakeAuthRepository : AuthRepository {
    val session = MutableStateFlow<SessionStatus>(SessionStatus.NotAuthenticated(false))
    override val sessionFlow = session
    var nextEmailResult: Result<Unit> = Result.success(Unit)
    var nextGoogleResult: Result<Unit> = Result.success(Unit)
    val emailCalls = mutableListOf<Pair<String, String>>()
    val googleCalls = mutableListOf<Unit>()
    val signOutCalls = mutableListOf<Unit>()

    override suspend fun signInEmail(email: String, password: String): Result<Unit> {
        emailCalls += email to password
        return nextEmailResult
    }
    override suspend fun signInWithGoogle(): Result<Unit> {
        googleCalls += Unit
        return nextGoogleResult
    }
    override suspend fun signOut(): Result<Unit> {
        signOutCalls += Unit
        return Result.success(Unit)
    }
}
```

> `SessionStatus.NotAuthenticated(false)` constructor signature may differ in supabase-kt 3.5.0 (the boolean is `isSignOutFlow` or similar). Adjust during execution if needed.

**Step 2: `FakeClipsRepository`**

```kotlin
package com.badmintontracker.android.testing

import com.badmintontracker.shared.model.RallyClip
import com.badmintontracker.shared.repo.ClipsRepository
import kotlinx.coroutines.flow.MutableStateFlow

class FakeClipsRepository : ClipsRepository {
    val clips = MutableStateFlow<List<RallyClip>>(emptyList())
    var refreshError: Throwable? = null
    val refreshCalls = mutableListOf<Unit>()

    override suspend fun listClips(): List<RallyClip> = clips.value
    override fun observeClips() = clips
    override suspend fun refresh() {
        refreshCalls += Unit
        refreshError?.let { throw it }
    }
    override suspend fun updateTitle(clipId: String, title: String?) = Result.success(Unit)
}
```

**Step 3: `FakeAnnotationsRepository`**

```kotlin
package com.badmintontracker.android.testing

import com.badmintontracker.shared.model.RallyAnnotation
import com.badmintontracker.shared.repo.AnnotationsRepository

class FakeAnnotationsRepository : AnnotationsRepository {
    var byClipId: Map<String, List<RallyAnnotation>> = emptyMap()
    var listError: Throwable? = null

    override suspend fun list(clipId: String): List<RallyAnnotation> {
        listError?.let { throw it }
        return byClipId[clipId] ?: emptyList()
    }
    override suspend fun add(clipId: String, timestampSeconds: Float, body: String) =
        Result.failure<RallyAnnotation>(NotImplementedError())
    override suspend fun delete(id: String) = Result.success(Unit)
}
```

**Step 4: `FakeMediaRepository`**

```kotlin
package com.badmintontracker.android.testing

import com.badmintontracker.shared.model.RallyClip
import com.badmintontracker.shared.repo.MediaRepository

class FakeMediaRepository : MediaRepository {
    var nextClipUrl: () -> String = { "https://signed/${'$'}{System.nanoTime()}.mp4" }
    var nextClipUrlError: Throwable? = null
    var nextThumbUrl: String? = "https://signed/thumb.jpg"
    val clipUrlCalls = mutableListOf<RallyClip>()

    override suspend fun signedClipUrl(clip: RallyClip): String {
        clipUrlCalls += clip
        nextClipUrlError?.let { throw it }
        return nextClipUrl()
    }
    override suspend fun signedThumbnailUrl(clip: RallyClip): String? = nextThumbUrl
}
```

**Step 5: Verify it compiles**

Run: `./gradlew :androidApp:compileDebugUnitTestKotlin`
Expected: BUILD SUCCESSFUL.

**Step 6: Commit**

```bash
git add androidApp/src/test
git commit -m "test(androidApp): handwritten fake repositories"
```

---

## Phase 4 — Sign-in screen

### Task 10: `SignInViewModel` (TDD)

**Files:**
- Create: `androidApp/src/test/java/com/badmintontracker/android/signin/SignInViewModelTest.kt`
- Create: `androidApp/src/main/java/com/badmintontracker/android/signin/SignInViewModel.kt`

**Step 1: Write the failing tests**

```kotlin
package com.badmintontracker.android.signin

import app.cash.turbine.test
import com.badmintontracker.android.testing.FakeAuthRepository
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test

class SignInViewModelTest {
    private val dispatcher = StandardTestDispatcher()

    @BeforeTest fun setMain() = Dispatchers.setMain(dispatcher)
    @AfterTest  fun resetMain() = Dispatchers.resetMain()

    @Test
    fun email_change_updates_state_and_clears_error() = runTest {
        val auth = FakeAuthRepository()
        val vm = SignInViewModel(auth)

        vm.onEmailChange("a@b.co")

        vm.state.value.email shouldBe "a@b.co"
        vm.state.value.error shouldBe null
    }

    @Test
    fun submitEmail_success_emits_SignedIn_event() = runTest {
        val auth = FakeAuthRepository().apply { nextEmailResult = Result.success(Unit) }
        val vm = SignInViewModel(auth)
        vm.onEmailChange("a@b.co"); vm.onPasswordChange("secret")

        vm.events.test {
            vm.submitEmail()
            awaitItem() shouldBe SignInEvent.SignedIn
            cancelAndIgnoreRemainingEvents()
        }
        auth.emailCalls shouldBe listOf("a@b.co" to "secret")
        vm.state.value.isSubmitting shouldBe false
    }

    @Test
    fun submitEmail_failure_surfaces_error_and_no_event() = runTest {
        val auth = FakeAuthRepository().apply {
            nextEmailResult = Result.failure(IllegalStateException("bad creds"))
        }
        val vm = SignInViewModel(auth)
        vm.onEmailChange("a@b.co"); vm.onPasswordChange("secret")

        vm.submitEmail()
        dispatcher.scheduler.advanceUntilIdle()

        vm.state.value.error shouldBe "bad creds"
        vm.state.value.isSubmitting shouldBe false
    }
}
```

**Step 2: Run, fail**

Run: `./gradlew :androidApp:test --tests "*SignInViewModelTest*"`
Expected: FAIL — `SignInViewModel` unresolved.

**Step 3: Implement**

```kotlin
package com.badmintontracker.android.signin

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.badmintontracker.shared.repo.AuthRepository
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class SignInState(
    val email: String = "",
    val password: String = "",
    val isSubmitting: Boolean = false,
    val error: String? = null,
)

sealed interface SignInEvent {
    data object SignedIn : SignInEvent
}

class SignInViewModel(private val auth: AuthRepository) : ViewModel() {
    val state  = MutableStateFlow(SignInState())
    val events = MutableSharedFlow<SignInEvent>(extraBufferCapacity = 1)

    fun onEmailChange(v: String)    { state.update { it.copy(email = v, error = null) } }
    fun onPasswordChange(v: String) { state.update { it.copy(password = v, error = null) } }

    fun submitEmail() {
        viewModelScope.launch {
            state.update { it.copy(isSubmitting = true, error = null) }
            auth.signInEmail(state.value.email.trim(), state.value.password)
                .onSuccess {
                    events.tryEmit(SignInEvent.SignedIn)
                    state.update { it.copy(isSubmitting = false) }
                }
                .onFailure { e ->
                    state.update { it.copy(isSubmitting = false, error = e.message) }
                }
        }
    }

    fun submitGoogle() {
        viewModelScope.launch {
            state.update { it.copy(isSubmitting = true, error = null) }
            auth.signInWithGoogle()
                .onFailure { e -> state.update { it.copy(isSubmitting = false, error = e.message) } }
            // success arrives via deeplink → sessionFlow.
        }
    }
}
```

**Step 4: Run, pass**

Run: `./gradlew :androidApp:test --tests "*SignInViewModelTest*"`
Expected: 3 tests PASS.

**Step 5: Commit**

```bash
git add androidApp/src/main/java/com/badmintontracker/android/signin \
        androidApp/src/test/java/com/badmintontracker/android/signin
git commit -m "feat(androidApp): SignInViewModel + tests"
```

---

### Task 11: `SignInScreen` + wire into `NavHost`

**Files:**
- Create: `androidApp/src/main/java/com/badmintontracker/android/signin/SignInScreen.kt`
- Modify: `androidApp/src/main/java/com/badmintontracker/android/AuthGate.kt`

**Step 1: `SignInScreen`**

```kotlin
package com.badmintontracker.android.signin

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle

@Composable
fun SignInScreen(
    vm: SignInViewModel,
    onSignedIn: () -> Unit,
) {
    val state by vm.state.collectAsStateWithLifecycle()

    LaunchedEffect(Unit) {
        vm.events.collect { if (it is SignInEvent.SignedIn) onSignedIn() }
    }

    Column(
        modifier = Modifier.fillMaxSize().padding(24.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text("Rally Clips", style = MaterialTheme.typography.headlineLarge)
        Spacer(Modifier.height(32.dp))

        OutlinedTextField(
            value = state.email,
            onValueChange = vm::onEmailChange,
            label = { Text("Email") },
            singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email),
            modifier = Modifier.fillMaxWidth(),
        )
        Spacer(Modifier.height(12.dp))
        OutlinedTextField(
            value = state.password,
            onValueChange = vm::onPasswordChange,
            label = { Text("Password") },
            singleLine = true,
            visualTransformation = PasswordVisualTransformation(),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
            modifier = Modifier.fillMaxWidth(),
        )
        Spacer(Modifier.height(20.dp))

        Button(
            onClick = vm::submitEmail,
            enabled = !state.isSubmitting,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text(if (state.isSubmitting) "Signing in…" else "Sign in")
        }
        Spacer(Modifier.height(8.dp))
        OutlinedButton(
            onClick = vm::submitGoogle,
            enabled = !state.isSubmitting,
            modifier = Modifier.fillMaxWidth(),
        ) { Text("Continue with Google") }

        if (state.error != null) {
            Spacer(Modifier.height(16.dp))
            Text(state.error!!, color = MaterialTheme.colorScheme.error)
        }
    }
}
```

**Step 2: Wire into `AuthGate`**

Replace the placeholder `Text("SignIn …")` inside `composable<Route.SignIn>`:

```kotlin
composable<Route.SignIn> {
    val signInVm: SignInViewModel = androidx.lifecycle.viewmodel.compose.viewModel(
        factory = androidx.lifecycle.viewmodel.viewModelFactory {
            androidx.lifecycle.viewmodel.initializer { SignInViewModel(rally.auth) }
        }
    )
    SignInScreen(
        vm = signInVm,
        onSignedIn = {
            nav.navigate(Route.ClipList) {
                popUpTo(Route.SignIn) { inclusive = true }
            }
        },
    )
}
```

> Move the `viewModel(factory = ...)` boilerplate into a small `inline fun <reified VM> rememberVm(crossinline create: () -> VM)` helper if you create more than two of them — but YAGNI for now.

**Step 3: Verify**

Run: `./gradlew :androidApp:assembleDebug`
Expected: BUILD SUCCESSFUL. Launching the app shows the sign-in form.

**Step 4: Commit**

```bash
git add androidApp/src/main
git commit -m "feat(androidApp): SignInScreen wired into NavHost"
```

---

## Phase 5 — Clip list screen

### Task 12: `ClipListViewModel` (TDD)

**Files:**
- Create: `androidApp/src/test/java/com/badmintontracker/android/cliplist/ClipListViewModelTest.kt`
- Create: `androidApp/src/main/java/com/badmintontracker/android/cliplist/ClipListViewModel.kt`

**Step 1: Failing tests**

```kotlin
package com.badmintontracker.android.cliplist

import app.cash.turbine.test
import com.badmintontracker.android.testing.FakeAuthRepository
import com.badmintontracker.android.testing.FakeClipsRepository
import com.badmintontracker.shared.model.RallyClip
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlinx.datetime.Instant
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test

class ClipListViewModelTest {
    private val dispatcher = StandardTestDispatcher()

    @BeforeTest fun setMain() = Dispatchers.setMain(dispatcher)
    @AfterTest  fun resetMain() = Dispatchers.resetMain()

    private fun clip(id: String) = RallyClip(
        id = id, videoId = "v", rallyIndex = 0,
        startTimestamp = 0f, endTimestamp = 1f, durationSeconds = 1f,
        clipStoragePath = "p/$id.mp4", thumbnailStoragePath = null,
        title = null, annotationCount = 0,
        createdAt = Instant.parse("2026-05-04T12:00:00Z"),
    )

    @Test
    fun init_triggers_refresh() = runTest {
        val clips = FakeClipsRepository()
        ClipListViewModel(clips, FakeAuthRepository())
        advanceUntilIdle()
        clips.refreshCalls shouldHaveSize 1
    }

    @Test
    fun state_reflects_observed_clips() = runTest {
        val clips = FakeClipsRepository().apply { this.clips.value = listOf(clip("a"), clip("b")) }
        val vm = ClipListViewModel(clips, FakeAuthRepository())
        vm.state.test {
            // skipFirst because StateFlow emits the initial empty value before the combine wires up
            var s = awaitItem()
            while (s.clips.isEmpty()) s = awaitItem()
            s.clips.map { it.id } shouldBe listOf("a", "b")
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun refresh_failure_surfaces_in_error() = runTest {
        val clips = FakeClipsRepository().apply { refreshError = IllegalStateException("net down") }
        val vm = ClipListViewModel(clips, FakeAuthRepository())
        advanceUntilIdle()
        vm.state.value.error shouldBe "net down"
    }
}
```

**Step 2: Run, fail**

Run: `./gradlew :androidApp:test --tests "*ClipListViewModelTest*"`
Expected: FAIL — unresolved `ClipListViewModel`.

**Step 3: Implement**

```kotlin
package com.badmintontracker.android.cliplist

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.badmintontracker.shared.model.RallyClip
import com.badmintontracker.shared.repo.AuthRepository
import com.badmintontracker.shared.repo.ClipsRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

data class ClipListState(
    val clips: List<RallyClip> = emptyList(),
    val isRefreshing: Boolean = false,
    val error: String? = null,
)

class ClipListViewModel(
    private val clips: ClipsRepository,
    private val auth: AuthRepository,
) : ViewModel() {
    private val refreshing = MutableStateFlow(false)
    private val errors     = MutableStateFlow<String?>(null)

    val state = combine(clips.observeClips(), refreshing, errors) { list, r, e ->
        ClipListState(list, r, e)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), ClipListState())

    init { refresh() }

    fun refresh() {
        viewModelScope.launch {
            refreshing.value = true
            runCatching { clips.refresh() }.onFailure { errors.value = it.message }
            refreshing.value = false
        }
    }

    fun signOut() = viewModelScope.launch { auth.signOut() }
    fun dismissError() { errors.value = null }
}
```

**Step 4: Run, pass**

Run: `./gradlew :androidApp:test --tests "*ClipListViewModelTest*"`
Expected: 3 tests PASS.

**Step 5: Commit**

```bash
git add androidApp/src/main/java/com/badmintontracker/android/cliplist \
        androidApp/src/test/java/com/badmintontracker/android/cliplist
git commit -m "feat(androidApp): ClipListViewModel + tests"
```

---

### Task 13: `ClipListScreen` + wire into NavHost

**Files:**
- Create: `androidApp/src/main/java/com/badmintontracker/android/cliplist/ClipListScreen.kt`
- Modify: `androidApp/src/main/java/com/badmintontracker/android/AuthGate.kt`

**Step 1: `ClipListScreen.kt`**

```kotlin
package com.badmintontracker.android.cliplist

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.*
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.*
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil3.compose.AsyncImage
import com.badmintontracker.shared.model.RallyClip
import com.badmintontracker.shared.repo.MediaRepository

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ClipListScreen(
    vm: ClipListViewModel,
    media: MediaRepository,
    onClipClick: (RallyClip) -> Unit,
) {
    val state by vm.state.collectAsStateWithLifecycle()
    val snackbarHostState = remember { SnackbarHostState() }

    LaunchedEffect(state.error) {
        val err = state.error ?: return@LaunchedEffect
        snackbarHostState.showSnackbar(err)
        vm.dismissError()
    }

    var menuOpen by remember { mutableStateOf(false) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Clips") },
                actions = {
                    IconButton(onClick = { menuOpen = true }) {
                        Icon(Icons.Default.MoreVert, contentDescription = "Menu")
                    }
                    DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                        DropdownMenuItem(
                            text = { Text("Sign out") },
                            onClick = { menuOpen = false; vm.signOut() },
                        )
                    }
                },
            )
        },
        snackbarHost = { SnackbarHost(snackbarHostState) },
    ) { padding ->
        PullToRefreshBox(
            isRefreshing = state.isRefreshing,
            onRefresh = vm::refresh,
            modifier = Modifier.padding(padding).fillMaxSize(),
        ) {
            if (state.clips.isEmpty() && !state.isRefreshing) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text("No clips yet. Record one in the desktop app.")
                }
            } else {
                LazyColumn(modifier = Modifier.fillMaxSize()) {
                    items(state.clips, key = { it.id }) { clip ->
                        ClipRow(clip, media, onClick = { onClipClick(clip) })
                        HorizontalDivider()
                    }
                }
            }
        }
    }
}

@Composable
private fun ClipRow(
    clip: RallyClip,
    media: MediaRepository,
    onClick: () -> Unit,
) {
    val thumbUrl by produceState<String?>(initialValue = null, clip.id) {
        value = runCatching { media.signedThumbnailUrl(clip) }.getOrNull()
    }

    Row(
        modifier = Modifier.fillMaxWidth().clickable(onClick = onClick).padding(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        AsyncImage(
            model = thumbUrl,
            contentDescription = null,
            modifier = Modifier.size(96.dp, 54.dp),
        )
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(
                clip.title ?: "Rally #${clip.rallyIndex + 1}",
                style = MaterialTheme.typography.titleMedium,
            )
            Text(
                "${clip.durationSeconds}s · ${clip.annotationCount} notes",
                style = MaterialTheme.typography.bodySmall,
            )
        }
    }
}
```

> If `PullToRefreshBox` import differs in your Compose Material 3 version, swap to whatever the current `pulltorefresh` package exposes. Annotation `@OptIn(ExperimentalMaterial3Api::class)` is required because TopAppBar and pull-to-refresh remain experimental in Material 3 1.3.x.

**Step 2: Wire into `AuthGate`**

Replace the placeholder `composable<Route.ClipList>`:

```kotlin
composable<Route.ClipList> {
    val vm: ClipListViewModel = androidx.lifecycle.viewmodel.compose.viewModel(
        factory = androidx.lifecycle.viewmodel.viewModelFactory {
            androidx.lifecycle.viewmodel.initializer { ClipListViewModel(rally.clips, rally.auth) }
        }
    )
    ClipListScreen(
        vm = vm,
        media = rally.media,
        onClipClick = { nav.navigate(Route.ClipDetail(it.id)) },
    )
}
```

**Step 3: Verify**

Run: `./gradlew :androidApp:assembleDebug`
Expected: BUILD SUCCESSFUL.

**Step 4: Commit**

```bash
git add androidApp/src/main
git commit -m "feat(androidApp): ClipListScreen with pull-to-refresh and Coil thumbnails"
```

---

## Phase 6 — Clip detail + player

### Task 14: `ClipDetailViewModel` (TDD)

**Files:**
- Create: `androidApp/src/test/java/com/badmintontracker/android/clipdetail/ClipDetailViewModelTest.kt`
- Create: `androidApp/src/main/java/com/badmintontracker/android/clipdetail/ClipDetailViewModel.kt`

**Step 1: Failing tests**

```kotlin
package com.badmintontracker.android.clipdetail

import app.cash.turbine.test
import com.badmintontracker.android.testing.FakeAnnotationsRepository
import com.badmintontracker.android.testing.FakeClipsRepository
import com.badmintontracker.android.testing.FakeMediaRepository
import com.badmintontracker.shared.model.RallyAnnotation
import com.badmintontracker.shared.model.RallyClip
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.*
import kotlinx.datetime.Instant
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test

class ClipDetailViewModelTest {
    private val dispatcher = StandardTestDispatcher()

    @BeforeTest fun setMain() = Dispatchers.setMain(dispatcher)
    @AfterTest  fun resetMain() = Dispatchers.resetMain()

    private val sampleClip = RallyClip(
        id = "c1", videoId = "v", rallyIndex = 0,
        startTimestamp = 0f, endTimestamp = 1f, durationSeconds = 1f,
        clipStoragePath = "p/c1.mp4", thumbnailStoragePath = null,
        title = null, annotationCount = 1,
        createdAt = Instant.parse("2026-05-04T12:00:00Z"),
    )

    private fun setup(
        clipsList: List<RallyClip> = listOf(sampleClip),
        annotations: List<RallyAnnotation> = emptyList(),
        media: FakeMediaRepository = FakeMediaRepository(),
    ): Triple<ClipDetailViewModel, FakeMediaRepository, FakeClipsRepository> {
        val clips = FakeClipsRepository().apply { this.clips.value = clipsList }
        val ann = FakeAnnotationsRepository().apply {
            byClipId = mapOf("c1" to annotations)
        }
        val vm = ClipDetailViewModel("c1", clips, ann, media)
        return Triple(vm, media, clips)
    }

    @Test
    fun init_loads_clip_annotations_and_signs_url() = runTest {
        val (vm, media, _) = setup(
            annotations = listOf(
                RallyAnnotation("a1", "c1", 1.5f, "great", Instant.parse("2026-05-04T12:00:00Z"))
            ),
            media = FakeMediaRepository().apply { nextClipUrl = { "https://signed/c1?token=1" } },
        )
        advanceUntilIdle()

        val s = vm.state.value
        s.clip?.id shouldBe "c1"
        s.annotations.map { it.id } shouldBe listOf("a1")
        s.signedClipUrl shouldContain "token=1"
        media.clipUrlCalls.size shouldBe 1
    }

    @Test
    fun onAnnotationTap_emits_seek_in_ms() = runTest {
        val (vm, _, _) = setup()
        advanceUntilIdle()

        vm.seekTo.test {
            vm.onAnnotationTap(RallyAnnotation("a", "c1", 4.2f, "x", Instant.parse("2026-05-04T12:00:00Z")))
            awaitItem() shouldBe 4200L
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun first_player_error_resigns_url() = runTest {
        var i = 0
        val media = FakeMediaRepository().apply { nextClipUrl = { "url-${++i}" } }
        val (vm, _, _) = setup(media = media)
        advanceUntilIdle()
        val firstUrl = vm.state.value.signedClipUrl

        vm.onPlayerError()
        advanceUntilIdle()

        vm.state.value.signedClipUrl shouldBe "url-2"
        firstUrl shouldBe "url-1"
        vm.state.value.error shouldBe null
    }

    @Test
    fun second_player_error_surfaces_user_facing_error() = runTest {
        val (vm, _, _) = setup()
        advanceUntilIdle()

        vm.onPlayerError(); advanceUntilIdle()
        vm.onPlayerError(); advanceUntilIdle()

        vm.state.value.error shouldBe "Couldn't load video"
    }

    @Test
    fun onManualRetry_resets_attempts_and_resigns() = runTest {
        val (vm, media, _) = setup()
        advanceUntilIdle()
        vm.onPlayerError(); advanceUntilIdle()
        vm.onPlayerError(); advanceUntilIdle()
        val callsBefore = media.clipUrlCalls.size

        vm.onManualRetry(); advanceUntilIdle()

        vm.state.value.error shouldBe null
        media.clipUrlCalls.size shouldBe callsBefore + 1
    }
}
```

**Step 2: Run, fail**

Run: `./gradlew :androidApp:test --tests "*ClipDetailViewModelTest*"`
Expected: FAIL — unresolved `ClipDetailViewModel`.

**Step 3: Implement**

```kotlin
package com.badmintontracker.android.clipdetail

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.badmintontracker.shared.model.RallyAnnotation
import com.badmintontracker.shared.model.RallyClip
import com.badmintontracker.shared.repo.AnnotationsRepository
import com.badmintontracker.shared.repo.ClipsRepository
import com.badmintontracker.shared.repo.MediaRepository
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class ClipDetailState(
    val clip: RallyClip? = null,
    val annotations: List<RallyAnnotation> = emptyList(),
    val signedClipUrl: String? = null,
    val error: String? = null,
)

class ClipDetailViewModel(
    private val clipId: String,
    private val clips: ClipsRepository,
    private val annotations: AnnotationsRepository,
    private val media: MediaRepository,
) : ViewModel() {

    val state  = MutableStateFlow(ClipDetailState())
    val seekTo = MutableSharedFlow<Long>(extraBufferCapacity = 1)

    private var resignAttempts = 0

    init { load() }

    private fun load() {
        viewModelScope.launch {
            val cached = clips.observeClips().first()
            val clip = cached.firstOrNull { it.id == clipId } ?: run {
                runCatching { clips.refresh() }
                clips.observeClips().first().firstOrNull { it.id == clipId }
            } ?: run {
                state.update { it.copy(error = "Clip not found") }
                return@launch
            }

            val ann = runCatching { annotations.list(clipId) }.getOrElse {
                state.update { it.copy(error = it.message ?: "Couldn't load annotations") }
                emptyList()
            }
            val url = runCatching { media.signedClipUrl(clip) }.getOrElse {
                state.update { it.copy(error = it.message ?: "Couldn't sign clip URL") }
                null
            }
            state.update { it.copy(clip = clip, annotations = ann, signedClipUrl = url) }
        }
    }

    fun onAnnotationTap(a: RallyAnnotation) {
        seekTo.tryEmit((a.timestampSeconds * 1000).toLong())
    }

    fun onPlayerError() {
        viewModelScope.launch {
            if (resignAttempts >= 1) {
                state.update { it.copy(error = "Couldn't load video") }
                return@launch
            }
            resignAttempts++
            val clip = state.value.clip ?: return@launch
            runCatching { media.signedClipUrl(clip) }
                .onSuccess { url -> state.update { it.copy(signedClipUrl = url, error = null) } }
                .onFailure { e -> state.update { it.copy(error = e.message ?: "Couldn't load video") } }
        }
    }

    fun onManualRetry() {
        resignAttempts = 0
        viewModelScope.launch {
            val clip = state.value.clip ?: return@launch
            runCatching { media.signedClipUrl(clip) }
                .onSuccess { url -> state.update { it.copy(signedClipUrl = url, error = null) } }
                .onFailure { e -> state.update { it.copy(error = e.message ?: "Couldn't load video") } }
        }
    }
}
```

**Step 4: Run, pass**

Run: `./gradlew :androidApp:test --tests "*ClipDetailViewModelTest*"`
Expected: 5 tests PASS.

**Step 5: Commit**

```bash
git add androidApp/src/main/java/com/badmintontracker/android/clipdetail \
        androidApp/src/test/java/com/badmintontracker/android/clipdetail
git commit -m "feat(androidApp): ClipDetailViewModel + tests"
```

---

### Task 15: `ClipDetailScreen` with ExoPlayer + wire into NavHost

**Files:**
- Create: `androidApp/src/main/java/com/badmintontracker/android/clipdetail/ClipDetailScreen.kt`
- Modify: `androidApp/src/main/java/com/badmintontracker/android/AuthGate.kt`

**Step 1: `ClipDetailScreen.kt`**

```kotlin
package com.badmintontracker.android.clipdetail

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.PlayerView
import com.badmintontracker.shared.model.RallyAnnotation

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ClipDetailScreen(
    vm: ClipDetailViewModel,
    onBack: () -> Unit,
) {
    val state by vm.state.collectAsStateWithLifecycle()
    val ctx = LocalContext.current
    val player = remember { ExoPlayer.Builder(ctx).build() }

    DisposableEffect(player) {
        val listener = object : Player.Listener {
            override fun onPlayerError(error: PlaybackException) { vm.onPlayerError() }
        }
        player.addListener(listener)
        onDispose {
            player.removeListener(listener)
            player.release()
        }
    }

    LaunchedEffect(state.signedClipUrl) {
        val url = state.signedClipUrl ?: return@LaunchedEffect
        player.setMediaItem(MediaItem.fromUri(url))
        player.prepare()
        player.playWhenReady = true
    }

    LaunchedEffect(Unit) {
        vm.seekTo.collect { ms -> player.seekTo(ms) }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(state.clip?.title ?: state.clip?.let { "Rally #${it.rallyIndex + 1}" } ?: "") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
            )
        },
    ) { padding ->
        Column(modifier = Modifier.padding(padding).fillMaxSize()) {
            Box(modifier = Modifier.fillMaxWidth().aspectRatio(16f / 9f)) {
                AndroidView(
                    factory = { PlayerView(ctx).apply { this.player = player } },
                    modifier = Modifier.fillMaxSize(),
                )
                if (state.error != null) {
                    Surface(
                        modifier = Modifier.fillMaxSize(),
                        color = MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.9f),
                    ) {
                        Column(
                            modifier = Modifier.fillMaxSize(),
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.Center,
                        ) {
                            Text(state.error!!, color = MaterialTheme.colorScheme.onErrorContainer)
                            Spacer(Modifier.height(8.dp))
                            Button(onClick = vm::onManualRetry) { Text("Retry") }
                        }
                    }
                }
            }

            if (state.annotations.isEmpty()) {
                Box(Modifier.fillMaxSize().padding(24.dp), contentAlignment = Alignment.Center) {
                    Text("No annotations on this clip.")
                }
            } else {
                LazyColumn(modifier = Modifier.fillMaxSize()) {
                    items(state.annotations, key = { it.id }) { a ->
                        AnnotationRow(a, onClick = { vm.onAnnotationTap(a) })
                        HorizontalDivider()
                    }
                }
            }
        }
    }
}

@Composable
private fun AnnotationRow(a: RallyAnnotation, onClick: () -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth().clickable(onClick = onClick).padding(16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text("%.1fs".format(a.timestampSeconds), style = MaterialTheme.typography.labelLarge)
        Spacer(Modifier.width(12.dp))
        Text(a.body, style = MaterialTheme.typography.bodyMedium)
    }
}
```

**Step 2: Wire into `AuthGate`**

Replace the placeholder `composable<Route.ClipDetail>`:

```kotlin
composable<Route.ClipDetail> { entry ->
    val args = entry.toRoute<Route.ClipDetail>()
    val vm: ClipDetailViewModel = androidx.lifecycle.viewmodel.compose.viewModel(
        factory = androidx.lifecycle.viewmodel.viewModelFactory {
            androidx.lifecycle.viewmodel.initializer {
                ClipDetailViewModel(args.clipId, rally.clips, rally.annotations, rally.media)
            }
        }
    )
    ClipDetailScreen(vm = vm, onBack = { nav.popBackStack() })
}
```

**Step 3: Verify**

Run: `./gradlew :androidApp:assembleDebug`
Expected: BUILD SUCCESSFUL.

**Step 4: Commit**

```bash
git add androidApp/src/main
git commit -m "feat(androidApp): ClipDetailScreen with ExoPlayer + retry overlay"
```

---

## Phase 7 — Polish & CI

### Task 16: Supabase OAuth deep-link intent-filter

**Files:**
- Modify: `androidApp/src/main/AndroidManifest.xml`

**Step 1: Add the intent-filter inside the existing `<activity android:name=".MainActivity">`**

```xml
<intent-filter android:autoVerify="false">
    <action   android:name="android.intent.action.VIEW"/>
    <category android:name="android.intent.category.DEFAULT"/>
    <category android:name="android.intent.category.BROWSABLE"/>
    <data android:scheme="badmintontracker" android:host="login"/>
</intent-filter>
```

**Step 2: Verify it assembles**

Run: `./gradlew :androidApp:assembleDebug`
Expected: BUILD SUCCESSFUL.

> Manual verification (not part of TDD): `adb shell am start -W -a android.intent.action.VIEW -d "badmintontracker://login?code=test" com.badmintontracker.android` should bring the app to the foreground. The session won't actually update without a real OAuth flow — this just verifies the manifest filter is wired.

**Step 3: Commit**

```bash
git add androidApp/src/main/AndroidManifest.xml
git commit -m "feat(androidApp): supabase oauth deep-link intent-filter"
```

---

### Task 17: CI — add Android jobs

**Files:**
- Modify: `.github/workflows/ci.yml`

**Step 1: Add two jobs after the existing `shared-tests` and `ios-framework` jobs**

```yaml
  android-tests:
    runs-on: ubuntu-latest
    steps:
      - uses: actions/checkout@v4
      - uses: actions/setup-java@v4
        with:
          distribution: temurin
          java-version: 17
      - uses: gradle/actions/setup-gradle@v4
      - name: Write local.properties
        run: |
          echo "SUPABASE_URL=${{ secrets.SUPABASE_URL }}"           >  local.properties
          echo "SUPABASE_ANON_KEY=${{ secrets.SUPABASE_ANON_KEY }}" >> local.properties
      - run: ./gradlew :androidApp:test

  android-assemble:
    runs-on: ubuntu-latest
    steps:
      - uses: actions/checkout@v4
      - uses: actions/setup-java@v4
        with:
          distribution: temurin
          java-version: 17
      - uses: gradle/actions/setup-gradle@v4
      - name: Write local.properties
        run: |
          echo "SUPABASE_URL=${{ secrets.SUPABASE_URL }}"           >  local.properties
          echo "SUPABASE_ANON_KEY=${{ secrets.SUPABASE_ANON_KEY }}" >> local.properties
      - run: ./gradlew :androidApp:assembleDebug
```

> The `local.properties` write step is only needed if you set `SUPABASE_URL` / `SUPABASE_ANON_KEY` as repository secrets. If you keep the values empty in CI (the build still compiles, just runs against an unreachable URL), you can omit those steps.

**Step 2: Commit + push**

```bash
git add .github/workflows/ci.yml
git commit -m "ci: android unit tests + assembleDebug"
git push
```

**Step 3: Verify CI passes** at https://github.com/coenhallie/badminton-rally-mobile/actions.

---

## Verification checklist (pre-handoff)

- `./gradlew :androidApp:test` — all green (~11 ViewModel tests across the three VMs)
- `./gradlew :androidApp:assembleDebug` — clean APK
- `./gradlew :shared:jvmTest` — still 14/14 green (sanity)
- App launches on a phone/emulator with API 26+
- Sign-in with email succeeds; sign-in with Google opens browser, returns to app via deeplink, lands on clip list
- Pull-to-refresh on clip list works; tapping a clip opens detail; player loads and plays; tapping an annotation seeks
- Sign out from clip list returns to sign-in
- CI green on all four jobs (`shared-tests`, `ios-framework`, `android-tests`, `android-assemble`)

## Out of scope for this plan (follow-ups)

1. **iOS UI plan** — SwiftUI sign-in, clip list, AVPlayer-based clip detail, annotations.
2. **Polish plan** — README + screenshots, release signing config + Play Store metadata, Apple sign-in, optional Realtime on `rally_annotations`, search/filter, edit clip title, full annotation CRUD on mobile, Compose UI tests for golden paths.

## Open decisions to lock during execution

- **Compose BOM version drift.** The 2025.01.00 BOM may not match your installed Kotlin/AGP exactly. If it doesn't compose-compile, bump and document — same playbook as the foundation's supabase-kt drift.
- **Material 3 pull-to-refresh API surface.** The `PullToRefreshBox` from `androidx.compose.material3.pulltorefresh` graduated from experimental somewhere around 1.3.x; if your version still requires `@OptIn`, leave the annotation in place.
- **`SharedPreferencesSettings` constructor.** multiplatform-settings 1.2.0 exposes `SharedPreferencesSettings(prefs: SharedPreferences)` — if a major bump changes that, switch to `SharedPreferencesSettings.Factory(context).create("rally")`.
