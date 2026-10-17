package com.apoorvdarshan.calorietracker.services

import kotlinx.coroutines.Dispatchers
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File
import java.nio.file.Files
import java.util.UUID

/**
 * File-level guarantees of Settings › Storage. Real pixel downscaling is covered by the
 * instrumented FoodImageDecoderTest; here nothing decodes, so every photo is "unreadable".
 */
class StorageMaintenanceTest {
    private lateinit var root: File
    private lateinit var photos: FoodImageStore
    private val photoDir get() = File(root, "fudai-food-images")
    private val thumbnailDir get() = File(root, "fudai-food-thumbnails-v2")

    @Before
    fun setUp() {
        root = Files.createTempDirectory("fud-storage").toFile()
        photos = FoodImageStore.forTests(root)
    }

    @After
    fun tearDown() {
        root.deleteRecursively()
    }

    private fun maintenance(
        frameBytes: Long = 0L,
        onClearFrames: () -> Unit = {},
        modelBytes: Long = 0L
    ) = StorageMaintenance(
        photos = photos,
        workoutFrameCacheBytes = { frameBytes },
        clearWorkoutFrameCache = onClearFrames,
        modelBytes = { modelBytes },
        dispatcher = Dispatchers.Unconfined
    )

    @Test
    fun usageSeparatesPhotosRegenerableCacheAndModels() {
        File(photoDir, "a.jpg").writeBytes(ByteArray(300))
        File(photoDir, "b.jpg").writeBytes(ByteArray(200))
        File(thumbnailDir, "a.jpg").writeBytes(ByteArray(40))

        assertEquals(FoodImageStore.PhotoStorageUsage(photoCount = 2, photoBytes = 500, thumbnailBytes = 40), photos.storageUsage())

        val storage = maintenance(frameBytes = 1_000, modelBytes = 5_000)
        storage.refresh()

        assertEquals(StorageMaintenance.Usage(photoBytes = 500, cacheBytes = 1_040, modelBytes = 5_000), storage.ui.value.usage)
    }

    @Test
    fun clearCacheDeletesThumbnailsAndFramesButNeverPhotos() {
        val photoBytes = ByteArray(64) { it.toByte() }
        File(photoDir, "meal.jpg").writeBytes(photoBytes)
        File(thumbnailDir, "meal.jpg").writeBytes(ByteArray(8))
        var framesCleared = false

        val storage = maintenance(onClearFrames = { framesCleared = true })
        storage.clearCache()

        assertArrayEquals(photoBytes, File(photoDir, "meal.jpg").readBytes())
        assertFalse(File(thumbnailDir, "meal.jpg").exists())
        assertTrue(framesCleared)
        assertFalse(storage.ui.value.busy)
        assertEquals(0L, storage.ui.value.usage?.cacheBytes)
    }

    @Test
    fun compressionLeavesUndecodablePhotosByteIdenticalAndCleansUp() {
        val original = "not an image".toByteArray()
        File(photoDir, "broken.jpg").writeBytes(original)

        val storage = maintenance()
        storage.compressPhotos()

        val result = storage.ui.value.compressionResult
        assertEquals(FoodImageStore.PhotoCompressionResult(compressedCount = 0, unreadableCount = 1, bytesSaved = 0), result)
        assertArrayEquals(original, File(photoDir, "broken.jpg").readBytes())
        assertEquals(listOf("broken.jpg"), photoDir.list()!!.toList())
        assertFalse(File(root, "fudai-food-images-compressing").exists())
        assertFalse(storage.ui.value.compressing)

        storage.dismissCompressionResult()
        assertEquals(null, storage.ui.value.compressionResult)
    }

    @Test
    fun storeAndRestoreKeepBytesThatAreNotOversizedPhotos() {
        val bytes = "small or unreadable".toByteArray()

        val stored = photos.storeBytes(bytes, UUID.randomUUID())!!
        assertTrue(photos.restoreBytes("restored.jpg", bytes))

        assertArrayEquals(bytes, photos.loadBytes(stored))
        assertArrayEquals(bytes, photos.loadBytes("restored.jpg"))
    }
}
