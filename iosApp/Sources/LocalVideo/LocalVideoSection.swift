import Shared
import SwiftUI

/// Navigation value for opening the local player (distinct from the String
/// destination used for remote match ids).
struct LocalPlayerRoute: Hashable {
    let entryId: String
}

/// The "On this phone" card.
///
/// The mock draws these as filled cards rather than list rows: this is the
/// shortest path from "I just filmed a match" to "analyze it", so it gets the
/// one raised surface in the drawer while the match rows below stay quiet.
/// Mirrors androidApp's LocalVideoRowItem.
struct LocalVideoRowView: View {
    let entry: LocalVideoEntry
    let thumbnails: LocalThumbnails
    let progress: AnalyzeProgress?
    /// What the on-device pipeline is doing with this video. Asked alongside the
    /// cloud stage rather than instead of it - see `LocalVideoStatus.canAnalyze`.
    var device: LocalAnalysisState = .idle
    /// Pushes `LocalPlayerRoute` on the host's behalf. A plain `Button` rather
    /// than `NavigationLink(value:)`: the host owns the destination as an
    /// `item:` binding now (Home owns every destination this row's list used
    /// to carry), so the route is reported upward instead of resolved through
    /// the navigation environment.
    let onTap: () -> Void
    let onAnalyze: () -> Void
    let onRemove: () -> Void
    let onEditDetails: () -> Void
    /// Opens the rallies an on-device run cut, or nil when it produced none.
    ///
    /// The clips used to be reachable only from the state of the run that made
    /// them, so they went with the process even though tens of megabytes of
    /// re-encoded video were on disk - half an hour of analysis with no way back
    /// to what it produced. Mirrors androidApp's `onOpenLocalClips`.
    var localClips: Int? = nil
    var onOpenLocalClips: (() -> Void)? = nil
    /// Opens the heatmap of a stored track, on the same footing and for the same
    /// reason. Nil when this entry has no track.
    var onOpenHeatmap: (() -> Void)? = nil

    private var subtitle: String { localVideoSubtitle(entry) }
    private var canAnalyze: Bool { LocalVideoStatus.canAnalyze(stage: entry.stage, device: device) }
    private var canRemove: Bool { LocalVideoStatus.canRemove(stage: entry.stage, device: device) }
    /// The menu renders when ANY of its four items applies; each is gated on its
    /// own rule, so a mid-pipeline row that can do none shows no menu at all.
    /// The two analysis items count: gating on remove and edit alone would hide
    /// the clips and the heatmap on exactly the rows most likely to have them.
    private var hasMenu: Bool {
        canRemove || LocalVideoStatus.canEditDetails(stage: entry.stage)
            || onOpenHeatmap != nil || (localClips != nil && onOpenLocalClips != nil)
    }
    /// A ring turns for work that is moving, on either pipeline.
    ///
    /// A paused device run is the exception, and it is the case this row is most
    /// likely to be SEEN in: the app switcher snapshots the scene on the way
    /// out, so the card a coach checks while he is away is this one. A ring
    /// turning there over a run getting no CPU would contradict the line beside
    /// it, which reads "Paused - keep Shuttl open".
    private var spins: Bool {
        if case .paused = device { return false }
        return (LocalVideoStatus.isRunning(stage: entry.stage) || isDeviceRunInFlight(device))
            && !canAnalyze
    }

    var body: some View {
        Button(action: onTap) {
            VStack(alignment: .leading, spacing: DrawerList.gap) {
                HStack(spacing: DrawerList.gap) {
                    Group {
                        if let image = thumbnails.images[entry.id] {
                            Image(uiImage: image).resizable().aspectRatio(contentMode: .fill)
                        } else {
                            // A thumbnail that has not decoded yet would
                            // otherwise be a card-coloured hole; this reads as
                            // an empty slot instead.
                            Shuttl.border
                        }
                    }
                    .frame(width: DrawerList.thumbWidth, height: DrawerList.thumbHeight)
                    .clipShape(RoundedRectangle(cornerRadius: ShuttlRadius.small))
                    .task { await thumbnails.load(for: entry) }

                    VStack(alignment: .leading, spacing: 3) {
                        Text(entry.title ?? entry.displayName)
                            .shuttlType(ShuttlType.titleMedium)
                            .foregroundStyle(Shuttl.text)
                            .lineLimit(1)
                        Text(subtitle)
                            .shuttlType(ShuttlType.bodySmall)
                            .foregroundStyle(Shuttl.textSecondary)
                            .lineLimit(1)
                        if let status = LocalVideoStatus.rowStatus(
                            stage: entry.stage, progress: progress, device: device
                        ) {
                            Text(status)
                                .shuttlType(ShuttlType.bodySmall)
                                .foregroundStyle(Shuttl.textSecondary)
                                // Two, not one: the drawer leaves this column
                                // about 118pt once the thumbnail and the menu
                                // have taken theirs.
                                .lineLimit(2)
                        }
                    }
                    Spacer(minLength: 0)
                    if spins {
                        // Settled stages (e.g. ANALYZED) show neither ring nor
                        // button - the status text already says what happened.
                        ProgressView().controlSize(.small)
                    }
                    if hasMenu {
                        Menu {
                            if let localClips, let onOpenLocalClips {
                                Button("Clips on this phone (\(localClips))") { onOpenLocalClips() }
                            }
                            if let onOpenHeatmap {
                                Button("Player heatmap") { onOpenHeatmap() }
                            }
                            if LocalVideoStatus.canEditDetails(stage: entry.stage) {
                                Button("Edit details") { onEditDetails() }
                            }
                            if canRemove {
                                Button("Remove from app", role: .destructive) { onRemove() }
                            }
                        } label: {
                            Image(systemName: "ellipsis")
                                .foregroundStyle(Shuttl.textSecondary)
                                // The same 44x44 minimum every other trailing
                                // control in this list carries, so the glyph is
                                // tappable without widening the card's inset.
                                .frame(width: 44, height: 44, alignment: .trailing)
                        }
                        .buttonStyle(.borderless)
                        .accessibilityLabel("Local video menu")
                    }
                }
                if canAnalyze {
                    // Its own line, not the trailing slot the mock draws it in.
                    // The mock's card carries a thumbnail, two lines and one
                    // pill; this one also carries the overflow menu, and
                    // squeezing a pill in beside it left the title about four
                    // characters wide. Full width instead, which is also how
                    // Home states its two primary actions, so the card reads as
                    // one clear next step.
                    Button(LocalVideoStatus.analyzeButtonLabel(stage: entry.stage)) { onAnalyze() }
                        .shuttlType(ShuttlType.labelMedium)
                        .foregroundStyle(Shuttl.onAccent)
                        .lineLimit(1)
                        .frame(maxWidth: .infinity)
                        .padding(.vertical, 10)
                        .background(Shuttl.accent)
                        .clipShape(Capsule())
                        .buttonStyle(.borderless)
                }
            }
            .padding(.horizontal, DrawerList.rowPaddingH)
            .padding(.vertical, DrawerList.rowPaddingV)
            .frame(maxWidth: .infinity, alignment: .leading)
            .background(Shuttl.bgTertiary)
            .clipShape(RoundedRectangle(cornerRadius: ShuttlRadius.large))
            // Without this, the Button's hit area is only its children's -
            // the Spacer in the middle has none of its own - so the empty
            // stretch between the text and the trailing control would go dead
            // and silently stop opening the video. NavigationLink gave the
            // whole row a hit area for free; a Button does not.
            .contentShape(RoundedRectangle(cornerRadius: ShuttlRadius.large))
        }
        .buttonStyle(.plain)
    }
}

extension LocalVideoEntry: Identifiable {}
