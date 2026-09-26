import AVFoundation
import AVKit
import Shared
import SwiftUI

/// The rallies an on-device run cut, as the Rallies section shows them until
/// the 2026-09-11 design turns them into real rally rows.
///
/// Only for a match with no cloud clips: when both exist the cloud clips are the
/// rallies, the rule the row's own rally count follows. Drawn as the cloud rally
/// rows are (`RallyRowLabel` in a plain list, under separators, in the page's
/// gutter) so a match's rallies look the same wherever they were cut, less the
/// thumbnail and the note count, which device clips do not have yet. They had been rounded cards with a play glyph, which
/// read as a different kind of thing from the cloud list one match over.
///
/// They exist so the two pipelines can be judged on what they actually produce.
/// Rally counts can match while the clips are cut at the wrong moment, and the
/// only way to see that is to watch them - the cloud's clips are already
/// playable in the app, so the local ones have to be too or the comparison is
/// numbers against video.
///
/// Port of androidApp's `DeviceRalliesSection`.
struct DeviceRalliesSection: View {
    let localAnalysis: LocalAnalysisRunner?
    let entryId: String

    /// Read once per entry, not per redraw: this is a directory listing and a
    /// small parse, and the set cannot grow while the section is up without a
    /// run the page would show.
    @State private var clips: [PlayerTrackStore.Clip] = []
    /// Whether `clips` has been read yet: until it has, an empty list is not
    /// news, and saying "No clips are stored" for the moment the read takes
    /// flashes a false message on every opening.
    @State private var loaded = false
    @State private var playing: PlayerTrackStore.Clip? = nil

    var body: some View {
        List {
            if loaded, clips.isEmpty {
                PanelMessage(text: MatchPageCopy.shared.DEVICE_CLIPS_EMPTY)
                    .listRowInsets(EdgeInsets())
                    .listRowSeparator(.hidden)
                    .listSectionSeparator(.hidden, edges: .top)
                    .listRowBackground(Color.clear)
            }
            ForEach(clips) { clip in
                Button { playing = clip } label: {
                    RallyRowLabel(title: deviceClipTitle(clip), meta: deviceClipLength(clip))
                }
                .rallyListRow()
            }
        }
        .listStyle(.plain)
        // The page's own background between and behind the rows, not the one
        // `List` paints over it.
        .scrollContentBackground(.hidden)
        .background(Shuttl.bg)
        // Off the main actor: `storedClips` parses a sidecar and stats one file
        // per clip, and this runs as the section appears. `storedClips` is
        // already `nonisolated` for exactly this. Keyed on the entry, so a page
        // that swaps entries under the section reads the new one.
        .task(id: entryId) {
            let runner = localAnalysis
            let id = entryId
            clips = await Task.detached(priority: .userInitiated) {
                runner?.storedClips(entryId: id) ?? []
            }.value
            loaded = true
        }
        .sheet(item: $playing) { clip in
            LocalClipPlayerSheet(clip: clip)
        }
    }
}

/// "Rally #3", the cloud rally row's own name for a rally (`clipRowTitle`), so a
/// device rally and a cloud one are named alike. Port of androidApp's
/// `deviceClipTitle`.
func deviceClipTitle(_ clip: PlayerTrackStore.Clip) -> String {
    "Rally #\(clip.index)"
}

/// "7.5s" through the shared formatter the cloud rows use, or nil for a clip
/// recovered by filename, whose bounds are unknown: `loadClips` falls back to
/// scanning the directory when the index sidecar is missing, and "0.0s" would
/// read as a broken clip rather than as missing bookkeeping. Port of androidApp's
/// `deviceClipLength`.
func deviceClipLength(_ clip: PlayerTrackStore.Clip) -> String? {
    guard clip.endSeconds > clip.startSeconds else { return nil }
    return RallyLengthKt.rallyLengthText(seconds: clip.endSeconds - clip.startSeconds)
}

/// The row's two lines on one, "Rally #3 · 7.5s": the clip player's title, so the
/// sheet names the rally the way the row that opened it did. Port of androidApp's
/// `deviceClipLabel`.
func deviceClipLabel(_ clip: PlayerTrackStore.Clip) -> String {
    [deviceClipTitle(clip), deviceClipLength(clip)].compactMap { $0 }.joined(separator: " · ")
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
                Text(deviceClipLabel(clip))
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
