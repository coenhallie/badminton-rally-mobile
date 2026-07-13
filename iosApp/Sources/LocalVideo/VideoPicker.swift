import PhotosUI
import SwiftUI
import UniformTypeIdentifiers

/// PHPicker (videos only) — no permission prompt; hands over a temp file copy.
struct VideoPicker: UIViewControllerRepresentable {
    let onPicked: (URL, String?) -> Void
    let onFailed: () -> Void
    @Environment(\.dismiss) private var dismiss

    func makeUIViewController(context: Context) -> PHPickerViewController {
        var config = PHPickerConfiguration()
        config.filter = .videos
        config.selectionLimit = 1
        let picker = PHPickerViewController(configuration: config)
        picker.delegate = context.coordinator
        return picker
    }

    func updateUIViewController(_ uiViewController: PHPickerViewController, context: Context) {}

    func makeCoordinator() -> Coordinator { Coordinator(self) }

    final class Coordinator: NSObject, PHPickerViewControllerDelegate {
        let parent: VideoPicker
        init(_ parent: VideoPicker) { self.parent = parent }

        func picker(_ picker: PHPickerViewController, didFinishPicking results: [PHPickerResult]) {
            parent.dismiss()
            guard let provider = results.first?.itemProvider,
                  provider.hasItemConformingToTypeIdentifier(UTType.movie.identifier) else { return }
            let suggestedName = provider.suggestedName.map { name in
                ["mp4", "mov", "m4v"].contains((name as NSString).pathExtension.lowercased())
                    ? name : "\(name).mp4"
            }
            provider.loadFileRepresentation(forTypeIdentifier: UTType.movie.identifier) { url, _ in
                let temp = FileManager.default.temporaryDirectory
                    .appendingPathComponent("import-\(UUID().uuidString).mp4")
                guard let url, (try? FileManager.default.copyItem(at: url, to: temp)) != nil else {
                    DispatchQueue.main.async { self.parent.onFailed() }
                    return
                }
                DispatchQueue.main.async {
                    self.parent.onPicked(temp, suggestedName ?? "video.mp4")
                }
            }
        }
    }
}
