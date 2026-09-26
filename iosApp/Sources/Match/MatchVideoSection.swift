import AVKit
import Shared
import SwiftUI

/// The Match section: the full recording on this phone, with its notes and
/// label chips. `ShuttlPlayer` owns fullscreen itself (a full-screen cover), so
/// nothing is hoisted here, unlike Android. Port of androidApp's
/// `MatchVideoSection`.
///
/// `analyzeLabel` is "Analyze" or "Retry" under the player while the match asks
/// for one (the row's own action), else nil.
struct MatchVideoSection: View {
    let rally: RallyApp
    let entryId: String
    let analyzeLabel: String?
    let onAnalyze: () -> Void

    @State private var model: LocalPlayerModel?
    @State private var liveEntry: LocalVideoEntry?
    @State private var addSheet: AddSheetItem? = nil
    @State private var deleteTarget: LocalAnnotation? = nil

    var body: some View {
        Group {
            if let model {
                content(model)
            } else {
                SplashView()
            }
        }
        // Both keyed on the entry they read, so a host that swaps the entry
        // under this section plays and lists the new one, not the old.
        .task(id: entryId) {
            // The model is built once per entry; what it observes is restarted
            // on every appearance. This used to `guard model == nil` around both,
            // so the loops that pushing court marking cancelled never came back.
            if model?.entry.id != entryId {
                model = nil
                // A vanished entry is not dismissed here: the page that hosts
                // this section falls back to another section when the file goes
                // (`shownSection`), so there is nothing to draw and nothing to do.
                guard let entry = rally.localVideos.get(id: entryId) else { return }
                model = LocalPlayerModel(rally: rally, entry: entry)
            }
            await model?.start()
        }
        .task(id: entryId) {
            // Keep the entry live (Android observes it the same way): its
            // description is drawn under the player, and an edit made elsewhere
            // must show here, not just on the match list. An entry that goes is
            // the host's to handle, as above.
            for await entries in rally.localVideos.entries {
                guard let entry = entries.first(where: { $0.id == entryId }) else { return }
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
                labels: model.labels,
                onTag: { label, positionMs in
                    model.add(label: label, body: "", atSeconds: Float(positionMs) / 1000)
                },
                tagError: model.actionError,
                allowsFullscreen: true,
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

            // The row's own action, under the video it would analyze, rather
            // than in the bar: the page's bar belongs to the whole match.
            if let analyzeLabel {
                Button(analyzeLabel, action: onAnalyze)
                    .buttonStyle(PrimaryButtonStyle())
                    .padding(.horizontal, ShuttlGutter.page)
                    .padding(.bottom, 8)
            }

            if let actionError = model.actionError {
                ErrorBanner(message: actionError)
                    .onTapGesture { model.actionError = nil }
            }

            if let description = shownEntry(model).description_ {
                Text(description)
                    .shuttlType(ShuttlType.titleMedium)
                    .foregroundStyle(Shuttl.textSecondary)
                    .frame(maxWidth: .infinity, alignment: .leading)
                    .padding(.horizontal, ShuttlGutter.page)
                    .padding(.vertical, 8)
            }

            // The storage note doubles as the notes' heading: where they live is
            // the one thing a coach needs to know before adding one, and the
            // add action sits beside it rather than in a bar the host owns.
            // Android's `NotesHeader`, which sets the same compact pill.
            HStack(spacing: 12) {
                Text("Notes are saved on this phone and are removed if you remove the video from the app.")
                    .shuttlType(ShuttlType.bodySmall)
                    .foregroundStyle(Shuttl.textTertiary)
                    .frame(maxWidth: .infinity, alignment: .leading)
                Button {
                    addSheet = AddSheetItem(timestamp: model.currentTimestampSeconds())
                } label: {
                    Label(MatchPageCopy.shared.ADD_NOTE, systemImage: "plus")
                        .lineLimit(1)
                }
                .buttonStyle(CompactPillButtonStyle())
                .fixedSize()
            }
            .padding(.horizontal, ShuttlGutter.page)
            .padding(.top, 8)

            List {
                ForEach(model.annotations, id: \.id) { annotation in
                    annotationRow(annotation, model: model)
                        // The page's gutter, as the header above takes: at the
                        // list's own inset the notes stood out of line with it.
                        .listRowInsets(NoteRow.insets)
                        .listRowSeparatorTint(Shuttl.border)
                        .listRowBackground(Color.clear)
                }
            }
            .listStyle(.plain)
            .scrollContentBackground(.hidden)
        }
        .background(Shuttl.bg)
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

    /// The live entry while it is this model's, else the one the model was
    /// built from: after the host swaps entries the old live one stands until
    /// the new entry's first emission, and must not lend the new video its
    /// description.
    private func shownEntry(_ model: LocalPlayerModel) -> LocalVideoEntry {
        if let liveEntry, liveEntry.id == model.entry.id { return liveEntry }
        return model.entry
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

/// A note row's insets: the page's gutter either side, and UIKit's own cell
/// margin above and below, which is what the list gave these rows before.
private enum NoteRow {
    static let insets = EdgeInsets(top: 11, leading: ShuttlGutter.page, bottom: 11, trailing: ShuttlGutter.page)
}

private struct AddSheetItem: Identifiable {
    let timestamp: Float
    let id = UUID()
}
