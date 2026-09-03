package com.yinxin.uavfir.wayline

import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files

class LocalKmzMissionControllerTest {

    @Test
    fun defaultKmzPath_matchesAppReadableKmzPath() {
        assertEquals(
            "/data/user/0/com.yinxin.uavfir/files/wayline-local/Kmz2.kmz",
            LocalKmzMissionController.DEFAULT_KMZ_PATH,
        )
    }

    @Test
    fun executeLocalKmz_usesInjectedDefaultKmzFile() = runTest {
        val kmz = tempKmz("Kmz2.kmz")
        val executor = FakeLocalKmzExecutor()
        val controller = LocalKmzMissionController(executor, defaultKmzFile = kmz)

        val result = controller.executeLocalKmz()

        assertTrue(result.ok)
        assertEquals(listOf(kmz.absolutePath), executor.pushedPaths)
        assertEquals("Kmz2", executor.startedMissions.single().missionFileName)
    }

    @Test
    fun executeLocalKmz_returnsMissingFileWhenKmzDoesNotExist() = runTest {
        val missing = File("/tmp/uavfire-missing-${System.nanoTime()}.kmz")
        val executor = FakeLocalKmzExecutor()
        val controller = LocalKmzMissionController(executor)

        val result = controller.executeLocalKmz(missing)

        assertFalse(result.ok)
        assertEquals("local-kmz-not-found: ${missing.absolutePath}", result.message)
        assertTrue(executor.pushedPaths.isEmpty())
        assertTrue(executor.startedMissions.isEmpty())
    }

    @Test
    fun executeLocalKmz_returnsPushFailureAndDoesNotStartMission() = runTest {
        val kmz = tempKmz()
        val executor = FakeLocalKmzExecutor(pushResult = LocalKmzPushResult(false, "push failed"))
        val controller = LocalKmzMissionController(executor)

        val result = controller.executeLocalKmz(kmz)

        assertFalse(result.ok)
        assertEquals("push failed", result.message)
        assertEquals(listOf(kmz.absolutePath), executor.pushedPaths)
        assertTrue(executor.startedMissions.isEmpty())
    }

    @Test
    fun executeLocalKmz_pushesLocalFileThenStartsMissionByFileName() = runTest {
        val kmz = tempKmz("Kmz2.kmz")
        val stagingDir = Files.createTempDirectory("local-kmz-stage").toFile()
        val executor = FakeLocalKmzExecutor()
        val controller = LocalKmzMissionController(executor, stagingDir)

        val result = controller.executeLocalKmz(kmz)

        assertTrue(result.ok)
        assertEquals("push SUCCESS; startMission fired for Kmz2", result.message)
        assertEquals(listOf(File(stagingDir, "Kmz2.kmz").absolutePath), executor.pushedPaths)
        assertEquals(kmz.readBytes().toList(), File(stagingDir, "Kmz2.kmz").readBytes().toList())
        assertEquals(1, executor.startedMissions.size)
        assertEquals("Kmz2", executor.startedMissions.single().missionFileName)
        assertEquals(null, executor.startedMissions.single().waylineIds)
        assertEquals(File(stagingDir, "Kmz2.kmz").absolutePath, executor.startedMissions.single().diagnosticKmzPath)
    }

    @Test
    fun executeLocalKmz_reportsStartGuardRejection() = runTest {
        val kmz = tempKmz("Kmz2.kmz")
        val executor = FakeLocalKmzExecutor(startRejection = "no-available-wayline-ids-on-aircraft")
        val controller = LocalKmzMissionController(executor)

        val result = controller.executeLocalKmz(kmz)

        assertFalse(result.ok)
        assertEquals("startMission rejected: no-available-wayline-ids-on-aircraft", result.message)
    }

    @Test
    fun executeLocalKmz_doesNotCopyWhenSourceAlreadyInStagingDir() = runTest {
        val stagingDir = Files.createTempDirectory("local-kmz-stage").toFile()
        val kmz = File(stagingDir, "Kmz2.kmz").apply { writeBytes(byteArrayOf(7, 8, 9)) }
        val executor = FakeLocalKmzExecutor()
        val controller = LocalKmzMissionController(executor, stagingDir)

        val result = controller.executeLocalKmz(kmz)

        assertTrue(result.ok)
        assertEquals(listOf(kmz.absolutePath), executor.pushedPaths)
        assertEquals(byteArrayOf(7, 8, 9).toList(), kmz.readBytes().toList())
    }

    private fun tempKmz(name: String = "mission.kmz"): File {
        val dir = Files.createTempDirectory("local-kmz-test").toFile()
        return File(dir, name).apply { writeBytes(byteArrayOf(1, 2, 3)) }
    }

    private class FakeLocalKmzExecutor(
        private val pushResult: LocalKmzPushResult = LocalKmzPushResult(true, null),
        private val startRejection: String? = null,
    ) : LocalKmzExecutor {
        val pushedPaths = mutableListOf<String>()
        val startedMissions = mutableListOf<StartedMission>()

        override fun pushKmz(missionId: String, kmzPath: String, onComplete: (LocalKmzPushResult) -> Unit) {
            pushedPaths += kmzPath
            onComplete(pushResult)
        }

        override fun startMission(
            missionId: String,
            missionFileName: String,
            waylineIds: List<Int>?,
            diagnosticKmzPath: String,
        ): String? {
            startedMissions += StartedMission(missionId, missionFileName, waylineIds, diagnosticKmzPath)
            return startRejection
        }
    }

    private data class StartedMission(
        val missionId: String,
        val missionFileName: String,
        val waylineIds: List<Int>?,
        val diagnosticKmzPath: String,
    )
}
