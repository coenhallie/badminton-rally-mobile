import AVKit
import SwiftUI
import Shared

struct ClipDetailView: View {
    let rally: RallyApp
    let clipId: String
    @State private var model: ClipDetailModel?
    @State private var showAddSheet = false
    @State private var deleteTarget: RallyAnnotation? = nil

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
            if model == nil {
                let m = ClipDetailModel(rally: rally, clipId: clipId)
                model = m
                await m.load()
            }
        }
    }

    @ViewBuilder
    private func content(_ model: ClipDetailModel) -> some View {
        VStack(spacing: 0) {
            VideoPlayer(player: model.player)
                .aspectRatio(16 / 9, contentMode: .fit)
                .background(Color.black)

            if let error = model.error {
                VStack(spacing: 8) {
                    ErrorBanner(message: error)
                    Button("Retry") { Task { await model.retry() } }
                        .buttonStyle(PrimaryButtonStyle())
                        .frame(maxWidth: 160)
                }
                .padding(16)
            }

            HStack {
                Text(model.clip.map { $0.title ?? "Rally #\($0.rallyIndex)" } ?? "")
                    .font(.title3.weight(.semibold))
                    .foregroundStyle(Shuttl.textHeading)
                Spacer()
                if model.isOwner {
                    Button {
                        showAddSheet = true
                    } label: {
                        Image(systemName: "plus")
                    }
                }
            }
            .padding(16)

            if let actionError = model.actionError {
                ErrorBanner(message: actionError)
            }

            List {
                ForEach(model.annotations, id: \.id) { annotation in
                    annotationRow(annotation, model: model)
                }
            }
            .listStyle(.plain)
        }
        .sheet(isPresented: $showAddSheet) {
            AddAnnotationSheet { kind, body in
                Task { await model.add(kind: kind, body: body) }
            }
            .presentationDetents([.medium])
        }
        .alert("Delete annotation?", isPresented: Binding(
            get: { deleteTarget != nil },
            set: { if !$0 { deleteTarget = nil } }
        )) {
            Button("Cancel", role: .cancel) { deleteTarget = nil }
            Button("Delete", role: .destructive) {
                if let target = deleteTarget {
                    Task { await model.delete(id: target.id) }
                }
                deleteTarget = nil
            }
        }
    }

    private func annotationRow(_ annotation: RallyAnnotation, model: ClipDetailModel) -> some View {
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
            if model.isOwner {
                Button {
                    deleteTarget = annotation
                } label: {
                    Image(systemName: "trash")
                }
                .buttonStyle(.borderless)
                .accessibilityLabel("Delete annotation")
            }
        }
        .contentShape(Rectangle())
        .onTapGesture { model.seek(to: annotation.timestampSeconds) }
    }
}

/// "m:ss" — matches Android formatTimestamp.
func formatTimestamp(_ seconds: Float) -> String {
    let total = Int(seconds)
    return String(format: "%d:%02d", total / 60, total % 60)
}
