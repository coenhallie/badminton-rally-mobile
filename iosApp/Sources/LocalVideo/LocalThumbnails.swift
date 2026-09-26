import AVFoundation
import Shared
import SwiftUI

/// First-frame thumbnails for local videos (Android uses Coil's VideoFrameDecoder).
@MainActor @Observable
final class LocalThumbnails {
    private(set) var images: [String: UIImage] = [:]   // entry id -> frame
    /// Ids `load` tried and produced nothing for: a missing file, a codec
    /// `AVAssetImageGenerator` cannot decode, or a permission revoked under
    /// the app. Distinct from an id simply absent from `images`, which has
    /// either not been asked for yet or is still decoding - the list's row
    /// needs that difference to fall through to the cloud's cover clip
    /// instead of sitting on an empty slot forever.
    private(set) var failed: Set<String> = []

    /// Keyed by the entry's id; `relativePath` is its file, as the entry and
    /// the match list row both carry it.
    func load(id: String, relativePath: String) async {
        guard images[id] == nil, !failed.contains(id) else { return }
        let url = LocalVideoFiles.resolve(relativePath: relativePath)
        let generator = AVAssetImageGenerator(asset: AVURLAsset(url: url))
        generator.appliesPreferredTrackTransform = true
        generator.maximumSize = CGSize(width: 192, height: 108)
        let time = CMTime(value: 1, timescale: 10) // 100ms in, like Android court marking
        if let cgImage = try? await generator.image(at: time).image {
            images[id] = UIImage(cgImage: cgImage)
        } else {
            failed.insert(id)
        }
    }

    func evict(id: String) {
        images[id] = nil
        failed.remove(id)
    }
}
