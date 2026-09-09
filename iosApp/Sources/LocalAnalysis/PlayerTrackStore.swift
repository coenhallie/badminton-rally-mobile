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
struct PlayerTrackStore {

    struct Stored {
        let track: PlayerTrack
        let fps: Double
    }

    /// Bumped if the columns change, so an old file is ignored rather than
    /// misread.
    ///
    /// v2 dropped the per-sample ankle flag: every sample is an ankle sample
    /// now. v1 files are refused rather than migrated, on purpose - see `has`.
    private static let version = "v2"

    private static let directoryName = "player-tracks"
    private static let clipsDirectoryName = "local-clips"

    func save(entryId: String, track: PlayerTrack, fps: Double) throws {
        let directory = try AnalysisFiles.directory(Self.directoryName)
        var text = "\(Self.version) \(fps) \(track.framesWithPose)\n"
        for sample in track.samples {
            text += "\(sample.frame),\(sample.courtPosition.x),\(sample.courtPosition.y)\n"
        }
        try text.write(to: directory.appendingPathComponent("\(entryId).track"), atomically: true, encoding: .utf8)
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
    func has(entryId: String) -> Bool {
        guard let handle = try? FileHandle(forReadingFrom: fileFor(entryId)) else { return false }
        defer { try? handle.close() }
        // The header is short; a fixed read is enough to reach the first space
        // and cannot be defeated by a huge single-line file.
        guard let head = try? handle.read(upToCount: 64), let text = String(data: head, encoding: .utf8)
        else { return false }
        return text.split(separator: " ", maxSplits: 1).first.map(String.init) == Self.version
    }

    /// Nil when there is nothing stored, or when what is stored cannot be read.
    func load(entryId: String) -> Stored? {
        guard let text = try? String(contentsOf: fileFor(entryId), encoding: .utf8) else { return nil }
        var lines = text.split(separator: "\n", omittingEmptySubsequences: true)
        guard !lines.isEmpty else { return nil }
        let header = lines.removeFirst().split(separator: " ")
        guard header.count >= 3, header[0] == Self.version,
              let fps = Double(header[1]), let framesWithPose = Int32(header[2])
        else { return nil }

        var samples: [PlayerSample] = []
        samples.reserveCapacity(lines.count)
        for line in lines {
            let fields = line.split(separator: ",")
            guard fields.count == 3,
                  let frame = Int32(fields[0]),
                  let x = Double(fields[1]), let y = Double(fields[2])
            else { continue }
            samples.append(PlayerSample(frame: frame, courtPosition: Point(x: x, y: y)))
        }
        // Rejections are not stored: they explain a thin track while it is being
        // produced, and the count that matters afterwards, coverage, is
        // recoverable from the samples.
        return Stored(
            track: PlayerTrack(samples: samples, framesWithPose: framesWithPose, rejections: [:]),
            fps: fps
        )
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

    private func fileFor(_ entryId: String) -> URL {
        AnalysisFiles.directory
            .appendingPathComponent(Self.directoryName, isDirectory: true)
            .appendingPathComponent("\(entryId).track")
    }

    private func clipsDirectoryFor(_ entryId: String) -> URL {
        AnalysisFiles.directory
            .appendingPathComponent(Self.clipsDirectoryName, isDirectory: true)
            .appendingPathComponent(entryId, isDirectory: true)
    }
}
