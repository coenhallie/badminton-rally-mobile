package com.badmintontracker.android.localvideo

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.badmintontracker.shared.localvideo.LocalAnnotation
import com.badmintontracker.shared.localvideo.LocalAnnotationsRepository
import com.badmintontracker.shared.model.AnnotationKind
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn

class LocalPlayerViewModel(
    private val videoId: String,
    private val annotations: LocalAnnotationsRepository,
) : ViewModel() {

    val state: StateFlow<List<LocalAnnotation>> = annotations.byVideoId
        .map { it[videoId].orEmpty() }
        .stateIn(viewModelScope, SharingStarted.Eagerly, annotations.annotationsFor(videoId))

    private val _seekTo = MutableSharedFlow<Long>(extraBufferCapacity = 1)
    val seekTo: SharedFlow<Long> = _seekTo

    fun onAnnotationTap(a: LocalAnnotation) {
        _seekTo.tryEmit((a.timestampSeconds * 1000).toLong())
    }

    fun addAnnotation(timestampSeconds: Float, body: String, kind: AnnotationKind?) {
        val trimmed = body.trim()
        if (trimmed.isEmpty() && kind == null) return
        annotations.add(videoId, timestampSeconds.coerceAtLeast(0f), trimmed, kind)
    }

    fun deleteAnnotation(id: String) = annotations.delete(videoId, id)
}
