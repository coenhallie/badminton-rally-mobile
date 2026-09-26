import Shared
import SwiftUI

/// One entry in a row's overflow menu, in the order the menu lists them.
struct RowMenuItem: Identifiable {
    let label: String
    var role: ButtonRole? = nil
    let action: () -> Void
    var id: String { label }
}

/// One match on the Matches tab's list: thumbnail, title, detail, players, the
/// status line, then whatever the match is asking of the coach.
///
/// The list keeps exactly one raised surface, and since it became one list it
/// marks "needs something from you" (`MatchListItem.isRaised`) rather
/// than "on this phone", which the status line now says. Every other row is
/// the quiet inset box, transparent until pressed. Mirrors androidApp's
/// MatchListRow.kt.
///
/// The text column is the row's inner width less the thumbnail, its gap and
/// the menu, with no gap of its own before the menu: the 44pt menu box is
/// trailing-aligned, so its glyph already sits well clear of the text. Sized
/// to fit the former 330pt drawer, measured there on the iPhone 17 Pro: 250pt
/// inside the row padding, 130pt for the text. The full-width Matches tab
/// gives the column more than that now. The old local card spent 24pt more on
/// the gaps either side of a Spacer, which left 106pt, too narrow for a full
/// status line.
struct MatchListRowView: View {
    let item: MatchListItem
    let thumbnails: LocalThumbnails
    let media: MediaRepository
    /// `ClipListModel.refreshGeneration`, threaded down to the thumbnail so a
    /// cover clip that failed to load gets another try on the next
    /// pull-to-refresh. See `MatchThumbnail.CoverRetryKey`.
    let refreshGeneration: Int
    let menu: [RowMenuItem]
    let onTap: () -> Void
    let onAction: () -> Void

    var body: some View {
        let shape = RoundedRectangle(cornerRadius: item.isRaised ? ShuttlRadius.large : ShuttlRadius.medium)
        Button(action: onTap) {
            VStack(alignment: .leading, spacing: 0) {
                HStack(spacing: 0) {
                    MatchThumbnail(item: item, thumbnails: thumbnails, media: media, refreshGeneration: refreshGeneration)
                        .padding(.trailing, DrawerList.gap)
                    VStack(alignment: .leading, spacing: 3) {
                        rowText(item.title, primary: true)
                        rowText(item.detail)
                        // Absent until somebody is named: "Home vs Away" on
                        // every row would say nothing.
                        if let players = item.playersLine { rowText(players) }
                        if let sharedBy = item.sharedBy { rowText(sharedBy) }
                        if item.hasStatusLine {
                            MatchStatusLine(item: item)
                                .padding(.top, 1)
                        }
                    }
                    .frame(maxWidth: .infinity, alignment: .leading)
                    if !menu.isEmpty { rowMenu }
                }
                if item.statusIsError, let failure = item.statusText {
                    Text(failure)
                        .shuttlType(ShuttlType.bodySmall)
                        .foregroundStyle(Shuttl.error)
                        // Two, not one: a pipeline's reason is a sentence, and
                        // cut to one line it loses the part that says what to do.
                        .lineLimit(2)
                        // Under the text column, not under the thumbnail.
                        .padding(.leading, DrawerList.thumbWidth + DrawerList.gap)
                        .padding(.top, 4)
                        .frame(maxWidth: .infinity, alignment: .leading)
                }
                if let label = MatchListCopy.shared.actionLabel(action: item.action) {
                    // Its own line, not a trailing pill: beside the overflow
                    // menu a pill left the title about four characters wide.
                    // Full width is also how Home states its two primary
                    // actions, so the card reads as one clear next step.
                    Button(label, action: onAction)
                        .shuttlType(ShuttlType.labelMedium)
                        .foregroundStyle(Shuttl.onAccent)
                        .lineLimit(1)
                        .frame(maxWidth: .infinity)
                        .padding(.vertical, 10)
                        .background(Shuttl.accent)
                        .clipShape(Capsule())
                        .contentShape(Capsule())
                        .buttonStyle(.borderless)
                        .padding(.top, DrawerList.gap)
                }
            }
            .padding(.horizontal, DrawerList.rowPaddingH)
            .padding(.vertical, DrawerList.rowPaddingV)
            .frame(maxWidth: .infinity, alignment: .leading)
            .background(item.isRaised ? Shuttl.bgTertiary : Color.clear, in: shape)
            // Without this, the Button's hit area is only its children's, and
            // the empty stretch beside a short title would go dead and silently
            // stop opening the match.
            .contentShape(shape)
        }
        .buttonStyle(.plain)
    }

    private func rowText(_ text: String, primary: Bool = false) -> some View {
        Text(text)
            .shuttlType(primary ? ShuttlType.titleMedium : ShuttlType.bodySmall)
            .foregroundStyle(primary ? Shuttl.text : Shuttl.textSecondary)
            .lineLimit(1)
    }

    private var rowMenu: some View {
        Menu {
            ForEach(menu) { entry in
                Button(entry.label, role: entry.role, action: entry.action)
            }
        } label: {
            Image(systemName: "ellipsis")
                .foregroundStyle(Shuttl.textSecondary)
                // The same 44x44 minimum every trailing control in this list
                // carries, trailing-aligned so the glyph lands the row's own
                // 16pt from the card's edge.
                .frame(width: 44, height: 44, alignment: .trailing)
                .contentShape(Rectangle())
        }
        .buttonStyle(.borderless)
        .accessibilityLabel(MatchListCopy.shared.MATCH_MENU)
    }
}

/// The file on this phone first (its own frame, no network), then the cloud's
/// cover clip, then the score, then a plain tile: the first source that exists
/// AND loads wins. A local frame that fails (the file removed, the codec
/// choking on it) falls through to the cover clip rather than leaving a hole
/// in a row that has a perfectly good cloud thumbnail.
///
/// Every state sits on the same tile: a fill one step off the row's own
/// surface and a 1pt ring, drawn behind whatever the tile shows. While a
/// picture loads the tile reads as an empty slot; once every source has
/// failed it carries the score or a glyph. On the raised card the fill is the
/// page colour: the card is `bgTertiary` itself, and a slot in
/// `borderSecondary` there nearly vanished in dark. Mirrors androidApp's
/// MatchThumbnail in MatchListRow.kt.
private struct MatchThumbnail: View {
    let item: MatchListItem
    let thumbnails: LocalThumbnails
    let media: MediaRepository
    /// `ClipListModel.refreshGeneration`. See `CoverRetryKey`.
    let refreshGeneration: Int
    @State private var coverFailed = false

    var body: some View {
        let shape = RoundedRectangle(cornerRadius: ShuttlRadius.small)
        let tileFill = item.isRaised ? Shuttl.bg : Shuttl.bgTertiary
        let hasVideo = item.localUri != nil || item.coverClip != nil
        // `thumbnails.failed` is keyed by entry id and shared across every row
        // that draws this entry, the same cache `images` already is; a row
        // with no entry has no local frame to try in the first place.
        //
        // Not reset by a refresh, unlike `coverFailed` below: this failure is
        // about a FILE ON THIS PHONE (missing, revoked permission, a codec
        // that cannot decode it), and `ClipListModel.refresh()` pulls the
        // cloud's clips, shares and score logs - none of which touches
        // anything on local disk. A file that is gone is still gone after a
        // pull-to-refresh; giving this the same generation-keyed retry as the
        // cover clip would just repeat a decode that fails for the same
        // reason every time.
        let localFailed = item.entryId.map { thumbnails.failed.contains($0) } ?? true
        let cover = item.coverClip
        let coverToLoad = coverFailed || cover?.thumbnailStoragePath == nil ? nil : cover

        ZStack {
            shape.fill(tileFill)
            shape.strokeBorder(Shuttl.borderSecondary, lineWidth: 1)
            if let entryId = item.entryId, let uri = item.localUri, !localFailed {
                // `Color.clear`, not a bare `if let image {}` with no `else`:
                // `.task` attached to a `Group` whose only content is the
                // `EmptyView` half of that condition never ran at all - no
                // load, no `failed` entry, no fallback - because SwiftUI
                // never gave that modifier a real view to hang its lifecycle
                // off. `Color.clear` is a real view in every frame, loaded or
                // not, so `.task` below fires reliably and the `Image` simply
                // draws over it once `thumbnails.images[entryId]` has one.
                ZStack {
                    Color.clear
                    if let image = thumbnails.images[entryId] {
                        Image(uiImage: image).resizable().aspectRatio(contentMode: .fill)
                    }
                }
                .task(id: entryId) { await thumbnails.load(id: entryId, relativePath: uri) }
            } else if let coverToLoad {
                CloudThumbnail(clip: coverToLoad, media: media, onFailed: { coverFailed = true })
            } else {
                switch thumbnailFallback(hasVideo: hasVideo, hasScore: item.scoreText != nil) {
                case .score:
                    Text(item.scoreText ?? "")
                        .shuttlType(ShuttlType.labelMedium)
                        .monospacedDigit()
                        .foregroundStyle(Shuttl.text)
                        .lineLimit(1)
                case .videoGlyph:
                    TileGlyph(systemName: "video")
                case .scoreGlyph:
                    TileGlyph(systemName: "number")
                }
            }
        }
        // Reset here, on the outer ZStack, not on `CloudThumbnail` above: that
        // view exists only while `coverToLoad != nil`, and `coverToLoad` is
        // nil exactly when `coverFailed` is true - the one moment this reset
        // is needed. An `.onChange` scoped to `CloudThumbnail` is torn down
        // the instant the cover fails, so it is not there to observe the
        // change that should undo the failure. The outer ZStack is mounted in
        // every state this view can be in, so it is always there to catch it.
        //
        // Keyed on `CoverRetryKey`, not on the path alone: `thumbnailStoragePath`
        // is a stable backend key for the clip, not a token for the fetch
        // attempt, so it does not change just because that attempt failed -
        // and the signed-URL cache remembers only successes, never a failure.
        // A coach who goes offline, fails once, then pulls to refresh once
        // back online would still see the SAME path on the SAME clip, with
        // nothing for a path-only `onChange` to fire on, and stay stuck on the
        // glyph tile forever despite doing exactly what the fallback asks.
        // `refreshGeneration` is the second half of the key for that reason:
        // it changes on every completed pull whether or not the path did, so
        // a failed cover gets one more try per refresh.
        .onChange(of: CoverRetryKey(path: cover?.thumbnailStoragePath, refreshGeneration: refreshGeneration)) { _, _ in
            coverFailed = false
        }
        .frame(width: DrawerList.thumbWidth, height: DrawerList.thumbHeight)
        .clipShape(shape)
    }
}

/// What decides whether a failed cover clip deserves another try: its own
/// storage path, paired with how many pulls-to-refresh have completed since.
/// A plain value type rather than the comparison inlined at the one
/// `.onChange` call site, so `CoverRetryKeyTests` can pin the two properties
/// that make this reset fire without needing a hosted view: `Equatable`'s
/// synthesized implementation already does the right thing on its own - two
/// keys differ, and the reset fires, the moment either the clip itself
/// changes (a different `path`) or a refresh has completed since the last
/// time this key was read (a different `refreshGeneration`), and agree, and
/// the reset stays silent, only when neither has.
struct CoverRetryKey: Equatable {
    let path: String?
    let refreshGeneration: Int
}

/// What a thumbnail tile shows once every picture has failed or there was
/// none. Port of Android's `ThumbnailFallback`.
enum ThumbnailFallback: Equatable {
    case score
    case videoGlyph
    case scoreGlyph
}

/// A row with a video whose picture would not load is still a video: it gets
/// the video glyph, never the score's "#", which would read as a scored
/// match. The score itself wins when there is one (the tile has room for
/// "21-18"), and "#" is left for a scored match nobody has started, with no
/// video at all. Port of Android's `thumbnailFallback`.
func thumbnailFallback(hasVideo: Bool, hasScore: Bool) -> ThumbnailFallback {
    switch (hasVideo, hasScore) {
    case (_, true): return .score
    case (true, false): return .videoGlyph
    case (false, false): return .scoreGlyph
    }
}

private struct TileGlyph: View {
    let systemName: String

    var body: some View {
        Image(systemName: systemName)
            .resizable()
            .scaledToFit()
            .foregroundStyle(Shuttl.textSecondary)
            .frame(width: 16, height: 16)
    }
}

/// The cover clip's thumbnail. The signed URL is cached by the repository for
/// its lifetime, so the same picture keeps the same URL and `AsyncImage`'s
/// URL cache serves it again rather than fetching it on every appearance.
/// `onFailed` fires once for a signed URL that never arrives and once for a
/// picture `AsyncImage` could not decode, so the tile above can fall through
/// to the score or a glyph either way.
private struct CloudThumbnail: View {
    let clip: RallyClip
    let media: MediaRepository
    let onFailed: () -> Void
    @State private var url: URL? = nil

    var body: some View {
        // `Color.clear` underneath, always present, for the same reason
        // `MatchThumbnail`'s local-frame branch needs one: `.task` below has
        // to be attached to a view that actually renders every time,
        // including the one frame before `url` exists at all, or it never
        // fires and the signed URL is never even asked for.
        ZStack {
            Color.clear
            if let url {
                AsyncImage(url: url) { phase in
                    switch phase {
                    case .success(let image):
                        image.resizable().aspectRatio(contentMode: .fill)
                    case .failure:
                        // Nothing drawn: the tile's own fill and ring show
                        // through, and the fallback below takes over.
                        Color.clear.onAppear(perform: onFailed)
                    case .empty:
                        Color.clear
                    @unknown default:
                        Color.clear
                    }
                }
            }
        }
        .task(id: clip.thumbnailStoragePath) {
            // Never throws in practice: a thumbnail is decorative, and the
            // repository returns nil for anything that goes wrong.
            let signed = try? await media.signedThumbnailUrl(clip: clip)
            if let signed, let parsed = URL(string: signed) {
                url = parsed
            } else {
                onFailed()
            }
        }
    }
}
