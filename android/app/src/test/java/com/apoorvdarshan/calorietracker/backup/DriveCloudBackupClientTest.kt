package com.apoorvdarshan.calorietracker.backup

import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okio.Buffer
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import kotlin.random.Random

class DriveCloudBackupClientTest {
    @get:Rule
    val temp = TemporaryFolder()

    private val server = MockWebServer()
    private lateinit var drive: DriveCloudBackupClient

    @Before
    fun setUp() {
        server.start()
        // Send the hard-coded googleapis.com URLs to the local server instead.
        val http = OkHttpClient.Builder()
            .addInterceptor { chain ->
                val original = chain.request()
                val local = original.url.newBuilder()
                    .scheme("http")
                    .host(server.hostName)
                    .port(server.port)
                    .build()
                chain.proceed(original.newBuilder().url(local).build())
            }
            .build()
        drive = DriveCloudBackupClient(webClientId = "", http = http)
    }

    @After
    fun tearDown() {
        server.shutdown()
    }

    @Test
    fun updateUploadsTheArchiveFileWithAKnownLength() = runBlocking {
        val bytes = Random(484).nextBytes(3 * 1024 * 1024)
        val zip = temp.newFile("backup.zip").apply { writeBytes(bytes) }
        server.enqueue(MockResponse().setResponseCode(200).setBody("{}"))

        assertEquals("file-1", drive.upload("token", zip, existingFileId = "file-1"))

        val request = server.takeRequest()
        assertEquals("PATCH", request.method)
        assertEquals("/upload/drive/v3/files/file-1?uploadType=media", request.path)
        assertEquals("application/zip", request.getHeader("Content-Type"))
        assertEquals(bytes.size.toString(), request.getHeader("Content-Length"))
        assertNull(request.getHeader("Transfer-Encoding"))
        assertArrayEquals(bytes, request.body.readByteArray())
    }

    @Test
    fun downloadStreamsTheBackupIntoTheDestinationFile() = runBlocking {
        val bytes = Random(7).nextBytes(2 * 1024 * 1024)
        server.enqueue(MockResponse().setResponseCode(200).setBody(Buffer().write(bytes)))
        val destination = temp.newFile("restore.zip")

        drive.download("token", "file-1", destination)

        assertEquals("/drive/v3/files/file-1?alt=media", server.takeRequest().path)
        assertArrayEquals(bytes, destination.readBytes())
    }

    @Test
    fun failedDownloadReportsTheHttpStatus() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(404))
        val destination = temp.newFile("missing.zip")

        val error = runCatching { drive.download("token", "gone", destination) }.exceptionOrNull()

        assertTrue(error is IllegalStateException)
        assertEquals("Drive download failed (404)", error!!.message)
    }
}
