package com.apoorvdarshan.calorietracker.backup

import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.UUID
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream
import kotlin.random.Random

class CloudBackupArchiveTest {
    @get:Rule
    val temp = TemporaryFolder()

    @Test
    fun roundTripKeepsFoodEntryIdsAndPhotos() {
        val id = UUID.fromString("11111111-2222-3333-4444-555555555555")
        val values = mapOf(
            "foodEntries" to CloudBackupValue.string("""[{"id":"$id","name":"Oats"}]"""),
            "healthChangesToken" to CloudBackupValue.string("device-token"),
            "healthFoodRestoreDone" to CloudBackupValue.bool(false),
        )
        val photo = byteArrayOf(1, 2, 3, 4)
        val zip = packToFile(values, listOf(memoryPhoto("$id.jpg", photo)))
        val document = CloudBackupArchive.readDocument(zip)
        val photos = readPhotos(zip)
        assertEquals(CloudBackupPolicy.FORMAT, document.format)
        assertEquals(1, document.format_version)
        assertEquals("""[{"id":"$id","name":"Oats"}]""", document.payload.values.getValue("foodEntries").s)
        assertFalse(document.payload.values.containsKey("healthChangesToken"))
        assertFalse(document.payload.values.containsKey("healthFoodRestoreDone"))
        assertTrue(photos.containsKey("$id.jpg"))
        assertEquals(photo.toList(), photos.getValue("$id.jpg").toList())
    }

    @Test
    fun newerFormatVersionFailsClosed() {
        val packed = packToFile(mapOf("useMetric" to CloudBackupValue.bool(true)), emptyList())
        val newer = CloudBackupArchive.readDocument(packed).copy(format_version = CloudBackupPolicy.VERSION + 1)
        val encoded = Json.encodeToString(CloudBackupDocument.serializer(), newer).toByteArray()
        val tampered = temp.newFile("newer.zip")
        ZipOutputStream(tampered.outputStream()).use { zipOut ->
            zipOut.putNextEntry(ZipEntry(CloudBackupPolicy.PAYLOAD_NAME))
            zipOut.write(encoded)
            zipOut.closeEntry()
        }
        val error = runCatching { CloudBackupArchive.readDocument(tampered) }.exceptionOrNull()
        assertTrue(error is IllegalArgumentException)
        assertTrue(error!!.message!!.contains("newer"))
    }

    @Test
    fun unchangedHashSkipsWork() {
        val values = mapOf("foodEntries" to CloudBackupValue.string("[]"))
        val hash1 = CloudBackupArchive.contentHash(values, emptyList())
        val hash2 = CloudBackupArchive.contentHash(values, emptyList())
        assertEquals(hash1, hash2)
        val hash3 = CloudBackupArchive.contentHash(
            mapOf("foodEntries" to CloudBackupValue.string("[1]")),
            emptyList(),
        )
        assertTrue(hash1 != hash3)
    }

    @Test
    fun rejectsPathTraversalPhotoNames() {
        assertEquals("secret.jpg", CloudBackupPolicy.safePhotoName("../secret.jpg"))
        assertEquals("meal.jpg", CloudBackupPolicy.safePhotoName("photos/meal.jpg"))
        assertEquals(null, CloudBackupPolicy.safePhotoName("notes.txt"))
    }

    @Test
    fun fileRoundTripWithManyPhotosRestoresEveryByte() {
        val random = Random(484)
        val photoDir = temp.newFolder("photos")
        val originals = (0 until 300).associate { index ->
            val name = "${UUID(index.toLong(), 484L)}.jpg"
            val bytes = random.nextBytes(random.nextInt(1, 24 * 1024))
            File(photoDir, name).writeBytes(bytes)
            name to bytes
        }
        val photos = originals.keys.map { CloudBackupPhoto.fromFile(it, File(photoDir, it)) }
        val zip = packToFile(sampleValues(), photos)

        val document = CloudBackupArchive.readDocument(zip)
        val restored = readPhotos(zip)
        assertEquals(originals.keys, restored.keys)
        originals.forEach { (name, bytes) -> assertArrayEquals(name, bytes, restored.getValue(name)) }
        val packedValues = sampleValues().filterKeys { it !in CloudBackupPolicy.excludedKeys }
        assertEquals(legacyContentHash(packedValues, originals), document.content_sha256)
        assertEquals(legacyContentHash(sampleValues(), originals), CloudBackupArchive.contentHash(sampleValues(), photos))
    }

    @Test
    fun packMatchesLegacyInMemoryArchiveByteForByte() {
        val random = Random(7)
        val originals = linkedMapOf(
            "b-second.jpg" to random.nextBytes(40_000),
            "a-first.png" to ByteArray(10_000) { (it % 7).toByte() },
            "notes.txt" to byteArrayOf(9, 9, 9),
            "c.webp" to byteArrayOf(),
        )
        val values = sampleValues()
        val legacy = legacyPack(values, originals, EXPORTED_AT, APP_VERSION)
        val streamed = packToFile(values, originals.map { (name, bytes) -> memoryPhoto(name, bytes) }).readBytes()

        assertArrayEquals(withoutDosTimes(legacy), withoutDosTimes(streamed))
        assertEquals(legacyContentHash(values, originals), CloudBackupArchive.contentHash(values, originals.map { (name, bytes) -> memoryPhoto(name, bytes) }))
    }

    @Test
    fun readsArchivesWrittenByTheLegacyPack() {
        val originals = mapOf("meal.jpg" to byteArrayOf(4, 8, 15, 16, 23, 42), "snack.png" to ByteArray(5_000) { 1 })
        val legacyFile = temp.newFile("legacy.zip").apply {
            writeBytes(legacyPack(sampleValues(), originals, EXPORTED_AT, APP_VERSION))
        }
        val document = CloudBackupArchive.readDocument(legacyFile)
        val (legacyDocument, legacyPhotos) = legacyUnpack(legacyFile.readBytes())
        assertEquals(legacyDocument, document)
        val restored = readPhotos(legacyFile)
        assertEquals(legacyPhotos.keys, restored.keys)
        legacyPhotos.forEach { (name, bytes) -> assertArrayEquals(bytes, restored.getValue(name)) }
    }

    @Test
    fun legacyUnpackReadsStreamedArchives() {
        val originals = mapOf("meal.jpg" to byteArrayOf(1, 1, 2, 3, 5, 8))
        val streamed = packToFile(sampleValues(), originals.map { (name, bytes) -> memoryPhoto(name, bytes) })
        val (document, photos) = legacyUnpack(streamed.readBytes())
        assertEquals(CloudBackupArchive.readDocument(streamed), document)
        assertArrayEquals(originals.getValue("meal.jpg"), photos.getValue("meal.jpg"))
    }

    @Test
    fun packOpensOnePhotoAtATime() {
        var open = 0
        var maxOpen = 0
        var closed = 0
        val photos = (0 until 50).map { index ->
            val bytes = ByteArray(2_048) { index.toByte() }
            CloudBackupPhoto("photo-$index.jpg", bytes.size.toLong()) {
                open++
                maxOpen = maxOf(maxOpen, open)
                object : ByteArrayInputStream(bytes) {
                    override fun close() {
                        open--
                        closed++
                    }
                }
            }
        }
        packToFile(sampleValues(), photos)
        assertEquals(1, maxOpen)
        assertEquals(50, closed)
    }

    @Test
    fun packLeavesOutPhotosDeletedAfterTheSnapshot() {
        val dir = temp.newFolder("snapshot")
        val kept = File(dir, "kept.jpg").apply { writeBytes(byteArrayOf(1, 2)) }
        val deleted = File(dir, "deleted.jpg").apply { writeBytes(byteArrayOf(3, 4)) }
        val photos = listOf(CloudBackupPhoto.fromFile("kept.jpg", kept), CloudBackupPhoto.fromFile("deleted.jpg", deleted))
        assertTrue(deleted.delete())
        val zip = packToFile(sampleValues(), photos)
        assertEquals(setOf("kept.jpg"), readPhotos(zip).keys)
    }

    @Test
    fun corruptArchiveFailsValidationBeforeAnyPhotoIsRead() {
        val zip = packToFile(sampleValues(), listOf(memoryPhoto("big.jpg", Random(1).nextBytes(64 * 1024))))
        val bytes = zip.readBytes()
        val middle = bytes.size / 2
        bytes[middle] = (bytes[middle].toInt() xor 0xFF).toByte()
        zip.writeBytes(bytes)
        try {
            CloudBackupArchive.readDocument(zip)
            fail("expected a corrupt archive to fail validation")
        } catch (_: IOException) {
        }
    }

    @Test
    fun tempArchiveIsDeletedAfterSuccessAndFailure() {
        val dir = temp.newFolder("cache")
        val result = CloudBackupArchive.withTempArchive(dir) { file ->
            file.writeBytes(byteArrayOf(1))
            assertTrue(file.exists())
            "uploaded"
        }
        assertEquals("uploaded", result)
        assertTrue(dir.listFiles().isNullOrEmpty())

        val failure = runCatching {
            CloudBackupArchive.withTempArchive(dir) { file ->
                CloudBackupArchive.pack(
                    destination = file,
                    values = sampleValues(),
                    photos = listOf(
                        memoryPhoto("ok.jpg", ByteArray(1_024) { 1 }),
                        CloudBackupPhoto("broken.jpg", 10) { BrokenInputStream() },
                    ),
                    exportedAt = EXPORTED_AT,
                    appVersion = APP_VERSION,
                )
            }
        }.exceptionOrNull()
        assertTrue(failure is IOException)
        assertTrue(dir.listFiles().isNullOrEmpty())
    }

    @Test
    fun deleteTempArchivesRemovesOnlyBackupLeftovers() {
        val dir = temp.newFolder("leftovers")
        val orphan = CloudBackupArchive.createTempArchive(dir).apply { writeBytes(byteArrayOf(1)) }
        val unrelated = File(dir, "other.zip").apply { writeBytes(byteArrayOf(2)) }
        CloudBackupArchive.deleteTempArchives(dir)
        assertFalse(orphan.exists())
        assertTrue(unrelated.exists())
    }

    private fun packToFile(values: Map<String, CloudBackupValue>, photos: List<CloudBackupPhoto>): File =
        temp.newFile().also { file ->
            CloudBackupArchive.pack(
                destination = file,
                values = values,
                photos = photos,
                exportedAt = EXPORTED_AT,
                appVersion = APP_VERSION,
            )
        }

    private fun readPhotos(zip: File): Map<String, ByteArray> {
        val out = linkedMapOf<String, ByteArray>()
        CloudBackupArchive.forEachPhoto(zip) { name, input ->
            // Closing must not end the archive stream for the photos that follow.
            out[name] = input.use { it.readBytes() }
        }
        return out
    }

    private fun memoryPhoto(name: String, bytes: ByteArray) =
        CloudBackupPhoto(name, bytes.size.toLong()) { ByteArrayInputStream(bytes) }

    private fun sampleValues() = mapOf(
        "foodEntries" to CloudBackupValue.string("""[{"id":"a","name":"Rice"}]"""),
        "useMetric" to CloudBackupValue.bool(true),
        "goalCalories" to CloudBackupValue.int(2100),
        "pinnedNutrients" to CloudBackupValue.stringSet(listOf("fiber", "sugar")),
        "healthChangesToken" to CloudBackupValue.string("device-token"),
    )

    private class BrokenInputStream : InputStream() {
        override fun read(): Int = throw IOException("disk read failed")
    }

    /** Zeroes the DOS modified time and date of every entry, the only bytes that vary by run. */
    private fun withoutDosTimes(zip: ByteArray): ByteArray {
        val out = zip.copyOf()
        val buffer = ByteBuffer.wrap(out).order(ByteOrder.LITTLE_ENDIAN)
        val end = out.size - 22
        assertEquals(0x06054b50, buffer.getInt(end))
        val entries = buffer.getShort(end + 10).toInt() and 0xFFFF
        var offset = buffer.getInt(end + 16)
        repeat(entries) {
            assertEquals(0x02014b50, buffer.getInt(offset))
            buffer.putInt(offset + 12, 0)
            val local = buffer.getInt(offset + 42)
            assertEquals(0x04034b50, buffer.getInt(local))
            buffer.putInt(local + 10, 0)
            val nameLength = buffer.getShort(offset + 28).toInt() and 0xFFFF
            val extraLength = buffer.getShort(offset + 30).toInt() and 0xFFFF
            val commentLength = buffer.getShort(offset + 32).toInt() and 0xFFFF
            offset += 46 + nameLength + extraLength + commentLength
        }
        return out
    }

    // The pre-#484 in-memory implementation, kept verbatim as the compatibility reference.
    private val legacyJson = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    private fun legacyContentHash(values: Map<String, CloudBackupValue>, photos: Map<String, ByteArray>): String {
        val canonical = buildString {
            values.toSortedMap().forEach { (key, value) ->
                append(key).append('=')
                when (value.t) {
                    "b" -> append("b:").append(value.b)
                    "i" -> append("i:").append(value.i)
                    "s" -> append("s:").append(value.s)
                    "ss" -> append("ss:").append(value.ss.orEmpty().sorted().joinToString(","))
                    else -> append(value.t)
                }
                append('\n')
            }
            photos.toSortedMap().forEach { (name, bytes) ->
                append("photo:").append(name).append(':').append(bytes.size).append('\n')
            }
        }
        return CloudBackupArchive.sha256(canonical.toByteArray(Charsets.UTF_8))
    }

    private fun legacyPack(
        values: Map<String, CloudBackupValue>,
        photos: Map<String, ByteArray>,
        exportedAt: String,
        appVersion: String,
        platform: String = "android",
    ): ByteArray {
        val filtered = values.filterKeys { it !in CloudBackupPolicy.excludedKeys }
        val safePhotos = photos.mapNotNull { (name, bytes) ->
            CloudBackupPolicy.safePhotoName(name)?.let { it to bytes }
        }.toMap()
        val document = CloudBackupDocument(
            format = CloudBackupPolicy.FORMAT,
            format_version = CloudBackupPolicy.VERSION,
            exported_at = exportedAt,
            app_version = appVersion,
            platform = platform,
            content_sha256 = legacyContentHash(filtered, safePhotos),
            payload = CloudBackupPayload(values = filtered),
        )
        val payload = legacyJson.encodeToString(document).toByteArray(Charsets.UTF_8)
        val out = ByteArrayOutputStream()
        ZipOutputStream(out).use { zip ->
            zip.putNextEntry(ZipEntry(CloudBackupPolicy.PAYLOAD_NAME))
            zip.write(payload)
            zip.closeEntry()
            for ((name, bytes) in safePhotos) {
                zip.putNextEntry(ZipEntry(CloudBackupPolicy.PHOTOS_DIR + name))
                zip.write(bytes)
                zip.closeEntry()
            }
        }
        return out.toByteArray()
    }

    private fun legacyUnpack(bytes: ByteArray): Pair<CloudBackupDocument, Map<String, ByteArray>> {
        var payload: ByteArray? = null
        val photos = linkedMapOf<String, ByteArray>()
        ZipInputStream(ByteArrayInputStream(bytes)).use { zip ->
            while (true) {
                val entry = zip.nextEntry ?: break
                val data = zip.readBytes()
                when {
                    entry.name == CloudBackupPolicy.PAYLOAD_NAME -> payload = data
                    entry.name.startsWith(CloudBackupPolicy.PHOTOS_DIR) -> {
                        CloudBackupPolicy.safePhotoName(entry.name)?.let { photos[it] = data }
                    }
                }
            }
        }
        val document = legacyJson.decodeFromString<CloudBackupDocument>(payload!!.toString(Charsets.UTF_8))
        return document to photos
    }

    private companion object {
        const val EXPORTED_AT = "2026-09-10T12:00:00Z"
        const val APP_VERSION = "7.0"
    }
}
