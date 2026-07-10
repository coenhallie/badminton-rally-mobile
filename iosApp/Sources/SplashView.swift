import SwiftUI

struct SplashView: View {
    var body: some View {
        VStack(spacing: 12) {
            Text("Rally Clips")
            ProgressView()
        }
        .frame(maxWidth: .infinity, maxHeight: .infinity)
    }
}
