import AVFoundation
import SwiftUI
import Shared

/// Lightweight AVFoundation QR scanner sheet. Uses the app's existing camera
/// permission (`NSCameraUsageDescription`) and shows a friendly fallback on
/// devices without a camera or when camera access is denied.
struct QrCodeScannerSheet: View {
    let onScanned: (String) -> Void

    @Environment(\.dismiss) private var dismiss
    @State private var unavailableReason: String? = nil

    var body: some View {
        NavigationStack {
            Group {
                if let unavailableReason {
                    VStack(spacing: 16) {
                        Text(unavailableReason)
                            .shuttlType(ShuttlType.bodyMedium)
                            .foregroundStyle(Shuttl.textSecondary)
                            .multilineTextAlignment(.center)
                            .padding(.horizontal, 32)
                    }
                    .frame(maxWidth: .infinity, maxHeight: .infinity)
                    .background(Shuttl.bg)
                } else {
                    QrCameraRepresentable(
                        onScanned: { payload in
                            onScanned(payload)
                            dismiss()
                        },
                        onUnavailable: {
                            unavailableReason = PlayerInviteCopy.shared.JOIN_QR_UNAVAILABLE
                        }
                    )
                    .ignoresSafeArea(edges: .bottom)
                }
            }
            .navigationTitle(PlayerInviteCopy.shared.JOIN_SCAN_QR)
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .cancellationAction) {
                    Button("Cancel") { dismiss() }
                }
            }
        }
        .task {
            await checkCameraAvailability()
        }
    }

    private func checkCameraAvailability() async {
        guard AVCaptureDevice.default(for: .video) != nil else {
            unavailableReason = PlayerInviteCopy.shared.JOIN_QR_UNAVAILABLE
            return
        }
        switch AVCaptureDevice.authorizationStatus(for: .video) {
        case .authorized:
            unavailableReason = nil
        case .notDetermined:
            let granted = await AVCaptureDevice.requestAccess(for: .video)
            if !granted {
                unavailableReason = PlayerInviteCopy.shared.JOIN_QR_UNAVAILABLE
            }
        case .denied, .restricted:
            unavailableReason = PlayerInviteCopy.shared.JOIN_QR_UNAVAILABLE
        @unknown default:
            unavailableReason = PlayerInviteCopy.shared.JOIN_QR_UNAVAILABLE
        }
    }
}

private struct QrCameraRepresentable: UIViewControllerRepresentable {
    let onScanned: (String) -> Void
    let onUnavailable: () -> Void

    func makeUIViewController(context: Context) -> QrScannerViewController {
        QrScannerViewController(onScanned: onScanned, onUnavailable: onUnavailable)
    }

    func updateUIViewController(_ uiViewController: QrScannerViewController, context: Context) {}
}

private final class QrScannerViewController: UIViewController, AVCaptureMetadataOutputObjectsDelegate {
    private let onScanned: (String) -> Void
    private let onUnavailable: () -> Void
    private let session = AVCaptureSession()
    private var previewLayer: AVCaptureVideoPreviewLayer?
    private var hasDelivered = false

    init(onScanned: @escaping (String) -> Void, onUnavailable: @escaping () -> Void) {
        self.onScanned = onScanned
        self.onUnavailable = onUnavailable
        super.init(nibName: nil, bundle: nil)
    }

    @available(*, unavailable)
    required init?(coder: NSCoder) { nil }

    override func viewDidLoad() {
        super.viewDidLoad()
        view.backgroundColor = .black

        guard let device = AVCaptureDevice.default(for: .video),
              let input = try? AVCaptureDeviceInput(device: device),
              session.canAddInput(input)
        else {
            onUnavailable()
            return
        }
        session.addInput(input)

        let output = AVCaptureMetadataOutput()
        guard session.canAddOutput(output) else {
            onUnavailable()
            return
        }
        session.addOutput(output)
        output.setMetadataObjectsDelegate(self, queue: .main)
        output.metadataObjectTypes = [.qr]

        let preview = AVCaptureVideoPreviewLayer(session: session)
        preview.videoGravity = .resizeAspectFill
        preview.frame = view.bounds
        view.layer.addSublayer(preview)
        previewLayer = preview

        DispatchQueue.global(qos: .userInitiated).async { [weak self] in
            self?.session.startRunning()
        }
    }

    override func viewDidLayoutSubviews() {
        super.viewDidLayoutSubviews()
        previewLayer?.frame = view.bounds
    }

    override func viewWillDisappear(_ animated: Bool) {
        super.viewWillDisappear(animated)
        if session.isRunning {
            DispatchQueue.global(qos: .userInitiated).async { [weak self] in
                self?.session.stopRunning()
            }
        }
    }

    func metadataOutput(
        _ output: AVCaptureMetadataOutput,
        didOutput metadataObjects: [AVMetadataObject],
        from connection: AVCaptureConnection
    ) {
        guard !hasDelivered,
              let first = metadataObjects.first as? AVMetadataMachineReadableCodeObject,
              let value = first.stringValue
        else { return }
        hasDelivered = true
        session.stopRunning()
        onScanned(value)
    }
}
