package com.yinxin.uavfir.wayline

import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import okio.Buffer
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.security.MessageDigest

class WaylineKmzDownloaderTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    private lateinit var server: MockWebServer
    private lateinit var downloader: WaylineKmzDownloader

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
        downloader = WaylineKmzDownloader(OkHttpClient(), tempFolder.newFolder("kmz-cache"))
    }

    @After
    fun tearDown() {
        server.shutdown()
    }

    @Test
    fun download_writesBytesAndComputesMd5OnSuccess() {
        val payload = "PKfake-kmz-bytes".toByteArray()
        val expectedMd5 = md5Hex(payload)
        server.enqueue(MockResponse()
            .setHeader(WaylineKmzDownloader.HEADER_KMZ_MD5, expectedMd5)
            .setBody(Buffer().write(payload)))

        val result = downloader.download(
            url = server.url("/kmz").toString(),
            agentToken = "jwt-token-abc",
            expectedMd5 = expectedMd5,
            missionId = "m-1",
        )

        assertTrue(result is WaylineKmzDownloader.Result.Success)
        val success = result as WaylineKmzDownloader.Result.Success
        assertEquals(expectedMd5, success.md5)
        assertArrayEquals(payload, success.file.readBytes())
        assertTrue(success.file.name.endsWith("m-1.kmz"))

        val req: RecordedRequest = server.takeRequest()
        assertEquals("jwt-token-abc", req.getHeader(WaylineKmzDownloader.HEADER_AGENT_TOKEN))
    }

    @Test
    fun download_failsWhenServerMd5DoesNotMatchBytes() {
        val payload = "anything".toByteArray()
        server.enqueue(MockResponse()
            .setHeader(WaylineKmzDownloader.HEADER_KMZ_MD5, "deadbeef")
            .setBody(Buffer().write(payload)))

        val result = downloader.download(
            url = server.url("/kmz").toString(),
            agentToken = "tok",
            expectedMd5 = null,
            missionId = "m-2",
        )

        assertTrue(result is WaylineKmzDownloader.Result.Failure)
        val reason = (result as WaylineKmzDownloader.Result.Failure).reason
        assertTrue(reason.startsWith("md5-mismatch-server"))
    }

    @Test
    fun download_failsWhenDispatchMd5DoesNotMatchBytes() {
        val payload = "anything".toByteArray()
        val actual = md5Hex(payload)
        server.enqueue(MockResponse()
            .setHeader(WaylineKmzDownloader.HEADER_KMZ_MD5, actual)
            .setBody(Buffer().write(payload)))

        val result = downloader.download(
            url = server.url("/kmz").toString(),
            agentToken = "tok",
            expectedMd5 = "0000000000",
            missionId = "m-3",
        )

        assertTrue(result is WaylineKmzDownloader.Result.Failure)
        assertTrue((result as WaylineKmzDownloader.Result.Failure).reason.startsWith("md5-mismatch-dispatch"))
    }

    @Test
    fun download_failsOnHttp404() {
        server.enqueue(MockResponse().setResponseCode(404))

        val result = downloader.download(
            url = server.url("/kmz").toString(),
            agentToken = "tok",
            expectedMd5 = null,
            missionId = "m-x",
        )

        assertTrue(result is WaylineKmzDownloader.Result.Failure)
        assertEquals("http-status:404", (result as WaylineKmzDownloader.Result.Failure).reason)
    }

    @Test
    fun download_acceptsAbsentServerMd5IfDispatchMd5Matches() {
        val payload = "kmz-ok".toByteArray()
        val expectedMd5 = md5Hex(payload)
        server.enqueue(MockResponse().setBody(Buffer().write(payload)))

        val result = downloader.download(
            url = server.url("/kmz").toString(),
            agentToken = "tok",
            expectedMd5 = expectedMd5,
            missionId = "m-4",
        )

        assertTrue(result is WaylineKmzDownloader.Result.Success)
        assertNotNull(result)
    }

    private fun md5Hex(bytes: ByteArray): String {
        val digest = MessageDigest.getInstance("MD5").digest(bytes)
        return digest.joinToString("") { "%02x".format(it) }
    }
}
