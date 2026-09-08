import SwiftUI

/// The matches drawer: the list that used to be the app's front door, now
/// behind Home.
///
/// A hand built overlay because SwiftUI has no drawer primitive. Arithmetic
/// lives in DrawerDragMath; this is the presentation.
///
/// One deliberate limitation: a close-drag is accepted on the scrim and on this
/// panel's own header, but NOT from inside the list body. The rows carry
/// `.swipeActions`, and a horizontal drag there is ambiguous between revealing
/// a row's Delete and dismissing the whole drawer. Losing a gesture is better
/// than a list where swiping a row sometimes closes the screen.
struct MatchesDrawer<Content: View>: View {
    @Binding var isOpen: Bool
    let onLabels: () -> Void
    let onSignOut: () -> Void
    @ViewBuilder let content: () -> Content

    @State private var dragTranslation: CGFloat = 0

    var body: some View {
        GeometryReader { geo in
            let width = DrawerDragMath.width(forScreenWidth: geo.size.width)
            let offset = DrawerDragMath.offset(
                translation: dragTranslation, width: width, isOpen: isOpen
            )
            let scrim = DrawerDragMath.scrimOpacity(offset: offset, width: width)

            ZStack(alignment: .leading) {
                Color.black.opacity(0.55 * scrim)
                    .ignoresSafeArea()
                    .allowsHitTesting(scrim > DrawerDragMath.minimumHitTestableScrimOpacity)
                    .onTapGesture { withAnimation(.snappy(duration: 0.24)) { isOpen = false } }

                panel(width: width)
                    .offset(x: offset)
            }
            .animation(dragTranslation == 0 ? .snappy(duration: 0.24) : nil, value: isOpen)
        }
    }

    private func panel(width: CGFloat) -> some View {
        VStack(spacing: 0) {
            header
            content()
            footer
        }
        .frame(width: width)
        // `bg`, not the `bgInput` this used to be. The mock's panel is one step
        // below its cards, and bgInput is byte identical to bgTertiary in the
        // light theme (both 0xEDF0EE), so a bgTertiary card on a bgInput panel
        // is invisible in light. `bg` is the light theme's white, which the
        // cards then sit on the way they do in the dark mock. Mirrored by
        // HomeScreen.kt's own drawerContainerColor.
        .background(Shuttl.bg)
        .overlay(alignment: .trailing) { Rectangle().fill(Shuttl.border).frame(width: 1) }
        .ignoresSafeArea(edges: .bottom)
    }

    private var header: some View {
        HStack {
            Text("Matches")
                .shuttlType(ShuttlType.headlineMedium)
                .foregroundStyle(Shuttl.textHeading)
            Spacer()
            Button { withAnimation(.snappy(duration: 0.24)) { isOpen = false } } label: {
                Image(systemName: "xmark")
                    .foregroundStyle(Shuttl.textSecondary)
            }
            .accessibilityLabel("Close matches")
        }
        .padding(.leading, 24)
        .padding(.trailing, 20)
        .padding(.top, 22)
        .contentShape(Rectangle())
        .gesture(closeDrag)
    }

    private var footer: some View {
        VStack(alignment: .leading, spacing: 2) {
            Divider().overlay(Shuttl.border)
                .padding(.bottom, 10)
            Button("Labels", action: onLabels)
                .shuttlType(ShuttlType.bodyMedium)
                .foregroundStyle(Shuttl.text)
                .padding(.horizontal, 24).padding(.vertical, 13)
            Button("Sign out", action: onSignOut)
                .shuttlType(ShuttlType.bodyMedium)
                .foregroundStyle(Shuttl.text)
                .padding(.horizontal, 24).padding(.vertical, 13)
            Text(versionLabel())
                .shuttlType(ShuttlType.bodySmall)
                .foregroundStyle(Shuttl.textTertiary)
                .padding(.horizontal, 24)
                .padding(.top, 6)
                .padding(.bottom, 26)
        }
    }

    private var closeDrag: some Gesture {
        DragGesture()
            .onChanged { dragTranslation = DrawerDragMath.closingTranslation($0.translation.width) }
            .onEnded { value in
                let shouldClose = DrawerDragMath.shouldClose(
                    translation: value.translation.width, velocity: value.velocity.width
                )
                dragTranslation = 0
                withAnimation(.snappy(duration: 0.24)) { isOpen = !shouldClose }
            }
    }
}
