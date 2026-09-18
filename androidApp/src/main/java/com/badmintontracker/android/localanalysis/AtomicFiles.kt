package com.badmintontracker.android.localanalysis

import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream

/** What [File.writeAtomically] writes to before the rename. Also what cleanup looks for. */
internal const val TMP_SUFFIX = ".tmp"

/**
 * Writes this file so that it is only ever absent, its old self, or complete.
 *
 * Everything the pipeline keeps is either the product of a run that took the
 * better part of an hour or something a later run trusts blindly, and this
 * process is one the system kills: it runs for that hour, often in the
 * background. A plain write that is interrupted leaves a truncated file under the
 * real name, and the readers here treat "exists and is not empty" as "is whole".
 *
 * The bytes go to a sibling, are forced to disk, and the sibling is renamed over
 * the target, which on one filesystem is atomic. The sync matters as much as the
 * rename: without it the rename can reach the disk before the data does, and a
 * power cut leaves the new name pointing at nothing.
 */
internal fun File.writeAtomically(write: (OutputStream) -> Unit) {
    parentFile?.mkdirs()
    val tmp = File(parentFile, name + TMP_SUFFIX)
    try {
        FileOutputStream(tmp).use { out ->
            write(out)
            out.flush()
            out.fd.sync()
        }
        if (!tmp.renameTo(this)) throw IOException("Failed to rename ${tmp.absolutePath} to $absolutePath")
    } finally {
        // A no-op after a successful rename, and the cleanup on every other path,
        // cancellation included.
        tmp.delete()
    }
}

internal fun File.writeTextAtomically(text: String) = writeAtomically { it.write(text.toByteArray()) }

/** [input] is read to its end but not closed; that is its owner's job. */
internal fun File.copyAtomicallyFrom(input: InputStream) = writeAtomically { input.copyTo(it) }

/**
 * Puts a bundled model on disk where ONNX Runtime can open it by path, once per
 * version of the app, and returns it.
 *
 * [versionKey] is part of the path because the file name is not an identity: the
 * next release can ship different weights as the same `tracknet.fp16.onnx`, and
 * a phone that already had one would go on running it while the app reported the
 * new version. Directories for any other key are deleted, since nothing will
 * read them again and the graphs are tens of megabytes.
 *
 * @param open the bundled bytes. Called only when the model has to be extracted.
 */
internal fun stageModel(root: File, versionKey: String, name: String, open: () -> InputStream): File {
    val dir = File(root, versionKey)
    val out = File(dir, name)
    // Present means whole, because writeAtomically is the only thing that ever
    // creates it.
    if (out.isFile) return out
    root.listFiles()?.filter { it.name != versionKey }?.forEach { it.deleteRecursively() }
    open().use { out.copyAtomicallyFrom(it) }
    return out
}
