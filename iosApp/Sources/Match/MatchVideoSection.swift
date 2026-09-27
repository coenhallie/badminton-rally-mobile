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
                // The row's own action, under the video it would analyze, rather
                // than in the bar: the page's bar belongs to the whole match.
                // Between the transport and the chips, where Android has it.
                primaryAction: analyzeLabel.map { PlayerPrimaryAction(label: $0, perform: onAnalyze) },
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
            // No top padding: the page puts `MatchPageLayout.sectionGap` above
            // every section, so each draws from its own top edge.

            if let actionError = model.actionError {
                ErrorBanner(message: actionError)
                    .padding(.top, 8)
                    .onTapGesture { model.actionError = nil }
            }

            if let description = shownEntry(model).description_ {
                Text(description)
                    .shuttlType(ShuttlType.titleMedium)
                    .foregroundStyle(Shuttl.textSecondary)
                    .frame(maxWidth: .infinity, alignment: .leading)
                    .padding(.horizontal, ShuttlGutter.page)
                    .padding(.top, NotesHeaderMetrics.gap)
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
                        // Android's pill is its 18dp icon tall: 30 in all.
                        .frame(minHeight: NotesHeaderMetrics.pillContent)
                }
                .buttonStyle(CompactPillButtonStyle())
                .fixedSize()
                // A 44pt target around the 30pt pill, reaching above and below
                // it without making the header taller than its caption.
                .contentShape(
                    Rectangle().inset(by: -(44 - NotesHeaderMetrics.pillContent - 12) / 2)
                )
            }
            .padding(.horizontal, ShuttlGutter.page)
            // Android's `NotesHeader`: 22 under whatever is above it, 8 over
            // the first note.
            .padding(.top, NotesHeaderMetrics.gap)
            .padding(.bottom, 8)

            List {
                ForEach(model.annotations, id: \.id) { annotation in
                    annotationRow(annotation, model: model)
                        // The page's gutter, as the header above takes: at the
                        // list's own inset the notes stood out of line with it.
                        .listRowInsets(NoteRow.insets)
                        // The rule in the gutter at both ends, as Android's
                        // between notes: the system's stops 16pt short of the
                        // trailing edge, out of line with the header's pill.
                        .alignmentGuide(.listRowSeparatorTrailing) { $0.width }
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

/// The notes header's rhythm, androidApp's `NotesHeader` and the description
/// above it: 22 over each, and a pill whose content is 18 tall (Android's icon
/// size), so 30 with the style's 6 above and below.
private enum NotesHeaderMetrics {
    static let gap: CGFloat = 22
    static let pillContent: CGFloat = 18
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
