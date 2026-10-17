package com.apoorvdarshan.calorietracker.backup

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.ByteArrayInputStream
import java.io.File
import java.io.InputStream
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

class CloudBackupRestoreTest {
    @get:Rule
    val temp = TemporaryFolder()

    @Test
    fun leavingTheScreenDuringPhotoExtractionStillRestoresTheValues() = runBlocking {
        val archive = archiveWith("a.jpg" to byteArrayOf(1, 2), "b.jpg" to byteArrayOf(3))
        val extracting = CountDownLatch(1)
        val cancelled = CountDownLatch(1)
        val store = FakePhotoStore(onRestore = {
            extracting.countDown()
            cancelled.await(5, TimeUnit.SECONDS)
        })
        val committed = CompletableDeferred<CloudBackupDocument>()
        val restore = launch(Dispatchers.IO) {
            CloudBackupRestore.apply(archive, store) { document, _ ->
                // Like a DataStore edit: suspends, so it throws if the caller was cancelled.
                withContext(Dispatchers.Default) { committed.complete(document) }
            }
        }
        assertTrue(extracting.await(5, TimeUnit.SECONDS))
        restore.cancel()
        cancelled.countDown()
        restore.join()

        assertTrue(committed.isCompleted)
        assertEquals(true, committed.await().payload.values.getValue("useMetric").b)
        assertEquals(listOf("a.jpg", "b.jpg"), store.restored.keys.toList())
    }

    @Test
    fun cancellingBeforePhotosAreClearedLeavesTheDeviceUntouched() = runBlocking {
        val archive = archiveWith("a.jpg" to byteArrayOf(1))
        val store = FakePhotoStore()
        var committed = false
        val restore = launch(Dispatchers.IO) {
            cancel()
            CloudBackupRestore.apply(archive, store) { _, _ -> committed = true }
        }
        restore.join()

        assertTrue(restore.isCancelled)
        assertFalse(store.cleared)
        assertTrue(store.restored.isEmpty())
        assertFalse(committed)
    }

    @Test
    fun corruptArchiveFailsBeforeAnythingIsCleared() = runBlocking {
        val corrupt = temp.newFile("corrupt.zip").apply { writeBytes(byteArrayOf(1, 2, 3)) }
        val store = FakePhotoStore()
        val error = runCatching { CloudBackupRestore.apply(corrupt, store) { _, _ -> } }.exceptionOrNull()

        assertTrue(error != null)
        assertFalse(store.cleared)
    }

    @Test
    fun notEnoughStorageFailsBeforeAnythingIsCleared() = runBlocking {
        val photo = ByteArray(4096) { it.toByte() }
        val archive = archiveWith("a.jpg" to photo)
        val needed = CloudBackupRestore.requiredBytes(CloudBackupArchive.inspect(archive))
        val store = FakePhotoStore(freeBytes = needed - 1)
        var committed = false
        val error = runCatching {
            CloudBackupRestore.apply(archive, store) { _, _ -> committed = true }
        }.exceptionOrNull()

        assertTrue(error is CloudBackupNoSpaceException)
        assertFalse(store.cleared)
        assertTrue(store.restored.isEmpty())
        assertFalse(committed)
    }

    @Test
    fun exactlyEnoughStorageRestores() = runBlocking {
        val archive = archiveWith("a.jpg" to ByteArray(4096))
        val needed = CloudBackupRestore.requiredBytes(CloudBackupArchive.inspect(archive))
        val store = FakePhotoStore(freeBytes = needed)
        var complete: Boolean? = null
        CloudBackupRestore.apply(archive, store) { _, photosComplete -> complete = photosComplete }

        assertEquals(true, complete)
        assertEquals(listOf("a.jpg"), store.restored.keys.toList())
    }

    @Test
    fun aPhotoThatFailsToWriteIsReportedAfterTheValuesAreRestored() = runBlocking {
        val archive = archiveWith("a.jpg" to byteArrayOf(1), "b.jpg" to byteArrayOf(2), "c.jpg" to byteArrayOf(3))
        val store = FakePhotoStore(failing = setOf("b.jpg"))
        var complete: Boolean? = null
        val error = runCatching {
            CloudBackupRestore.apply(archive, store) { _, photosComplete -> complete = photosComplete }
        }.exceptionOrNull()

        // The values still land so the diary matches the photos that did, and the
        // caller learns the restore is incomplete instead of reporting success.
        assertEquals(false, complete)
        assertTrue(error is CloudBackupIncompleteException)
        assertEquals(listOf("a.jpg", "c.jpg"), store.restored.keys.toList())
    }

    @Test
    fun deletionsStayPausedUntilTheRestoredValuesAreWritten() = runBlocking {
        val archive = archiveWith("a.jpg" to byteArrayOf(1))
        val store = FakePhotoStore()
        CloudBackupRestore.apply(archive, store) { _, _ -> store.events += "commit" }

        assertEquals(listOf("begin", "clear", "photo a.jpg", "commit", "end"), store.events)
    }

    @Test
    fun aFailedCommitStillResumesDeletions() = runBlocking {
        val archive = archiveWith("a.jpg" to byteArrayOf(1))
        val store = FakePhotoStore()
        val error = runCatching {
            CloudBackupRestore.apply(archive, store) { _, _ -> error("DataStore write failed") }
        }.exceptionOrNull()

        assertTrue(error is IllegalStateException)
        assertEquals("end", store.events.last())
    }

    private fun archiveWith(vararg photos: Pair<String, ByteArray>): File {
        val zip = temp.newFile()
        CloudBackupArchive.pack(
            destination = zip,
            values = mapOf("useMetric" to CloudBackupValue.bool(true)),
            photos = photos.map { (name, bytes) ->
                CloudBackupPhoto(name, bytes.size.toLong()) { ByteArrayInputStream(bytes) }
            },
            exportedAt = "2026-10-17T00:00:00Z",
            appVersion = "test",
        )
        return zip
    }

    private class FakePhotoStore(
        private val freeBytes: Long = Long.MAX_VALUE,
        private val failing: Set<String> = emptySet(),
        private val onRestore: () -> Unit = {},
    ) : CloudBackupPhotoStore {
        var cleared = false
        val restored = linkedMapOf<String, ByteArray>()
        val events = mutableListOf<String>()

        override fun bytesFreeAfterClear(): Long = freeBytes

        override fun beginRestore() {
            events += "begin"
        }

        override fun endRestore() {
            events += "end"
        }

        override fun clearAll() {
            cleared = true
            events += "clear"
        }

        override fun restoreStream(filename: String, input: InputStream): Boolean {
            onRestore()
            events += "photo $filename"
            if (filename in failing) return false
            restored[filename] = input.readBytes()
            return true
        }
    }
}
