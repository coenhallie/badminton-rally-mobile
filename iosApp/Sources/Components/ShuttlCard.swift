import SwiftUI

struct ShuttlCard<Content: View>: View {
    @ViewBuilder let content: Content

    var body: some View {
        VStack(alignment: .leading, spacing: 0) { content }
            .padding(16)
            .frame(maxWidth: .infinity)
            .background(Shuttl.bgSecondary)
            .overlay(Rectangle().stroke(Shuttl.border, lineWidth: 1))
    }
}
