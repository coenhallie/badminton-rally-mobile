package com.badmintontracker.shared.exercises

/** Whether an exercise is performed on court with badminton footwork/shuttles or off court. */
enum class ExerciseCategory(val label: String) {
    ON_COURT("On-court"),
    OFF_COURT("Off-court"),
}

/** Primary training focus addressed by an exercise. */
enum class FocusArea(val label: String) {
    BASE_RECOVERY("Base recovery"),
    COURT_COVERAGE("Court coverage"),
    LATERAL_BALANCE("Lateral balance"),
    ENDURANCE("Endurance"),
    EXPLOSIVE_SPEED("Explosive speed"),
    ATTACK("Attack"),
    DEFENCE("Defence"),
    NET_PLAY("Net play"),
    SERVE_RETURN("Serve & return"),
    STRENGTH_MOBILITY("Strength & mobility"),
}

/** Category filter pills for browsing the full drill library. */
enum class DrillLibraryFilter(val label: String) {
    ALL("All"),
    FOOTWORK("Footwork"),
    ATTACK("Attack"),
    DEFENCE("Defence"),
    NET_AND_SERVE("Net & serve"),
    OFF_COURT("Off-court"),
}

/** Measured positioning or movement finding that triggered a suggestion. */
enum class FindingKind {
    DEEP_BASE,
    SHALLOW_BASE,
    LATERAL_BIAS,
    BASE_DRIFT_LATE,
    INCONSISTENT_BASE,
    LOW_FRONT_PRESENCE,
    LATE_SPEED_DROP,
}

/** Outcome of evaluating one analysed video track. */
enum class ExerciseReportStatus {
    INSUFFICIENT_DATA,
    BALANCED,
    SUGGESTIONS,
}

/** One curated badminton drill or conditioning exercise. */
data class Exercise(
    val id: String,
    val title: String,
    val category: ExerciseCategory,
    val focus: FocusArea,
    val prescription: String,
    val setup: String,
    val steps: List<String>,
    val coachingCue: String,
)

/** One ranked exercise suggestion tied to the finding and measured evidence behind it. */
data class ExerciseSuggestion(
    val exercise: Exercise,
    val finding: FindingKind,
    val evidence: String,
    val severity: Double,
)

/** Compact headline measurements shown above the suggestions or status message. */
data class MovementSummary(
    val countedRallies: Int,
    val coveragePercent: Int,
    val peakSpeedText: String?,
    val frontPercent: Int,
    val midPercent: Int,
    val rearPercent: Int,
)

/** Full result rendered by the Drills panel for one player side. */
data class ExerciseReport(
    val status: ExerciseReportStatus,
    val message: String?,
    val summary: MovementSummary?,
    val suggestions: List<ExerciseSuggestion>,
)
