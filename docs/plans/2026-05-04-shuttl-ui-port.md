# SHUTTL. UI Port — Implementation Plan

> **For Claude:** REQUIRED SUB-SKILL: Use superpowers:executing-plans to implement this plan task-by-task.

**Goal:** Re-skin the Android client to match the `badminton-tracker` web app's flat/brutalist "SHUTTL." aesthetic so phone and web read as one product.

**Architecture:** Override Material 3 theme tokens (color, shape, typography) and add six thin custom Compose primitives that draw with `Box` + `border` + `clickable` instead of `Surface`/tonal-elevation. Theme preference (light/dark) persisted via the existing `multiplatform-settings` infrastructure (no new deps). All ViewModel, navigation, and repository code is untouched.

**Tech Stack:** Kotlin 2.3.20, Jetpack Compose (BOM 2025.01.00), Material 3, `multiplatform-settings` 1.2.0, kotlinx.coroutines 1.10.2 (StateFlow), kotest-assertions + turbine for unit tests.

**Companion design doc:** [`docs/plans/2026-05-04-shuttl-ui-port-design.md`](2026-05-04-shuttl-ui-port-design.md)

---

## Conventions for every task

- After each task, run `./gradlew :androidApp:assembleDebug :androidApp:testDebugUnitTest` — must succeed.
- Commit messages follow the existing `feat(androidApp):` / `refactor(androidApp):` / `style(androidApp):` style (see `git log --oneline | head`).
- Imports: keep alphabetised, match existing file style (no wildcard imports).
- Skill: when stuck on a failing test, invoke `superpowers:systematic-debugging`. When something is "done", invoke `superpowers:verification-before-completion` before claiming so.

---

## Task 1: Theme tokens

Replace the single-file `Theme.kt` with four files mirroring the web's `app.css`. Existing screens immediately pick up new colors and zero-radius shapes.

**Files:**
- Create: `androidApp/src/main/java/com/badmintontracker/android/ui/theme/ShuttlColors.kt`
- Create: `androidApp/src/main/java/com/badmintontracker/android/ui/theme/ShuttlShapes.kt`
- Create: `androidApp/src/main/java/com/badmintontracker/android/ui/theme/ShuttlType.kt`
- Modify: `androidApp/src/main/java/com/badmintontracker/android/ui/theme/Theme.kt` (full rewrite)

**Step 1: Create `ShuttlColors.kt`**

```kotlin
package com.badmintontracker.android.ui.theme

import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color

// Web tokens (badminton-tracker/src/app.css) → Compose colors.
private val LightBg              = Color(0xFFFFFFFF)
private val LightBgSecondary     = Color(0xFFF8F9FA)
private val LightBgTertiary      = Color(0xFFF0F1F3)
private val LightBgInput         = Color(0xFFF0F1F3)
private val LightBorder          = Color(0xFFE0E0E0)
private val LightBorderSecondary = Color(0xFFD0D0D0)
private val LightTextHeading     = Color(0xFF0D0D0D)
private val LightText            = Color(0xFF1A1A2E)
private val LightTextSecondary   = Color(0xFF555555)
private val LightTextTertiary    = Color(0xFF777777)
private val LightAccent          = Color(0xFF16A34A)
private val LightAccentDark      = Color(0xFF166534)

private val DarkBg               = Color(0xFF0D0D0D)
private val DarkBgSecondary      = Color(0xFF141414)
private val DarkBgTertiary       = Color(0xFF1A1A1A)
private val DarkBgInput          = Color(0xFF111111)
private val DarkBorder           = Color(0xFF222222)
private val DarkBorderSecondary  = Color(0xFF333333)
private val DarkTextHeading      = Color(0xFFFFFFFF)
private val DarkText             = Color(0xFFE2E8F0)
private val DarkTextSecondary    = Color(0xFF888888)
private val DarkTextTertiary     = Color(0xFF666666)
private val DarkAccent           = Color(0xFF22C55E)
private val DarkAccentDark       = Color(0xFF16A34A)

private val Error   = Color(0xFFEF4444)
private val Warning = Color(0xFFF59E0B)
private val Info    = Color(0xFF3B82F6)

internal val ShuttlLightColorScheme = lightColorScheme(
    primary          = LightAccent,
    onPrimary        = Color.Black,
    background       = LightBg,
    onBackground     = LightTextHeading,
    surface          = LightBg,
    onSurface        = LightText,
    surfaceVariant   = LightBgSecondary,
    onSurfaceVariant = LightTextSecondary,
    outline          = LightBorderSecondary,
    outlineVariant   = LightBorder,
    error            = Error,
    onError          = Color.White,
)

internal val ShuttlDarkColorScheme = darkColorScheme(
    primary          = DarkAccent,
    onPrimary        = Color.Black,
    background       = DarkBg,
    onBackground     = DarkTextHeading,
    surface          = DarkBg,
    onSurface        = DarkText,
    surfaceVariant   = DarkBgSecondary,
    onSurfaceVariant = DarkTextSecondary,
    outline          = DarkBorderSecondary,
    outlineVariant   = DarkBorder,
    error            = Error,
    onError          = Color.White,
)

/** Extended palette beyond M3's ColorScheme. */
@Immutable
data class ShuttlExtendedColors(
    val accentDark:      Color,
    val bgInput:         Color,
    val bgTertiary:      Color,
    val textTertiary:    Color,
    val warning:         Color,
    val info:            Color,
)

internal val ShuttlLightExtended = ShuttlExtendedColors(
    accentDark   = LightAccentDark,
    bgInput      = LightBgInput,
    bgTertiary   = LightBgTertiary,
    textTertiary = LightTextTertiary,
    warning      = Warning,
    info         = Info,
)

internal val ShuttlDarkExtended = ShuttlExtendedColors(
    accentDark   = DarkAccentDark,
    bgInput      = DarkBgInput,
    bgTertiary   = DarkBgTertiary,
    textTertiary = DarkTextTertiary,
    warning      = Warning,
    info         = Info,
)

val LocalShuttlColors = staticCompositionLocalOf { ShuttlLightExtended }

object ShuttlTheme {
    val extended: ShuttlExtendedColors
        @Composable @ReadOnlyComposable
        get() = LocalShuttlColors.current
}
```

**Step 2: Create `ShuttlShapes.kt`**

```kotlin
package com.badmintontracker.android.ui.theme

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Shapes
import androidx.compose.ui.unit.dp

internal val ShuttlShapes = Shapes(
    extraSmall = RoundedCornerShape(0.dp),
    small      = RoundedCornerShape(0.dp),
    medium     = RoundedCornerShape(0.dp),
    large      = RoundedCornerShape(0.dp),
    extraLarge = RoundedCornerShape(0.dp),
)
```

**Step 3: Create `ShuttlType.kt`**

```kotlin
package com.badmintontracker.android.ui.theme

import androidx.compose.material3.Typography
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

private val Default = Typography()

internal val ShuttlTypography = Typography(
    // Headings: tighter tracking, system sans-serif, 700.
    headlineLarge  = Default.headlineLarge.copy (fontFamily = FontFamily.Default, fontWeight = FontWeight.Bold,    letterSpacing = (-0.5).sp),
    headlineMedium = Default.headlineMedium.copy(fontFamily = FontFamily.Default, fontWeight = FontWeight.Bold,    letterSpacing = (-0.4).sp),
    titleLarge     = Default.titleLarge.copy    (fontFamily = FontFamily.Default, fontWeight = FontWeight.SemiBold),
    titleMedium    = Default.titleMedium.copy   (fontFamily = FontFamily.Default, fontWeight = FontWeight.Medium),
    bodyLarge      = Default.bodyLarge.copy     (fontFamily = FontFamily.Default),
    bodyMedium     = Default.bodyMedium.copy    (fontFamily = FontFamily.Default),
    bodySmall      = Default.bodySmall.copy     (fontFamily = FontFamily.Default),
    // Tiny uppercase tracked label — matches `.field-label` (text-transform: uppercase, letter-spacing: 0.05em).
    labelSmall = TextStyle(
        fontFamily    = FontFamily.Default,
        fontWeight    = FontWeight.Medium,
        fontSize      = 11.sp,
        letterSpacing = 0.55.sp,    // ~0.05em at 11sp
    ),
)
```

**Step 4: Rewrite `Theme.kt`**

```kotlin
package com.badmintontracker.android.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider

@Composable
fun RallyTheme(
    darkTheme: Boolean = false,           // Default LIGHT — matches web.
    content:   @Composable () -> Unit,
) {
    val colors    = if (darkTheme) ShuttlDarkColorScheme else ShuttlLightColorScheme
    val extended  = if (darkTheme) ShuttlDarkExtended    else ShuttlLightExtended

    CompositionLocalProvider(LocalShuttlColors provides extended) {
        MaterialTheme(
            colorScheme = colors,
            typography  = ShuttlTypography,
            shapes      = ShuttlShapes,
            content     = content,
        )
    }
}
```

**Step 5: Build & visually confirm**

Run: `./gradlew :androidApp:assembleDebug`
Expected: BUILD SUCCESSFUL.

Install on emulator (`./gradlew :androidApp:installDebug`) and confirm: app launches in light mode, list rows have white background and dark green accent on the pull-to-refresh indicator, shapes are square (no rounded buttons).

**Step 6: Commit**

```bash
git add androidApp/src/main/java/com/badmintontracker/android/ui/theme/
git commit -m "$(cat <<'EOF'
style(androidApp): port SHUTTL. theme tokens

Splits Theme.kt into ShuttlColors / ShuttlShapes / ShuttlType.
Mirrors badminton-tracker/src/app.css palette, sharp corners (0dp),
and tracked uppercase labelSmall.

Co-Authored-By: Claude Opus 4.7 (1M context) <noreply@anthropic.com>
EOF
)"
```

---

## Task 2: ThemePreferenceRepository (TDD)

**Files:**
- Create: `androidApp/src/main/java/com/badmintontracker/android/data/ThemePreferenceRepository.kt`
- Create: `androidApp/src/test/java/com/badmintontracker/android/data/ThemePreferenceRepositoryTest.kt`

**Step 1: Write the failing test**

```kotlin
package com.badmintontracker.android.data

import app.cash.turbine.test
import com.russhwolf.settings.MapSettings
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.test.runTest
import org.junit.Test

class ThemePreferenceRepositoryTest {

    @Test
    fun `defaults to LIGHT when no value stored`() = runTest {
        val repo = ThemePreferenceRepository(MapSettings())
        repo.mode.value shouldBe ThemeMode.LIGHT
    }

    @Test
    fun `set DARK is observable and persisted`() = runTest {
        val settings = MapSettings()
        val repo     = ThemePreferenceRepository(settings)

        repo.mode.test {
            awaitItem() shouldBe ThemeMode.LIGHT
            repo.set(ThemeMode.DARK)
            awaitItem() shouldBe ThemeMode.DARK
        }

        // New repo over the same settings rehydrates DARK.
        ThemePreferenceRepository(settings).mode.value shouldBe ThemeMode.DARK
    }
}
```

**Step 2: Run the test, verify it fails**

Run: `./gradlew :androidApp:testDebugUnitTest --tests "com.badmintontracker.android.data.ThemePreferenceRepositoryTest"`
Expected: COMPILATION FAILURE — `ThemePreferenceRepository` and `ThemeMode` are unresolved.

**Step 3: Add `multiplatform-settings-test` to the androidApp test classpath**

In `androidApp/build.gradle.kts` `dependencies { ... }`, add (alongside other test deps if any; otherwise create the block):

```kotlin
testImplementation(libs.settings.test)
testImplementation(libs.kotlinx.coroutines.test)
testImplementation(libs.kotest.assertions)
testImplementation(libs.turbine)
testImplementation("junit:junit:4.13.2")
```

(Check `git diff` afterwards: only add lines that aren't already present. The existing `ClipDetailViewModelTest` already runs, so several of these may exist — keep the file diff minimal.)

**Step 4: Write the minimal implementation**

```kotlin
package com.badmintontracker.android.data

import com.russhwolf.settings.Settings
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

enum class ThemeMode { LIGHT, DARK }

class ThemePreferenceRepository(private val settings: Settings) {

    private val state = MutableStateFlow(load())
    val mode: StateFlow<ThemeMode> = state.asStateFlow()

    fun set(mode: ThemeMode) {
        settings.putString(KEY, mode.name)
        state.value = mode
    }

    private fun load(): ThemeMode =
        settings.getStringOrNull(KEY)
            ?.let { runCatching { ThemeMode.valueOf(it) }.getOrNull() }
            ?: ThemeMode.LIGHT

    private companion object { const val KEY = "theme_mode" }
}
```

**Step 5: Run the test, verify it passes**

Run: `./gradlew :androidApp:testDebugUnitTest --tests "com.badmintontracker.android.data.ThemePreferenceRepositoryTest"`
Expected: 2 tests, all PASS.

**Step 6: Commit**

```bash
git add androidApp/src/main/java/com/badmintontracker/android/data/ \
        androidApp/src/test/java/com/badmintontracker/android/data/ \
        androidApp/build.gradle.kts
git commit -m "$(cat <<'EOF'
feat(androidApp): ThemePreferenceRepository with multiplatform-settings

Reactive StateFlow<ThemeMode> persisted via the existing
SharedPreferencesSettings instance. Defaults to LIGHT, matching the
web app.

Co-Authored-By: Claude Opus 4.7 (1M context) <noreply@anthropic.com>
EOF
)"
```

---

## Task 3: Wire theme preference into the app shell

**Files:**
- Modify: `androidApp/src/main/java/com/badmintontracker/android/RallyAndroidApp.kt`
- Modify: `androidApp/src/main/java/com/badmintontracker/android/MainActivity.kt`

**Step 1: Expose the repo from `RallyAndroidApp`**

Replace the body so both `rally` and `themePrefs` are constructed from the same `Settings`:

```kotlin
package com.badmintontracker.android

import android.app.Application
import com.badmintontracker.android.data.ThemePreferenceRepository
import com.badmintontracker.shared.RallyApp
import com.badmintontracker.shared.SupabaseConfig
import com.russhwolf.settings.SharedPreferencesSettings

class RallyAndroidApp : Application() {

    lateinit var rally:       RallyApp                    private set
    lateinit var themePrefs:  ThemePreferenceRepository   private set

    override fun onCreate() {
        super.onCreate()
        val settings = SharedPreferencesSettings(getSharedPreferences("rally", MODE_PRIVATE))
        rally       = RallyApp(SupabaseConfig(BuildConfig.SUPABASE_URL, BuildConfig.SUPABASE_ANON_KEY), settings)
        themePrefs  = ThemePreferenceRepository(settings)
    }
}
```

**Step 2: Consume it from `MainActivity`**

```kotlin
package com.badmintontracker.android

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.badmintontracker.android.data.ThemeMode
import com.badmintontracker.android.ui.theme.RallyTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val app = application as RallyAndroidApp
        setContent {
            val mode by app.themePrefs.mode.collectAsStateWithLifecycle()
            RallyTheme(darkTheme = mode == ThemeMode.DARK) {
                Surface(modifier = Modifier.fillMaxSize()) {
                    AuthGate(rally = app.rally)
                }
            }
        }
    }
}
```

**Step 3: Build**

Run: `./gradlew :androidApp:assembleDebug`
Expected: BUILD SUCCESSFUL.

**Step 4: Commit**

```bash
git add androidApp/src/main/java/com/badmintontracker/android/RallyAndroidApp.kt \
        androidApp/src/main/java/com/badmintontracker/android/MainActivity.kt
git commit -m "$(cat <<'EOF'
feat(androidApp): wire theme preference into app shell

RallyAndroidApp exposes themePrefs alongside rally, sharing the same
Settings. MainActivity collects the StateFlow and passes the resolved
darkTheme into RallyTheme.

Co-Authored-By: Claude Opus 4.7 (1M context) <noreply@anthropic.com>
EOF
)"
```

---

## Task 4: Custom primitives (`ui/components/`)

Build the six bespoke composables described in the design doc. They aren't used yet — just made available for the next tasks. Verify each compiles via the full build.

**Files (all create):**
- `androidApp/src/main/java/com/badmintontracker/android/ui/components/ShuttlButton.kt`
- `androidApp/src/main/java/com/badmintontracker/android/ui/components/ShuttlOutlinedTextField.kt`
- `androidApp/src/main/java/com/badmintontracker/android/ui/components/ShuttlCard.kt`
- `androidApp/src/main/java/com/badmintontracker/android/ui/components/FieldLabel.kt`
- `androidApp/src/main/java/com/badmintontracker/android/ui/components/ErrorBanner.kt`
- `androidApp/src/main/java/com/badmintontracker/android/ui/components/DividerWithText.kt`

**Step 1: `FieldLabel.kt`** (used by the text field; build this first)

```kotlin
package com.badmintontracker.android.ui.components

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier

@Composable
fun FieldLabel(text: String, modifier: Modifier = Modifier) {
    Text(
        text     = text.uppercase(),
        style    = MaterialTheme.typography.labelSmall,
        color    = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = modifier,
    )
}
```

**Step 2: `ShuttlButton.kt`**

```kotlin
package com.badmintontracker.android.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.badmintontracker.android.ui.theme.ShuttlTheme

enum class ShuttlButtonVariant { Primary, Secondary }

@Composable
fun ShuttlButton(
    text:     String,
    onClick:  () -> Unit,
    modifier: Modifier = Modifier,
    variant:  ShuttlButtonVariant = ShuttlButtonVariant.Primary,
    enabled:  Boolean = true,
    loading:  Boolean = false,
) {
    val isPrimary    = variant == ShuttlButtonVariant.Primary
    val bg           = if (isPrimary) MaterialTheme.colorScheme.primary else ShuttlTheme.extended.bgTertiary
    val fg           = if (isPrimary) Color.Black else MaterialTheme.colorScheme.onSurface
    val borderColor  = if (isPrimary) Color.Transparent else MaterialTheme.colorScheme.outline
    val effective    = enabled && !loading

    Row(
        modifier = modifier
            .background(bg.copy(alpha = if (effective) 1f else 0.5f))
            .border(width = if (isPrimary) 0.dp else 1.dp, color = borderColor)
            .clickable(enabled = effective, onClick = onClick)
            .padding(horizontal = 24.dp, vertical = 12.dp),
        horizontalArrangement = Arrangement.Center,
        verticalAlignment     = Alignment.CenterVertically,
    ) {
        if (loading) {
            CircularProgressIndicator(
                color       = fg,
                strokeWidth = 2.dp,
                modifier    = Modifier.size(14.dp),
            )
            Spacer(Modifier.width(8.dp))
        }
        Text(
            text       = text,
            color      = fg,
            fontWeight = FontWeight.SemiBold,
            style      = MaterialTheme.typography.bodyLarge,
        )
    }
}
```

**Step 3: `ShuttlOutlinedTextField.kt`**

```kotlin
package com.badmintontracker.android.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import com.badmintontracker.android.ui.theme.ShuttlTheme

enum class ShuttlFieldType { Text, Email, Password }

@Composable
fun ShuttlOutlinedTextField(
    value:         String,
    onValueChange: (String) -> Unit,
    label:         String,
    modifier:      Modifier = Modifier,
    type:          ShuttlFieldType = ShuttlFieldType.Text,
    enabled:       Boolean = true,
) {
    val interaction = remember { MutableInteractionSource() }
    val isFocused   by interaction.collectIsFocusedAsState()
    val borderColor =
        if (isFocused) MaterialTheme.colorScheme.primary
        else            MaterialTheme.colorScheme.outline

    val keyboard = when (type) {
        ShuttlFieldType.Email    -> KeyboardOptions(keyboardType = KeyboardType.Email)
        ShuttlFieldType.Password -> KeyboardOptions(keyboardType = KeyboardType.Password)
        ShuttlFieldType.Text     -> KeyboardOptions.Default
    }
    val visual: VisualTransformation =
        if (type == ShuttlFieldType.Password) PasswordVisualTransformation()
        else                                   VisualTransformation.None

    Column(modifier = modifier) {
        FieldLabel(label)
        Spacer(Modifier.height(6.dp))
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .background(ShuttlTheme.extended.bgInput)
                .border(width = 1.dp, color = borderColor)
                .padding(horizontal = 12.dp, vertical = 10.dp),
        ) {
            BasicTextField(
                value                  = value,
                onValueChange          = onValueChange,
                enabled                = enabled,
                singleLine             = true,
                interactionSource      = interaction,
                keyboardOptions        = keyboard,
                visualTransformation   = visual,
                textStyle              = LocalTextStyle.current.copy(color = MaterialTheme.colorScheme.onSurface),
                cursorBrush            = androidx.compose.ui.graphics.SolidColor(MaterialTheme.colorScheme.primary),
                modifier               = Modifier.fillMaxWidth(),
            )
        }
    }
}
```

**Step 4: `ShuttlCard.kt`**

```kotlin
package com.badmintontracker.android.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

@Composable
fun ShuttlCard(
    modifier: Modifier = Modifier,
    content:  @Composable () -> Unit,
) {
    Box(
        modifier = modifier
            .background(MaterialTheme.colorScheme.surfaceVariant)
            .border(width = 1.dp, color = MaterialTheme.colorScheme.outlineVariant)
            .padding(24.dp),
    ) {
        content()
    }
}
```

**Step 5: `ErrorBanner.kt`**

```kotlin
package com.badmintontracker.android.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp

@Composable
fun ErrorBanner(message: String, modifier: Modifier = Modifier) {
    val errorColor = MaterialTheme.colorScheme.error
    Row(
        modifier = modifier
            .fillMaxWidth()
            .background(errorColor.copy(alpha = 0.08f))
            .border(
                width = 0.dp,
                color = Color.Transparent,
            )
            // Left accent bar via wrapper Row + drawn inset
            .padding(horizontal = 12.dp, vertical = 10.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment     = Alignment.Top,
    ) {
        Icon(
            imageVector        = Icons.Outlined.Info,
            contentDescription = null,
            tint               = errorColor,
            modifier           = Modifier.size(14.dp),
        )
        Text(
            text  = message,
            color = errorColor,
            style = MaterialTheme.typography.bodySmall,
        )
    }
}
```

(Note: a 2px left accent stripe matching `border-left: 2px solid var(--color-error)` is intentionally omitted here for simplicity. If it reads as visually too plain when you eyeball the login screen in Task 6, swap the outer `Row` for a `Row` with a leading 2.dp-wide `Box(Modifier.fillMaxHeight().background(errorColor))`.)

**Step 6: `DividerWithText.kt`**

```kotlin
package com.badmintontracker.android.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.badmintontracker.android.ui.theme.ShuttlTheme

@Composable
fun DividerWithText(text: String = "or", modifier: Modifier = Modifier) {
    Row(
        modifier              = modifier.fillMaxWidth().padding(vertical = 20.dp),
        verticalAlignment     = Alignment.CenterVertically,
    ) {
        Box(Modifier.weight(1f).height(1.dp).background(MaterialTheme.colorScheme.outlineVariant))
        Text(
            text     = text.uppercase(),
            color    = ShuttlTheme.extended.textTertiary,
            style    = MaterialTheme.typography.labelSmall,
            modifier = Modifier.padding(horizontal = 12.dp),
        )
        Box(Modifier.weight(1f).height(1.dp).background(MaterialTheme.colorScheme.outlineVariant))
    }
}
```

**Step 7: Build**

Run: `./gradlew :androidApp:assembleDebug`
Expected: BUILD SUCCESSFUL.

**Step 8: Commit**

```bash
git add androidApp/src/main/java/com/badmintontracker/android/ui/components/
git commit -m "$(cat <<'EOF'
feat(androidApp): SHUTTL. ui primitives

Adds ShuttlButton, ShuttlOutlinedTextField, ShuttlCard, FieldLabel,
ErrorBanner, DividerWithText. All flat (sharp corners, 1px borders,
no surface elevation). Not yet wired into screens.

Co-Authored-By: Claude Opus 4.7 (1M context) <noreply@anthropic.com>
EOF
)"
```

---

## Task 5: ThemeToggleButton + plumb into existing screens

**Files:**
- Create: `androidApp/src/main/java/com/badmintontracker/android/ui/components/ThemeToggleButton.kt`
- Modify: `androidApp/src/main/java/com/badmintontracker/android/AuthGate.kt` (pass `themePrefs` down to `ClipListScreen` + `MatchClipsScreen`)
- Modify: `androidApp/src/main/java/com/badmintontracker/android/cliplist/ClipListScreen.kt` (accept `themePrefs`, add toggle to `TopAppBar.actions`)
- Modify: `androidApp/src/main/java/com/badmintontracker/android/cliplist/MatchClipsScreen.kt` (same)

**Step 1: Create `ThemeToggleButton.kt`**

```kotlin
package com.badmintontracker.android.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.DarkMode
import androidx.compose.material.icons.outlined.LightMode
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.badmintontracker.android.data.ThemeMode

@Composable
fun ThemeToggleButton(
    mode:     ThemeMode,
    onToggle: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Box(
        modifier = modifier
            .size(36.dp)
            .background(MaterialTheme.colorScheme.surfaceVariant)
            .border(width = 1.dp, color = MaterialTheme.colorScheme.outlineVariant)
            .clickable(onClick = onToggle),
        contentAlignment = Alignment.Center,
    ) {
        val (icon, desc) = when (mode) {
            ThemeMode.LIGHT -> Icons.Outlined.DarkMode  to "Switch to dark mode"
            ThemeMode.DARK  -> Icons.Outlined.LightMode to "Switch to light mode"
        }
        Icon(icon, contentDescription = desc, tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(18.dp))
    }
}
```

**Step 2: Pass `themePrefs` through `AuthGate`**

Modify `AuthGate.kt`:

```kotlin
@Composable
fun AuthGate(rally: RallyApp, themePrefs: ThemePreferenceRepository) {
    // … existing body …
}
```

Then in each `composable<Route.ClipList>` / `composable<Route.MatchClips>` block, pass `themePrefs = themePrefs` to the screen call. Pass it from `MainActivity` too: change the call to `AuthGate(rally = app.rally, themePrefs = app.themePrefs)`.

(Also add the import: `import com.badmintontracker.android.data.ThemePreferenceRepository`.)

**Step 3: Update `ClipListScreen` signature & `TopAppBar`**

In `ClipListScreen.kt`:

```kotlin
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ClipListScreen(
    vm:           ClipListViewModel,
    media:        MediaRepository,
    themePrefs:   ThemePreferenceRepository,
    onMatchClick: (MatchSummary) -> Unit,
) {
    // …
    val themeMode by themePrefs.mode.collectAsStateWithLifecycle()
    // …

    TopAppBar(
        title = { Text("MATCHES", style = MaterialTheme.typography.labelSmall.copy(fontSize = 14.sp)) },
        actions = {
            ThemeToggleButton(
                mode = themeMode,
                onToggle = {
                    themePrefs.set(if (themeMode == ThemeMode.LIGHT) ThemeMode.DARK else ThemeMode.LIGHT)
                },
            )
            IconButton(onClick = { menuOpen = true }) { Icon(Icons.Default.MoreVert, contentDescription = "Menu") }
            // existing dropdown unchanged
        },
    )
}
```

(Add the imports: `androidx.lifecycle.compose.collectAsStateWithLifecycle` is already there. Add `androidx.compose.ui.unit.sp`, `com.badmintontracker.android.data.ThemeMode`, `com.badmintontracker.android.data.ThemePreferenceRepository`, `com.badmintontracker.android.ui.components.ThemeToggleButton`.)

**Step 4: Update `MatchClipsScreen` signature & `TopAppBar`**

Same shape as above — add `themePrefs` parameter, place `ThemeToggleButton` first in `actions`, set the title to the uppercased match identifier (or just `"RALLIES"` if no match name is available — keep whatever the current title is, just uppercase it and use `labelSmall.copy(fontSize = 14.sp)`).

**Step 5: Build & verify**

Run: `./gradlew :androidApp:assembleDebug :androidApp:testDebugUnitTest`
Expected: BUILD SUCCESSFUL, all tests pass.

Install on emulator. Tap toggle on the Matches screen — colors swap. Force-stop the app and relaunch — preference persists.

**Step 6: Commit**

```bash
git add androidApp/src/main/java/com/badmintontracker/android/ui/components/ThemeToggleButton.kt \
        androidApp/src/main/java/com/badmintontracker/android/AuthGate.kt \
        androidApp/src/main/java/com/badmintontracker/android/MainActivity.kt \
        androidApp/src/main/java/com/badmintontracker/android/cliplist/
git commit -m "$(cat <<'EOF'
feat(androidApp): theme toggle in match list app bar

Adds ThemeToggleButton and plumbs ThemePreferenceRepository through
AuthGate into ClipListScreen + MatchClipsScreen. Title styling moves
to uppercase tracked labelSmall.

Co-Authored-By: Claude Opus 4.7 (1M context) <noreply@anthropic.com>
EOF
)"
```

---

## Task 6: Restyle SignInScreen to SHUTTL. login layout

**Files:**
- Modify: `androidApp/src/main/java/com/badmintontracker/android/signin/SignInScreen.kt` (full rewrite)
- Modify: `androidApp/src/main/java/com/badmintontracker/android/AuthGate.kt` (pass `themePrefs` to `SignInScreen`)

**Step 1: Rewrite `SignInScreen.kt`**

Reference layout: `badminton-tracker/src/views/LoginView.vue`. Key visual elements: centered column max-width 400dp, "SHUTTL." wordmark, "alpha v1.9" gradient pill (non-clickable on Android), subtitle "Sign in to continue", `ShuttlCard` containing email/password fields, "Sign in" `ShuttlButton(Primary)`, optional `ErrorBanner`, `DividerWithText("or")`, "Continue with Google" `ShuttlButton(Secondary)` with leading multi-color G icon, hint text under card, fixed top-right `ThemeToggleButton`.

```kotlin
package com.badmintontracker.android.signin

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.badmintontracker.android.data.ThemeMode
import com.badmintontracker.android.data.ThemePreferenceRepository
import com.badmintontracker.android.ui.components.DividerWithText
import com.badmintontracker.android.ui.components.ErrorBanner
import com.badmintontracker.android.ui.components.ShuttlButton
import com.badmintontracker.android.ui.components.ShuttlButtonVariant
import com.badmintontracker.android.ui.components.ShuttlCard
import com.badmintontracker.android.ui.components.ShuttlFieldType
import com.badmintontracker.android.ui.components.ShuttlOutlinedTextField
import com.badmintontracker.android.ui.components.ThemeToggleButton
import com.badmintontracker.android.ui.theme.ShuttlTheme

@Composable
fun SignInScreen(
    vm:          SignInViewModel,
    themePrefs:  ThemePreferenceRepository,
    onSignedIn:  () -> Unit,
) {
    val state     by vm.state.collectAsStateWithLifecycle()
    val themeMode by themePrefs.mode.collectAsStateWithLifecycle()

    LaunchedEffect(Unit) {
        vm.events.collect { if (it is SignInEvent.SignedIn) onSignedIn() }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .padding(horizontal = 16.dp, vertical = 32.dp),
    ) {
        ThemeToggleButton(
            mode     = themeMode,
            onToggle = {
                themePrefs.set(if (themeMode == ThemeMode.LIGHT) ThemeMode.DARK else ThemeMode.LIGHT)
            },
            modifier = Modifier.align(Alignment.TopEnd),
        )

        Column(
            modifier = Modifier
                .align(Alignment.Center)
                .widthIn(max = 400.dp)
                .verticalScroll(rememberScrollState()),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Brand()
            Spacer(Modifier.height(8.dp))
            Text(
                "Sign in to continue",
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                style = MaterialTheme.typography.bodySmall,
            )
            Spacer(Modifier.height(24.dp))

            ShuttlCard(modifier = Modifier.fillMaxWidth()) {
                Column {
                    ShuttlOutlinedTextField(
                        value         = state.email,
                        onValueChange = vm::onEmailChange,
                        label         = "Email",
                        type          = ShuttlFieldType.Email,
                        enabled       = !state.isSubmitting,
                        modifier      = Modifier.fillMaxWidth(),
                    )
                    Spacer(Modifier.height(16.dp))
                    ShuttlOutlinedTextField(
                        value         = state.password,
                        onValueChange = vm::onPasswordChange,
                        label         = "Password",
                        type          = ShuttlFieldType.Password,
                        enabled       = !state.isSubmitting,
                        modifier      = Modifier.fillMaxWidth(),
                    )
                    Spacer(Modifier.height(16.dp))
                    ShuttlButton(
                        text     = if (state.isSubmitting) "Signing in…" else "Sign in",
                        onClick  = vm::submitEmail,
                        enabled  = !state.isSubmitting,
                        loading  = state.isSubmitting,
                        modifier = Modifier.fillMaxWidth(),
                    )

                    if (state.error != null) {
                        Spacer(Modifier.height(16.dp))
                        ErrorBanner(state.error!!)
                    }

                    DividerWithText("or")

                    ShuttlButton(
                        text     = "Continue with Google",
                        onClick  = vm::submitGoogle,
                        variant  = ShuttlButtonVariant.Secondary,
                        enabled  = !state.isSubmitting,
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            }

            Spacer(Modifier.height(24.dp))
            Text(
                text  = "Registration is closed. Contact the admin if you need an account.",
                color = ShuttlTheme.extended.textTertiary,
                style = MaterialTheme.typography.bodySmall,
                textAlign = TextAlign.Center,
                modifier  = Modifier.widthIn(max = 320.dp),
            )
        }
    }
}

@Composable
private fun Brand() {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(
            "SHUTTL.",
            color         = MaterialTheme.colorScheme.onBackground,
            fontWeight    = FontWeight.Bold,
            fontSize      = 24.sp,
            letterSpacing = (-0.24).sp,   // ~ -0.01em at 24sp
        )
        Spacer(Modifier.padding(start = 8.dp))
        AlphaBadge()
    }
}

@Composable
private fun AlphaBadge() {
    val gradient = Brush.linearGradient(
        colors = listOf(
            MaterialTheme.colorScheme.primary,
            ShuttlTheme.extended.accentDark,
        ),
    )
    Box(
        modifier = Modifier
            .background(gradient)
            .padding(horizontal = 8.dp, vertical = 3.dp),
    ) {
        Text(
            text          = "ALPHA V1.9",
            color         = Color.Black,
            fontWeight    = FontWeight.SemiBold,
            fontSize      = 9.sp,
            letterSpacing = 0.5.sp,
        )
    }
}
```

**Step 2: Pass `themePrefs` from `AuthGate.kt`**

In the `composable<Route.SignIn>` block, change the `SignInScreen` call to include `themePrefs = themePrefs`:

```kotlin
SignInScreen(
    vm         = signInVm,
    themePrefs = themePrefs,
    onSignedIn = { /* unchanged */ },
)
```

**Step 3: Build & verify**

Run: `./gradlew :androidApp:assembleDebug :androidApp:testDebugUnitTest`
Expected: BUILD SUCCESSFUL, all tests pass.

Install. Sign out (if needed) and inspect the login screen visually next to `LoginView.vue` in a browser. Toggle theme via top-right button — should persist across kill/relaunch.

**Step 4: Commit**

```bash
git add androidApp/src/main/java/com/badmintontracker/android/signin/SignInScreen.kt \
        androidApp/src/main/java/com/badmintontracker/android/AuthGate.kt
git commit -m "$(cat <<'EOF'
feat(androidApp): SHUTTL. sign-in screen

Replaces the default Material 3 form with the SHUTTL. brand layout:
wordmark + alpha pill, framed card, sharp-corner inputs/buttons,
divider-with-text, ErrorBanner, top-right theme toggle.

Co-Authored-By: Claude Opus 4.7 (1M context) <noreply@anthropic.com>
EOF
)"
```

---

## Task 7: Restyle list rows + ClipDetail retry button

**Files:**
- Modify: `androidApp/src/main/java/com/badmintontracker/android/cliplist/ClipListScreen.kt` (the `MatchRow` and `ClipRow` composables — typography & padding only)
- Modify: `androidApp/src/main/java/com/badmintontracker/android/clipdetail/ClipDetailScreen.kt` (retry overlay button)

**Step 1: Tighten row styling**

In `ClipListScreen.kt`, update both `MatchRow` and `ClipRow`:

- Change row padding from `padding(12.dp)` to `padding(horizontal = 16.dp, vertical = 14.dp)`.
- Change subtitle line ("N rallies · date" or "Ns · N notes") from `style = MaterialTheme.typography.bodySmall` to `style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant`. Uppercase the *whole* subtitle string with `.uppercase()`.
- Title (`titleMedium`) gets `color = MaterialTheme.colorScheme.onBackground` so it pulls the heading shade.

**Step 2: Update `ClipDetailScreen.kt` retry button**

Locate the retry overlay (look for "retry" or "error" inside `ClipDetailScreen.kt`). Replace the existing M3 `Button` with `ShuttlButton(text = "Retry", onClick = ..., variant = ShuttlButtonVariant.Primary)`. Keep the `Box` overlay positioning unchanged.

(If the retry button uses `OutlinedButton` instead of `Button`, swap to `ShuttlButton(variant = ShuttlButtonVariant.Secondary)`.)

**Step 3: Build & run all tests**

Run: `./gradlew :androidApp:assembleDebug :androidApp:testDebugUnitTest :shared:allTests`
Expected: BUILD SUCCESSFUL, all tests pass.

**Step 4: End-to-end visual sweep**

Install and walk every screen with the web app open side-by-side:
- Sign-in screen (light + dark)
- Matches list (light + dark; toggle persists)
- Match clips
- Clip detail (force a load failure if practical and confirm the retry overlay reads as SHUTTL.-styled)

Confirm: no rounded corners anywhere except the M3 dropdown menu and snackbar (acceptable — they're transient overlays). Pull-to-refresh indicator is accent green. App bar titles are uppercase tracked. List row subtitles are uppercase tracked.

**Step 5: Commit**

```bash
git add androidApp/src/main/java/com/badmintontracker/android/cliplist/ClipListScreen.kt \
        androidApp/src/main/java/com/badmintontracker/android/clipdetail/ClipDetailScreen.kt
git commit -m "$(cat <<'EOF'
style(androidApp): SHUTTL. row & retry-button styling

Match/Clip rows: 16/14 padding, uppercase tracked labelSmall subtitles,
heading-color titles. ClipDetail retry button → ShuttlButton.

Co-Authored-By: Claude Opus 4.7 (1M context) <noreply@anthropic.com>
EOF
)"
```

---

## Done criteria

- All 7 commits land on `main`.
- `./gradlew :androidApp:assembleDebug :androidApp:testDebugUnitTest` is green.
- Visually, every screen reads as the same product as the web app (sharp corners, monochrome + green accent, uppercase tracked labels, light by default, toggle persists).
- No new files in `docs/`, `androidApp/src/main/res/values/`, or anywhere outside `ui/`, `data/`, `signin/`, `cliplist/`, `clipdetail/`, `RallyAndroidApp.kt`, `MainActivity.kt`, `AuthGate.kt`.
