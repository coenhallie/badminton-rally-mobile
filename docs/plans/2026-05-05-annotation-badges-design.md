# Design: Shot-quality badges on annotations

**Date:** 2026-05-05
**Status:** Approved, ready for implementation plan

## Goal

Let users tag rally annotations with one of three badminton-specific shot-quality labels — **Good shot**, **Forced error**, **Unforced error** — rendered as a colored pill on each annotation row. Plain free-text annotations remain supported.

## Scope

In:
- New optional `kind` field on `RallyAnnotation` (one of three values, or null).
- DB migration adding the column + CHECK constraints.
- AnnotationRow renders a leading colored pill when `kind != null`.
- AddAnnotationDialog gets a chip selector above the text field; body is now optional.
- Repository + ViewModel signature updates and unit tests.

Out:
- Editing existing annotations (delete-and-readd to change a badge).
- Multiple badges per annotation.
- Filtering or stats on badges.
- iOS UI (shared model only; iOS surface is out of scope here).

## Decisions

| Question | Choice |
| --- | --- |
| Badges per annotation | One (optional) |
| Body | Now optional, but at least one of body/kind required |
| Picker UX | Three chips inside the existing Add dialog, single Add button |
| Editing existing annotations | Not in this change |
| Pill style | Solid filled, color-coded, label inside (green / amber / red) |

## Data model

```kotlin
@Serializable
enum class AnnotationKind {
    @SerialName("good_shot")       GOOD_SHOT,
    @SerialName("forced_error")    FORCED_ERROR,
    @SerialName("unforced_error")  UNFORCED_ERROR,
}

@Serializable
data class RallyAnnotation(
    val id: String,
    @SerialName("clip_id")           val clipId: String,
    @SerialName("timestamp_seconds") val timestampSeconds: Float,
    val body: String,                            // may be empty
    val kind: AnnotationKind? = null,            // NEW
    @SerialName("created_at")        val createdAt: Instant,
)
```

`kind` defaults to null so existing JSON rows decode unchanged.

## Database migration

Add a Supabase migration under `supabase/migrations/`:

1. `alter table rally_annotations add column kind text` (nullable).
2. `check (kind in ('good_shot','forced_error','unforced_error'))` allowing NULL.
3. `check (kind is not null or length(trim(body)) > 0)` — at least one of body/kind is present.
4. Drop any existing NOT NULL on `body` if present.

## Repository

`AnnotationsRepositoryImpl.NewAnnotationRow` gains `kind: String?` (SerialName-encoded). `add()` signature becomes:

```kotlin
suspend fun add(
    clipId: String,
    timestampSeconds: Float,
    body: String,
    kind: AnnotationKind?,
): Result<RallyAnnotation>
```

`FakeAnnotationsRepository` mirrors the new signature.

## UI: AnnotationRow

Layout: `[pill] [body, weight=1f] [delete icon]`.

- `kind == null` → no pill, body renders alone (current behavior).
- `body.isBlank()` → row is just the pill + delete icon.
- Pill colors (filled chip, white label):
  - Good shot — green (`#2E7D32` light / brighter on dark)
  - Forced error — amber (`#B26A00`)
  - Unforced error — red (`#C62828`)
- Colors and labels live in an `AnnotationKindStyle` helper alongside `AnnotationRow` so light/dark theming is centralized.
- Tap-to-seek behavior unchanged.

## UI: AddAnnotationDialog

A row of three selectable chips above the text field, in order: Good shot, Forced error, Unforced error.

- Tapping the already-selected chip deselects it ("no badge" path).
- Selected chip uses the same color as the pill; unselected is a neutral outlined chip.
- Text field is optional. Placeholder still "Note".
- Add button enabled when `selectedKind != null || body.isNotBlank()`.
- Dialog returns `(body: String, kind: AnnotationKind?)`.

## ViewModel

`ClipDetailViewModel.addAnnotation` signature becomes `(timestampSeconds: Float, body: String, kind: AnnotationKind?)`. Validation lives in the dialog; the VM just forwards to the repository and refreshes.

## Tests

- `RallyAnnotationSerializationTest` — round-trip each `kind` value and `null`; confirm null kind is omitted on encode and accepted on decode.
- `AnnotationsRepositoryTest` and `FakeAnnotationsRepository` — `add` persists `kind`; existing call sites pass `kind = null`.
- `ClipDetailViewModelTest` — new case: add succeeds with empty body and a non-null kind. Existing add-with-body test updated to pass `kind = null`.
- No Compose UI tests (consistent with current repo conventions).

## Backward compatibility

- Existing annotations have `kind = null` and non-empty `body`; both DB and Kotlin decode them unchanged.
- The CHECK constraint is satisfied for all existing rows because their body is non-empty.
- The Kotlin model defaults `kind` to null, so any older serialized payload without the field decodes cleanly.
