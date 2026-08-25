/// The one piece of expansion state for the whole Labels screen: which row
/// currently shows its in-place editor. `.new` is the not-yet-created draft
/// row opened by the toolbar "+"; `.existing` is a real label being renamed
/// or recoloured. Both live in the same optional, so it is structurally
/// impossible for two editors to be open at once.
enum LabelEditTarget: Equatable {
    case existing(String)
    case new
}

/// Row expansion is its own toggle: tapping the open row (or the toolbar
/// "+" again) closes it.
enum LabelsLogic {
    static func nextExpanded(current: LabelEditTarget?, tapped: LabelEditTarget) -> LabelEditTarget? {
        current == tapped ? nil : tapped
    }
}
