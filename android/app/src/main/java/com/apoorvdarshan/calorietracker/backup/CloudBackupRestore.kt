package com.apoorvdarshan.calorietracker.backup

import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
import java.io.InputStream

/** Where a restore puts photos: FoodImageStore in the app, a fake in tests. */
interface CloudBackupPhotoStore {
    /** Free storage once [clearAll] has removed the current photos. */
    fun bytesFreeAfterClear(): Long

    /**
     * Brackets a restore, from clearing photos until the restored values are
     * written. In between, deletions decided from the old diary are skipped.
     */
    fun beginRestore()
    fun endRestore()
    fun clearAll()
    fun restoreStream(filename: String, input: InputStream): Boolean
}

/** Not enough free storage to restore. Thrown before anything local changes. */
class CloudBackupNoSpaceException : IOException()

/** Some photos could not be written. The backed-up values were still restored. */
class CloudBackupIncompleteException : IOException()

object CloudBackupRestore {
    // Each restored photo also gets a thumbnail; the reserve covers file-system
    // overhead and keeps the phone from being filled to the last byte.
    const val THUMBNAIL_ALLOWANCE_BYTES = 64L * 1024
    const val RESERVE_BYTES = 16L * 1024 * 1024

    /** Storage a restore of [contents] needs: photos, thumbnails, and the values write. */
    fun requiredBytes(contents: CloudBackupContents): Long =
        contents.photoBytes +
            contents.photoCount * THUMBNAIL_ALLOWANCE_BYTES +
            contents.payloadBytes +
            RESERVE_BYTES

    /**
     * Replaces every local photo with the ones in [archive], then passes its
     * document to [commit], which restores the backed-up values. `photosComplete`
     * is false when a photo could not be written; [commit] still runs, so the
     * diary matches the photos that did land, and then this throws
     * [CloudBackupIncompleteException].
     */
    suspend fun apply(
        archive: File,
        photos: CloudBackupPhotoStore,
        commit: suspend (document: CloudBackupDocument, photosComplete: Boolean) -> Unit,
    ) {
        // Validates the whole archive before anything local is cleared.
        val contents = CloudBackupArchive.inspect(archive)
        // The downloaded archive already takes up room, so check before clearing
        // rather than run out partway through the photos.
        if (photos.bytesFreeAfterClear() < requiredBytes(contents)) throw CloudBackupNoSpaceException()
        currentCoroutineContext().ensureActive()
        var photosComplete = true
        // The app stays usable during a restore, so the screen that started it
        // can close midway (#484). Once photos are cleared the restore has to
        // reach [commit], or the old diary is left pointing at deleted photos.
        withContext(NonCancellable) {
            photos.beginRestore()
            try {
                photos.clearAll()
                CloudBackupArchive.forEachPhoto(archive) { name, input ->
                    if (!photos.restoreStream(name, input)) photosComplete = false
                }
                commit(contents.document, photosComplete)
            } finally {
                photos.endRestore()
            }
        }
        if (!photosComplete) throw CloudBackupIncompleteException()
    }
}
