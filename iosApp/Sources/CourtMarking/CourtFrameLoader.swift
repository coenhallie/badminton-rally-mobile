import AVFoundation
import UIKit

/// First frame at t = 0.1s, source resolution — mirror of Android's loadFirstFrame.
enum CourtFrameLoader {
    static func loadFirstFrame(relativePath: String) async throws -> UIImage {
        let url = LocalVideoFiles.resolve(relativePath: relativePath)
        let generator = AVAssetImageGenerator(asset: AVURLAsset(url: url))
        generator.appliesPreferredTrackTransform = true
        generator.requestedTimeToleranceBefore = .zero
        generator.requestedTimeToleranceAfter = .positiveInfinity
        let time = CMTime(value: 1, timescale: 10)   // 0.1s, like Android's 100_000µs
        do {
            let cgImage = try await generator.image(at: time).image
            return UIImage(cgImage: cgImage)
        } catch {
            throw NSError(
                domain: "CourtFrameLoader", code: 1,
                userInfo: [NSLocalizedDescriptionKey: "Couldn't extract video frame"]
            )
        }
    }
}
