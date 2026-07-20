import Foundation
import Shared

/// Sort keys as a protocol so sorting is unit-testable via ClipInfo without Kotlin construction.
protocol ClipSortKeys {
    var rallyIndex: Int32 { get }
    var annotationCount: Int32 { get }
}

extension RallyClip: ClipSortKeys {}
extension ClipInfo: ClipSortKeys {}

enum ClipSort {
    case rallyOrder
    case mostNotes

    func sorted<T: ClipSortKeys>(_ clips: [T]) -> [T] {
        switch self {
        case .rallyOrder:
            return clips.sorted { $0.rallyIndex < $1.rallyIndex }
        case .mostNotes:
            return clips.sorted {
                if $0.annotationCount != $1.annotationCount {
                    return $0.annotationCount > $1.annotationCount
                }
                return $0.rallyIndex < $1.rallyIndex
            }
        }
    }
}
