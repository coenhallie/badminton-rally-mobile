package com.badmintontracker.android.clipdetail

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.badmintontracker.shared.model.AnnotationKind
import com.badmintontracker.shared.model.RallyAnnotation
import com.badmintontracker.shared.model.RallyClip
import com.badmintontracker.shared.repo.AnnotationsRepository
import com.badmintontracker.shared.repo.AuthRepository
import com.badmintontracker.shared.repo.ClipsRepository
import com.badmintontracker.shared.repo.MediaRepository
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class ClipDetailState(
    val clip: RallyClip? = null,
    val annotations: List<RallyAnnotation> = emptyList(),
    val signedClipUrl: String? = null,
    val error: String? = null,
    val actionError: String? = null,
    val isOwner: Boolean = false,
)

class ClipDetailViewModel(
    private val clipId: String,
    private val clips: ClipsRepository,
    private val annotations: AnnotationsRepository,
    private val media: MediaRepository,
    private val auth: AuthRepository,
) : ViewModel() {

    val state  = MutableStateFlow(ClipDetailState())
    val seekTo = MutableSharedFlow<Long>(extraBufferCapacity = 1)

    private var resignAttempts = 0

    init { load() }

    private fun load() {
        viewModelScope.launch {
            val cached = clips.observeClips().first()
            val clip = cached.firstOrNull { it.id == clipId } ?: run {
                runCatching { clips.refresh() }
                clips.observeClips().first().firstOrNull { it.id == clipId }
            } ?: run {
                state.update { it.copy(error = "Clip not found") }
                return@launch
            }

            val ann = runCatching { annotations.list(clipId) }.getOrElse { e ->
                state.update { it.copy(error = e.message ?: "Couldn't load annotations") }
                emptyList()
            }
            val url = runCatching { media.signedClipUrl(clip) }.getOrElse { e ->
                state.update { it.copy(error = e.message ?: "Couldn't sign clip URL") }
                null
            }
            val isOwner = clip.ownerId == auth.currentUserId()
            state.update {
                it.copy(
                    clip = clip,
                    annotations = ann,
                    signedClipUrl = url,
                    isOwner = isOwner,
                )
            }
        }
    }

    fun onAnnotationTap(a: RallyAnnotation) {
        seekTo.tryEmit((a.timestampSeconds * 1000).toLong())
    }

    fun onPlayerError() {
        viewModelScope.launch {
            if (resignAttempts >= 1) {
                state.update { it.copy(error = "Couldn't load video") }
                return@launch
            }
            resignAttempts++
            val clip = state.value.clip ?: return@launch
            runCatching { media.signedClipUrl(clip) }
                .onSuccess { url -> state.update { it.copy(signedClipUrl = url, error = null) } }
                .onFailure { e -> state.update { it.copy(error = e.message ?: "Couldn't load video") } }
        }
    }

    fun addAnnotation(timestampSeconds: Float, body: String, kind: AnnotationKind?) {
        if (!state.value.isOwner) return
        val trimmed = body.trim()
        if (trimmed.isEmpty() && kind == null) return
        val ts = timestampSeconds.coerceAtLeast(0f)
        viewModelScope.launch {
            annotations.add(clipId, ts, trimmed, kind)
                .onSuccess { row ->
                    state.update {
                        it.copy(
                            annotations = (it.annotations + row).sortedBy { a -> a.timestampSeconds },
                            actionError = null,
                        )
                    }
                }
                .onFailure { e ->
                    state.update { it.copy(actionError = e.message ?: "Couldn't add annotation") }
                }
        }
    }

    fun deleteAnnotation(id: String) {
        if (!state.value.isOwner) return
        viewModelScope.launch {
            annotations.delete(id)
                .onSuccess {
                    state.update {
                        it.copy(
                            annotations = it.annotations.filterNot { a -> a.id == id },
                            actionError = null,
                        )
                    }
                }
                .onFailure { e ->
                    state.update { it.copy(actionError = e.message ?: "Couldn't delete annotation") }
                }
        }
    }

    fun clearActionError() {
        state.update { it.copy(actionError = null) }
    }

    fun onManualRetry() {
        resignAttempts = 0
        viewModelScope.launch {
            val clip = state.value.clip ?: return@launch
            runCatching { media.signedClipUrl(clip) }
                .onSuccess { url -> state.update { it.copy(signedClipUrl = url, error = null) } }
                .onFailure { e -> state.update { it.copy(error = e.message ?: "Couldn't load video") } }
        }
    }
}
