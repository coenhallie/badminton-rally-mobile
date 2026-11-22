import SwiftUI
import Shared

struct ShareSheetView: View {
    let rally: RallyApp
    let videoId: String
    @State private var email = ""
    @State private var recipients: [MatchShare] = []
    @State private var isLoading = true
    @State private var loadError: String? = nil
    @State private var isBusy = false
    @State private var error: String? = nil
    @State private var pendingRevoke: MatchShare? = nil

    var body: some View {
        ScrollView {
            VStack(alignment: .leading, spacing: 16) {
                Text("Share match")
                    .shuttlType(ShuttlType.headlineMedium)
                    .foregroundStyle(Shuttl.textHeading)
                    .accessibilityAddTraits(.isHeader)
                ShuttlTextField("Email", text: $email)
                    .keyboardType(.emailAddress)
                    .textContentType(.emailAddress)
                    .textInputAutocapitalization(.never)
                    .autocorrectionDisabled()
                    .submitLabel(.done)
                    .onSubmit {
                        if !isBusy && !email.trimmingCharacters(in: .whitespaces).isEmpty {
                            share()
                        }
                    }
                    .onChange(of: email) { _, _ in
                        if error != nil { error = nil }
                    }
                if let error {
                    ErrorBanner(message: error)
                }
                HStack {
                    Spacer()
                    Button("Share") { share() }
                        .buttonStyle(PrimaryButtonStyle())
                        .frame(maxWidth: 120)
                        .disabled(isBusy || email.trimmingCharacters(in: .whitespaces).isEmpty)
                }
                Divider().overlay(Shuttl.border)
                Shuttl.sectionLabel("People with access")
                if isLoading {
                    ProgressView()
                        .tint(Shuttl.accent)
                        .frame(maxWidth: .infinity)
                        .padding(.vertical, 12)
                } else if let loadError, recipients.isEmpty {
                    VStack(alignment: .leading, spacing: 10) {
                        ErrorBanner(message: loadError)
                        Button("Retry") {
                            Task { await refresh(showLoading: true) }
                        }
                        .buttonStyle(CompactPillButtonStyle(secondary: true, fullTarget: true))
                    }
                } else if recipients.isEmpty {
                    Text("No one yet.")
                        .shuttlType(ShuttlType.bodyMedium)
                        .foregroundStyle(Shuttl.textSecondary)
                } else {
                    if let loadError {
                        ErrorBanner(message: loadError)
                    }
                    ForEach(recipients, id: \.sharedWithUserId) { r in
                        HStack(spacing: 8) {
                            Text(r.email ?? "Unknown user")
                                .shuttlType(ShuttlType.bodyMedium)
                                .foregroundStyle(Shuttl.text)
                                .frame(maxWidth: .infinity, alignment: .leading)
                            Button {
                                pendingRevoke = r
                            } label: {
                                Image(systemName: "xmark")
                                    .shuttlType(ShuttlType.labelMedium)
                                    .foregroundStyle(Shuttl.textSecondary)
                                    .frame(width: ShuttlTarget.minimum, height: ShuttlTarget.minimum)
                                    .contentShape(Rectangle())
                            }
                            .buttonStyle(.borderless)
                            .disabled(isBusy)
                            .accessibilityLabel(r.email.map { "Remove \($0)" } ?? "Remove access")
                        }
                    }
                }
            }
            .padding(.horizontal, ShuttlGutter.page)
            .padding(.top, 24)
            .padding(.bottom, 16)
        }
        .scrollBounceBehavior(.basedOnSize)
        .scrollDismissesKeyboard(.interactively)
        .presentationDetents([.medium, .large])
        .presentationDragIndicator(.visible)
        .presentationBackground(Shuttl.bgSecondary)
        .task { await refresh(showLoading: true) }
        .alert(
            "Remove access?",
            isPresented: Binding(
                get: { pendingRevoke != nil },
                set: { if !$0 { pendingRevoke = nil } }
            ),
            presenting: pendingRevoke
        ) { recipient in
            Button("Remove", role: .destructive) {
                let userId = recipient.sharedWithUserId
                pendingRevoke = nil
                unshare(userId: userId)
            }
            Button("Cancel", role: .cancel) { pendingRevoke = nil }
        } message: { recipient in
            Text("\(recipient.email ?? "This person") will no longer be able to view this match.")
        }
    }

    private func refresh(showLoading: Bool = false) async {
        if showLoading {
            isLoading = true
            loadError = nil
        }
        defer { isLoading = false }
        do {
            if let shares = try await SwiftInteropKt.listSharesOrNull(rally.shares, videoId: videoId) {
                recipients = shares
                loadError = nil
            } else {
                loadError = "Couldn't load people with access."
            }
        } catch {
            if !Task.isCancelled {
                loadError = "Couldn't load people with access."
            }
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
        error = nil
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
