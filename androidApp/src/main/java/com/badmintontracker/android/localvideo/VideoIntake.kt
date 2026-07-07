package com.badmintontracker.android.localvideo

import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.provider.MediaStore
import android.provider.OpenableColumns
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import java.util.UUID

private const val MAX_SIZE_BYTES = 1_073_741_824L // 1 GB, same cap as the web app

/** Entry points for getting a video into the app. */
class VideoIntake(val record: () -> Unit, val import: () -> Unit)

/**
 * Record via the system camera (output owned by the app in MediaStore Movies/Shuttl)
 * or import via the Photo Picker (persistable read permission taken best-effort).
 */
@Composable
fun rememberVideoIntake(
    onAdded: (LocalVideoEntry) -> Unit,
    onError: (String) -> Unit,
): VideoIntake {
    val context = LocalContext.current

    val pickLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.PickVisualMedia(),
    ) { uri ->
        if (uri != null) {
            // Photo-picker grants may not be persistable on all OEMs; best effort.
            runCatching {
                context.contentResolver.takePersistableUriPermission(
                    uri, Intent.FLAG_GRANT_READ_URI_PERMISSION,
                )
            }
            addEntryFromUri(context, uri, onAdded, onError)
        }
    }

    // Holds the MediaStore URI we hand to the camera between launch and result.
    val pendingRecordUri = remember { arrayOfNulls<Uri>(1) }

    val recordLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CaptureVideo(),
    ) { ok ->
        val uri = pendingRecordUri[0]
        pendingRecordUri[0] = null
        if (ok && uri != null) {
            addEntryFromUri(context, uri, onAdded, onError)
        } else {
            uri?.let { runCatching { context.contentResolver.delete(it, null, null) } }
        }
    }

    return remember {
        VideoIntake(
            record = {
                val values = ContentValues().apply {
                    put(MediaStore.Video.Media.DISPLAY_NAME, "shuttl_${System.currentTimeMillis()}.mp4")
                    put(MediaStore.Video.Media.MIME_TYPE, "video/mp4")
                    put(MediaStore.Video.Media.RELATIVE_PATH, "Movies/Shuttl")
                }
                val uri = context.contentResolver
                    .insert(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, values)
                if (uri == null) {
                    onError("Couldn't create a recording destination")
                } else {
                    pendingRecordUri[0] = uri
                    recordLauncher.launch(uri)
                }
            },
            import = {
                pickLauncher.launch(
                    PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.VideoOnly),
                )
            },
        )
    }
}

private fun addEntryFromUri(
    context: Context,
    uri: Uri,
    onAdded: (LocalVideoEntry) -> Unit,
    onError: (String) -> Unit,
) {
    val resolver = context.contentResolver

    var displayName = "video.mp4"
    var sizeBytes = 0L
    runCatching {
        resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE), null, null, null)
            ?.use { cursor ->
                if (cursor.moveToFirst()) {
                    cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                        .takeIf { it >= 0 }?.let { displayName = cursor.getString(it) ?: displayName }
                    cursor.getColumnIndex(OpenableColumns.SIZE)
                        .takeIf { it >= 0 }?.let { sizeBytes = cursor.getLong(it) }
                }
            }
    }

    if (sizeBytes > MAX_SIZE_BYTES) {
        onError("Video is larger than 1GB. Please use a shorter recording.")
        return
    }

    val durationMs = runCatching {
        val retriever = MediaMetadataRetriever()
        try {
            retriever.setDataSource(context, uri)
            retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLong() ?: 0L
        } finally {
            retriever.release()
        }
    }.getOrDefault(0L)

    onAdded(
        LocalVideoEntry(
            id = UUID.randomUUID().toString(),
            uri = uri.toString(),
            displayName = displayName,
            durationMs = durationMs,
            sizeBytes = sizeBytes,
            addedAtEpochMs = System.currentTimeMillis(),
        ),
    )
}
