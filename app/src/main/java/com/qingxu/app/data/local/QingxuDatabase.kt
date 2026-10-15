package com.qingxu.app.data.local

import androidx.room.Database
import androidx.room.RoomDatabase
import androidx.room.TypeConverters
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

@Database(entities = [NotificationEntry::class, SourceKindRule::class, SenderPriorityRule::class], version = 4, exportSchema = true)
@TypeConverters(LabelConverters::class)
abstract class QingxuDatabase : RoomDatabase() {
    abstract fun notifications(): NotificationDao
}

val MIGRATION_3_4 = object : Migration(3, 4) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE notifications ADD COLUMN handledAt INTEGER")
        db.execSQL("CREATE TABLE IF NOT EXISTS sender_priority_rules (packageName TEXT NOT NULL, scopeHash TEXT NOT NULL, PRIMARY KEY(packageName, scopeHash))")
        // Keep encrypted text untouched while undoing the old overly broad hide preference.
        db.execSQL("""UPDATE notifications SET machineBucket = 'REVIEW', analysisStatus = 'pending',
            analysisSource = '', machineConfidence = 0.0
            WHERE analysisSource = 'preference' AND machineBucket = 'HIDDEN'
            AND machineKind != 'marketing' AND labelSource != 'manual' AND handledAt IS NULL""")
    }
}

val MIGRATION_2_3 = object : Migration(2, 3) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE notifications ADD COLUMN storageEncryptionVersion INTEGER NOT NULL DEFAULT 0")
    }
}

/** Run at startup before exposing DAO flows or accepting notifications. */
suspend fun QingxuDatabase.initializeNotificationEncryption(): Int = withContext(Dispatchers.IO) {
    val sql = openHelper.writableDatabase
    sql.query("PRAGMA secure_delete=ON").use { while (it.moveToNext()) { } }
    val count = notifications().encryptLegacyRecords()
    if (count > 0) compactNotificationStorage()
    count
}

/** Best-effort physical page reclamation; this is not a promise of forensic flash erasure. */
suspend fun QingxuDatabase.compactNotificationStorage() = withContext(Dispatchers.IO) {
    val sql = openHelper.writableDatabase
    sql.query("PRAGMA secure_delete=ON").use { while (it.moveToNext()) { } }
    sql.query("PRAGMA wal_checkpoint(TRUNCATE)").use { while (it.moveToNext()) { } }
    sql.execSQL("VACUUM")
    sql.query("PRAGMA wal_checkpoint(TRUNCATE)").use { while (it.moveToNext()) { } }
}

val MIGRATION_1_2 = object : Migration(1, 2) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE notifications ADD COLUMN machineBucket TEXT NOT NULL DEFAULT 'REVIEW'")
        db.execSQL("ALTER TABLE notifications ADD COLUMN machineKind TEXT NOT NULL DEFAULT 'unknown'")
        db.execSQL("ALTER TABLE notifications ADD COLUMN machineReason TEXT NOT NULL DEFAULT '等待整理'")
        db.execSQL("ALTER TABLE notifications ADD COLUMN machineConfidence REAL NOT NULL DEFAULT 0.0")
        db.execSQL("ALTER TABLE notifications ADD COLUMN analysisStatus TEXT NOT NULL DEFAULT 'pending'")
        db.execSQL("ALTER TABLE notifications ADD COLUMN analysisSource TEXT NOT NULL DEFAULT ''")
        // Zero is reserved for the prior release's persistent manual classifications.
        db.execSQL("ALTER TABLE notifications ADD COLUMN manualVersion INTEGER NOT NULL DEFAULT 0")
        db.execSQL("CREATE TABLE IF NOT EXISTS source_kind_rules (packageName TEXT NOT NULL, kind TEXT NOT NULL, hidden INTEGER NOT NULL, PRIMARY KEY(packageName, kind))")
    }
}
