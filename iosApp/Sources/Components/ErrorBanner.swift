import SwiftUI

struct ErrorBanner: View {
    let message: String

    var body: some View {
        Text(message)
            .shuttlType(ShuttlType.bodySmall)
            .foregroundStyle(Shuttl.onError)
            .frame(maxWidth: .infinity, alignment: .leading)
            .padding(12)
            .background(Shuttl.error)
    }
}
