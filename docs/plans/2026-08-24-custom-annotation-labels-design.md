# Design: Custom annotation labels

**Date:** 2026-08-24
**Status:** Draft, pending review

## Goal

Replace the three hardcoded shot-quality badges with a label set the user owns:
create, rename, recolour and delete their own labels, then apply them to
annotations on both cloud clips and local videos.

`AnnotationKind` is a three-case serialized enum today, enumerated in a DB CHECK
constraint, in three literal chip calls per platform, and in two hand-tuned
colour tables. This replaces it with user-owned rows.

The app has no real users yet, so this is a clean cutover. `AnnotationKind` is
deleted outright: no legacy column, no dual read path, no compatibility shims.
That decision is what keeps this change small.

## Scope

In:
- `annotation_labels` table, owner scoped, seeded with the current three.
- A Labels screen for management, reached from the ClipList overflow menu.
- Inline label creation from the "Add note" sheet.
- Label name and colour snapshotted onto each annotation, cloud and local.
- Curated colour palette shared by both platforms, theme independent.

Out:
- `AnnotationKind`, deleted along with every reference to it.
- Multiple labels per annotation. Stays one, as the original badge design decided.
- Editing an existing annotation's label. Delete and re-add, unchanged.
- Filtering or statistics by label.
- Manual reordering. Labels sort by creation order.
- Sharing a label set between users.

## Decisions

| Question | Choice |
| --- | --- |
| Config page, or inside the label picker? | Both. A Labels screen owns management; the picker owns creation. |
| What does a share recipient see? | A name and colour snapshotted onto the annotation row. |
| Fate of the three built-ins | Seeded as ordinary editable rows. No `is_builtin` flag. |
| Colour choice | Curated palette of ten swatches, each a fill with a foreground verified against it. |
| Colour stored on the annotation | The palette key, not a hex value. |
| Inline creation | Name only, colour auto-assigned. Recolour on the Labels screen. |
| Delete semantics | Label leaves the picker; tagged annotations keep their badge. |
| Legacy `kind` column | Dropped. No users to migrate. |

## Why both surfaces

Neither surface exists today, so the choice is genuinely open. The two jobs pull
in opposite directions.

Management needs room and consequences. Deleting a label has to explain that
annotations already tagged keep their badge; rename and recolour want a list you
can scan. That is a screen, not something you do mid-rally.

Creation needs to happen where the need appears. You discover you want "Net kill"
while watching the clip that needs it. A detour through settings at that moment
kills the feature.

So: a Labels screen as the source of truth, and a `+ New label` chip in the
picker that creates a label and immediately selects it. One repository behind
both, so the picker never holds state the screen does not.

## Mobile UI constraints

No modal carries substantial content. Concretely:

- The Labels screen is a pushed route, not a sheet.
- Editing happens in place. Tapping a row expands it to reveal a name field and
  the swatch grid. No nested editor screen, no nested modal.
- Inline creation adds exactly one text field to the existing "Add note" sheet.
  The colour is auto-assigned, so the swatch grid never appears inside a sheet.
- Delete follows the existing convention: `SwipeToRemoveRow` on Android,
  `.swipeActions(edge: .trailing)` on iOS, with a confirm dialog.

The Labels screen is a plain list on the app background: a colour dot, the name,
and nothing else per row. The expanded state adds a single-line text field and
one row of swatches. No cards, no section headers, no counts.

## Data model

```kotlin
@Serializable
data class AnnotationLabel(
    val id: String,
    val name: String,
    @SerialName("color_key")  val colorKey: String,
    @SerialName("created_at") val createdAt: Instant,
)

/**
 * The offered colours, shared so Android, iOS and the DB CHECK agree on one set.
 * Each key resolves to a background and a foreground at render time.
 */
enum class LabelColor(val key: String) {
    GREEN("green"),   TEAL("teal"),     BLUE("blue"),
    INDIGO("indigo"), PURPLE("purple"), PINK("pink"),
    RED("red"),       ORANGE("orange"), AMBER("amber"),
    SLATE("slate"),
    ;
    companion object {
        fun from(key: String?): LabelColor? = entries.firstOrNull { it.key == key }
    }
}
```

`RallyAnnotation` and `LocalAnnotation` both swap `kind` for the snapshot:

```kotlin
@SerialName("label_name")  val labelName: String? = null,
@SerialName("label_color") val labelColor: String? = null,
```

Colour crosses serialization as a `String`, not as `LabelColor`. kotlinx throws
on an unknown enum value, so a snapshot naming a swatch later dropped from the
palette would make the whole annotation undecodable. `LabelColor.from(key)`
resolves it at render time and returns null for anything unrecognised, which
renders the neutral chip. That is one companion function, and it removes a crash
class for the lifetime of the palette.

### Why a snapshot rather than a foreign key

A recipient of a shared match can already see the sharer's annotations. With a
foreign key, resolving "Net kill" would need a new RLS policy letting one user
read another's label rows through `match_shares`, plus a rule for what happens to
annotations when the label is deleted.

The snapshot removes both problems: no cross-user read, and a deleted label
cannot orphan anything. The cost is that renaming a label does not retitle
annotations already carrying the old name. Rename is therefore a typo fix, not a
recategorisation, and the Labels screen should not imply otherwise.

### Why the palette key rather than a hex value

A hex snapshot freezes a single colour into the row with no matching foreground.
The current badges show why that is not enough: `forcedError` is black on amber
while the others are white on their background, because each pair was tuned by
hand. Snapshotting the key keeps the pair a render-time decision, so the palette
can be retuned later without rewriting stored rows, and constrains every badge to
a combination that has been checked.

The palette itself is theme independent. A solid saturated fill with a fixed
foreground reads correctly on both the light (`#FFFFFF`) and dark (`#0D0D0D`) app
backgrounds, which is already true of the three badges shipping today, so no
per-theme variant is needed. Resolution happens at render time regardless, so
splitting the palette by theme later costs one function.

## Database migration: `supabase/migrations/20260824000000_annotation_labels.sql`

```sql
create table public.annotation_labels (
    id         uuid primary key default gen_random_uuid(),
    owner_id   uuid not null default auth.uid()
               references auth.users(id) on delete cascade,
    name       text not null check (length(trim(name)) between 1 and 24),
    color_key  text not null check (color_key in (
                   'green','teal','blue','indigo','purple','pink','red','orange','amber','slate')),
    created_at timestamptz not null default now()
);

create unique index annotation_labels_owner_name_key
    on public.annotation_labels (owner_id, lower(name));
```

RLS: owner only, on all four verbs.

Seeding is DB side. A trigger on `auth.users` insert writes the three defaults
for each new account, and the migration backfills existing accounts once. The
client-side alternative, "if the list is empty, insert the defaults", would
resurrect the built-ins for anyone who deliberately deleted them all.

The trigger must set `owner_id = NEW.id` explicitly and be `security definer`.
Inside an `auth.users` insert there is no authenticated session, so the column's
`default auth.uid()` yields NULL, the `not null` fires, and the signup itself
rolls back. Leaning on the default here breaks account creation.

`rally_annotations` is cut over rather than extended:

```sql
alter table public.rally_annotations
    add column label_name  text,
    add column label_color text;

-- Backfill before the column goes, or the CHECK below rejects kind-only rows.
update public.rally_annotations set
    label_name  = case kind when 'good_shot'      then 'Good shot'
                            when 'forced_error'   then 'Forced error'
                            when 'unforced_error' then 'Unforced error' end,
    label_color = case kind when 'good_shot'      then 'green'
                            when 'forced_error'   then 'amber'
                            when 'unforced_error' then 'red' end
where kind is not null;

alter table public.rally_annotations
    drop constraint if exists rally_annotations_body_or_kind_check,
    drop constraint if exists rally_annotations_kind_check,
    drop column if exists kind;

alter table public.rally_annotations
    add constraint rally_annotations_body_or_label_check
    check (
        (label_name is not null and length(trim(label_name)) > 0)
        or (body is not null and length(trim(body)) > 0)
    );
```

The backfill is not optional and not merely courteous. `ADD CONSTRAINT` validates
existing rows on the spot, and the 2026-05-05 migration dropped `body`'s NOT NULL
precisely so a badge-only annotation could exist. Any such row would have a null
`body` and a null `label_name`, violate the new CHECK, and abort the migration.
Backfilling first also preserves the badges already on development data, which
costs four lines.

Dropping the column is still the cutover: nothing reads `kind` afterwards.
`20260505000000_annotation_kind.sql` stays in place unedited; this migration
supersedes it.

Existing on-device files are safe. `LocalAnnotationsRepository.kt:20` already
constructs its decoder as `Json { ignoreUnknownKeys = true }`, so a file still
carrying `"kind"` decodes cleanly with a null label. Those local badges are not
backfilled, since a one-off rewrite of every file on disk is not worth it for
development data; the note and timestamp survive.

## Repository

```kotlin
interface AnnotationLabelsRepository {
    val labels: StateFlow<List<AnnotationLabel>>
    suspend fun refresh(): Result<Unit>
    suspend fun create(name: String, color: LabelColor?): Result<AnnotationLabel>
    suspend fun rename(id: String, name: String): Result<Unit>
    suspend fun recolor(id: String, color: LabelColor): Result<Unit>
    suspend fun delete(id: String): Result<Unit>
}
```

`create` with a null colour, which is what the inline path always passes, takes
the first swatch not already in use. Past ten labels every swatch is taken, so it
falls back to `palette[labelCount % 10]` rather than returning null.

The last known list is cached through `Settings`, the same mechanism the
preference repositories use. The local video player is offline by definition and
still needs a picker; since local annotations carry the same snapshot, a cached
list is all it needs.

## Platform wiring

| Layer | Change |
| --- | --- |
| shared | Add `AnnotationLabel`, `LabelColor`, `AnnotationLabelsRepository`. Delete `AnnotationKind`. Snapshot fields on both annotation models; `add(...)` takes an `AnnotationLabel?` and stores its `name` and `colorKey` into `label_name` and `label_color`. |
| Android | `Route.Labels`; `labels/LabelsScreen` + `LabelsViewModel`; overflow menu entry; `AnnotationUi` chips driven by the list; `AnnotationKindStyle` becomes `LabelStyle`, resolving a key to theme colours. |
| iOS | `Labels/LabelsView` + `LabelsModel`; `KindBadge` becomes `LabelBadge`; `AddAnnotationSheet` chips driven by the list; pbxproj regenerated with xcodegen. |

Deleting `AnnotationKind` is what makes this tractable: the compiler lists every
site that needs attention, on Android exhaustively and on iOS at each `switch`.

Two existing defects disappear as a consequence rather than as extra work:

- `KindBadge`'s `default:` branch renders an empty pill for an unrecognised kind,
  where Android's `when` is compiler checked. `LabelBadge` takes a name and a
  colour, so there is no unmatched case left to render silently.
- The chip list is duplicated as a literal on each platform. Driving both from
  the shared list removes the duplication by construction.

Not in blast radius: `MatchRowLabelsTest` and `MatchGrouping` concern the shared
match sharer label, an unrelated feature.

## Error handling

Label writes follow the existing pattern: `Result`, a short user-facing message,
no raw technical text. A failed create from the inline path leaves the sheet open
with the typed name intact rather than dismissing.

Offline behaviour is gated on connectivity, not on which player is open. A local
video lives on the device but the device itself may well be online, and reviewing
a match you just recorded is the surface where wanting a new label is most
likely. So both players offer creation whenever the label table is reachable.
While offline the cached list stays fully usable for tagging, and the
`+ New label` chip is hidden rather than shown failing.

## Testing

- `AnnotationLabelsRepositoryTest`: create, rename, recolour, delete, duplicate
  name rejection, first-unused-swatch selection, wraparound past ten labels.
- Serialization: `RallyAnnotation` and `LocalAnnotation` decode rows and files
  with and without the snapshot; a pre-change local file decodes with a null
  label.
- `LabelsViewModel` and `LabelsModel`: expand to edit, delete confirm, offline.
- Existing `ClipDetailViewModelTest` and `LocalPlayerViewModelTest` updated for
  the new add signature.
