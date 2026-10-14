package com.qingxu.app.data.local

import android.app.Application
import android.content.Context
import androidx.room.Room
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.sqlite.db.SupportSQLiteOpenHelper
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import androidx.test.core.app.ApplicationProvider
import com.qingxu.app.core.decision.DecisionBucket
import com.qingxu.app.core.decision.effectiveBucket
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], application = Application::class)
class DatabaseMigrationTest {
    @Test fun versionOneDataAndManualPreferencesSurviveValidatedMigration() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val name = "migration-${System.nanoTime()}.db"
        val old = FrameworkSQLiteOpenHelperFactory().create(
            SupportSQLiteOpenHelper.Configuration.builder(context).name(name)
                .callback(object : SupportSQLiteOpenHelper.Callback(1) {
                    override fun onCreate(db: SupportSQLiteDatabase) {
                        db.execSQL("""CREATE TABLE notifications (
                            notificationKey TEXT NOT NULL PRIMARY KEY, packageName TEXT NOT NULL,
                            appLabel TEXT NOT NULL, title TEXT NOT NULL, body TEXT NOT NULL,
                            channelId TEXT, postedAt INTEGER NOT NULL, updatedAt INTEGER NOT NULL,
                            contentHash TEXT NOT NULL, contentVersion INTEGER NOT NULL,
                            userLabel TEXT NOT NULL, previousLabel TEXT NOT NULL, labelSource TEXT NOT NULL,
                            interested INTEGER NOT NULL, removedAt INTEGER)""")
                        db.execSQL("CREATE INDEX index_notifications_packageName ON notifications(packageName)")
                        db.execSQL("CREATE INDEX index_notifications_postedAt ON notifications(postedAt)")
                    }
                    override fun onUpgrade(db: SupportSQLiteDatabase, oldVersion: Int, newVersion: Int) = Unit
                }).build(),
        )
        old.writableDatabase.execSQL("""INSERT INTO notifications VALUES
            ('manual', 'example.app', '示例', '标题', '正文', NULL, 1, 2, 'old-hash', 1,
            'IMPORTANT', 'UNSORTED', 'manual', 1, NULL),
            ('unclassified', 'example.app', '示例', '标题', '正文', NULL, 1, 2, 'old-hash', 1,
            'UNSORTED', 'UNSORTED', 'unclassified', 0, NULL)""")
        old.close()
        val migrated = Room.databaseBuilder(context, QingxuDatabase::class.java, name)
            .addMigrations(MIGRATION_1_2, MIGRATION_2_3).build()
        try {
            val dao = migrated.notifications()
            dao.configureCipher(testNotificationCipher())
            assertEquals(2, migrated.initializeNotificationEncryption())
            val manual = dao.find("manual")!! // Opens and runs Room's full migrated schema validation.
            assertEquals("正文", manual.body)
            assertEquals(0, manual.manualVersion)
            assertEquals(DecisionBucket.LATER, manual.effectiveBucket())
            assertTrue(manual.interested)
            assertEquals(listOf("unclassified"), dao.pending().map { it.notificationKey })
            dao.saveRule(SourceKindRule("example.app", "marketing", true))
            assertEquals(1, dao.rules().size)
            migrated.openHelper.writableDatabase.query("SELECT body, storageEncryptionVersion FROM notifications").use {
                assertTrue(it.moveToFirst())
                assertTrue(it.getString(0).startsWith("qx1:"))
                assertEquals(1, it.getInt(1))
            }
        } finally {
            migrated.close()
            context.deleteDatabase(name)
        }
    }
}
