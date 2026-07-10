import SwiftUI
import Shared

struct ShareSheetView: View {
    let rally: RallyApp
    let videoId: String
    @State private var email = ""
    @State private var recipients: [MatchShare] = []
    @State private var isBusy = false
    @State private var error: String? = nil

    var body: some View {
        VStack(alignment: .leading, spacing: 16) {
            Text("Share match")
                .font(.headline)
            TextField("Email", text: $email)
                .keyboardType(.emailAddress)
                .textInputAutocapitalization(.never)
                .autocorrectionDisabled()
                .padding(12)
                .background(Shuttl.bgInput)
            if let error {
                Text(error)
                    .font(.footnote)
                    .foregroundStyle(Shuttl.error)
            }
            HStack {
                Spacer()
                Button("Share") { share() }
                    .buttonStyle(PrimaryButtonStyle())
                    .frame(maxWidth: 120)
                    .disabled(isBusy || email.trimmingCharacters(in: .whitespaces).isEmpty)
            }
            Divider()
            Shuttl.sectionLabel("People with access")
            if recipients.isEmpty {
                Text("No one yet.")
                    .font(.subheadline)
                    .foregroundStyle(Shuttl.textSecondary)
            }
            ForEach(recipients, id: \.sharedWithUserId) { r in
                HStack {
                    Text(r.email ?? "Unknown user")
                        .frame(maxWidth: .infinity, alignment: .leading)
                    Button {
                        unshare(userId: r.sharedWithUserId)
                    } label: {
                        Image(systemName: "xmark")
                    }
                    .accessibilityLabel("Remove access")
                }
            }
            Spacer()
        }
        .padding(16)
        .presentationDetents([.medium])
        .task { await refresh() }
    }

    private func refresh() async {
        if let shares = try? await SwiftInteropKt.listSharesOrNull(rally.shares, videoId: videoId) {
            recipients = shares
        }
    }

    private func share() {
        let trimmed = email.trimmingCharacters(in: .whitespaces)
        guard !trimmed.isEmpty else { return }
        isBusy = true
        error = nil
        Task {
            do {
                let message = try await SwiftInteropKt.shareOrMessage(
                    rally.shares, videoId: videoId, email: trimmed
                )
                if let message {
                    error = message
                } else {
                    email = ""
                    await refresh()
                }
            } catch {
                self.error = "Could not share — please try again."
            }
            isBusy = false
        }
    }

    private func unshare(userId: String) {
        isBusy = true
        Task {
            do {
                let message = try await SwiftInteropKt.unshareOrMessage(
                    rally.shares, videoId: videoId, userId: userId
                )
                if let message { error = message } else { await refresh() }
            } catch {
                self.error = "Couldn't remove access — please try again."
            }
            isBusy = false
        }
    }
}
