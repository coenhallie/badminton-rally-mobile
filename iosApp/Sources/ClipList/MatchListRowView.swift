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
/// wins.
private struct MatchThumbnail: View {
    let item: MatchListItem
    let thumbnails: LocalThumbnails
    let media: MediaRepository

    var body: some View {
        let shape = RoundedRectangle(cornerRadius: ShuttlRadius.small)
        Group {
            if let entryId = item.entryId, let uri = item.localUri {
                Group {
                    if let image = thumbnails.images[entryId] {
                        Image(uiImage: image).resizable().aspectRatio(contentMode: .fill)
                    } else {
                        // A thumbnail that has not decoded yet would otherwise
                        // be a card-coloured hole; this reads as an empty slot.
                        Shuttl.border
                    }
                }
                .task(id: entryId) { await thumbnails.load(id: entryId, relativePath: uri) }
            } else if let cover = item.coverClip {
                CloudThumbnail(clip: cover, media: media)
            } else {
                // A tile, not a slot: nothing is loading into it. On the raised
                // card bgTertiary would be the card itself, so the tile takes
                // the page colour there to still read as a tile.
                ZStack {
                    shape.fill(item.isRaised ? Shuttl.bg : Shuttl.bgTertiary)
                    shape.strokeBorder(Shuttl.borderSecondary, lineWidth: 1)
                    if let score = item.scoreText {
                        Text(score)
                            .shuttlType(ShuttlType.labelMedium)
                            .monospacedDigit()
                            .foregroundStyle(Shuttl.text)
                            .lineLimit(1)
                    } else {
                        Image(systemName: "number")
                            .resizable()
                            .scaledToFit()
                            .foregroundStyle(Shuttl.textSecondary)
                            .frame(width: 16, height: 16)
                    }
                }
            }
        }
        .frame(width: DrawerList.thumbWidth, height: DrawerList.thumbHeight)
        .clipShape(shape)
    }
}

/// The cover clip's thumbnail. The signed URL is cached by the repository for
/// its lifetime, so the same picture keeps the same URL and `AsyncImage`'s
/// URL cache serves it again rather than fetching it on every appearance.
private struct CloudThumbnail: View {
    let clip: RallyClip
    let media: MediaRepository
    @State private var url: URL? = nil

    var body: some View {
        AsyncImage(url: url) { phase in
            if let image = phase.image {
                image.resizable().aspectRatio(contentMode: .fill)
            } else {
                Shuttl.border
            }
        }
        .task(id: clip.thumbnailStoragePath) {
            // Never throws in practice: a thumbnail is decorative, and the
            // repository returns nil for anything that goes wrong.
            let signed = try? await media.signedThumbnailUrl(clip: clip)
            url = signed.flatMap(URL.init(string:))
        }
    }
}
