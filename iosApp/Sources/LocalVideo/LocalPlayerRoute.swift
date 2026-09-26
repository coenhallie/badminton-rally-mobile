import Shared
import SwiftUI

/// Navigation value for opening the local player (distinct from the String
/// destination used for remote match ids).
struct LocalPlayerRoute: Hashable {
    let entryId: String
}

extension LocalVideoEntry: Identifiable {}
