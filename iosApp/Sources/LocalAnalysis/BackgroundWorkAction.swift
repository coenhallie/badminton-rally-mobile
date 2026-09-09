import Shared
import SwiftUI

/// The chrome indicator: what is running, on every screen that carries a bar.
/// Port of Android's `BackgroundWorkAction`.
///
/// Absent rather than greyed out when nothing runs, so it costs nothing on the
/// bars it sits in when the app is idle.
///
/// Tapping opens a sheet saying what each run is doing, rather than navigating.
/// The stages differ in ways a ring cannot express - copying a video, building a
/// background plate and running inference all look like the same spin - and
/// "what is it doing" is the question the indicator provokes.
struct BackgroundWorkAction: View {
    @Environment(\.backgroundWork) private var work
    @Environment(\.backgroundWorkClick) private var goToMatches
    @State private var showDetail = false

    var body: some View {
        if let work {
            Button { showDetail = true } label: { ring(work) }
                // The label is the accessibility label rather than only a
                // tooltip, because a progress ring on its own tells a screen
                // reader nothing about which pipeline is running or how far
                // along it is.
                .accessibilityLabel(work.label)
                .sheet(isPresented: $showDetail) {
                    BackgroundWorkSheet(work: work) {
                        showDetail = false
                        goToMatches()
                    }
                }
        }
    }

    @ViewBuilder
    private func ring(_ work: BackgroundWork) -> some View {
        ZStack {
            if work.activeCount == 0 {
                // A failure with nothing left running: a ring would imply work
                // is still happening.
                Circle()
                    .fill(Shuttl.error)
                    .frame(width: 10, height: 10)
            } else {
                let fraction = work.fraction?.floatValue
                // The threshold gates the ARC, not the number. Below a couple of
                // percent a determinate ring is its own track and nothing else,
                // so it spins; but a run that has started and is at 0% is a
                // different thing from a run still copying its video, and hiding
                // the number collapsed the two into one long silent spin.
                // Whenever there is a fraction at all, it is shown.
                if let fraction, fraction >= Self.minDeterminate {
                    // The unfilled part, drawn under the arc. Without it a run
                    // at 10% is a stray tick beside a number rather than a ring
                    // one tenth of the way round, which is what a determinate
                    // indicator is FOR - androidApp gets the track from M3 and
                    // this is where it comes from here.
                    Circle()
                        .stroke(Shuttl.borderSecondary, lineWidth: Self.stroke)
                        .frame(width: Self.ring, height: Self.ring)
                    Circle()
                        .trim(from: 0, to: CGFloat(fraction))
                        .stroke(Shuttl.accent, style: StrokeStyle(lineWidth: Self.stroke, lineCap: .round))
                        .rotationEffect(.degrees(-90))
                        .frame(width: Self.ring, height: Self.ring)
                } else {
                    ProgressView()
                        .progressViewStyle(.circular)
                        .controlSize(.small)
                        .frame(width: Self.ring, height: Self.ring)
                }
                if let fraction {
                    // Inside the ring, so the number and the arc it belongs to
                    // are one object rather than two things to reconcile.
                    // labelSmall at 9pt, which is androidApp's
                    // `labelSmall.copy(fontSize = 9.sp)`: at 11pt three digits
                    // do not fit inside a 26pt ring.
                    Text("\(Int(fraction * 100))")
                        .shuttlType(ShuttlType.labelSmall.at(size: 9))
                        .foregroundStyle(Shuttl.text)
                        .lineLimit(1)
                }
                if work.hasFailure {
                    Circle()
                        .fill(Shuttl.error)
                        .frame(width: 8, height: 8)
                        .offset(x: Self.ring / 2 - 1, y: Self.ring / 2 - 1)
                }
            }
        }
        .frame(width: Self.ring, height: Self.ring)
    }

    /// Big enough to hold two digits legibly, small enough for a bar action.
    private static let ring: CGFloat = 26
    private static let stroke: CGFloat = 2.5
    /// Under this the arc is invisible, so the ring spins instead of pretending.
    private static let minDeterminate: Float = 0.02
}

/// What each run is doing, in words, under the ring that could only spin.
private struct BackgroundWorkSheet: View {
    let work: BackgroundWork
    let onOpenMatches: () -> Void

    /// The heading, one row per run, the caption when any run has no percentage,
    /// the button, and the padding around all of it.
    private static func height(_ work: BackgroundWork) -> CGFloat {
        let rows = CGFloat(work.items.count) * 44
        let caption: CGFloat = work.items.contains { $0.fraction == nil } ? 64 : 0
        return 160 + rows + caption
    }

    var body: some View {
        VStack(alignment: .leading, spacing: 0) {
            Text("Working on")
                .shuttlType(ShuttlType.titleMedium)
                .foregroundStyle(Shuttl.textHeading)
                .padding(.bottom, 12)

            ForEach(Array(work.items.enumerated()), id: \.offset) { _, item in
                HStack(spacing: 16) {
                    ZStack {
                        let fraction = item.fraction?.floatValue
                        if let fraction, fraction >= 0.02 {
                            Circle().stroke(Shuttl.borderSecondary, lineWidth: 2)
                            Circle()
                                .trim(from: 0, to: CGFloat(fraction))
                                .stroke(Shuttl.accent, style: StrokeStyle(lineWidth: 2, lineCap: .round))
                                .rotationEffect(.degrees(-90))
                        } else {
                            ProgressView().progressViewStyle(.circular).controlSize(.small)
                        }
                    }
                    .frame(width: 18, height: 18)
                    Text(item.label)
                        .shuttlType(ShuttlType.bodyMedium)
                        .foregroundStyle(Shuttl.text)
                    Spacer(minLength: 0)
                }
                .padding(.vertical, 8)
            }

            if work.items.contains(where: { $0.fraction == nil }) {
                // Named, because a spin with no number looks stuck otherwise:
                // before inference can report a fraction the app copies the
                // video out of the library and builds TrackNet's background
                // plate by sampling the whole match, and neither has a
                // percentage to give.
                Text(
                    "Preparing steps have no percentage: the video is copied and a "
                        + "background image of the whole match is built before analysis starts."
                )
                .shuttlType(ShuttlType.labelSmall)
                .foregroundStyle(Shuttl.textTertiary)
                .padding(.top, 8)
            }

            Button("Go to matches", action: onOpenMatches)
                .buttonStyle(CompactPillButtonStyle())
                .padding(.top, 16)
            Spacer(minLength: 0)
        }
        .frame(maxWidth: .infinity, maxHeight: .infinity, alignment: .topLeading)
        .padding(.horizontal, ShuttlGutter.page)
        .padding(.top, 24)
        .padding(.bottom, 32)
        // Sized to what it holds rather than to a half screen: one run is three
        // lines, and a medium detent under them is empty space the coach has to
        // dismiss. androidApp's `ModalBottomSheet` wraps its content for the
        // same reason; SwiftUI has no fitting detent, so the height is stated.
        .presentationDetents([.height(Self.height(work))])
        .presentationBackground(Shuttl.bgSecondary)
    }
}
