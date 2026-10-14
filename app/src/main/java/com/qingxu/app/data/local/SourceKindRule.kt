package com.qingxu.app.data.local

import androidx.room.Entity

/** Only an explicit “this kind in this app” action creates a persistent preference. */
@Entity(tableName = "source_kind_rules", primaryKeys = ["packageName", "kind"])
data class SourceKindRule(val packageName: String, val kind: String, val hidden: Boolean)
