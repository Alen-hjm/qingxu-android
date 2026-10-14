package com.qingxu.app.data.local

import androidx.room.Entity
import androidx.room.ColumnInfo
import androidx.room.Index
import androidx.room.PrimaryKey
import androidx.room.TypeConverter

enum class UserLabel(val title: String) {
    UNSORTED("待整理"),
    IMPORTANT_URGENT("重要且紧急"),
    URGENT("紧急但不重要"),
    IMPORTANT("重要但不紧急"),
    LOW_PRIORITY("不紧急不重要"),
    FILTERED("已过滤"),
}

@Entity(tableName = "notifications", indices = [Index("packageName"), Index("postedAt")])
data class NotificationEntry(
    @PrimaryKey val notificationKey: String,
    val packageName: String,
    val appLabel: String,
    val title: String,
    val body: String,
    val channelId: String?,
    val postedAt: Long,
    val updatedAt: Long,
    val contentHash: String,
    val contentVersion: Int = 1,
    val userLabel: UserLabel = UserLabel.UNSORTED,
    val previousLabel: UserLabel = UserLabel.UNSORTED,
    val labelSource: String = "unclassified",
    val interested: Boolean = false,
    val removedAt: Long? = null,
    @ColumnInfo(defaultValue = "'REVIEW'") val machineBucket: String = "REVIEW",
    @ColumnInfo(defaultValue = "'unknown'") val machineKind: String = "unknown",
    @ColumnInfo(defaultValue = "'等待整理'") val machineReason: String = "等待整理",
    @ColumnInfo(defaultValue = "0.0") val machineConfidence: Double = 0.0,
    @ColumnInfo(defaultValue = "'pending'") val analysisStatus: String = "pending",
    @ColumnInfo(defaultValue = "''") val analysisSource: String = "",
    @ColumnInfo(defaultValue = "0") val manualVersion: Int = 0,
    @ColumnInfo(defaultValue = "0") val storageEncryptionVersion: Int = 0,
)

data class CapturedNotification(
    val notificationKey: String,
    val packageName: String,
    val appLabel: String,
    val title: String,
    val body: String,
    val channelId: String?,
    val postedAt: Long,
    val capturedAt: Long,
    val contentHash: String,
)

/** A correction is for the displayed text; upgraded legacy corrections retain their old scope. */
fun mergeNotification(previous: NotificationEntry?, incoming: CapturedNotification): NotificationEntry {
    val base = previous ?: NotificationEntry(
        notificationKey = incoming.notificationKey,
        packageName = incoming.packageName,
        appLabel = incoming.appLabel,
        title = incoming.title,
        body = incoming.body,
        channelId = incoming.channelId,
        postedAt = incoming.postedAt,
        updatedAt = incoming.capturedAt,
        contentHash = incoming.contentHash,
    )
    val changed = previous != null && previous.contentHash != incoming.contentHash
    val expireCorrection = changed && base.labelSource == "manual" && base.manualVersion > 0
    return base.copy(
        appLabel = incoming.appLabel,
        title = incoming.title,
        body = incoming.body,
        channelId = incoming.channelId,
        postedAt = incoming.postedAt,
        updatedAt = incoming.capturedAt,
        contentHash = incoming.contentHash,
        contentVersion = base.contentVersion + if (changed) 1 else 0,
        removedAt = null,
        userLabel = if (expireCorrection) UserLabel.UNSORTED else base.userLabel,
        previousLabel = if (expireCorrection) UserLabel.UNSORTED else base.previousLabel,
        labelSource = if (expireCorrection) "unclassified" else base.labelSource,
        manualVersion = if (expireCorrection) 0 else base.manualVersion,
        machineBucket = if (changed) "REVIEW" else base.machineBucket,
        machineKind = if (changed) "unknown" else base.machineKind,
        machineReason = if (changed) "内容已更新，等待重新整理" else base.machineReason,
        machineConfidence = if (changed) 0.0 else base.machineConfidence,
        analysisStatus = if (changed) "pending" else base.analysisStatus,
        analysisSource = if (changed) "" else base.analysisSource,
    )
}

class LabelConverters {
    @TypeConverter fun encode(value: UserLabel): String = value.name
    @TypeConverter fun decode(value: String): UserLabel =
        UserLabel.entries.firstOrNull { it.name == value } ?: UserLabel.UNSORTED
}
