package com.badmintontracker.android.localvideo

import android.content.ActivityNotFoundException
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import android.provider.OpenableColumns
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import com.badmintontracker.shared.localvideo.LocalVideoEntry
import com.badmintontracker.shared.localvideo.LocalVideoLimits
import com.badmintontracker.shared.scoring.Side
import java.util.UUID
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Entry points for getting a video into the app. [forMatch] is the scored match
 * the video is for (its page's attach), [forPlayer] the player it is for (their
 * page's Add match); null for neither. See [newLocalVideoEntry].
 */
class VideoIntake(
    val record: (forMatch: MatchTarget?, forPlayer: String?) -> Unit,
    val import: (forMatch: MatchTarget?, forPlayer: String?) -> Unit,
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
    val appContext = context.applicationContext
    val scope = rememberCoroutineScope()
    val currentOnAdded by rememberUpdatedState(onAdded)
    val currentOnError by rememberUpdatedState(onError)

    // Saved across process death while the external picker or camera activity is
    // in front: plain remember loses the target match / MediaStore URI if Android
    // kills our process while filming or browsing.
    var pendingImportMatchId by rememberSaveable { mutableStateOf<String?>(null) }
    var pendingImportMatchTitle by rememberSaveable { mutableStateOf<String?>(null) }
    var pendingImportPlayer by rememberSaveable { mutableStateOf<String?>(null) }

    val pickLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.PickVisualMedia(),
    ) { uri ->
        val matchId = pendingImportMatchId
        val matchTitle = pendingImportMatchTitle
        val target = if (matchId != null && matchTitle != null) MatchTarget(matchId, matchTitle) else null
        val player = pendingImportPlayer
        pendingImportMatchId = null
        pendingImportMatchTitle = null
        pendingImportPlayer = null
        if (uri != null) {
            // Photo-picker grants may not be persistable on all OEMs; best effort.
            runCatching {
                appContext.contentResolver.takePersistableUriPermission(
                    uri, Intent.FLAG_GRANT_READ_URI_PERMISSION,
                )
            }
            scope.launch {
                addEntryFromUri(appContext, uri, target, player, currentOnAdded, currentOnError)
            }
        }
    }

    // Holds the MediaStore URI we hand to the camera between launch and result.
    var pendingRecordUri by rememberSaveable { mutableStateOf<String?>(null) }
    var pendingRecordMatchId by rememberSaveable { mutableStateOf<String?>(null) }
    var pendingRecordMatchTitle by rememberSaveable { mutableStateOf<String?>(null) }
    var pendingRecordPlayer by rememberSaveable { mutableStateOf<String?>(null) }

    val recordLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CaptureVideo(),
    ) { ok ->
        val uri = pendingRecordUri?.let(Uri::parse)
        val matchId = pendingRecordMatchId
        val matchTitle = pendingRecordMatchTitle
        val target = if (matchId != null && matchTitle != null) MatchTarget(matchId, matchTitle) else null
        val player = pendingRecordPlayer
        pendingRecordUri = null
        pendingRecordMatchId = null
        pendingRecordMatchTitle = null
        pendingRecordPlayer = null
        if (ok && uri != null) {
            scope.launch {
                addEntryFromUri(appContext, uri, target, player, currentOnAdded, currentOnError)
            }
        } else {
            uri?.let { runCatching { appContext.contentResolver.delete(it, null, null) } }
        }
    }

    return remember(appContext, scope) {
        VideoIntake(
            record = { forMatch, forPlayer ->
                val values = ContentValues().apply {
                    put(MediaStore.Video.Media.DISPLAY_NAME, "shuttl_${System.currentTimeMillis()}.mp4")
                    put(MediaStore.Video.Media.MIME_TYPE, "video/mp4")
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                        put(MediaStore.Video.Media.RELATIVE_PATH, "Movies/Shuttl")
                    }
                }
                val uri = appContext.contentResolver
                    .insert(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, values)
                if (uri == null) {
                    currentOnError("Couldn't create a recording destination")
                } else {
                    pendingRecordUri = uri.toString()
                    pendingRecordMatchId = forMatch?.scoreLogId
                    pendingRecordMatchTitle = forMatch?.title
                    pendingRecordPlayer = forPlayer
                    try {
                        recordLauncher.launch(uri)
                    } catch (_: ActivityNotFoundException) {
                        pendingRecordUri = null
                        pendingRecordMatchId = null
                        pendingRecordMatchTitle = null
                        pendingRecordPlayer = null
                        runCatching { appContext.contentResolver.delete(uri, null, null) }
                        currentOnError("No camera app found to record video")
                    }
                }
            },
            import = { forMatch, forPlayer ->
                pendingImportMatchId = forMatch?.scoreLogId
                pendingImportMatchTitle = forMatch?.title
                pendingImportPlayer = forPlayer
                try {
                    pickLauncher.launch(
                        PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.VideoOnly),
                    )
                } catch (_: ActivityNotFoundException) {
                    pendingImportMatchId = null
                    pendingImportMatchTitle = null
                    pendingImportPlayer = null
                    currentOnError("No video picker available on this device")
                }
            },
        )
    }
}

private suspend fun addEntryFromUri(
    context: Context,
    uri: Uri,
    target: MatchTarget?,
    player: String?,
    onAdded: (LocalVideoEntry) -> Unit,
    onError: (String) -> Unit,
) {
    val (displayName, sizeBytes) = withContext(Dispatchers.IO) {
        var name = "video.mp4"
        var size = 0L
        runCatching {
            context.contentResolver.query(
                uri,
                arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE),
                null,
                null,
                null,
            )?.use { cursor ->
                if (cursor.moveToFirst()) {
                    cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                        .takeIf { it >= 0 }?.let { name = cursor.getString(it) ?: name }
                    cursor.getColumnIndex(OpenableColumns.SIZE)
                        .takeIf { it >= 0 }?.let { size = cursor.getLong(it) }
                }
            }
        }
        name to size
    }

    LocalVideoLimits.oversizeMessage(sizeBytes)?.let {
        onError(it)
        return
    }

    val durationMs = withContext(Dispatchers.IO) {
        runCatching {
            val retriever = MediaMetadataRetriever()
            try {
                retriever.setDataSource(context, uri)
                retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLong() ?: 0L
            } finally {
                retriever.release()
            }
        }.getOrDefault(0L)
    }

    onAdded(
        newLocalVideoEntry(
            id = UUID.randomUUID().toString(),
            uri = uri.toString(),
            displayName = displayName,
            durationMs = durationMs,
            sizeBytes = sizeBytes,
            addedAtEpochMs = System.currentTimeMillis(),
            forMatch = target,
            forPlayer = player,
        ),
    )
}

/**
 * The entry a picked or recorded file becomes. A video for a match carries the
 * match's log and name (see [MatchTarget]). A video for a player has them at
 * home and at the near end: the coach filmed from their side, which is the
 * side an on-device run follows.
 */
internal fun newLocalVideoEntry(
    id: String,
    uri: String,
    displayName: String,
    durationMs: Long,
    sizeBytes: Long,
    addedAtEpochMs: Long,
    forMatch: MatchTarget?,
    forPlayer: String?,
): LocalVideoEntry = LocalVideoEntry(
    id = id,
    uri = uri,
    displayName = displayName,
    durationMs = durationMs,
    sizeBytes = sizeBytes,
    addedAtEpochMs = addedAtEpochMs,
    title = forMatch?.title,
    scoreLogId = forMatch?.scoreLogId,
    homePlayerId = forPlayer,
    nearSide = forPlayer?.let { Side.HOME },
)
