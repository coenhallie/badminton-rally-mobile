import SwiftUI
import Shared

struct SignInView: View {
    let rally: RallyApp
    @State private var email = ""
    @State private var password = ""
    @State private var isSubmitting = false
    @State private var error: String? = nil

    var body: some View {
        ScrollView {
            VStack(spacing: 0) {
                brand
                Spacer().frame(height: 8)
                Text("Sign in to continue")
                    .font(.footnote)
                    .foregroundStyle(Shuttl.textSecondary)
                Spacer().frame(height: 24)
                ShuttlCard {
                    VStack(spacing: 16) {
                        field("Email", text: $email)
                            .keyboardType(.emailAddress)
                            .textContentType(.username)
                        secureField("Password", text: $password)
                        Button(isSubmitting ? "Signing in…" : "Sign in") { submit() }
                            .buttonStyle(PrimaryButtonStyle())
                            .disabled(isSubmitting)
                        if let error {
                            ErrorBanner(message: error)
                        }
                    }
                }
                Spacer().frame(height: 24)
                Text("Registration is closed. Contact the admin if you need an account.")
                    .font(.footnote)
                    .foregroundStyle(Shuttl.textTertiary)
                    .multilineTextAlignment(.center)
                    .frame(maxWidth: 320)
                Spacer().frame(height: 8)
                Text(versionLabel)
                    .font(.footnote)
                    .foregroundStyle(Shuttl.textTertiary)
            }
            .frame(maxWidth: 400)
            .padding(.horizontal, 16)
            .padding(.top, 56)
            .frame(maxWidth: .infinity)
        }
        .background(Shuttl.bg)
    }

    private var brand: some View {
        HStack(spacing: 8) {
            Text("SHUTTL.")
                .font(.system(size: 24, weight: .bold))
                .kerning(-0.24)
                .foregroundStyle(Shuttl.textHeading)
            Text("BETA 2.0")
                .font(.system(size: 9, weight: .semibold))
                .kerning(0.5)
                .foregroundStyle(.black)
                .padding(.horizontal, 6)
                .padding(.vertical, 2)
                .background(LinearGradient(
                    colors: [Shuttl.accent, Shuttl.accentDark],
                    startPoint: .leading, endPoint: .trailing
                ))
        }
    }

    private func field(_ label: String, text: Binding<String>) -> some View {
        TextField(label, text: text)
            .textInputAutocapitalization(.never)
            .autocorrectionDisabled()
            .padding(12)
            .background(Shuttl.bgInput)
    }

    private func secureField(_ label: String, text: Binding<String>) -> some View {
        SecureField(label, text: text)
            .padding(12)
            .background(Shuttl.bgInput)
    }

    private var versionLabel: String {
        let v = Bundle.main.infoDictionary?["CFBundleShortVersionString"] as? String ?? "?"
        let b = Bundle.main.infoDictionary?["CFBundleVersion"] as? String ?? "?"
        return "Version \(v) (\(b))"
    }

    private func submit() {
        isSubmitting = true
        error = nil
        Task {
            do {
                let message = try await SwiftInteropKt.signInEmailOrMessage(
                    rally.auth, email: email, password: password
                )
                if let message { error = message }
            } catch {
                self.error = "Something went wrong. Please check your connection and try again."
            }
            isSubmitting = false
        }
    }
}
