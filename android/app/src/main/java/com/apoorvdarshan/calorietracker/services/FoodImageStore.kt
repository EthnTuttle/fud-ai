package com.apoorvdarshan.calorietracker.services

import android.app.ActivityManager
import android.content.Context
import android.graphics.Bitmap
import android.util.LruCache
import androidx.annotation.VisibleForTesting
import com.apoorvdarshan.calorietracker.backup.CloudBackupPhotoStore
import com.apoorvdarshan.calorietracker.backup.CloudBackupPhotoWriteException
import com.apoorvdarshan.calorietracker.backup.CloudBackupPolicy
import com.apoorvdarshan.calorietracker.models.UserExercise
import com.apoorvdarshan.calorietracker.services.FoodImageDecoder.scaledToMaxDimension
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.io.InputStream
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.locks.ReentrantReadWriteLock
import kotlin.concurrent.read
import kotlin.concurrent.write

/**
 * Local food-photo cache. Port of iOS FoodImageStore.
 * JPEGs live under filesDir/fudai-food-images/{uuid}.jpg so they stay out of
 * the DataStore blob (which would otherwise inflate past quick-read limits).
 */
class FoodImageStore private constructor(
    private val filesRoot: File,
    cleanupLegacyThumbnails: Boolean,
    thumbnailCacheKb: Int
) : CloudBackupPhotoStore {
    private val dir: File = File(filesRoot, DIR_NAME).apply { mkdirs() }
    private val thumbnailDir: File = File(filesRoot, THUMBNAIL_DIR_NAME).apply { mkdirs() }
    private val thumbnailCache = object : LruCache<String, Bitmap>(thumbnailCacheKb) {
        override fun sizeOf(key: String, value: Bitmap): Int = value.byteCount / 1024
    }
    private val thumbnailLocks = ConcurrentHashMap<String, Any>()

    // A Drive restore writes its photos here first and moves them into [dir]
    // only once every one of them has been read and written (#484).
    private val stagingDir: File = File(filesRoot, STAGING_DIR_NAME)
    private val stagingThumbnailDir: File = File(filesRoot, STAGING_THUMBNAIL_DIR_NAME)
    private val stagingLock = Any()
    private var stagingActive = false

    // A Drive restore replaces the photos while the app stays usable (#484).
    // Deletions decided from the diary as it was before the restore would
    // remove restored photos, so they are skipped until it commits; the
    // reference-checked prune collects anything left behind. Written under
    // the write lock.
    private val restoreLock = ReentrantReadWriteLock()
    private var restoresRunning = 0
    private var generation = 0L

    constructor(context: Context) : this(
        filesRoot = context.filesDir,
        cleanupLegacyThumbnails = true,
        thumbnailCacheKb = thumbnailCacheKbFor(context)
    )

    /** One-time cleanup of the pre-v2 thumbnail cache directory. Safe to call repeatedly. */
    fun cleanupLegacyThumbnailDirectory() {
        // Legacy thumbnails lost EXIF orientation during compression. Rebuild them
        // lazily in the new cache directory; original photos remain byte-identical.
        runCatching { File(filesRoot, LEGACY_THUMBNAIL_DIR_NAME).deleteRecursively() }
    }

    /** Writes the bitmap as JPEG (quality 80) under a new filename. Returns filename or null. */
    fun store(bitmap: Bitmap, entryId: UUID): String? = runCatching {
        val filename = "${entryId}.jpg"
        FileOutputStream(File(dir, filename)).use { out ->
            bitmap.compress(Bitmap.CompressFormat.JPEG, 80, out)
        }
        runCatching { writeThumbnail(filename, bitmap) }
        filename
    }.getOrNull()

    /**
     * Writes the original bytes untouched. Pass [writeThumbnail] = false on latency-sensitive
     * paths; [loadThumbnail] / [warmThumbnails] generate the thumbnail lazily on first use.
     */
    fun storeBytes(bytes: ByteArray, entryId: UUID, writeThumbnail: Boolean = true): String? = runCatching {
        val filename = "${entryId}.jpg"
        File(dir, filename).writeBytes(bytes)
        if (writeThumbnail) runCatching {
            FoodImageDecoder.decode(bytes, THUMBNAIL_MAX_DIMENSION)?.let { writeThumbnail(filename, it) }
        }
        filename
    }.getOrNull()

    fun load(filename: String): Bitmap? =
        runCatching { FoodImageDecoder.decode(File(dir, filename)) }.getOrNull()

    /** Bounded decode for full-screen viewer — avoids loading full-resolution on the main thread. */
    fun loadForViewer(filename: String, maxDimension: Int = VIEWER_MAX_DIMENSION): Bitmap? =
        runCatching { FoodImageDecoder.decode(File(dir, filename), maxDimension) }.getOrNull()

    fun loadThumbnail(filename: String, maxDimension: Int = THUMBNAIL_MAX_DIMENSION): Bitmap? {
        val key = "$filename:$maxDimension"
        thumbnailCache.get(key)?.takeUnless { it.isRecycled }?.let { return it }

        synchronized(thumbnailLockFor(filename)) {
            thumbnailCache.get(key)?.takeUnless { it.isRecycled }?.let { return it }

            val thumbFile = File(thumbnailDir, filename)
            val bitmap = when {
                thumbFile.exists() -> runCatching {
                    FoodImageDecoder.decode(thumbFile, maxDimension)
                }.getOrNull()
                else -> runCatching {
                    val fullFile = File(dir, filename)
                    FoodImageDecoder.decode(fullFile, maxDimension)?.also { writeThumbnail(filename, it) }
                }.getOrNull()
            }

            if (bitmap != null) thumbnailCache.put(key, bitmap)
            return bitmap
        }
    }

    /** Prefetch thumbnails into memory/disk cache. Call from a background dispatcher. */
    fun warmThumbnails(filenames: Collection<String>) {
        filenames.asSequence()
            .filter { it.isNotBlank() }
            .distinct()
            .forEach { loadThumbnail(it) }
    }

    fun file(filename: String): File = File(dir, filename)

    fun listedFilenames(): List<String> =
        dir.listFiles()?.filter { it.isFile }?.map { it.name }.orEmpty()

    fun loadBytes(filename: String): ByteArray? {
        val file = File(dir, filename)
        return if (file.isFile) runCatching { file.readBytes() }.getOrNull() else null
    }

    fun restoreBytes(filename: String, bytes: ByteArray): Boolean = runCatching {
        File(dir, filename).writeBytes(bytes)
        runCatching {
            FoodImageDecoder.decode(bytes, THUMBNAIL_MAX_DIMENSION)?.let { writeThumbnail(filename, it) }
        }
        true
    }.getOrDefault(false)

    override fun bytesFree(): Long = filesRoot.usableSpace

    override fun startStaging() {
        synchronized(stagingLock) {
            stagingActive = true
            deleteStagingFolders()
            if (!stagingDir.mkdirs() || !stagingThumbnailDir.mkdirs()) throw CloudBackupPhotoWriteException()
        }
    }

    /**
     * Copies a restored photo into the staging folder without holding it in
     * memory. The copy goes to a temp file first, so a failed write (a full
     * disk) leaves no cut-off photo.
     */
    override fun stagePhoto(filename: String, input: InputStream): Boolean {
        val target = childFile(stagingDir, filename) ?: return false
        val temp = File(target.parentFile, "${target.name}.${UUID.randomUUID()}$TEMP_SUFFIX")
        return runCatching {
            FileOutputStream(temp).use { input.copyTo(it) }
            check(temp.renameTo(target)) { "Could not move the staged photo into place" }
            runCatching {
                FoodImageDecoder.decode(target, THUMBNAIL_MAX_DIMENSION)?.let {
                    writeThumbnailFile(it, File(stagingThumbnailDir, target.name))
                }
            }
            true
        }.getOrElse {
            temp.delete()
            false
        }
    }

    override fun commitStaged() {
        val staged = stagingDir.listFiles()
            ?.mapNotNull { file -> CloudBackupPolicy.safePhotoName(file.name)?.takeIf { file.isFile } }
            .orEmpty()
        try {
            for (name in staged) {
                val source = childFile(stagingDir, name) ?: throw CloudBackupPhotoWriteException()
                val target = childFile(dir, name) ?: throw CloudBackupPhotoWriteException()
                // A rename within the app's files folder replaces any photo
                // with the same name in one step.
                if (!source.renameTo(target)) throw CloudBackupPhotoWriteException()
                val thumbnail = File(thumbnailDir, target.name)
                val stagedThumbnail = File(stagingThumbnailDir, target.name)
                // A thumbnail of the photo just replaced must not outlive it.
                if (!stagedThumbnail.isFile || !stagedThumbnail.renameTo(thumbnail)) thumbnail.delete()
            }
        } finally {
            thumbnailCache.evictAll()
        }
    }

    override fun discardStaged() {
        synchronized(stagingLock) {
            deleteStagingFolders()
            stagingActive = false
        }
    }

    /** Removes staging a restore left behind when the process died; keeps one still running. */
    fun discardAbandonedStaging() {
        synchronized(stagingLock) {
            if (!stagingActive) deleteStagingFolders()
        }
    }

    @VisibleForTesting
    fun stagedFile(filename: String): File = File(stagingDir, filename)

    private fun deleteStagingFolders() {
        stagingDir.deleteRecursively()
        stagingThumbnailDir.deleteRecursively()
    }

    /**
     * [folder]/[filename] when [filename] is a plain photo name whose canonical
     * path is a direct child of [folder], else null. Restored names come from a
     * downloaded archive, so this keeps a crafted name, or a symbolic link
     * already at that name, from reaching outside the folder.
     */
    private fun childFile(folder: File, filename: String): File? {
        val name = CloudBackupPolicy.safePhotoName(filename) ?: return null
        return try {
            val root = folder.canonicalFile
            val file = File(root, name).canonicalFile
            if (file.toPath().startsWith(root.toPath()) && file.parentFile == root) file else null
        } catch (_: IOException) {
            null
        }
    }

    /** Stores a custom exercise photo under a fresh filename (never overwrites diary snapshots). */
    fun storeExercisePhoto(bytes: ByteArray): String? = runCatching {
        val filename = UserExercise.newPhotoFilename()
        val bitmap = FoodImageDecoder.decode(bytes, VIEWER_MAX_DIMENSION) ?: return null
        val jpeg = ByteArrayOutputStream().use { out ->
            bitmap.compress(Bitmap.CompressFormat.JPEG, 80, out)
            out.toByteArray()
        }
        bitmap.recycle()
        if (restoreBytes(filename, jpeg)) filename else null
    }.getOrNull()

    override fun beginRestore() {
        restoreLock.write {
            restoresRunning++
            generation++
        }
    }

    override fun endRestore() {
        restoreLock.write {
            restoresRunning--
            generation++
        }
    }

    /** Read before collecting the references passed to [pruneUnreferenced]. */
    fun restoreGeneration(): Long = restoreLock.read { generation }

    /** Skipped while a Drive restore runs; see [beginRestore]. */
    fun delete(filename: String) {
        restoreLock.read {
            if (restoresRunning > 0) return
            deleteFiles(filename)
        }
    }

    private fun deleteFiles(filename: String) {
        runCatching { File(dir, filename).delete() }
        runCatching { File(thumbnailDir, filename).delete() }
        evictThumbnails(filename)
    }

    fun clearAll() {
        dir.listFiles()?.forEach { runCatching { it.delete() } }
        thumbnailDir.listFiles()?.forEach { runCatching { it.delete() } }
        thumbnailCache.evictAll()
    }

    /**
     * Removes only image files that are no longer referenced by persisted app data.
     * Callers must include food-log, saved-meal, and pending-draft filenames in
     * [referencedFilenames]. This is safe to run repeatedly, including at startup,
     * and repairs orphaned files left by older builds without touching user data.
     *
     * Pass the [restoreGeneration] read before collecting [referencedFilenames]:
     * if a Drive restore started since, those references describe the photos it
     * replaced, and nothing is pruned.
     */
    fun pruneUnreferenced(referencedFilenames: Set<String>, generationAtRead: Long = restoreGeneration()) {
        restoreLock.read {
            if (restoresRunning > 0 || generationAtRead != generation) return
            val referenced = referencedFilenames.mapTo(mutableSetOf()) { File(it).name }

            dir.listFiles()
                ?.filter { it.isFile && it.name !in referenced }
                ?.forEach { deleteFiles(it.name) }

            // A crash can leave a thumbnail without its full-size image. Clean those
            // independently while preserving every thumbnail still referenced.
            thumbnailDir.listFiles()
                ?.filter { it.isFile && it.name !in referenced }
                ?.forEach { file ->
                    runCatching { file.delete() }
                    evictThumbnails(file.name)
                }
        }
    }

    private fun writeThumbnail(filename: String, bitmap: Bitmap) {
        val thumb = writeThumbnailFile(bitmap, File(thumbnailDir, filename))
        thumbnailCache.put("$filename:$THUMBNAIL_MAX_DIMENSION", thumb)
    }

    /** Writes [bitmap]'s thumbnail to [target] through a temp file and returns it. */
    private fun writeThumbnailFile(bitmap: Bitmap, target: File): Bitmap {
        val thumb = bitmap.scaledToMaxDimension(THUMBNAIL_MAX_DIMENSION)
        val temp = File(target.parentFile, "${target.name}.${UUID.randomUUID()}$TEMP_SUFFIX")
        try {
            FileOutputStream(temp).use { out ->
                thumb.compress(Bitmap.CompressFormat.JPEG, 76, out)
            }
            if (!temp.renameTo(target)) {
                temp.copyTo(target, overwrite = true)
                temp.delete()
            }
            return thumb
        } catch (e: Exception) {
            temp.delete()
            throw e
        }
    }

    private fun thumbnailLockFor(filename: String): Any =
        thumbnailLocks.getOrPut(filename) { Any() }

    private fun evictThumbnails(filename: String) {
        for (key in thumbnailCache.snapshot().keys) {
            if (key.startsWith("$filename:")) thumbnailCache.remove(key)
        }
    }

    companion object {
        private const val DIR_NAME = "fudai-food-images"
        private const val THUMBNAIL_DIR_NAME = "fudai-food-thumbnails-v2"
        private const val LEGACY_THUMBNAIL_DIR_NAME = "fudai-food-thumbnails"
        private const val STAGING_DIR_NAME = "fudai-food-images-restore"
        private const val STAGING_THUMBNAIL_DIR_NAME = "fudai-food-thumbnails-restore"
        private const val TEMP_SUFFIX = ".tmp"
        private const val THUMBNAIL_MAX_DIMENSION = 320
        const val VIEWER_MAX_DIMENSION = 2048
        private const val THUMBNAIL_CACHE_KB_DEFAULT = 12 * 1024

        fun thumbnailCacheKbFor(context: Context): Int {
            val memoryClass =
                (context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager).memoryClass
            return when {
                memoryClass <= 128 -> 4 * 1024
                memoryClass <= 192 -> 8 * 1024
                else -> THUMBNAIL_CACHE_KB_DEFAULT
            }
        }

        @VisibleForTesting
        fun forTests(baseDir: File): FoodImageStore =
            FoodImageStore(
                baseDir.apply { mkdirs() },
                cleanupLegacyThumbnails = false,
                thumbnailCacheKb = THUMBNAIL_CACHE_KB_DEFAULT
            )
    }
}
