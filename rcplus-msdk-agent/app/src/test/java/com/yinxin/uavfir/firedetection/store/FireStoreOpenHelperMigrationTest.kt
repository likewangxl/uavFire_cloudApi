package com.yinxin.uavfir.firedetection.store

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import androidx.test.core.app.ApplicationProvider
import java.util.UUID
import org.junit.Assert.assertThrows
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.SQLiteMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
@SQLiteMode(SQLiteMode.Mode.NATIVE)
class FireStoreOpenHelperMigrationTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()

    @Test
    fun upgradeAndDowngradeFailClosedInsteadOfDeletingData() {
        val upgrade = FireStoreOpenHelper(context, "upgrade-${UUID.randomUUID()}.db")
        assertThrows(IllegalStateException::class.java) {
            upgrade.onUpgrade(upgrade.writableDatabase, 2, 3)
        }
        val downgrade = FireStoreOpenHelper(context, "downgrade-${UUID.randomUUID()}.db")
        assertThrows(IllegalStateException::class.java) {
            downgrade.onDowngrade(downgrade.writableDatabase, 3, 2)
        }
        upgrade.close()
        downgrade.close()
    }

    @Test
    fun schema3To4AddsRestartableCoordinatorIdentityWithoutDroppingRows() {
        val db = SQLiteDatabase.create(null)
        db.execSQL(
            "CREATE TABLE fire_session (session_id TEXT PRIMARY KEY, event_id TEXT NOT NULL)",
        )
        db.execSQL("INSERT INTO fire_session(session_id,event_id) VALUES('s1','e1')")
        val helper = FireStoreOpenHelper(context, "migration-${UUID.randomUUID()}.db")
        helper.onUpgrade(db, 3, 4)
        val columns = db.rawQuery("PRAGMA table_info(fire_session)", null).use { cursor ->
            buildSet { while (cursor.moveToNext()) add(cursor.getString(1)) }
        }
        assertTrue(
            columns.containsAll(
                setOf(
                    "task_id", "source_generation", "coordinator_generation",
                    "roi_left", "roi_top", "roi_right", "roi_bottom",
                    "recovery_proof_version", "recovery_proof_payload", "recovery_proof_sha256",
                ),
            ),
        )
        assertTrue(db.rawQuery("SELECT COUNT(*) FROM fire_session", null).use {
            it.moveToFirst() && it.getInt(0) == 1
        })
        db.close()
        helper.close()
    }

    @Test
    fun schema4To5AddsIndependentUnboundDroneWithoutDerivingItFromTask() {
        val db = SQLiteDatabase.create(null)
        db.execSQL(
            "CREATE TABLE fire_session (session_id TEXT PRIMARY KEY, event_id TEXT NOT NULL, task_id TEXT NOT NULL)",
        )
        db.execSQL("INSERT INTO fire_session(session_id,event_id,task_id) VALUES('s1','e1','fire-fake-drone')")
        val helper = FireStoreOpenHelper(context, "migration-${UUID.randomUUID()}.db")
        helper.onUpgrade(db, 4, 5)

        assertEquals("legacy-unbound", db.rawQuery("SELECT drone_sn FROM fire_session", null).use {
            it.moveToFirst()
            it.getString(0)
        })
        assertEquals("fire-fake-drone", db.rawQuery("SELECT task_id FROM fire_session", null).use {
            it.moveToFirst()
            it.getString(0)
        })
        db.close()
        helper.close()
    }
}
