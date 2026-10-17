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
            CloudBackupRestore.apply(archive, store) { document ->
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
            CloudBackupRestore.apply(archive, store) { committed = true }
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
        val error = runCatching { CloudBackupRestore.apply(corrupt, store) { } }.exceptionOrNull()

        assertTrue(error != null)
        assertFalse(store.cleared)
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
        private val onRestore: () -> Unit = {},
    ) : CloudBackupPhotoStore {
        var cleared = false
        val restored = linkedMapOf<String, ByteArray>()

        override fun clearAll() {
            cleared = true
        }

        override fun restoreStream(filename: String, input: InputStream): Boolean {
            onRestore()
            restored[filename] = input.readBytes()
            return true
        }
    }
}
