import Foundation

/// File store for on-device videos. Entries persist paths RELATIVE to Documents
/// (the app container path changes across app updates).
enum LocalVideoFiles {
    static let folderName = "LocalVideos"

    static var documents: URL {
        FileManager.default.urls(for: .documentDirectory, in: .userDomainMask)[0]
    }

    static var directory: URL {
        documents.appendingPathComponent(folderName, isDirectory: true)
    }

    static func resolve(relativePath: String) -> URL {
        documents.appendingPathComponent(relativePath)
    }

    /// Moves (or copies, if moving across volumes fails) the temp file into the
    /// store under a fresh UUID name. Returns the relative path to persist.
    static func store(tempURL: URL) throws -> String {
        try FileManager.default.createDirectory(at: directory, withIntermediateDirectories: true)
        let name = "\(UUID().uuidString).mp4"
        let dest = directory.appendingPathComponent(name)
        do {
            try FileManager.default.moveItem(at: tempURL, to: dest)
        } catch {
            try FileManager.default.copyItem(at: tempURL, to: dest)
        }
        return "\(folderName)/\(name)"
    }

    static func delete(relativePath: String) {
        try? FileManager.default.removeItem(at: resolve(relativePath: relativePath))
    }
}
