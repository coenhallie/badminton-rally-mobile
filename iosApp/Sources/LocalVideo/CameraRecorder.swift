import SwiftUI
import UniformTypeIdentifiers

/// System camera in movie mode - mirrors Android's ActivityResultContracts.CaptureVideo.
struct CameraRecorder: UIViewControllerRepresentable {
    let onRecorded: (URL) -> Void
    @Environment(\.dismiss) private var dismiss

    /// How long one recording may run, in seconds.
    ///
    /// Set because UIKit's default is not "no limit": `videoMaximumDuration`
    /// defaults to 600, and at ten minutes the picker simply stops recording -
    /// no alert - which is a few rallies into the second game of a match.
    ///
    /// Eight hours, because the app has no duration constraint to be honest
    /// about. Its one real limit is `LocalVideoLimits.MAX_SIZE_BYTES`, and that
    /// cannot be turned into seconds without guessing a bitrate that moves with
    /// the phone, its camera format setting and how much the picture changes -
    /// a guess that is wrong on the low side cuts a legitimate match short, the
    /// very defect this exists to remove. Intake checks the size for real once
    /// the file exists. So this only has to be a limit no recording meets:
    /// the longest professional matches run under three hours, and a phone filming
    /// continuously is out of battery or storage well inside eight.
    static let maximumRecordingSeconds: TimeInterval = 8 * 60 * 60

    static var isAvailable: Bool {
        UIImagePickerController.isSourceTypeAvailable(.camera)
    }

    /// Everything about the recording that does not need a camera to set, apart
    /// from `makeUIViewController` so a simulator - which has no camera, and
    /// traps on `sourceType = .camera` - can still check it.
    static func applyRecordingSettings(to picker: UIImagePickerController) {
        picker.mediaTypes = [UTType.movie.identifier]
        picker.videoQuality = .typeHigh
        picker.videoMaximumDuration = maximumRecordingSeconds
    }

    func makeUIViewController(context: Context) -> UIImagePickerController {
        let picker = UIImagePickerController()
        picker.sourceType = .camera
        Self.applyRecordingSettings(to: picker)
        picker.delegate = context.coordinator
        return picker
    }

    func updateUIViewController(_ uiViewController: UIImagePickerController, context: Context) {}

    func makeCoordinator() -> Coordinator { Coordinator(self) }

    final class Coordinator: NSObject, UIImagePickerControllerDelegate, UINavigationControllerDelegate {
        let parent: CameraRecorder
        init(_ parent: CameraRecorder) { self.parent = parent }

        func imagePickerController(
            _ picker: UIImagePickerController,
            didFinishPickingMediaWithInfo info: [UIImagePickerController.InfoKey: Any]
        ) {
            parent.dismiss()
            if let url = info[.mediaURL] as? URL {
                parent.onRecorded(url)
            }
        }

        func imagePickerControllerDidCancel(_ picker: UIImagePickerController) {
            parent.dismiss()
        }
    }
}
