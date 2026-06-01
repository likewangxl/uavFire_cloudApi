package com.yinxin.uavfir.api

import kotlinx.coroutines.test.runTest
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File

class ThermalSnapshotUploaderTest {
    private lateinit var server: MockWebServer

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
    }

    @After
    fun tearDown() {
        server.shutdown()
    }

    @Test
    fun upload_retriesAsUnverifiedFallbackWhenThermalValidationReturns422() = runTest {
        server.enqueue(
            MockResponse()
                .setResponseCode(422)
                .setBody("""{"detail":"Snapshot does not look like a thermal frame"}"""),
        )
        server.enqueue(
            MockResponse()
                .setResponseCode(201)
                .setBody(
                    """
                    {
                      "url": "http://ai/snapshots/event-001-annotated.jpg",
                      "thermal_verified": false,
                      "thermal_reject_reason": "not_thermal_frame"
                    }
                    """.trimIndent(),
                ),
        )
        val snapshot = File.createTempFile("thermal-upload", ".jpg")
        snapshot.writeBytes(byteArrayOf(1, 2, 3, 4))
        val uploader = AiServiceThermalSnapshotUploader(baseUrl = server.url("/").toString())

        val url = uploader.upload("event-001", snapshot.absolutePath)

        assertEquals("http://ai/snapshots/event-001-annotated.jpg", url)
        assertEquals(2, server.requestCount)
        val first = server.takeRequest()
        val second = server.takeRequest()
        assertEquals("/api/v1/snapshots/msdk-thermal/event-001", first.path)
        assertEquals("/api/v1/snapshots/msdk-thermal/event-001", second.path)
        assertTrue(second.body.readUtf8().contains("allow_unverified"))
    }
}
