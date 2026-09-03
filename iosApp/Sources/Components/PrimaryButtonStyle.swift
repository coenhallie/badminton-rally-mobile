import SwiftUI

struct PrimaryButtonStyle: ButtonStyle {
    func makeBody(configuration: Configuration) -> some View {
        configuration.label
            .shuttlType(ShuttlType.titleLarge)
            .foregroundStyle(Shuttl.onAccent)
            .frame(maxWidth: .infinity)
            .padding(.vertical, 14)
            .background(Shuttl.accent.opacity(configuration.isPressed ? 0.8 : 1))
            .clipShape(Capsule())
    }
}
