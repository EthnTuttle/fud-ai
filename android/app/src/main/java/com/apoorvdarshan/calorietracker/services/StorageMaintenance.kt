package com.apoorvdarshan.calorietracker.services

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * Settings › Data Management › Storage: what is using space, plus the two user-run cleanups.
 * Work runs on an app-lifetime scope so leaving Settings never stops a compression half way
 * (each photo is replaced atomically, so stopping would be safe, just incomplete).
 * Nothing here runs automatically and nothing here deletes a referenced photo.
 */
class StorageMaintenance(
    private val photos: FoodImageStore,
    private val workoutFrameCacheBytes: () -> Long,
    private val clearWorkoutFrameCache: () -> Unit,
    private val modelBytes: () -> Long,
    dispatcher: CoroutineDispatcher = Dispatchers.IO
) {
    data class Usage(
        val photoBytes: Long,
        /** Regenerable files only: photo thumbnails and downloaded workout frames. */
        val cacheBytes: Long,
        val modelBytes: Long
    )

    data class UiState(
        val usage: Usage? = null,
        val compressing: Boolean = false,
        val compressedSoFar: Int = 0,
        val compressionTotal: Int = 0,
        val clearingCache: Boolean = false,
        val compressionResult: FoodImageStore.PhotoCompressionResult? = null
    ) {
        val busy: Boolean get() = compressing || clearingCache
    }

    private val scope = CoroutineScope(SupervisorJob() + dispatcher)
    private val _ui = MutableStateFlow(UiState())
    val ui: StateFlow<UiState> = _ui.asStateFlow()

    fun refresh() {
        scope.launch { _ui.update { it.copy(usage = measure()) } }
    }

    /** Downscales existing photos over the stored-size cap. Call only after the user confirms. */
    fun compressPhotos() {
        if (!begin { it.copy(compressing = true, compressedSoFar = 0, compressionTotal = 0, compressionResult = null) }) return
        scope.launch {
            val result = runCatching {
                photos.compressExistingPhotos { done, total ->
                    _ui.update { it.copy(compressedSoFar = done, compressionTotal = total) }
                }
            }.getOrElse { FoodImageStore.PhotoCompressionResult(0, 0, 0L) }
            val usage = measure()
            _ui.update { it.copy(compressing = false, compressionResult = result, usage = usage) }
        }
    }

    /** Deletes thumbnails and downloaded workout frames; both are rebuilt on demand. */
    fun clearCache() {
        if (!begin { it.copy(clearingCache = true) }) return
        scope.launch {
            runCatching { photos.clearThumbnailCache() }
            runCatching { clearWorkoutFrameCache() }
            val usage = measure()
            _ui.update { it.copy(clearingCache = false, usage = usage) }
        }
    }

    fun dismissCompressionResult() {
        _ui.update { it.copy(compressionResult = null) }
    }

    private fun begin(transform: (UiState) -> UiState): Boolean {
        var started = false
        _ui.update { state ->
            started = !state.busy
            if (started) transform(state) else state
        }
        return started
    }

    private fun measure(): Usage {
        val photoUsage = runCatching { photos.storageUsage() }.getOrNull()
        return Usage(
            photoBytes = photoUsage?.photoBytes ?: 0L,
            cacheBytes = (photoUsage?.thumbnailBytes ?: 0L) + runCatching(workoutFrameCacheBytes).getOrDefault(0L),
            modelBytes = runCatching(modelBytes).getOrDefault(0L)
        )
    }
}
