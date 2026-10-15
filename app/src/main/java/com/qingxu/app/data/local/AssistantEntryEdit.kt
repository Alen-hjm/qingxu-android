package com.qingxu.app.data.local

/** A local, versioned proposal. Persisted content and privacy policy never come from model output. */
data class AssistantEntryEdit(val before: NotificationEntry, val after: NotificationEntry)
