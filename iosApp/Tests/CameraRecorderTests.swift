import UIKit
import UniformTypeIdentifiers
import XCTest
@testable import iosApp

/// What the in-app recorder asks of the system camera.
///
/// Only the settings, on a picker that never opens: the simulator has no
/// camera. That the camera honours them is a device check.
@MainActor
final class CameraRecorderTests: XCTestCase {

    func testARecordingIsNotCutOffAtUIKitsTenMinutes() {
        // `videoMaximumDuration` defaults to 600 seconds, and the picker stops
        // on its own when it gets there - no alert, the recording just ends.
        // The recorder never set it, so every match filmed in the app stopped
        // ten minutes in.
        let picker = UIImagePickerController()
        CameraRecorder.applyRecordingSettings(to: picker)

        XCTAssertEqual(picker.videoMaximumDuration, CameraRecorder.maximumRecordingSeconds)
        // The longest professional matches run to nearly three hours. Whatever
        // the constant becomes, it must clear that with room either side.
        XCTAssertGreaterThanOrEqual(CameraRecorder.maximumRecordingSeconds, 4 * 60 * 60)
    }

    func testItRecordsVideoAtTheHighQualityPreset() {
        let picker = UIImagePickerController()
        CameraRecorder.applyRecordingSettings(to: picker)

        XCTAssertEqual(picker.mediaTypes, [UTType.movie.identifier])
        XCTAssertEqual(picker.videoQuality, .typeHigh)
    }
}
