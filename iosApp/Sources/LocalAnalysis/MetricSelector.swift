import Shared
import SwiftUI

/// What to compute, and what it will cost in time.
///
/// Port of Android's `MetricSelector`. Pose roughly doubles an analysis and a
/// long video is hours rather than minutes, so which metrics are wanted is the
/// coach's decision and not a default to be discovered afterwards. The estimate
/// is shown per option, next to the option, because a total alone does not tell
/// anyone which switch to turn off.
struct MetricSelector: View {
    let frames: Int
    let fps: Double
    let selected: Set<AnalysisMetric>
    let throughput: DeviceThroughput
    let onToggle: (AnalysisMetric) -> Void

    private var total: AnalysisEstimate {
        AnalysisPlanKt.estimateAnalysis(
            frames: Int32(frames), fps: fps, metrics: selected,
            throughput: throughput, rallyFraction: AnalysisPlanKt.TYPICAL_RALLY_FRACTION
        )
    }

    var body: some View {
        VStack(alignment: .leading, spacing: 8) {
            ForEach(AnalysisMetric.allCases, id: \.self) { metric in
                // The cost OF THIS OPTION, measured as what turning it off would
                // save rather than what it would cost alone. Pose is shared, so
                // quoting a standalone price would promise a saving that turning
                // one of the two pose options off does not deliver.
                let without = AnalysisPlanKt.estimateAnalysis(
                    frames: Int32(frames), fps: fps, metrics: selected.subtracting([metric]),
                    throughput: throughput, rallyFraction: AnalysisPlanKt.TYPICAL_RALLY_FRACTION
                )
                card(
                    metric: metric,
                    marginal: total.fastSeconds - without.fastSeconds,
                    isOn: selected.contains(metric)
                )
            }

            HStack {
                Text("Estimated time")
                    .shuttlType(ShuttlType.bodySmall)
                    .foregroundStyle(Shuttl.textTertiary)
                Spacer()
                Text(selected.isEmpty ? "Nothing selected" : total.describe())
                    .shuttlType(ShuttlType.titleLarge)
                    .foregroundStyle(Shuttl.textHeading)
            }
            .padding(.top, 18)

            Text(throughput.measured
                 ? "Based on how fast this phone ran your last analysis."
                 // Said plainly rather than hidden: the first estimate on an
                 // unmeasured phone comes from a different one - and on iPhone
                 // it comes from a different PLATFORM, which is why
                 // tools/models/reports/ios-throughput-simulator.md exists.
                 : "First estimate, from a reference phone. It corrects itself after one run.")
                .shuttlType(ShuttlType.bodySmall)
                .foregroundStyle(Shuttl.textTertiary)

            // The estimate and the choice above it both price the on-device run.
            // The cloud's cost is someone else's GPU and its worker decides its
            // own stages, so laying the two buttons under one list of options
            // would otherwise imply a control over the cloud run that does not
            // exist.
            Text("A cloud run decides its own stages and takes as long as it takes.")
                .shuttlType(ShuttlType.bodySmall)
                .foregroundStyle(Shuttl.textTertiary)

            // The one line Android has no need of. iOS has no foreground
            // service, so a run stops when the app does; saying it here, next to
            // the number that says how long it will take, is the only place a
            // coach can act on it.
            Text("An on-device run needs Shuttl open and the phone unlocked. It pauses if you leave.")
                .shuttlType(ShuttlType.bodySmall)
                .foregroundStyle(Shuttl.textTertiary)
        }
        .frame(maxWidth: .infinity, alignment: .leading)
    }

    /// One card in the mock's shape: the name and its cost on the left, a mark
    /// on the right, and the accent around the card while it is on.
    @ViewBuilder
    private func card(metric: AnalysisMetric, marginal: Double, isOn: Bool) -> some View {
        Button {
            onToggle(metric)
        } label: {
            HStack(alignment: .top, spacing: 16) {
                VStack(alignment: .leading, spacing: 4) {
                    Text(metric.title)
                        .shuttlType(ShuttlType.titleLarge)
                        .foregroundStyle(Shuttl.textHeading)
                    Text(metric.detail(marginalSeconds: marginal, isSelected: isOn))
                        .shuttlType(ShuttlType.bodySmall)
                        .foregroundStyle(Shuttl.textTertiary)
                        .multilineTextAlignment(.leading)
                }
                .frame(maxWidth: .infinity, alignment: .leading)
                mark(isOn: isOn)
            }
            .padding(.horizontal, 20)
            .padding(.vertical, 18)
            .frame(maxWidth: .infinity, alignment: .leading)
            .background(Shuttl.bgSecondary, in: RoundedRectangle(cornerRadius: ShuttlRadius.large))
            .overlay(
                RoundedRectangle(cornerRadius: ShuttlRadius.large)
                    .stroke(isOn ? Shuttl.accent : Shuttl.border, lineWidth: 1)
            )
        }
        .buttonStyle(.plain)
        .accessibilityAddTraits(isOn ? [.isButton, .isSelected] : .isButton)
    }

    private func mark(isOn: Bool) -> some View {
        ZStack {
            Circle()
                .fill(isOn ? Shuttl.accent : Shuttl.bgSecondary)
                .overlay(Circle().stroke(isOn ? Color.clear : Shuttl.border, lineWidth: 1))
            if isOn {
                Image(systemName: "checkmark")
                    .font(.system(size: 11, weight: .bold))
                    .foregroundStyle(Shuttl.onAccent)
            }
        }
        .frame(width: 22, height: 22)
    }
}

extension AnalysisMetric {
    /// Every metric, in the order the Kotlin enum declares them.
    ///
    /// `allCases` rather than a hand-written array: SKIE gives the Kotlin enum
    /// `CaseIterable`, so a metric added in shared appears here without anyone
    /// remembering to add it, which is the failure mode a literal list has.
    var title: String {
        switch self {
        case .rallyClips: return "Rally clips"
        case .playerMovement: return "Player movement and heatmap"
        case .skeletonPlayback: return "Skeleton playback"
        default: return "Analysis"
        }
    }

    func detail(marginalSeconds: Double, isSelected: Bool) -> String {
        let base: String
        switch self {
        case .rallyClips: base = "Each rally cut into its own playable clip"
        case .playerMovement: base = "Where the near player spent the match"
        case .skeletonPlayback: base = "Every joint kept, to draw over the video"
        default: base = ""
        }
        guard isSelected else { return base }
        let minutes = marginalSeconds / 60
        let cost: String
        switch true {
        // Zero marginal cost is the honest answer when another selected option
        // already pays for the same pose pass, and saying so stops it reading as
        // a bug. Only a pose option can be free for that reason: clip cutting is
        // its own pass, and on a short video it rounds to nothing without any
        // pose pass running at all.
        case marginalSeconds < 1 && needsPose:
            cost = "no extra time, the pose pass is already running"
        case marginalSeconds < 1:
            cost = "no measurable extra time"
        case minutes < 1:
            cost = "adds under a minute"
        default:
            cost = "adds about \(Int(minutes)) min"
        }
        return "\(base) · \(cost)"
    }
}
