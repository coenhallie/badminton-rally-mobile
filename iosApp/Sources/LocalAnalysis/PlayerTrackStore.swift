import Foundation
import Shared

/// The player track and the clip index, kept on disk so a heatmap outlives the
/// process that made it.
///
/// Port of Android's `PlayerTrackStore`. An on-device run with pose costs
/// minutes to half an hour; holding its only copy in memory means an app swipe,
/// a reinstall or a termination throws that away and the coach is asked to spend
/// it again, which is not a reasonable thing to ask twice - and on iOS the app
/// being backgrounded is the normal case rather than the unlucky one.
///
/// A plain text table rather than a serialization framework: the shape is three
/// numbers and it has to be readable by a person debugging a bad heatmap. The
/// whole file for a 30-minute match is under a megabyte.
/// Which analysis produced an artifact: this phone's, or the cloud's.
///
/// Separate directories rather than a field inside one file. `skeletonAction`
/// holds that "a completed run is the new truth for its entry", which is right
/// for a device run and wrong across the two sources: a rally-only run would
/// otherwise delete a cloud skeleton the coach waited on a GPU for. Two
/// subtrees make that impossible rather than merely discouraged.
///
/// `local`'s directories are the ones already on every phone, unchanged,
/// because moving them would orphan every artifact a device run has written.
/// Mirrors Android's `TrackSource`, name for name and directory for directory.
enum TrackSource {
    case local
    case cloud

    var trackDir: String {
        switch self {
        case .local: return "player-tracks"
        case .cloud: return "cloud-tracks"
        }
    }

    var skeletonDir: String {
        switch self {
        case .local: return "skeletons"
        case .cloud: return "cloud-skeletons"
        }
    }
}

struct PlayerTrackStore {

    /// One player's path, and which half of the court they were on.
    struct SideTrack {
        let side: CourtSide
        let track: PlayerTrack
    }

    struct Stored {
        let tracks: [SideTrack]
        let fps: Double

        /// The near player, or nil when the file holds only a far one.
        ///
        /// Every caller that predates the far player wants this, and reading
        /// `tracks[0]` at each of them would break silently the first time a
        /// file arrived far-first.
        var near: PlayerTrack? { tracks.first { $0.side == .near }?.track }
    }

    /// Bumped if the columns change, so an old file is ignored rather than
    /// misread.
    ///
    /// v2 dropped the per-sample ankle flag: every sample is an ankle sample
    /// now. v1 files are refused rather than migrated, on purpose - see `has`.
    private static let version = "v2"

    /// A SECOND format the readers accept, not a replacement for v2. Comparing
    /// the header for exact equality against one version is what made a bump
    /// refuse every track already on every phone.
    private static let versionV3 = "v3"
    private static let readableVersions = [version, versionV3]

    private static let clipsDirectoryName = "local-clips"

    func save(entryId: String, track: PlayerTrack, fps: Double) throws {
        let directory = try AnalysisFiles.directory(TrackSource.local.trackDir)
        var text = "\(Self.version) \(fps) \(track.framesWithPose)\n"
        for sample in track.samples {
            text += "\(sample.frame),\(sample.courtPosition.x),\(sample.courtPosition.y)\n"
        }
        try text.write(to: directory.appendingPathComponent("\(entryId).track"), atomically: true, encoding: .utf8)
    }

    /// Every track from one analysis, in the v3 format.
    ///
    /// v3 is a SECOND format the readers accept, not a replacement for v2: see
    /// `has` for what changing `version` would have cost.
    ///
    ///   v3 <fps> <trackCount>
    ///   <side> <framesWithPose> <sampleCount>
    ///   <frame>,<x>,<y>   x sampleCount
    ///   ...repeated per track
    func saveAll(
        entryId: String,
        source: TrackSource,
        selections: [PlayerSelection],
        fps: Double
    ) throws {
        // Nothing to draw is nothing to offer, matching save(): no file, so
        // has() stays false rather than answering true for an empty court.
        guard selections.contains(where: { !$0.track.samples.isEmpty }) else { return }
        let directory = try AnalysisFiles.directory(source.trackDir)
        var text = "\(Self.versionV3) \(fps) \(selections.count)\n"
        for selection in selections {
            text += "\(selection.side.name) \(selection.track.framesWithPose) \(selection.track.samples.count)\n"
            for sample in selection.track.samples {
                text += "\(sample.frame),\(sample.courtPosition.x),\(sample.courtPosition.y)\n"
            }
        }
        // .atomic: a partial write must never become visible, which is what
        // makes `has` reading the header alone sound for files this store wrote.
        try text.write(
            to: directory.appendingPathComponent("\(entryId).track"),
            atomically: true, encoding: .utf8
        )
    }

    /// Whether a track this version of the app can draw exists for `entryId`,
    /// without reading it.
    ///
    /// `load` is the wrong way to ask: it reads every line of a file that runs
    /// to roughly a megabyte for a 30-minute match, so a list asking the
    /// question once per row pays for a full parse of every analysed video on
    /// the phone. Reading the header line costs one small read and answers the
    /// question that matters: is this a track the current format can load.
    ///
    /// A file from an older format is not, and must not be offered - the v1
    /// tracks were built on a homography that could be metres off and on hip
    /// positions two to three metres off, and a row offering one would offer a
    /// heatmap that is wrong rather than merely unloadable.
    func has(entryId: String, source: TrackSource = .local) -> Bool {
        guard let handle = try? FileHandle(forReadingFrom: fileFor(entryId, source)) else { return false }
        defer { try? handle.close() }
        // The header is short; a fixed read is enough to reach the first space
        // and cannot be defeated by a huge single-line file.
        guard let head = try? handle.read(upToCount: 64), let text = String(data: head, encoding: .utf8)
        else { return false }
        let version = text.split(separator: " ", maxSplits: 1).first.map(String.init)
        return version.map(Self.readableVersions.contains) ?? false
    }

    /// Nil when there is nothing stored, or when what is stored cannot be read.
    func load(entryId: String, source: TrackSource = .local) -> Stored? {
        guard let text = try? String(contentsOf: fileFor(entryId, source), encoding: .utf8) else { return nil }
        var lines = text.split(separator: "\n", omittingEmptySubsequences: true)
        guard !lines.isEmpty else { return nil }
        let header = lines.removeFirst().split(separator: " ")
        guard header.count >= 3, let fps = Double(header[1]) else { return nil }
        switch String(header[0]) {
        case Self.version: return loadV2(fps: fps, framesWithPose: header[2], lines: lines)
        case Self.versionV3: return loadV3(fps: fps, trackCount: header[2], lines: lines)
        default: return nil
        }
    }

    /// The single-track format: one header line, then every sample.
    private func loadV2(
        fps: Double, framesWithPose: Substring, lines: [Substring]
    ) -> Stored? {
        guard let framesWithPose = Int32(framesWithPose) else { return nil }
        // Rejections are not stored: they explain a thin track while it is being
        // produced, and the count that matters afterwards, coverage, is
        // recoverable from the samples.
        let track = PlayerTrack(
            samples: lines.compactMap(Self.parseSample),
            framesWithPose: framesWithPose,
            rejections: [:]
        )
        return Stored(tracks: [SideTrack(side: .near, track: track)], fps: fps)
    }

    /// The multi-track format: a header line, then a section per track.
    private func loadV3(
        fps: Double, trackCount: Substring, lines: [Substring]
    ) -> Stored? {
        guard let trackCount = Int(trackCount) else { return nil }
        // Rejected before anything allocates on it: a negative or implausibly
        // large count is corruption, not a file this store wrote, and there are
        // never more tracks than CourtSide has sides.
        guard trackCount >= 0, trackCount <= CourtSide.allCases.count else { return nil }
        var tracks: [SideTrack] = []
        tracks.reserveCapacity(trackCount)
        var at = 0
        for _ in 0..<trackCount {
            guard at < lines.count else { return nil }
            let section = lines[at].split(separator: " ")
            guard section.count >= 3,
                  let side = CourtSide.allCases.first(where: { $0.name == String(section[0]) }),
                  let framesWithPose = Int32(section[1]),
                  let sampleCount = Int(section[2]), sampleCount >= 0
            else { return nil }
            // Bounds-checked against the file's own length before the slice, so
            // a truncated file returns nil rather than trapping.
            guard at + 1 + sampleCount <= lines.count else { return nil }
            let samples = lines[(at + 1)..<(at + 1 + sampleCount)].compactMap(Self.parseSample)
            tracks.append(SideTrack(
                side: side,
                track: PlayerTrack(samples: samples, framesWithPose: framesWithPose, rejections: [:])
            ))
            at += 1 + sampleCount
        }
        return Stored(tracks: tracks, fps: fps)
    }

    private static func parseSample(_ line: Substring) -> PlayerSample? {
        let fields = line.split(separator: ",")
        guard fields.count == 3,
              let frame = Int32(fields[0]),
              let x = Double(fields[1]), let y = Double(fields[2])
        else { return nil }
        return PlayerSample(frame: frame, courtPosition: Point(x: x, y: y))
    }

    // MARK: - Clips

    /// One cut rally.
    struct Clip: Equatable {
        let index: Int
        let url: URL
        let startSeconds: Double
        let endSeconds: Double
    }

    /// The clips a run produced, so they outlive it too.
    ///
    /// Same reason as the track: cutting re-encodes and the files are tens of
    /// megabytes, and without an index they are reachable only from the
    /// in-memory state of the run that made them.
    func saveClips(entryId: String, clips: [Clip]) throws {
        guard !clips.isEmpty else { return }
        let directory = try AnalysisFiles.directory("\(Self.clipsDirectoryName)/\(entryId)")
        var text = "\(Self.version)\n"
        for clip in clips {
            text += "\(clip.index),\(clip.startSeconds),\(clip.endSeconds),\(clip.url.lastPathComponent)\n"
        }
        try text.write(to: directory.appendingPathComponent("clips.index"), atomically: true, encoding: .utf8)
    }

    func loadClips(entryId: String) -> [Clip] {
        let directory = clipsDirectoryFor(entryId)
        let index = directory.appendingPathComponent("clips.index")
        // No index: recover whatever is on disk anyway. The clips are the
        // expensive artifact - tens of megabytes and minutes of re-encoding -
        // and refusing to list them because a small sidecar is missing would
        // throw away the thing worth keeping to protect the bookkeeping.
        guard let text = try? String(contentsOf: index, encoding: .utf8) else {
            return scanClips(entryId: entryId)
        }
        var lines = text.split(separator: "\n", omittingEmptySubsequences: true)
        guard !lines.isEmpty, lines.removeFirst() == Self.version else { return [] }
        return lines.compactMap { line in
            let fields = line.split(separator: ",")
            guard fields.count == 4,
                  let index = Int(fields[0]),
                  let start = Double(fields[1]), let end = Double(fields[2])
            else { return nil }
            let url = directory.appendingPathComponent(String(fields[3]))
            // A clip whose file has gone is not a clip; listing it would offer
            // a player that opens on nothing.
            guard FileManager.default.isReadableFile(atPath: url.path) else { return nil }
            return Clip(index: index, url: url, startSeconds: start, endSeconds: end)
        }
    }

    /// Clips found by filename, for runs made before the index existed or
    /// interrupted before it was written.
    ///
    /// Their bounds are not recoverable from the file, so they are left at zero
    /// and the caller shows the rally number alone rather than a fabricated
    /// "0.0s - 0.0s".
    private func scanClips(entryId: String) -> [Clip] {
        let directory = clipsDirectoryFor(entryId)
        let names = (try? FileManager.default.contentsOfDirectory(atPath: directory.path)) ?? []
        return names.compactMap { name -> Clip? in
            guard name.hasPrefix("rally-"), name.hasSuffix(".mp4"),
                  let index = Int(name.dropFirst("rally-".count).dropLast(".mp4".count))
            else { return nil }
            return Clip(
                index: index, url: directory.appendingPathComponent(name),
                startSeconds: 0, endSeconds: 0
            )
        }.sorted { $0.index < $1.index }
    }

    private func fileFor(_ entryId: String, _ source: TrackSource = .local) -> URL {
        AnalysisFiles.directory
            .appendingPathComponent(source.trackDir, isDirectory: true)
            .appendingPathComponent("\(entryId).track")
    }

    private func clipsDirectoryFor(_ entryId: String) -> URL {
        AnalysisFiles.directory
            .appendingPathComponent(Self.clipsDirectoryName, isDirectory: true)
            .appendingPathComponent(entryId, isDirectory: true)
    }
}
