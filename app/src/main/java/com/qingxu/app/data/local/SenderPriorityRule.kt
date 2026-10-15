package com.qingxu.app.data.local

import androidx.room.Entity

/** Explicit local preference for one application's visible conversation title, never a verified identity. */
@Entity(tableName = "sender_priority_rules", primaryKeys = ["packageName", "scopeHash"])
data class SenderPriorityRule(val packageName: String, val scopeHash: String)
