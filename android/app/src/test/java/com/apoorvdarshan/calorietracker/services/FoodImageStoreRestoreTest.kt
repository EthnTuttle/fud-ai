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
