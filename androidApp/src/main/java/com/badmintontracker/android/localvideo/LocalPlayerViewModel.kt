package com.badmintontracker.android.localvideo

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.badmintontracker.shared.localvideo.LocalAnnotation
import com.badmintontracker.shared.localvideo.LocalAnnotationsRepository
import com.badmintontracker.shared.model.AnnotationLabel
import com.badmintontracker.shared.repo.AnnotationLabelsRepository
import com.badmintontracker.shared.repo.userFacingMessage
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

class LocalPlayerViewModel(
    private val videoId: String,
    private val annotations: LocalAnnotationsRepository,
    private val labels: AnnotationLabelsRepository,
) : ViewModel() {

    val state: StateFlow<List<LocalAnnotation>> = annotations.byVideoId
        .map { it[videoId].orEmpty() }
        .stateIn(viewModelScope, SharingStarted.Eagerly, annotations.annotationsFor(videoId))

    val labelOptions: StateFlow<List<AnnotationLabel>> = labels.labels
        .stateIn(viewModelScope, SharingStarted.Eagerly, labels.labels.value)

    private val _errorMessage = MutableStateFlow<String?>(null)
    val errorMessage: StateFlow<String?> = _errorMessage.asStateFlow()

    fun errorShown() { _errorMessage.value = null }

    private val _seekTo = MutableSharedFlow<Long>(extraBufferCapacity = 1)
    val seekTo: SharedFlow<Long> = _seekTo

    init {
        viewModelScope.launch {
            labels.refresh().onFailure { e ->
                _errorMessage.value = e.userFacingMessage("Couldn't load labels")
            }
        }
    }

    fun onAnnotationTap(a: LocalAnnotation) {
        _seekTo.tryEmit((a.timestampSeconds * 1000).toLong())
    }

    fun addAnnotation(timestampSeconds: Float, body: String, label: AnnotationLabel?) {
        val trimmed = body.trim()
        if (trimmed.isEmpty() && label == null) return
        annotations.add(videoId, timestampSeconds.coerceAtLeast(0f), trimmed, label)
    }

    fun deleteAnnotation(id: String) = annotations.delete(videoId, id)
}
