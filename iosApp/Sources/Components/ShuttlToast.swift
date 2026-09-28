import SwiftUI

/// A short message over the bottom of a screen, gone after four seconds: what
/// a finished run says on the match list. iOS has no system snackbar, and the
/// inline `ErrorBanner` is for failures that stay until dealt with.
///
/// Posts a VoiceOver announcement when it appears, since a message that
/// leaves on its own is otherwise missed by anyone not looking at it. While
/// VoiceOver runs it does not leave on its own at all: four seconds is not
/// enough to find and activate its action by swiping. It then stays until
/// its action, a new message, or leaving the screen. Android's snackbar does
/// not: with an action it stays ten seconds, or the user's accessibility
/// timeout if that is longer, and then leaves.
///
/// Identity is the `id`, not the text: two runs of the same match landing a
/// minute apart are two messages, and the toast restarts its four seconds for
/// the second.
struct ToastMessage: Equatable {
    let id = UUID()
    let text: String
    let actionLabel: String?
    let action: (() -> Void)?

    static func == (a: ToastMessage, b: ToastMessage) -> Bool { a.id == b.id }
}

/// The toast itself: the page inverted, so it reads as sitting over the list
/// rather than in it. Mirrors androidApp's snackbar, whose three colours are
/// the same three tokens (`inverseSurface`, `inverseOnSurface`,
/// `inversePrimary` in ShuttlColors.kt).
private struct ShuttlToast: View {
    let message: ToastMessage
    let onDone: () -> Void

    @Environment(\.accessibilityVoiceOverEnabled) private var voiceOver

    /// What the timer restarts on: a new message, or VoiceOver turning on or
    /// off while one shows.
    private struct TimerKey: Hashable {
        let message: UUID
        let voiceOver: Bool
    }

    var body: some View {
        HStack(spacing: 12) {
            Text(message.text)
                .shuttlType(ShuttlType.bodyMedium)
                .foregroundStyle(Shuttl.bg)
                .lineLimit(2)
                .frame(maxWidth: .infinity, alignment: .leading)
                // Air above and below a second line; one line is held to 48
                // below, Material's snackbar height, which the action's 44pt
                // target also fits inside.
                .padding(.vertical, 14)
            if let label = message.actionLabel, let action = message.action {
                Button { action(); onDone() } label: {
                    Text(label)
                        .shuttlType(ShuttlType.labelMedium)
                        // Not `accent`: that is a fill colour, 1.5:1 on this
                        // surface in dark. See `ShuttlPalette.inverseAccent`.
                        .foregroundStyle(Shuttl.inverseAccent)
                        .frame(minWidth: 44, minHeight: 44)
                        .contentShape(Rectangle())
                }
                .buttonStyle(.plain)
            }
        }
        // Less on the trailing side under an action: its 44pt target already
        // carries air around the word, which then sits about 16 in from the
        // edge, as the text does on the leading side.
        .padding(.leading, 16)
        .padding(.trailing, message.action == nil ? 16 : 8)
        .frame(minHeight: 48)
        .background(Shuttl.textHeading, in: RoundedRectangle(cornerRadius: ShuttlRadius.medium, style: .continuous))
        .padding(.horizontal, ShuttlGutter.page)
        .accessibilityElement(children: .contain)
        .task(id: message.id) {
            AccessibilityNotification.Announcement(message.text).post()
        }
        .task(id: TimerKey(message: message.id, voiceOver: voiceOver)) {
            guard !voiceOver else { return }
            // A cancelled wait dismisses nothing: the task is cancelled by a
            // replacement and by VoiceOver changing, and neither is a reason
            // for the message on screen to go.
            do { try await Task.sleep(for: .seconds(4)) } catch { return }
            onDone()
        }
        // Leaving the screen ends it, timer or not: a push over the list
        // must not leave it waiting there when the coach comes back.
        .onDisappear(perform: onDone)
    }
}

extension View {
    /// Shows `toast` at the bottom, `bottomInset` above the edge, until it
    /// times out (not under VoiceOver), its action runs, or the screen goes.
    /// Setting a new message while one shows replaces it and restarts the
    /// clock.
    func shuttlToast(_ toast: Binding<ToastMessage?>, bottomInset: CGFloat) -> some View {
        overlay(alignment: .bottom) {
            ZStack {
                if let message = toast.wrappedValue {
                    ShuttlToast(message: message) {
                        // Only this message's own dismissal clears the
                        // binding, so a late call for a message already
                        // replaced cannot take the new one down with it.
                        if toast.wrappedValue == message { toast.wrappedValue = nil }
                    }
                    .padding(.bottom, bottomInset)
                    .transition(.move(edge: .bottom).combined(with: .opacity))
                }
            }
            // On the toast's own container, not the screen: a run that lands
            // changes its row in the same update that raises the toast, and
            // the row must not animate along with it.
            .animation(.default, value: toast.wrappedValue)
        }
    }
}
