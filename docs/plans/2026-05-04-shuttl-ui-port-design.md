# SHUTTL. UI Port — Design

**Date:** 2026-05-04
**Status:** approved
**Builds on:** the existing Android client (`AuthGate`, `ClipListScreen`, `MatchClipsScreen`, `ClipDetailScreen`, default Material 3 theme).

## Goal

Make the Android app visually a first-class extension of the `badminton-tracker` web app ("SHUTTL."). A user holding their phone next to the web app should read them as one product, not two.

This is a styling change, not a behavioural one. No ViewModel, navigation, repository, or test logic changes.

## Reference: web design system

Source: `badminton-tracker/src/app.css`, `badminton-tracker/src/views/LoginView.vue`.

- **Aesthetic:** flat / brutalist — sharp corners everywhere (`border-radius: 0`), single-pixel borders, no shadows.
- **Palette:** monochrome (white/black/gray) with a single green accent (`#16a34a` light / `#22c55e` dark).
- **Typography:** system sans-serif. Tiny uppercase labels with `letter-spacing: 0.05em`. Headings 700, body 400.
- **Themes:** light by default; manual toggle (sun/moon icon, top-right) flips to dark; preference persists.
- **Brand:** "SHUTTL." wordmark + small "alpha vN" gradient pill badge.

## Scope

| Topic | Decision |
| --- | --- |
| Depth | Full re-skin: tokens, primitives, every existing screen, including pre-auth sign-in |
| Theme | Default LIGHT on first launch; toggle flips light/dark only (no system-follow); persist via DataStore |
| Custom fonts | None — system sans-serif (matches web's `font-family: inherit`) |
| Behaviour changes | None |
| Out of scope | Launcher icon, splash, animations beyond M3 defaults, clickable alpha badge (no changelog screen on Android) |

## Approach: M3 tokens + thin custom primitives

Override `MaterialTheme`'s `ColorScheme`, `Shapes`, `Typography`. Keep `Scaffold`, `TopAppBar`, `SnackbarHost`, `LazyColumn` from M3. Build six bespoke composables for the surfaces M3 fights us on (buttons, text fields, cards, error banner, divider-with-text, theme toggle).

Considered alternatives:

- *Pure M3 token override* — rejected: M3 components carry tonal elevation and rounded ripples that bleed through token overrides.
- *Fully custom design system* — rejected: overkill for an app of this size.

## Theme tokens & files

Restructure `ui/theme/`:

```
ui/theme/
  ShuttlColors.kt   light & dark ColorScheme + LocalShuttlColors for extended palette
                    (accentDark, bgInput, bgTertiary, textTertiary, borderSecondary, warning, info)
  ShuttlShapes.kt   Shapes(extraSmall..extraLarge) all RoundedCornerShape(0.dp)
  ShuttlType.kt     Typography overrides; labelSmall = uppercase + 0.05em letterSpacing
  Theme.kt          RallyTheme(darkTheme: Boolean) wires the above
```

### Color mapping

| Web token | Compose role |
| --- | --- |
| `--color-bg` | `colorScheme.background` / `surface` |
| `--color-bg-secondary` | `surfaceVariant` (cards) |
| `--color-bg-input` | extended `LocalShuttlColors.bgInput` |
| `--color-bg-tertiary` | extended `bgTertiary` (secondary button bg) |
| `--color-accent` | `colorScheme.primary` |
| `--color-accent-dark` | extended `accentDark` |
| `--color-text-heading` | `onBackground` |
| `--color-text` | `onSurface` |
| `--color-text-secondary` | `onSurfaceVariant` |
| `--color-text-tertiary` | extended `textTertiary` |
| `--color-border` | `outlineVariant` |
| `--color-border-secondary` | `outline` |
| `--color-error` | `colorScheme.error` |

## Theme persistence

`data/ThemePreferenceRepository.kt` backed by the existing `com.russhwolf.multiplatform-settings` (already used by `RallyAndroidApp`):

```kotlin
enum class ThemeMode { LIGHT, DARK }
class ThemePreferenceRepository(settings: Settings) {
  val mode: StateFlow<ThemeMode>     // default LIGHT
  fun set(mode: ThemeMode)
}
```

Implementation: hydrate a `MutableStateFlow<ThemeMode>` from settings on construction; `set()` updates the flow and writes through. `RallyAndroidApp` constructs the repo alongside `rally` (passing the same `Settings` instance). `MainActivity` collects `themePrefs.mode` and resolves to `Boolean darkTheme` passed to `RallyTheme`. **No new deps.**

## Custom primitives (`ui/components/`)

```kotlin
ShuttlButton(text, onClick, loading=false, enabled=true, variant: Primary | Secondary, modifier)
ShuttlOutlinedTextField(value, onValueChange, label, type, enabled, modifier)
ShuttlCard(modifier, content)
FieldLabel(text)
ErrorBanner(message)
DividerWithText(text = "or")
ThemeToggleButton(currentMode, onToggle)
```

Built with `Box` + `border` + `clickable` (no `Surface` tonal elevation). Sharp corners, paddings ported from `LoginView.vue` (10/12 for inputs, 12/24 for buttons, 24-32 for cards).

## Screen restyles

1. **`signin/SignInScreen.kt`** — centered column: "SHUTTL." wordmark (1.5rem-equivalent, 700, `-0.01em` tracking) + "alpha" gradient pill badge (non-clickable on Android). Subtitle "Sign in to continue". `ShuttlCard` containing email + password `ShuttlOutlinedTextField`s, `ShuttlButton(Primary)` "Sign in", `DividerWithText("or")`, `ShuttlButton(Secondary)` "Continue with Google" with the multi-color G drawn from path data. `ErrorBanner` above divider when present. Hint text below card. Top-right `ThemeToggleButton`.
2. **`ClipListScreen.kt`** — `TopAppBar` title becomes uppercase **"MATCHES"** with 0.05em tracking. `actions` = `ThemeToggleButton` then existing overflow `MoreVert`. `MatchRow` keeps 96×54 thumbnail, padding 16h/14v, title `titleMedium` heading color, subtitle ("N rallies · date") in `labelSmall` uppercase tracking. `HorizontalDivider` uses `outlineVariant`. Pull-to-refresh tints accent. Empty state text uses muted secondary color.
3. **`MatchClipsScreen.kt`** — same treatment, title "RALLIES" or uppercased match name.
4. **`ClipDetailScreen.kt`** — ExoPlayer + retry overlay logic unchanged. Retry button → `ShuttlButton(Primary)`. App bar inherits new styling.

## Testing

- Unit test `ThemePreferenceRepository` against an in-memory `DataStore<Preferences>`: default = LIGHT, `set(DARK)` round-trips through `observe()`.
- Existing tests (`ClipDetailViewModelTest`, `FakeAnnotationsRepository`) must remain green — no changes expected.
- No Compose UI / screenshot tests; visual correctness is verified by running the app on emulator next to the web app.
- CI's existing `assembleDebug` + unit tests must still pass.

## Rollout order

Each step compiles and runs cleanly:

1. Theme tokens — split `Theme.kt` → `ShuttlColors/Shapes/Type/Theme.kt`. No `LocalShuttlColors` consumers yet.
2. `ThemePreferenceRepository` (uses existing `multiplatform-settings`), wired into `RallyAndroidApp` + `MainActivity`, default LIGHT, no UI yet.
3. Custom primitives in `ui/components/`. Not used yet.
4. `ThemeToggleButton` into `TopAppBar` actions on `ClipListScreen` + `MatchClipsScreen`.
5. Restyle `SignInScreen` to SHUTTL. layout + fixed top-right toggle.
6. Restyle `ClipListScreen` + `MatchClipsScreen` rows & app bar (uppercase titles, dividers, paddings).
7. `ClipDetailScreen` retry overlay → `ShuttlButton`.
