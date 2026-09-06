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
    let progress: AnalyzeProgress?
    /// Pushes `LocalPlayerRoute` on the host's behalf. A plain `Button` rather
    /// than `NavigationLink(value:)`: the host owns the destination as an
    /// `item:` binding now (Home owns every destination this row's list used
    /// to carry), so the route is reported upward instead of resolved through
    /// the navigation environment.
    let onTap: () -> Void
    let onAnalyze: () -> Void
    let onRemove: () -> Void
    let onEditDetails: () -> Void

    private var subtitle: String { localVideoSubtitle(entry) }

    var body: some View {
        Button(action: onTap) {
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
                    Text(entry.title ?? entry.displayName)
                        .shuttlType(ShuttlType.titleMedium)
                        .foregroundStyle(Shuttl.text)
                        .lineLimit(1)
                    Text(subtitle)
                        .shuttlType(ShuttlType.labelSmall)
                        .foregroundStyle(Shuttl.textSecondary)
                        .lineLimit(2)
                    if let status = LocalVideoStatus.text(
                        stage: entry.stage,
                        uploadProgress: progress?.uploadProgress?.floatValue,
                        pipelineProgress: progress?.pipelineProgress?.floatValue
                    ) {
                        Text(status)
                            .shuttlType(ShuttlType.bodySmall)
                            .foregroundStyle(Shuttl.textSecondary)
                    }
                }
                Spacer()
                if LocalVideoStatus.canAnalyze(stage: entry.stage) {
                    Button(LocalVideoStatus.analyzeButtonLabel(stage: entry.stage)) { onAnalyze() }
                        .shuttlType(ShuttlType.labelMedium)
                        .foregroundStyle(Shuttl.onAccent)
                        .lineLimit(1)
                        .fixedSize(horizontal: true, vertical: false)
                        .layoutPriority(1)
                        .padding(.horizontal, 12)
                        .padding(.vertical, 6)
                        .background(Shuttl.accent)
                        .clipShape(Capsule())
                        .buttonStyle(.borderless)
                } else if LocalVideoStatus.isRunning(stage: entry.stage) {
                    // Settled stages (e.g. ANALYZED) show neither button nor
                    // spinner — the status text already says what happened.
                    ProgressView()
                        .controlSize(.small)
                }
                // The menu renders when either action applies; each item is gated
                // on its own rule, so a mid-pipeline row shows no menu at all.
                if LocalVideoStatus.canRemove(stage: entry.stage)
                    || LocalVideoStatus.canEditDetails(stage: entry.stage) {
                    Menu {
                        if LocalVideoStatus.canEditDetails(stage: entry.stage) {
                            Button("Edit details") { onEditDetails() }
                        }
                        if LocalVideoStatus.canRemove(stage: entry.stage) {
                            Button("Remove from app", role: .destructive) { onRemove() }
                        }
                    } label: {
                        Image(systemName: "ellipsis")
                    }
                    .buttonStyle(.borderless)
                    .accessibilityLabel("Local video menu")
                }
                // NavigationLink drew this for free; a Button does not, so it is
                // restored explicitly to keep the row reading as navigable.
                Image(systemName: "chevron.right")
                    .shuttlType(ShuttlType.bodySmall)
                    .foregroundStyle(Shuttl.textSecondary)
            }
            // Without this, the Button's hit area is only its children's -
            // the Spacer in the middle has none of its own - so the empty
            // stretch between the text and the trailing control would go dead
            // and silently stop opening the video. NavigationLink gave the
            // whole row a hit area for free; a Button does not.
            .contentShape(Rectangle())
        }
        .buttonStyle(.plain)
    }
}

extension LocalVideoEntry: Identifiable {}
