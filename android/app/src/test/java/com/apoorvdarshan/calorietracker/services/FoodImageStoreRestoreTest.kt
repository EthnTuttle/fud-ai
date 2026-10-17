package com.apoorvdarshan.calorietracker.services

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.ByteArrayInputStream
import java.io.IOException
import java.io.InputStream

/**
 * JVM tests for the restore paths that never touch the thumbnail LruCache, which
 * is not available off-device; FoodImageStoreRestoreGuardTest covers the rest.
 */
class FoodImageStoreRestoreTest {
    @get:Rule
    val temp = TemporaryFolder()

    @Test
    fun restoredPhotoIsWrittenInFull() {
        val store = FoodImageStore.forTests(temp.newFolder("files"))
        val bytes = ByteArray(20_000) { it.toByte() }

        assertTrue(store.restoreStream("a.jpg", ByteArrayInputStream(bytes)))
        assertArrayEquals(bytes, store.file("a.jpg").readBytes())
        assertEquals(listOf("a.jpg"), store.listedFilenames())
    }

    @Test
    fun aFailedRestoreWriteLeavesNoCutOffPhoto() {
        val store = FoodImageStore.forTests(temp.newFolder("files"))

        assertFalse(store.restoreStream("a.jpg", FullDiskStream(after = 10_000)))
        assertFalse(store.file("a.jpg").exists())
        assertTrue(store.listedFilenames().isEmpty())
    }

    @Test
    fun pruneWithReferencesReadBeforeARestoreKeepsTheRestoredPhotos() {
        val store = FoodImageStore.forTests(temp.newFolder("files"))
        // A diary edit reads the generation and the old diary's references...
        val generation = store.restoreGeneration()
        val oldReferences = setOf("old.jpg")
        // ...a restore then replaces the photos and commits the new diary...
        store.beginRestore()
        assertTrue(store.restoreStream("restored.jpg", ByteArrayInputStream(byteArrayOf(1))))
        store.endRestore()
        // ...and only afterwards does the edit prune.
        store.pruneUnreferenced(oldReferences, generation)

        assertTrue(store.file("restored.jpg").exists())
    }

    @Test
    fun pruneAndDeleteAreSkippedWhileARestoreRuns() {
        val store = FoodImageStore.forTests(temp.newFolder("files"))
        store.beginRestore()
        assertTrue(store.restoreStream("restored.jpg", ByteArrayInputStream(byteArrayOf(1))))

        store.pruneUnreferenced(setOf("old.jpg"))
        store.delete("restored.jpg")

        assertTrue(store.file("restored.jpg").exists())
        store.endRestore()
    }

    /** Hands out [after] bytes, then fails, so the copy stops partway as it does on a full disk. */
    private class FullDiskStream(private val after: Int) : InputStream() {
        private var sent = 0

        override fun read(): Int {
            if (sent >= after) throw IOException("No space left on device")
            sent++
            return 1
        }
    }
}
