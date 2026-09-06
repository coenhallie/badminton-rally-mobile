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
import com.badmintontracker.shared.localvideo.LocalVideoEntry
import com.badmintontracker.shared.localvideo.LocalVideoLimits
import java.util.UUID

/** Entry points for getting a video into the app. */
class VideoIntake(
    val record: (forMatch: MatchTarget?) -> Unit,
    val import: (forMatch: MatchTarget?) -> Unit,
)

/**
 * The match a pick is for. The title is not decoration: it rides along on the
 * videos INSERT and the database grants no UPDATE on videos.title, so this is the
 * only moment the video can be given the name the coach already chose.
 */
data class MatchTarget(val scoreLogId: String, val title: String)

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

    // Holds the match a pick was launched for between launch and result, the same
    // way pendingRecordUri holds the camera destination.
    val pendingImportTarget = remember { arrayOfNulls<MatchTarget>(1) }

    val pickLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.PickVisualMedia(),
    ) { uri ->
        val target = pendingImportTarget[0]
        pendingImportTarget[0] = null
        if (uri != null) {
            // Photo-picker grants may not be persistable on all OEMs; best effort.
            runCatching {
                context.contentResolver.takePersistableUriPermission(
                    uri, Intent.FLAG_GRANT_READ_URI_PERMISSION,
                )
            }
            addEntryFromUri(context, uri, target, onAdded, onError)
        }
    }

    // Holds the MediaStore URI we hand to the camera between launch and result.
    val pendingRecordUri = remember { arrayOfNulls<Uri>(1) }
    val pendingRecordTarget = remember { arrayOfNulls<MatchTarget>(1) }

    val recordLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CaptureVideo(),
    ) { ok ->
        val uri = pendingRecordUri[0]
        val target = pendingRecordTarget[0]
        pendingRecordUri[0] = null
        pendingRecordTarget[0] = null
        if (ok && uri != null) {
            addEntryFromUri(context, uri, target, onAdded, onError)
        } else {
            uri?.let { runCatching { context.contentResolver.delete(it, null, null) } }
        }
    }

    return remember {
        VideoIntake(
            record = { forMatch ->
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
                    pendingRecordTarget[0] = forMatch
                    recordLauncher.launch(uri)
                }
            },
            import = { forMatch ->
                pendingImportTarget[0] = forMatch
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
    target: MatchTarget?,
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

    LocalVideoLimits.oversizeMessage(sizeBytes)?.let {
        onError(it)
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
            title = target?.title,
            scoreLogId = target?.scoreLogId,
        ),
    )
}
