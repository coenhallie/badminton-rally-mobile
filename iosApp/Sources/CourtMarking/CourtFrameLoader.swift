import AVFoundation
import Shared
import UIKit

/// The still the court is marked on, and what the analysis estimate needs to
/// price a run over the same file.
///
/// Both from one call, matching Android's `CourtFrame`. The estimate is priced
/// in FRAMES, not duration: a six-minute 50fps video has more frames, and costs
/// more to analyse, than an eight-minute 25fps one, and pricing in duration is
/// the mistake that made an earlier routing threshold wrong.
struct CourtFrame {
    let image: UIImage
    let fps: Double
    let frameCount: Int
    /// When in the video `image` was taken, seconds: the generator's own
    /// answer, not the 0.1 s it was asked for. See `CourtFrameLoader.still`.
    let markedAtSeconds: Double

    /// The marking this frame opens on: `saved` when the entry has marks, which
    /// keep their own time, or an empty one drawn on this frame.
    func marking(restoring saved: CourtKeypoints?) -> CourtMarkingState {
        CourtMarkingState.companion.restored(
            videoWidth: Int32(image.size.width * image.scale),
            videoHeight: Int32(image.size.height * image.scale),
            keypoints: saved,
            frameSeconds: KotlinDouble(value: markedAtSeconds)
        )
    }
}

enum CourtFrameLoader {

    static func loadFirstFrame(relativePath: String) async throws -> CourtFrame {
        let url = LocalVideoFiles.resolve(relativePath: relativePath)
        let image: UIImage
        let markedAtSeconds: Double
        do {
            let found = try await still(url: url)
            image = UIImage(cgImage: found.image)
            markedAtSeconds = found.seconds
        } catch {
            throw NSError(
                domain: "CourtFrameLoader", code: 1,
                userInfo: [NSLocalizedDescriptionKey: "Couldn't extract video frame"]
            )
        }

        // Counted rather than estimated, through the same reader the analysis
        // uses, so the number the estimate is priced on and the number the run
        // actually decodes are the same number. It decodes nothing - see
        // `VideoFrameSource.metadata` - but it does walk the container, so it
        // runs off the main actor.
        //
        // A failure here is not a failure to mark a court. The estimate loses
        // its footing and says so through `AnalysisEstimate`; refusing the
        // whole screen would block the cloud run too, which needs none of this.
        let metadata = try? await Task.detached(priority: .userInitiated) {
            try VideoFrameSource(url: url).metadata()
        }.value

        return CourtFrame(
            image: image,
            fps: metadata?.fps ?? 0,
            frameCount: metadata?.frameCount ?? 0,
            markedAtSeconds: markedAtSeconds
        )
    }

    /// When in the video the still this screen shows for `url` was taken, for
    /// marks saved before they carried a time: the camera tracker anchors
    /// there. The same generator, with the same tolerances, so it names the
    /// frame those marks were drawn on.
    ///
    /// 0.1 s when the video cannot give a still: what every other marking
    /// screen shows, and a run that could not read the file fails on its own
    /// terms a moment later.
    static func stillSeconds(url: URL) async -> Double {
        guard let seconds = try? await still(url: url).seconds else { return 0.1 }
        return seconds
    }

    /// The still the court is marked on, and when in the video it was taken.
    ///
    /// Asked for 0.1 s, like Android's 100_000 µs, with no tolerance before and
    /// unlimited tolerance after, so AVFoundation may answer with a later sync
    /// frame: 1.0 s on a real recording (camera-motion design §3). The
    /// tolerances stay, because they decide which frame a coach sees; the time
    /// is recorded instead, since marks drawn at 1.0 s and anchored at 0.1 s
    /// are off by whatever the camera did in between.
    private static func still(url: URL) async throws -> (image: CGImage, seconds: Double) {
        let generator = AVAssetImageGenerator(asset: AVURLAsset(url: url))
        generator.appliesPreferredTrackTransform = true
        generator.requestedTimeToleranceBefore = .zero
        generator.requestedTimeToleranceAfter = .positiveInfinity
        let (image, actual) = try await generator.image(at: CMTime(value: 1, timescale: 10))
        let seconds = CMTimeGetSeconds(actual)
        // An invalid time has no seconds; the frame asked for is the honest
        // fallback, as for a still that could not be read at all.
        return (image, seconds.isFinite ? seconds : 0.1)
    }
}
