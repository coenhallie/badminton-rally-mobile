import Shared
import XCTest

/// The playback preference lives in Kotlin, so every value the iOS bar shows
/// crosses the SKIE bridge as a boxed number. These lock that crossing down:
/// the bar itself cannot be exercised in a unit test, so a silent change in how
/// SKIE boxes `List<Int>` / `List<Float>` would otherwise reach users first.
final class PlaybackBridgingTests: XCTestCase {

    func testSkipOptionsBridgeToSwiftInts() {
        XCTAssertEqual(
            PlaybackOptions.shared.skipSecondsOptions.map { $0.intValue },
            [1, 2, 5, 10, 15, 30]
        )
    }

    func testSpeedOptionsBridgeToSwiftFloats() {
        XCTAssertEqual(
            PlaybackOptions.shared.speedOptions.map { $0.floatValue },
            [0.25, 0.5, 1.0, 1.5, 2.0]
        )
    }

    func testDefaultsMatchTheStatesTheBarInitialisesWith() {
        XCTAssertEqual(Int(PlaybackOptions.shared.DEFAULT_SKIP_SECONDS), 10)
        XCTAssertEqual(PlaybackOptions.shared.DEFAULT_SPEED, 1.0)
    }

    func testSpeedLabelsRenderTheSameTextAsAndroid() {
        let labels = PlaybackOptions.shared.speedOptions.map {
            PlaybackOptions.shared.formatSpeed(speed: $0.floatValue)
        }
        XCTAssertEqual(labels, ["0.25\u{00d7}", "0.5\u{00d7}", "1\u{00d7}", "1.5\u{00d7}", "2\u{00d7}"])
    }

    func testSkipMathClampsThroughTheMillisecondsTheBarPassesIt() {
        // The bar converts CMTime seconds to ms before calling in; a 10s skip
        // from 57s of a 60s clip must land on the end, not past it.
        XCTAssertEqual(
            SkipMath.shared.targetMs(positionMs: 57_000, deltaSeconds: 10, durationMs: 60_000),
            60_000
        )
        // An indefinite CMTime arrives as -1 and must not clamp the skip away.
        XCTAssertEqual(
            SkipMath.shared.targetMs(positionMs: 12_000, deltaSeconds: 10, durationMs: -1),
            22_000
        )
    }

    func testTheTransportRowLayoutCrossesTheBridge() {
        // The row is spaced by shared numbers on both platforms; what is checked
        // here is that they arrive as numbers Swift can lay out with.
        let layout = TransportRowLayout.shared
        let wide = layout.fit(availableWidth: 800, readoutWidth: 32, pillLabelWidth: 22, signedPillLabelWidth: 29)
        XCTAssertEqual(wide.gap, 8)
        XCTAssertEqual(wide.pillPadding, 16)
        XCTAssertTrue(wide.showPillIcons)
        XCTAssertEqual(wide.gutter, 48)

        let narrow = layout.fit(availableWidth: 360, readoutWidth: 32, pillLabelWidth: 22, signedPillLabelWidth: 29)
        XCTAssertFalse(narrow.showPillIcons)
        XCTAssertLessThanOrEqual(
            layout.controlsWidth(metrics: narrow, pillLabelWidth: 22, signedPillLabelWidth: 29),
            360 - 2 * narrow.gutter
        )
        XCTAssertEqual(layout.pillLabel(skipSeconds: 10, forward: true, showPillIcons: false), "+10s")
        XCTAssertEqual(layout.pillLabel(skipSeconds: 10, forward: false, showPillIcons: true), "10s")
    }

    func testTheFloatingWindowsArithmeticCrossesTheBridge() {
        let math = FloatingPanelMath.shared
        let off = math.clamp(
            position: PanelPosition(x: 5000, y: -40),
            panelWidth: 240, panelHeight: 150, areaWidth: 900, areaHeight: 400
        )
        XCTAssertEqual(off, PanelPosition(x: 660, y: 0))

        let anchor = math.anchor(
            position: PanelPosition(x: 660, y: 125),
            panelWidth: 240, panelHeight: 150, areaWidth: 900, areaHeight: 400
        )
        XCTAssertEqual(anchor, PanelAnchor(x: 1, y: 0.5))
        // A smaller screen: still hard against the trailing edge, not off it.
        let restored = math.position(anchor: anchor, panelWidth: 240, panelHeight: 150, areaWidth: 600, areaHeight: 300)
        XCTAssertEqual(restored, PanelPosition(x: 360, y: 75))
        XCTAssertEqual(PanelAnchor.companion.DEFAULT, PanelAnchor(x: 1, y: 0))
    }
}
