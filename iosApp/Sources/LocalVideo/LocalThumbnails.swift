import AVFoundation
import Shared
import SwiftUI

/// First-frame thumbnails for local videos (Android uses Coil's VideoFrameDecoder).
@MainActor @Observable
final class LocalThumbnails {
    private static let maxCachedImages = 80

    private(set) var images: [String: UIImage] = [:]   // entry id -> frame
    /// Ids `load` tried and produced nothing for: a missing file, a codec
    /// `AVAssetImageGenerator` cannot decode, or a permission revoked under
    /// the app. Distinct from an id simply absent from `images`, which has
    /// either not been asked for yet or is still decoding - the list's row
    /// needs that difference to fall through to the cloud's cover clip
    /// instead of sitting on an empty slot forever.
    private(set) var failed: Set<String> = []
    private var inFlight: Set<String> = []
    private var accessOrder: [String] = []

    /// Keyed by the entry's id; `relativePath` is its file, as the entry and
    /// the match list row both carry it.
    func load(id: String, relativePath: String) async {
        if images[id] != nil {
            touch(id)
            return
        }
        guard !failed.contains(id), !inFlight.contains(id) else { return }
        inFlight.insert(id)
        defer { inFlight.remove(id) }
        let url = LocalVideoFiles.resolve(relativePath: relativePath)
        let generator = AVAssetImageGenerator(asset: AVURLAsset(url: url))
        generator.appliesPreferredTrackTransform = true
        generator.maximumSize = CGSize(width: 192, height: 108)
        let time = CMTime(value: 1, timescale: 10) // 100ms in, like Android court marking
        if let cgImage = try? await generator.image(at: time).image {
            if Task.isCancelled { return }
            store(UIImage(cgImage: cgImage), for: id)
        } else {
            if Task.isCancelled { return }
            failed.insert(id)
        }
    }

    func evict(id: String) {
        images[id] = nil
        failed.remove(id)
        accessOrder.removeAll { $0 == id }
    }

    private func touch(_ id: String) {
        accessOrder.removeAll { $0 == id }
        accessOrder.append(id)
    }

    private func store(_ image: UIImage, for id: String) {
        images[id] = image
        touch(id)
        while images.count > Self.maxCachedImages, let oldest = accessOrder.first {
            accessOrder.removeFirst()
            images.removeValue(forKey: oldest)
        }
    }
}
