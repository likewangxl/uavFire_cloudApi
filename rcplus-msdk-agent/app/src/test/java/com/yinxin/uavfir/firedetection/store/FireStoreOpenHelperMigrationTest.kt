package com.yinxin.uavfir.firedetection.store

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import java.util.UUID
import org.junit.Assert.assertThrows
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
            upgrade.onUpgrade(upgrade.writableDatabase, 1, 2)
        }
        val downgrade = FireStoreOpenHelper(context, "downgrade-${UUID.randomUUID()}.db")
        assertThrows(IllegalStateException::class.java) {
            downgrade.onDowngrade(downgrade.writableDatabase, 2, 1)
        }
        upgrade.close()
        downgrade.close()
    }
}
