import SwiftUI

/// A player page's body: the player and what sits with it (the head) pinned,
/// with the notes scrolling under it, as Android's portrait layout has them.
/// When the head leaves the notes less than `minimumNotesHeight`, the whole
/// body scrolls as one instead, the notes drawn as rows under the head.
///
/// Decided by measuring, not by the text size alone: the player keeps its own
/// height (`ShuttlPlayer`), so nothing in a pinned layout gives way, and at a
/// large text size, on a short phone, or on a match page with a banner and a
/// section row above it, the notes list was squeezed to nothing and the head's
/// last lines cut short. The head is measured at its natural height in both
/// layouts, so the choice cannot flip back and forth.
///
/// One structure for both: the head sits in the same scroll view either way
/// (held to its own height and still while pinned), so switching never
/// rebuilds it. That matters because the head holds the player, whose
/// fullscreen cover is its own state: fullscreen turns the window to landscape,
/// the page underneath goes one scroll, and a rebuilt player would close the
/// cover it is showing.
///
/// Used by the match page's Match section and by the rally page.
struct PlayerNotesLayout<Item, ID: Hashable, Head: View, Row: View>: View {
    let notes: [Item]
    let id: KeyPath<Item, ID>
    /// Each note row's insets, in both layouts; the rule under a row takes the
    /// same side insets.
    let rowInsets: EdgeInsets
    @ViewBuilder let head: () -> Head
    @ViewBuilder let row: (Item) -> Row

    @State private var headHeight: CGFloat = 0

    /// Two note rows at the default text size, about 44pt each: less than that
    /// under a pinned head reads as a list that is not there.
    static var minimumNotesHeight: CGFloat { 88 }

    /// Undecided until the head has been measured, and drawn as one scroll
    /// meanwhile, which lays the head out at the same place.
    private func pinned(in available: CGFloat) -> Bool {
        headHeight > 0 && available - headHeight >= Self.minimumNotesHeight
    }

    var body: some View {
        // The size offered, taken whole: the body's width must come from the
        // screen and never from its content. The transport sizes its pills
        // from the width it is given, and a scroll view offers its content
        // what the content asks for, so once the row had been laid out wide
        // (under a fullscreen cover, in landscape) it kept itself wide back in
        // portrait and pushed the page past both edges.
        GeometryReader { proxy in
            let pinned = pinned(in: proxy.size.height)
            stack(pinned: pinned, width: proxy.size.width)
        }
    }

    private func stack(pinned: Bool, width: CGFloat) -> some View {
        VStack(spacing: 0) {
            ScrollView {
                VStack(spacing: 0) {
                    // One stack around the head, so it is measured whole and
                    // not one of its parts at a time.
                    VStack(spacing: 0) { head() }
                        .fixedSize(horizontal: false, vertical: true)
                        .onGeometryChange(for: CGFloat.self, of: { $0.size.height }) { headHeight = $0 }
                    if !pinned {
                        ForEach(notes, id: id) { note in
                            row(note)
                                .padding(rowInsets)
                            Divider()
                                .overlay(Shuttl.border)
                                .padding(.leading, rowInsets.leading)
                                .padding(.trailing, rowInsets.trailing)
                        }
                    }
                }
                // The screen's width, not the content's: at accessibility
                // sizes the transport's minimum is wider than the screen, and a
                // scroll view grows to its content.
                .frame(width: width)
            }
            .scrollDisabled(pinned)
            .scrollBounceBehavior(.basedOnSize)
            .frame(height: pinned ? headHeight : nil)
            if pinned {
                List {
                    ForEach(notes, id: id) { note in
                        row(note)
                            .listRowInsets(rowInsets)
                            // The rule within the row's insets at both ends:
                            // the system's stops 16pt short of the trailing
                            // edge, out of line with everything above it.
                            .alignmentGuide(.listRowSeparatorTrailing) { $0.width }
                            .listRowSeparatorTint(Shuttl.border)
                            .listRowBackground(Color.clear)
                    }
                }
                .listStyle(.plain)
                .scrollContentBackground(.hidden)
            }
        }
    }
}
