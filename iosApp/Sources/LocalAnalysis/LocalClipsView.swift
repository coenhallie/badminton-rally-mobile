import AVFoundation
import AVKit
import Shared
import SwiftUI

/// Navigation value for the clips one on-device run cut.
struct LocalClipsRoute: Hashable {
    let entryId: String
}

/// How one cut rally is named in the list and over the player.
///
/// Bounds are unknown for clips recovered by filename - `loadClips` falls back
/// to scanning the directory when the index sidecar is missing - and a
/// fabricated "0.0s - 0.0s" would read as a broken clip rather than as missing
/// bookkeeping, so those get the rally number alone.
///
/// `decimals` is 1 in the list, where the number is a label being scanned, and
/// 2 over the player, where it is the boundary being judged. Port of the two
/// format strings Android's `Route.LocalClips` and `LocalClipPlayerDialog`
/// carry.
func localClipLabel(_ clip: PlayerTrackStore.Clip, decimals: Int = 1) -> String {
    guard clip.endSeconds > clip.startSeconds else { return "Rally \(clip.index)" }
    let seconds = { (value: Double) in String(format: "%.\(decimals)f", value) }
    let span = "\(seconds(clip.startSeconds))s - \(seconds(clip.endSeconds))s"
    guard decimals == 1 else { return "Rally \(clip.index)  \(span)" }
    return "Rally \(clip.index)  \(span)  (\(seconds(clip.endSeconds - clip.startSeconds))s)"
}

/// The rallies an on-device run cut, on this phone.
///
/// Exists so the two pipelines can be judged on what they actually produce.
/// Rally counts can match while the clips are cut at the wrong moment, and the
/// only way to see that is to watch them - the cloud's clips are already
/// playable in the app, so the local ones have to be too or the comparison is
/// numbers against video.
///
/// Port of Android's `Route.LocalClips` composable.
struct LocalClipsView: View {
    let localAnalysis: LocalAnalysisRunner?
    let entryId: String

    /// Read once per opening, not per redraw: this is a directory listing and a
    /// small parse, and the set cannot change while the screen is up - a run
    /// that could add to it is one this row would not have offered.
    @State private var clips: [PlayerTrackStore.Clip] = []
    @State private var playing: PlayerTrackStore.Clip? = nil

    var body: some View {
        ScrollView {
            VStack(alignment: .leading, spacing: 0) {
                if clips.isEmpty {
                    PanelMessage(text: "No clips are stored for this video.")
                }
                ForEach(clips) { clip in
                    Button { playing = clip } label: {
                        HStack(spacing: 12) {
                            Text(localClipLabel(clip))
                                .shuttlType(ShuttlType.bodyMedium)
                                .foregroundStyle(Shuttl.text)
                                .lineLimit(1)
                            Spacer(minLength: 0)
                            Image(systemName: "play.circle")
                                .foregroundStyle(Shuttl.accent)
                        }
                        .padding(.horizontal, 20)
                        .padding(.vertical, 14)
                        .frame(maxWidth: .infinity, alignment: .leading)
                        .background(
                            Shuttl.bgSecondary,
                            in: RoundedRectangle(cornerRadius: ShuttlRadius.large)
                        )
                        .contentShape(RoundedRectangle(cornerRadius: ShuttlRadius.large))
                    }
                    .buttonStyle(.plain)
                    .padding(.horizontal, ShuttlGutter.page)
                    .padding(.top, 8)
                }
            }
            .padding(.vertical, 12)
            .frame(maxWidth: .infinity, alignment: .leading)
        }
        .background(Shuttl.bg)
        .navigationTitle("Clips on this phone")
        .navigationBarTitleDisplayMode(.inline)
        .task { clips = localAnalysis?.storedClips(entryId: entryId) ?? [] }
        .sheet(item: $playing) { clip in
            LocalClipPlayerSheet(clip: clip)
        }
    }
}

/// The rally number is the identity: one clip per rally, per entry, and it is
/// what the file on disk is named after.
extension PlayerTrackStore.Clip: Identifiable {
    var id: Int { index }
}

/// Plays one locally cut clip.
///
/// Loops, because a rally is a few seconds long and judging a boundary usually
/// takes more than one viewing. A sheet rather than Android's dialog: a dialog
/// on iOS is an alert, and the platform's own presentation for "a thing to look
/// at, dismissed by putting it away" is the sheet.
///
/// Port of Android's `LocalClipPlayerDialog`.
struct LocalClipPlayerSheet: View {
    let clip: PlayerTrackStore.Clip

    @Environment(\.dismiss) private var dismiss
    @State private var player: AVPlayer? = nil
    @State private var looper: ClipLooper? = nil

    var body: some View {
        // No `NavigationStack`: this sheet has nowhere to push to, and an empty
        // inline navigation bar costs 100pt of blank chrome above a clip that is
        // five seconds long. The label and the Close button are one row instead.
        VStack(alignment: .leading, spacing: 12) {
            HStack(alignment: .firstTextBaseline) {
                Text(localClipLabel(clip, decimals: 2))
                    .shuttlType(ShuttlType.labelMedium)
                    .foregroundStyle(Shuttl.textSecondary)
                Spacer(minLength: 8)
                Button("Close") { dismiss() }
                    .shuttlType(ShuttlType.labelMedium)
                    .foregroundStyle(Shuttl.accent)
                    .buttonStyle(.plain)
            }
            ZStack {
                Color.black
                PlayerSurface(player: player)
            }
            .aspectRatio(16.0 / 9.0, contentMode: .fit)
            .clipShape(RoundedRectangle(cornerRadius: ShuttlRadius.large))
            Spacer(minLength: 0)
        }
        .padding(ShuttlGutter.page)
        .frame(maxWidth: .infinity, alignment: .leading)
        .background(Shuttl.bg)
        // Half the screen: a 16:9 clip and one line of label do not fill a page,
        // and a full-height sheet over a list the coach is working through reads
        // as a screen they have to navigate back out of.
        .presentationDetents([.medium, .large])
        .presentationDragIndicator(.visible)
        .task {
            // Built here rather than in an initialiser: the sheet's body runs
            // before it is presented, and a player made there starts decoding
            // for a sheet that may never appear.
            let item = AVPlayerItem(url: clip.url)
            let created = AVPlayer(playerItem: item)
            looper = ClipLooper(player: created)
            player = created
            created.play()
        }
        .onDisappear {
            player?.pause()
            looper = nil
            player = nil
        }
    }
}

/// Rewinds to the start on every end, which is AVPlayer's only loop for a
/// single item - `AVPlayerLooper` needs an `AVQueuePlayer` and buys nothing at
/// this length. Holds the observer so it dies with the sheet rather than
/// outliving the player it rewinds.
@MainActor
private final class ClipLooper {
    private var token: NSObjectProtocol?

    init(player: AVPlayer) {
        token = NotificationCenter.default.addObserver(
            forName: AVPlayerItem.didPlayToEndTimeNotification,
            object: player.currentItem,
            queue: .main
        ) { [weak player] _ in
            player?.seek(to: .zero, toleranceBefore: .zero, toleranceAfter: .zero)
            player?.play()
        }
    }

    deinit {
        if let token { NotificationCenter.default.removeObserver(token) }
    }
}
