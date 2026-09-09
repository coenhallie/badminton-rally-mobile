import CoreGraphics

/// The page gutter the mock lays every card in.
///
/// One token rather than the per-screen `private let GUTTER = 24` androidApp
/// repeats in each of these files - a habit iOS copied into four screens before
/// this. The older copies (`AnalyticsList.gutter`, `CourtHeatmapView.gutter`,
/// `ShuttlVideoCard`'s and `ShuttlTransportBar`'s) still stand; they are the
/// same number and are worth folding in here when those files are next touched.
enum ShuttlGutter {
    static let page: CGFloat = 24
}
