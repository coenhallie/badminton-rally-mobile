import Shared
import SwiftUI

/// What each of the two runs produces, opened from the "?" beside
/// "What to analyze".
///
/// Every string comes from `Shared`, so this sheet and its Android twin cannot
/// drift: the copy is a promise about what the app does, and the two phones
/// making different promises is the failure the shared analytics rules exist
/// to prevent.
///
/// Two columns rather than a paragraph. The coach is standing in a sports hall
/// deciding between two buttons, and the question is a comparison.
struct CapabilitySheet: View {
    var body: some View {
        NavigationStack {
            ScrollView {
                VStack(alignment: .leading, spacing: 0) {
                    Text(AnalysisCapabilityKt.CAPABILITY_SHARED_LINE)
                        .shuttlType(ShuttlType.bodyMedium)
                        .foregroundStyle(Shuttl.textTertiary)
                        .fixedSize(horizontal: false, vertical: true)
                        .padding(.top, 4)

                    row(what: "", cloud: "In cloud", device: "On device", header: true)
                        .padding(.top, 20)
                    ForEach(AnalysisCapabilityKt.panelCapabilities, id: \.panel) { capability in
                        Divider().overlay(Shuttl.border)
                        row(
                            what: capability.panel.label,
                            cloud: capability.cloud,
                            device: capability.device
                        )
                    }

                    notes(title: "In cloud", lines: AnalysisCapabilityKt.cloudNotes)
                        .padding(.top, 24)
                    notes(title: "On device", lines: AnalysisCapabilityKt.deviceNotes)
                        .padding(.top, 16)
                }
                .frame(maxWidth: .infinity, alignment: .leading)
                .padding(.horizontal, 20)
                .padding(.bottom, 32)
            }
            .background(Shuttl.bg)
            .navigationTitle("What each run produces")
            .navigationBarTitleDisplayMode(.inline)
        }
    }

    /// One line of the table.
    ///
    /// Weighted rather than fixed widths so the longest cell - "Both players,
    /// if the video is on this phone" - wraps instead of pushing the row off
    /// the screen at an accessibility text size.
    @ViewBuilder
    private func row(what: String, cloud: String, device: String, header: Bool = false) -> some View {
        HStack(alignment: .top, spacing: 12) {
            Text(what)
                .shuttlType(ShuttlType.labelMedium)
                .foregroundStyle(header ? Shuttl.textTertiary : Shuttl.text)
                .frame(maxWidth: .infinity, alignment: .leading)
            Text(cloud)
                .shuttlType(header ? ShuttlType.labelMedium : ShuttlType.bodySmall)
                .foregroundStyle(header ? Shuttl.textTertiary : Shuttl.text)
                .frame(maxWidth: .infinity, alignment: .leading)
            Text(device)
                .shuttlType(header ? ShuttlType.labelMedium : ShuttlType.bodySmall)
                .foregroundStyle(header ? Shuttl.textTertiary : Shuttl.text)
                .frame(maxWidth: .infinity, alignment: .leading)
        }
        .fixedSize(horizontal: false, vertical: true)
        .padding(.vertical, 12)
    }

    @ViewBuilder
    private func notes(title: String, lines: [String]) -> some View {
        VStack(alignment: .leading, spacing: 6) {
            Text(title)
                .shuttlType(ShuttlType.labelMedium)
                .foregroundStyle(Shuttl.text)
            ForEach(lines, id: \.self) { line in
                // The bullet in its own column rather than inline in the
                // string: a wrapped line has to hang under the text, not run
                // back to the margin and read as a new bullet.
                HStack(alignment: .firstTextBaseline, spacing: 8) {
                    Text("\u{2022}")
                        .shuttlType(ShuttlType.bodySmall)
                        .foregroundStyle(Shuttl.textTertiary)
                    Text(line)
                        .shuttlType(ShuttlType.bodySmall)
                        .foregroundStyle(Shuttl.textTertiary)
                        .fixedSize(horizontal: false, vertical: true)
                        .frame(maxWidth: .infinity, alignment: .leading)
                }
            }
        }
        .frame(maxWidth: .infinity, alignment: .leading)
    }
}
