import SwiftUI

struct SplashView: View {
    var body: some View {
        VStack(spacing: 12) {
            Text("Shuttl")
            ProgressView()
        }
        .frame(maxWidth: .infinity, maxHeight: .infinity)
    }
}
