package com.apoorvdarshan.calorietracker.services

import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.File
import java.util.UUID

/** Deleting evicts thumbnails from the LruCache, so this half of the restore guard runs on a device. */
class FoodImageStoreRestoreGuardTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext

    @Test fun deletionsResumeOnceTheRestoreEnds() {
        val root = File(context.cacheDir, "restore-guard-${UUID.randomUUID()}")
        try {
            val store = FoodImageStore.forTests(root)
            store.beginRestore()
            assertTrue(store.restoreStream("kept.jpg", ByteArrayInputStream(byteArrayOf(1))))
            assertTrue(store.restoreStream("orphan.jpg", ByteArrayInputStream(byteArrayOf(2))))
            store.endRestore()

            // References read after the restore committed describe the restored photos.
            store.pruneUnreferenced(setOf("kept.jpg"), store.restoreGeneration())
            assertTrue(store.file("kept.jpg").exists())
            assertFalse(store.file("orphan.jpg").exists())

            store.delete("kept.jpg")
            assertFalse(store.file("kept.jpg").exists())
        } finally {
            root.deleteRecursively()
        }
    }
}
