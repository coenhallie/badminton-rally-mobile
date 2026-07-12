import AVFoundation
import Shared
import SwiftUI

/// First-frame thumbnails for local videos (Android uses Coil's VideoFrameDecoder).
@MainActor @Observable
final class LocalThumbnails {
    private(set) var images: [String: UIImage] = [:]   // entry id -> frame

    func load(for entry: LocalVideoEntry) async {
        guard images[entry.id] == nil else { return }
        let url = LocalVideoFiles.resolve(relativePath: entry.uri)
        let generator = AVAssetImageGenerator(asset: AVURLAsset(url: url))
        generator.appliesPreferredTrackTransform = true
        generator.maximumSize = CGSize(width: 192, height: 108)
        let time = CMTime(value: 1, timescale: 10) // 100ms in, like Android court marking
        if let cgImage = try? await generator.image(at: time).image {
            images[entry.id] = UIImage(cgImage: cgImage)
        }
    }
}
