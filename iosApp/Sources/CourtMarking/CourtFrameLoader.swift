import AVFoundation
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
}

enum CourtFrameLoader {

    static func loadFirstFrame(relativePath: String) async throws -> CourtFrame {
        let url = LocalVideoFiles.resolve(relativePath: relativePath)
        let generator = AVAssetImageGenerator(asset: AVURLAsset(url: url))
        generator.appliesPreferredTrackTransform = true
        generator.requestedTimeToleranceBefore = .zero
        generator.requestedTimeToleranceAfter = .positiveInfinity
        let time = CMTime(value: 1, timescale: 10)   // 0.1s, like Android's 100_000µs
        let image: UIImage
        do {
            image = UIImage(cgImage: try await generator.image(at: time).image)
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
            frameCount: metadata?.frameCount ?? 0
        )
    }
}
