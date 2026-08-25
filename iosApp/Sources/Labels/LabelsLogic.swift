/// Row expansion is its own toggle: tapping the open row closes it.
enum LabelsLogic {
    static func nextExpanded(current: String?, tapped: String) -> String? {
        current == tapped ? nil : tapped
    }
}
