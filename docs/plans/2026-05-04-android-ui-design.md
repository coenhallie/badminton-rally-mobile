# Android UI — Design

**Date:** 2026-05-04
**Status:** approved
**Builds on:** the foundation `:shared` module (auth, clips, annotations, media repos + `RallyApp` DI holder).

## Goal

Ship the first Android client for Rally Clips: sign in, browse clips, watch a clip with its annotations as a list. Read-only consumption only — no add/edit/delete from the mobile UI in this plan.

## Scope decisions

| Topic | Decision |
| --- | --- |
| Sign-in providers | Email + Google |
| Annotations UX | Read-only list (tap row to seek). No timeline track widget, no marker overlay |
| Dependency injection | Manual — `Application` holds `RallyApp`; ViewModels constructed via `viewModelFactory { initializer { ... } }` |
| Navigation | Jetpack Navigation Compose 2.8+ with type-safe `@Serializable` routes |
| Test coverage | ViewModel unit tests only (JVM, fake repositories, turbine). No Compose UI tests |
| Secrets | `local.properties` → `BuildConfig.SUPABASE_URL` / `SUPABASE_ANON_KEY` |
| `:androidApp` | Re-added to `settings.gradle.kts` (it was removed during the foundation plan) |

Out of scope: add/edit/delete annotations, edit clip title, search/filter, offline persistence beyond in-memory `StateFlow`, UI tests, screenshot tests, deep links from outside the app.

## Module & Gradle setup

`:androidApp` is a plain `com.android.application` module (no `kotlin.multiplatform` plugin) that depends on `:shared`.

- `namespace` / `applicationId`: `com.badmintontracker.android`
- minSdk 26, targetSdk 35, compileSdk 35, JVM 17, Kotlin 2.3.20
- `buildFeatures.compose = true`, `buildFeatures.buildConfig = true`

New entries in `gradle/libs.versions.toml`:

- `androidx.activity:activity-compose`
- Compose BOM (latest stable) → `compose.material3`, `compose.ui`, `compose.ui.tooling.preview` (debug-only `ui.tooling`)
- `androidx.lifecycle:lifecycle-viewmodel-compose`
- `androidx.lifecycle:lifecycle-runtime-compose` (for `collectAsStateWithLifecycle`)
- `androidx.navigation:navigation-compose 2.8.x`
- `androidx.media3:media3-exoplayer`, `media3-ui`, `media3-common`
- `io.coil-kt.coil3:coil-compose` (thumbnails)

Secrets: Gradle reads `local.properties` and emits `BuildConfig.SUPABASE_URL` / `SUPABASE_ANON_KEY`. `local.properties` is already gitignored. CI sets the same fields from secrets.

Manifest: an `intent-filter` on `MainActivity` for `badmintontracker://login` so the supabase-kt OAuth callback lands in the app. supabase-kt's `Auth` plugin handles the rest — no custom intent code.

## App initialization & DI

```kotlin
class RallyAndroidApp : Application() {
    lateinit var rally: RallyApp
        private set

    override fun onCreate() {
        super.onCreate()
        rally = RallyApp(
            config = SupabaseConfig(BuildConfig.SUPABASE_URL, BuildConfig.SUPABASE_ANON_KEY),
            settings = SharedPreferencesSettings.Factory(this).create("rally"),
        )
    }
}
```

Registered via `android:name=".RallyAndroidApp"` in the manifest. supabase-kt's `SettingsSessionManager` wraps the `multiplatform-settings` `SharedPreferencesSettings` for session persistence.

ViewModel construction in the NavHost:

```kotlin
val rally = (LocalContext.current.applicationContext as RallyAndroidApp).rally

composable<Route.ClipList> {
    val vm: ClipListViewModel = viewModel(factory = viewModelFactory {
        initializer { ClipListViewModel(rally.clips, rally.auth) }
    })
    ClipListScreen(vm = vm, onClipClick = { nav.navigate(Route.ClipDetail(it.id)) })
}
```

A small `rememberRallyApp()` composable extracts the `LocalContext` cast.

## Navigation graph

Three type-safe routes:

```kotlin
sealed interface Route {
    @Serializable data object SignIn   : Route
    @Serializable data object ClipList : Route
    @Serializable data class  ClipDetail(val clipId: String) : Route
}
```

Start destination is decided at runtime from the first emission of `auth.sessionFlow`:

- `Authenticated` → `ClipList`
- `NotAuthenticated` → `SignIn`

`MainActivity` shows a `Splash` composable (centered `CircularProgressIndicator` + app name) until the first session value arrives, then renders the `NavHost` with the resolved start destination.

`AuthGate` wraps the `NavHost` and continues collecting `sessionFlow` so silent expiry (e.g. revoked refresh token) navigates back to `SignIn` with `popUpTo<Route.ClipList> { inclusive = true }`.

After successful sign-in, the `SignInViewModel`'s `events` flow emits `SignedIn` and the screen calls `nav.navigate(Route.ClipList) { popUpTo<Route.SignIn> { inclusive = true } }`.

## Sign-in screen

```kotlin
data class SignInState(
    val email: String = "",
    val password: String = "",
    val isSubmitting: Boolean = false,
    val error: String? = null,
)
sealed interface SignInEvent { data object SignedIn : SignInEvent }

class SignInViewModel(private val auth: AuthRepository) : ViewModel() {
    val state  = MutableStateFlow(SignInState())
    val events = MutableSharedFlow<SignInEvent>(extraBufferCapacity = 1)

    fun onEmailChange(v: String)    { state.update { it.copy(email = v, error = null) } }
    fun onPasswordChange(v: String) { state.update { it.copy(password = v, error = null) } }

    fun submitEmail() = viewModelScope.launch {
        state.update { it.copy(isSubmitting = true, error = null) }
        auth.signInEmail(state.value.email.trim(), state.value.password)
            .onSuccess { events.tryEmit(SignInEvent.SignedIn) }
            .onFailure { e -> state.update { it.copy(isSubmitting = false, error = e.message) } }
    }

    fun submitGoogle() = viewModelScope.launch {
        state.update { it.copy(isSubmitting = true, error = null) }
        auth.signInWithGoogle()
            .onFailure { e -> state.update { it.copy(isSubmitting = false, error = e.message) } }
        // success arrives via deeplink → sessionFlow; AuthGate handles routing.
    }
}
```

`SignInScreen`: Material 3 `Scaffold`; centered `Column` with app title, two `OutlinedTextField`s (email / password — password masked), primary `Button("Sign in")`, divider "or", secondary `OutlinedButton` with Google logo, and an error `Text` below the form when `state.error != null`. The submit button disables itself during `isSubmitting`. A `LaunchedEffect` collects `events` and invokes the nav callback.

`events` is intentionally a separate `MutableSharedFlow` rather than part of state — replaying `SignedIn` after a rotation would re-navigate.

## Clip list screen

```kotlin
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

    fun refresh() = viewModelScope.launch {
        refreshing.value = true
        runCatching { clips.refresh() }.onFailure { errors.value = it.message }
        refreshing.value = false
    }

    fun signOut() = viewModelScope.launch { auth.signOut() }
    fun dismissError() { errors.value = null }
}
```

`ClipListScreen`: `Scaffold` with `TopAppBar` (title "Clips", overflow menu → "Sign out"). Body is a `LazyColumn` of `ClipRow` items wrapped in `PullToRefreshBox(isRefreshing = state.isRefreshing, onRefresh = vm::refresh)`. Each row shows: thumbnail (Coil loads via `media.signedThumbnailUrl(clip)`, with a placeholder), title (or "Rally #${rallyIndex + 1}" if null), and `${duration}s · ${annotationCount} notes`. Tapping a row calls the nav callback.

Empty state: `clips.isEmpty() && !isRefreshing` → centered "No clips yet. Record one in the desktop app."

Error state: a `Snackbar` shows `state.error`; dismissal calls `vm.dismissError()`.

Thumbnail signing: signed thumbnail URLs are 1h-TTL. Each row computes its URL once via `produceState(initialValue = null, clip.id) { value = media.signedThumbnailUrl(clip) }`. Re-entering the screen refreshes them. No background pre-fetch in v1.

## Clip detail screen + player

```kotlin
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
    val state = MutableStateFlow(ClipDetailState())
    val seekTo = MutableSharedFlow<Long>(extraBufferCapacity = 1) // ms

    private var resignAttempts = 0

    init {
        viewModelScope.launch {
            // 1) read clip from in-memory cache (clips.observeClips() already populated by list)
            //    fall back to a single-row select if cache miss.
            // 2) annotations.list(clipId)
            // 3) media.signedClipUrl(clip) → state.signedClipUrl
        }
    }

    fun onAnnotationTap(a: RallyAnnotation) {
        seekTo.tryEmit((a.timestampSeconds * 1000).toLong())
    }

    fun onPlayerError() = viewModelScope.launch {
        if (resignAttempts >= 1) {
            state.update { it.copy(error = "Couldn't load video") }
            return@launch
        }
        resignAttempts++
        val clip = state.value.clip ?: return@launch
        runCatching { media.signedClipUrl(clip) }
            .onSuccess { url -> state.update { it.copy(signedClipUrl = url, error = null) } }
            .onFailure { e -> state.update { it.copy(error = e.message) } }
    }

    fun onManualRetry() {
        resignAttempts = 0
        onPlayerError()
    }
}
```

`ClipDetailScreen`: `Scaffold` with `TopAppBar` (back nav, title = clip title or "Rally #${rallyIndex + 1}"). Body is a vertical column:

1. **Player** (16:9): `AndroidView` wrapping Media3 `PlayerView`. The `ExoPlayer` lives in `remember { ExoPlayer.Builder(ctx).build() }` and is released in `DisposableEffect`. A `LaunchedEffect(state.signedClipUrl)` calls `setMediaItem(MediaItem.fromUri(url)); prepare()` whenever the URL changes. A `Player.Listener` forwards `onPlayerError` → `vm::onPlayerError`. The screen also `collect`s `vm.seekTo` and calls `player.seekTo(ms)`. `DisposableEffect(LocalLifecycleOwner.current.lifecycle)` pauses on `ON_PAUSE`.
2. **Annotations list**: `LazyColumn` of `AnnotationRow(timestampSeconds, body)`; tap → `vm.onAnnotationTap(a)`. Empty state: "No annotations on this clip."
3. **Error**: if `state.error != null`, an overlay on the player area with a "Retry" button calling `vm.onManualRetry()`.

Player-error → re-sign flow: on `Player.Listener.onPlayerError` (commonly an HTTP 403 from an expired URL), the VM signs a new URL and updates `state.signedClipUrl`; the `LaunchedEffect` reloads the player. We cap automatic retries at 1 per session to avoid loops on a genuinely broken file; further errors surface with a manual "Retry".

## Data flow

```
SignIn      :  VM → auth.signInEmail / signInWithGoogle  → events.SignedIn → nav to ClipList
ClipList    :  VM ← clips.observeClips() (StateFlow); init → clips.refresh()
ClipDetail  :  VM ← annotations.list(clipId) + media.signedClipUrl(clip)
AuthGate    :  collects auth.sessionFlow at activity scope; routes on every emission
```

## Error handling

In priority order:

1. **Network / Postgrest errors** — repos already return `Result<T>` for mutations and throw for reads. ViewModels wrap reads in `runCatching` and surface `error: String?` in `UiState`. UI renders a Snackbar; dismissal clears.
2. **Player errors** — covered above: 1 automatic re-sign retry, then user-facing "Retry" button.
3. **Auth expiry mid-session** — `AuthGate` collector sees `sessionFlow` flip to `NotAuthenticated`, navigates to `SignIn` with `popUpTo(Route.ClipList) { inclusive = true }`. No per-screen 401 handling needed.
4. **Cold-start with no network** — `clips.refresh()` fails; the (empty) cached `observeClips()` value still renders, with a "Couldn't load" Snackbar and pull-to-refresh available.

## Testing

ViewModel unit tests only, JVM, no Robolectric.

- `androidApp` `src/test/java/...` source set.
- For each VM, three handwritten fakes (`FakeAuthRepository`, `FakeClipsRepository`, `FakeAnnotationsRepository`, `FakeMediaRepository`) implement the `:shared` interfaces in test code.
- `kotlinx-coroutines-test`'s `runTest` + `StandardTestDispatcher`; `app.cash.turbine` for state / event flows.

Cases:

- `SignInViewModel`: success → `SignedIn` event emitted; failure → state contains error, no event.
- `ClipListViewModel`: init triggers `refresh()` and emits the cached clips; refresh failure surfaces in `error`.
- `ClipDetailViewModel`: init loads clip + annotations + signed URL; `onAnnotationTap` emits the right ms value to `seekTo`; first `onPlayerError` re-signs; second `onPlayerError` flips state to error; `onManualRetry` resets and re-signs.

~7–10 tests total. Fast, host-only, no emulator.

## Open questions / follow-ups

- Polish-plan additions: edit clip title, add / edit / delete annotations from the mobile UI, screenshot tests, optional Realtime subscription on `rally_annotations`, Apple sign-in for iOS parity.
- If iOS later wants to share ViewModels with Android, migrate from manual DI to Koin and move VMs into `commonMain`. Out of scope here.
