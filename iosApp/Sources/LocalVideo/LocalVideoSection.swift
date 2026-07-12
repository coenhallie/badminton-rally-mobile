import Shared
import SwiftUI

/// Navigation value for opening the local player (distinct from the String
/// destination used for remote match ids).
struct LocalPlayerRoute: Hashable {
    let entryId: String
}

struct LocalVideoRowView: View {
    let entry: LocalVideoEntry
    let thumbnails: LocalThumbnails
    let onRemove: () -> Void

    private var subtitle: String {
        let duration = LocalVideoLogic.formatDuration(ms: entry.durationMs)
        let date = formatMatchDate(millis: entry.addedAtEpochMs)
        return "\(duration) · \(date)".uppercased()
    }

    var body: some View {
        NavigationLink(value: LocalPlayerRoute(entryId: entry.id)) {
            HStack(spacing: 12) {
                Group {
                    if let image = thumbnails.images[entry.id] {
                        Image(uiImage: image).resizable().aspectRatio(contentMode: .fill)
                    } else {
                        Shuttl.bgTertiary
                    }
                }
                .frame(width: 96, height: 54)
                .clipped()
                .task { await thumbnails.load(for: entry) }

                VStack(alignment: .leading, spacing: 4) {
                    Text(entry.displayName)
                        .font(.body.weight(.medium))
                        .foregroundStyle(Shuttl.text)
                        .lineLimit(1)
                    Text(subtitle)
                        .font(.system(size: 11, weight: .medium))
                        .kerning(0.55)
                        .foregroundStyle(Shuttl.textSecondary)
                }
                Spacer()
                Menu {
                    Button("Remove from app", role: .destructive) { onRemove() }
                } label: {
                    Image(systemName: "ellipsis")
                }
                .buttonStyle(.borderless)
                .accessibilityLabel("Local video menu")
            }
        }
    }
}

extension LocalVideoEntry: Identifiable {}
