package com.apoorvdarshan.calorietracker.backup

import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.io.File
import java.io.InputStream

/** Where a restore puts photos: FoodImageStore in the app, a fake in tests. */
interface CloudBackupPhotoStore {
    fun clearAll()
    fun restoreStream(filename: String, input: InputStream): Boolean
}

object CloudBackupRestore {
    /**
     * Replaces every local photo with the ones in [archive], then passes its
     * document to [commit], which restores the backed-up values.
     */
    suspend fun apply(
        archive: File,
        photos: CloudBackupPhotoStore,
        commit: suspend (CloudBackupDocument) -> Unit,
    ) {
        // Validates the whole archive before anything local is cleared.
        val document = CloudBackupArchive.readDocument(archive)
        currentCoroutineContext().ensureActive()
        // The app stays usable during a restore, so the screen that started it
        // can close midway (#484). Once photos are cleared the restore has to
        // reach [commit], or the old diary is left pointing at deleted photos.
        withContext(NonCancellable) {
            photos.clearAll()
            CloudBackupArchive.forEachPhoto(archive) { name, input -> photos.restoreStream(name, input) }
            commit(document)
        }
    }
}
