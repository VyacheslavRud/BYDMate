package com.bydmate.app.data.backup

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29])
class BackupRestoreSchemaTest {
    private val context: Context = ApplicationProvider.getApplicationContext()

    @Test fun `validator accepts manifest schema matching SQLite user_version`() {
        val file = sqliteFile(userVersion = 18)

        BackupManager.validateSqliteFile(file, expectedSchemaVersion = 18)

        assertTrue(file.exists())
        file.delete()
    }

    @Test fun `validator rejects and removes database when manifest schema differs`() {
        val file = sqliteFile(userVersion = 17)

        assertThrows(IllegalStateException::class.java) {
            BackupManager.validateSqliteFile(file, expectedSchemaVersion = 18)
        }

        assertFalse(file.exists())
    }

    private fun sqliteFile(userVersion: Int): File {
        val file = File(context.cacheDir, "backup-schema-${System.nanoTime()}.db")
        SQLiteDatabase.openOrCreateDatabase(file, null).use { database ->
            database.execSQL("PRAGMA user_version = $userVersion")
        }
        return file
    }
}
