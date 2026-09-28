import Shared

/// What a change in the finished runs does to the list's toast: the seen set
/// to keep, and the runs the toast should now name.
struct RunToastUpdate: Equatable {
    /// Pass back in as `seen` next time. See `announceFinishedRuns`.
    let seen: Set<String>
    /// The runs the toast should name, or nil when there is nothing new to
    /// say and the toast (if any) stays as it is.
    let toastRuns: Set<String>?
}

/// HomeView's announcement rule, kept out of the view so it can be tested:
/// gathers the finished runs of both pipelines, leaves out device runs that
/// found nothing, applies the shared seen-set rule, and merges what is new
/// with what a toast on screen already says.
///
/// A device run that found nothing (no rally, no track, no skeleton) is not
/// "analyzed", and its row still offers Analyze; it is dropped from its own
/// outcome (`Done.foundSomething`) rather than from the list, which can lag a
/// run. Cloud runs are not filtered: one that finds no rallies fails instead
/// of finishing. The two sets are unioned so a video analyzed both ways
/// announces once. Mirrors the watcher in androidApp's AuthGate.
///
/// `showing` is the runs the toast on screen names, nil when none is up. Two
/// runs landing seconds apart then read as one "2 matches" toast rather than
/// the second wiping out the first; once the toast has gone, a new one starts
/// from the new runs alone.
func runToastUpdate(
    deviceStates: [String: LocalAnalysisState],
    cloudFinished: Set<String>,
    seen: Set<String>,
    onHome: Bool,
    showing: Set<String>?
) -> RunToastUpdate {
    let device = deviceStates.compactMap { id, state -> String? in
        if case .done(let done) = state, done.foundSomething { return id } else { return nil }
    }
    let announcement = RunAnnouncementKt.announceFinishedRuns(
        finished: Set(device).union(cloudFinished),
        seen: seen,
        onHome: onHome
    )
    guard !announcement.announce.isEmpty else {
        return RunToastUpdate(seen: announcement.seen, toastRuns: nil)
    }
    return RunToastUpdate(
        seen: announcement.seen,
        toastRuns: (showing ?? []).union(announcement.announce)
    )
}
