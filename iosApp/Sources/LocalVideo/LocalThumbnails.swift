import AVFoundation
import Shared
import SwiftUI

/// First-frame thumbnails for local videos (Android uses Coil's VideoFrameDecoder).
@MainActor @Observable
final class LocalThumbnails {
    private(set) var images: [String: UIImage] = [:]   // entry id -> frame

    /// Keyed by the entry's id; `relativePath` is its file, as the entry and
    /// the match list row both carry it.
    func load(id: String, relativePath: String) async {
        guard images[id] == nil else { return }
        let url = LocalVideoFiles.resolve(relativePath: relativePath)
        let generator = AVAssetImageGenerator(asset: AVURLAsset(url: url))
        generator.appliesPreferredTrackTransform = true
        generator.maximumSize = CGSize(width: 192, height: 108)
        let time = CMTime(value: 1, timescale: 10) // 100ms in, like Android court marking
        if let cgImage = try? await generator.image(at: time).image {
            images[id] = UIImage(cgImage: cgImage)
        }
    }

    func evict(id: String) {
        images[id] = nil
    }
}
