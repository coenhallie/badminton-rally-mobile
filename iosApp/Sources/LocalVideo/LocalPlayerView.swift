import AVKit
import Shared
import SwiftUI

struct LocalPlayerView: View {
    let rally: RallyApp
    let analyze: AnalyzeCoordinator
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
            await m.observeAnnotations()
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
            // 60% of the screen for the video so rallies can be evaluated closely;
            // the annotation list scrolls in whatever space remains.
            ZStack {
                VideoPlayer(player: model.player)
                    .frame(maxWidth: .infinity)
                    .containerRelativeFrame(.vertical) { height, _ in height * 0.6 }
                    .background(Color.black)
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

            FrameStepBar(player: model.player, step: { model.stepFrames($0) })

            List {
                ForEach(model.annotations, id: \.id) { annotation in
                    annotationRow(annotation, model: model)
                }
                Text("Notes are saved on this phone and are removed if you remove the video from the app.")
                    .font(.footnote)
                    .foregroundStyle(Shuttl.textTertiary)
            }
            .listStyle(.plain)
        }
        .navigationTitle((liveEntry ?? model.entry).displayName)
        .toolbar {
            ToolbarItem(placement: .topBarTrailing) {
                // Live stage, not the model's load-time snapshot: after "Start
                // Analysis" the button must disappear while the pipeline runs.
                let stage = (liveEntry ?? model.entry).stage
                if LocalVideoStatus.canAnalyze(stage: stage) {
                    Button(LocalVideoStatus.analyzeButtonLabel(stage: stage)) {
                        courtTarget = CourtMarkingRoute(entryId: entryId)
                    }
                    .font(.footnote.weight(.semibold))
                    .lineLimit(1)
                    .fixedSize(horizontal: true, vertical: false)
                }
            }
            if #available(iOS 26.0, *) {
                ToolbarSpacer(.fixed, placement: .topBarTrailing)
            }
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
            CourtMarkingView(rally: rally, analyze: analyze, entryId: route.entryId)
        }
        .sheet(item: $addSheet) { item in
            AddAnnotationSheet { kind, body in
                model.add(kind: kind, body: body, atSeconds: item.timestamp)
            }
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
                .font(.footnote.monospacedDigit())
                .foregroundStyle(Shuttl.textSecondary)
            if let kind = annotation.kind {
                KindBadge(kind: kind)
            }
            if !annotation.body.isEmpty {
                Text(annotation.body)
                    .font(.subheadline)
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
