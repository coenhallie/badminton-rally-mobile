import SwiftUI
import Shared

func kindLabel(_ kind: AnnotationKind) -> String {
    switch kind {
    case .goodShot: return "Good shot"
    case .forcedError: return "Forced error"
    case .unforcedError: return "Unforced error"
    default: return ""
    }
}

struct KindBadge: View {
    let kind: AnnotationKind

    private var container: Color {
        switch kind {
        case .goodShot: return Color(rgb: 0x2E7D32)
        case .forcedError: return Color(rgb: 0xB26A00)
        case .unforcedError: return Color(rgb: 0xC62828)
        default: return Shuttl.bgTertiary
        }
    }
    private var onContainer: Color {
        kind == .forcedError ? .black : .white
    }

    var body: some View {
        Text(kindLabel(kind))
            .font(.system(size: 11, weight: .medium))
            .foregroundStyle(onContainer)
            .padding(.horizontal, 10)
            .padding(.vertical, 4)
            .background(Capsule().fill(container))
    }
}
