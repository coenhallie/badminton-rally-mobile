import AVKit
import Shared
import SwiftUI

struct LocalPlayerView: View {
    let rally: RallyApp
    let analyze: AnalyzeCoordinator
    /// Routed through only so the court marking this screen can push offers the
    /// same two targets it offers everywhere else. A coach who marks a court
    /// from the player must not get a cloud-only flow because of where they
    /// started.
    let localAnalysis: LocalAnalysisRunner?
    let entryId: String
    @Environment(\.dismiss) private var dismiss
    @State private var model: LocalPlayerModel?
    @State private var liveEntry: LocalVideoEntry?
    @State private var addSheet: AddSheetItem? = nil
    @State private var deleteTarget: LocalAnnotation? = nil
    @State private var courtTarget: CourtMarkingRoute? = nil

    var body: some View {
        Group {
            if let model {
                content(model)
            } else {
                SplashView()
            }
        }
        .navigationBarTitleDisplayMode(.inline)
        .task {
            guard model == nil else { return }
            guard let entry = rally.localVideos.get(id: entryId) else {
                dismiss()   // entry vanished — mirror Android's popBackStack
                return
            }
            let m = LocalPlayerModel(rally: rally, entry: entry)
            model = m
            await m.loadMetadata()
            await m.refreshLabels()
            // Structured child task: torn down automatically if this .task is
            // cancelled (the view disappearing) before it is awaited.
            async let labelsLoop: Void = m.observeLabels()
            await m.observeAnnotations()
            _ = await labelsLoop
        }
        .task {
            // Keep the entry live (Android observes it the same way): the stage
            // drives the Analyze button, and analysis started from this screen
            // must be reflected here, not just on the home list.
            for await entries in rally.localVideos.entries {
                guard let entry = entries.first(where: { $0.id == entryId }) else {
                    dismiss()   // removed (e.g. analysis succeeded) — pop back
                    return
                }
                liveEntry = entry
            }
        }
    }

    @ViewBuilder
    private func content(_ model: LocalPlayerModel) -> some View {
        VStack(spacing: 0) {
            // The card takes the video's own ratio rather than a share of the
            // screen: the mock sizes it that way, and the 60% slab this replaced
            // letterboxed a 16:9 recording with black above and below it.
            ShuttlPlayer(
                player: model.player,
                prefs: rally.playbackPrefs,
                step: { model.stepFrames($0) },
                overlay: { EmptyView() },
                errorContent: {
                    if let error = model.playbackError {
                        VStack(spacing: 8) {
                            ErrorBanner(message: error)
                            Button("Retry") { model.retry() }
                                .buttonStyle(PrimaryButtonStyle())
                                .frame(maxWidth: 160)
                        }
                        .padding(16)
                    }
                }
            )
            .padding(.top, 8)
            .padding(.bottom, 8)

            if let actionError = model.actionError {
                ErrorBanner(message: actionError)
                    .onTapGesture { model.actionError = nil }
            }

            if let description = (liveEntry ?? model.entry).description_ {
                Text(description)
                    .shuttlType(ShuttlType.titleMedium)
                    .foregroundStyle(Shuttl.textSecondary)
                    .frame(maxWidth: .infinity, alignment: .leading)
                    .padding(.horizontal, 16)
                    .padding(.vertical, 8)
            }

            List {
                ForEach(model.annotations, id: \.id) { annotation in
                    annotationRow(annotation, model: model)
                }
                Text("Notes are saved on this phone and are removed if you remove the video from the app.")
                    .shuttlType(ShuttlType.bodySmall)
                    .foregroundStyle(Shuttl.textTertiary)
            }
            .listStyle(.plain)
        }
        .navigationTitle({
            let e = liveEntry ?? model.entry
            return e.title ?? e.displayName
        }())
        .toolbar {
            // The chrome indicator, on every bar androidApp puts it on. See
            // `BackgroundWorkAction`.
            ToolbarItem(placement: .topBarTrailing) { BackgroundWorkAction() }
            ToolbarItem(placement: .topBarTrailing) {
                // Live stage, not the model's load-time snapshot: after "Start
                // Analysis" the button must disappear while the pipeline runs.
                let stage = (liveEntry ?? model.entry).stage
                // And the device's own liveness: the stage rules out a cloud run
                // in flight, but a device run never moves it, so this button
                // would sit here live over the run it already started.
                if LocalVideoStatus.canAnalyze(
                    stage: stage, device: localAnalysis?.state(for: entryId) ?? .idle
                ) {
                    Button(LocalVideoStatus.analyzeButtonLabel(stage: stage)) {
                        courtTarget = CourtMarkingRoute(entryId: entryId)
                    }
                    .shuttlType(ShuttlType.labelMedium)
                    .lineLimit(1)
                    .fixedSize(horizontal: true, vertical: false)
                }
            }
            // #if too: ToolbarSpacer must exist in the SDK at compile time,
            // and CI's Xcode 16 / iOS 18 SDK doesn't have it.
            #if compiler(>=6.2)
                if #available(iOS 26.0, *) {
                    ToolbarSpacer(.fixed, placement: .topBarTrailing)
                }
            #endif
            ToolbarItem(placement: .topBarTrailing) {
                Button {
                    addSheet = AddSheetItem(timestamp: model.currentTimestampSeconds())
                } label: {
                    Image(systemName: "plus")
                }
                .accessibilityLabel("Add note")
            }
        }
        .navigationDestination(item: $courtTarget) { route in
            CourtMarkingView(
                rally: rally, analyze: analyze,
                localAnalysis: localAnalysis, entryId: route.entryId
            )
        }
        .sheet(item: $addSheet) { item in
            AddAnnotationSheet(
                labels: model.labels,
                onAdd: { label, body in
                    model.add(label: label, body: body, atSeconds: item.timestamp)
                }
            )
            .presentationDetents([.medium])
        }
        .alert("Delete note?", isPresented: Binding(
            get: { deleteTarget != nil },
            set: { if !$0 { deleteTarget = nil } }
        )) {
            Button("Cancel", role: .cancel) { deleteTarget = nil }
            Button("Delete", role: .destructive) {
                if let target = deleteTarget { model.delete(annotationId: target.id) }
                deleteTarget = nil
            }
        } message: {
            if let target = deleteTarget {
                Text(target.body.isEmpty ? "This note" : "\"\(target.body)\"")
            }
        }
    }

    private func annotationRow(_ annotation: LocalAnnotation, model: LocalPlayerModel) -> some View {
        HStack(spacing: 12) {
            Text(formatTimestamp(annotation.timestampSeconds))
                .shuttlType(ShuttlType.bodySmall, monospacedDigit: true)
                .foregroundStyle(Shuttl.textSecondary)
            if let name = annotation.labelName {
                LabelBadge(name: name, colorKey: annotation.labelColor)
            }
            if !annotation.body.isEmpty {
                Text(annotation.body)
                    .shuttlType(ShuttlType.titleMedium)
                    .foregroundStyle(Shuttl.text)
            }
            Spacer()
            Button {
                deleteTarget = annotation
            } label: {
                Image(systemName: "trash")
            }
            .buttonStyle(.borderless)
            .accessibilityLabel("Delete note")
        }
        .contentShape(Rectangle())
        .onTapGesture { model.seek(toSeconds: annotation.timestampSeconds) }
    }
}

private struct AddSheetItem: Identifiable {
    let timestamp: Float
    let id = UUID()
}
