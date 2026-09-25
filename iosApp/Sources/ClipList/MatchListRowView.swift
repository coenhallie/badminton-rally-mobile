import Shared
import SwiftUI

/// One entry in a row's overflow menu, in the order the menu lists them.
struct RowMenuItem: Identifiable {
    let label: String
    var role: ButtonRole? = nil
    let action: () -> Void
    var id: String { label }
}

/// One match in the drawer: thumbnail, title, detail, players, the status
/// line, then whatever the match is asking of the coach.
///
/// The drawer keeps exactly one raised surface, and since the list became one
/// list it marks "needs something from you" (`MatchListItem.isRaised`) rather
/// than "on this phone", which the status line now says. Every other row is
/// the quiet inset box, transparent until pressed. Mirrors androidApp's
/// MatchListRow.kt.
///
/// The text column is the row's inner width less the thumbnail, its gap and
/// the menu, with no gap of its own before the menu: the 44pt menu box is
/// trailing-aligned, so its glyph already sits well clear of the text. Measured
/// on the iPhone 17 Pro drawer: 250pt inside the row padding, 130pt for the
/// text. The old local card spent 24pt more on the gaps either side of a
/// Spacer, which left 106pt, too narrow for a full status line.
struct MatchListRowView: View {
    let item: MatchListItem
    let thumbnails: LocalThumbnails
    let media: MediaRepository
    let menu: [RowMenuItem]
    let onTap: () -> Void
    let onAction: () -> Void

    var body: some View {
        let shape = RoundedRectangle(cornerRadius: item.isRaised ? ShuttlRadius.large : ShuttlRadius.medium)
        Button(action: onTap) {
            VStack(alignment: .leading, spacing: 0) {
                HStack(spacing: 0) {
                    MatchThumbnail(item: item, thumbnails: thumbnails, media: media)
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
                if let label = actionLabel(item.action) {
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
        .accessibilityLabel("Match menu")
    }

    /// The button for what the row asks. A failed run is "Retry" even when it
    /// will resume without court marking: the caller decides what retrying does.
    private func actionLabel(_ action: MatchAction) -> String? {
        switch action {
        case .analyze: return "Analyze"
        case .retry: return "Retry"
        case .markCourt: return "Mark court"
        // Running and paused speak through the status line; nothing to press.
        case .none, .running, .paused: return nil
        }
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
    @State private var coverFailed = false

    var body: some View {
        let shape = RoundedRectangle(cornerRadius: ShuttlRadius.small)
        let tileFill = item.isRaised ? Shuttl.bg : Shuttl.bgTertiary
        let hasVideo = item.localUri != nil || item.coverClip != nil
        // `thumbnails.failed` is keyed by entry id and shared across every row
        // that draws this entry, the same cache `images` already is; a row
        // with no entry has no local frame to try in the first place.
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
                    // Reset when the cover itself changes, not just when it
                    // reappears with the same path: a cover lost while
                    // offline (`coverFailed` latched true) must get another
                    // try once a refresh brings back a signed URL for it,
                    // and this id is the only thing that changes when that
                    // happens - `coverFailed` itself is `@State` and survives
                    // across body re-evaluations of this same row on purpose.
                    .onChange(of: cover?.thumbnailStoragePath) { _, _ in coverFailed = false }
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
        .frame(width: DrawerList.thumbWidth, height: DrawerList.thumbHeight)
        .clipShape(shape)
    }
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
