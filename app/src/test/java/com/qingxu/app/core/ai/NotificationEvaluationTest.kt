package com.qingxu.app.core.ai

import android.app.Application
import com.qingxu.app.core.decision.CloudDecisionPolicy
import com.qingxu.app.core.decision.Decision
import com.qingxu.app.core.decision.DecisionBucket
import com.qingxu.app.core.decision.LocalClassifier
import com.qingxu.app.data.local.NotificationEntry
import java.io.File
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], application = Application::class)
class NotificationEvaluationTest {
    @Test fun syntheticChineseNotificationsProduceOfflineBaselineReport() {
        val file = listOf(File("../evaluation/notifications.zh-CN.json"), File("evaluation/notifications.zh-CN.json"))
            .first { it.isFile }
        val fixtures = JSONObject(file.readText(Charsets.UTF_8))
        assertTrue(fixtures.getBoolean("synthetic"))
        val now = fixtures.getLong("now")
        val cases = fixtures.getJSONArray("cases")
        assertTrue(cases.length() >= 30)
        val rows = JSONArray()
        var matches = 0
        var falseHidden = 0
        var rawFalseHidden = 0
        val ids = mutableSetOf<String>()
        for (index in 0 until cases.length()) {
            val item = cases.getJSONObject(index)
            val id = item.getString("id")
            assertTrue(ids.add(id))
            val entry = NotificationEntry(id, item.getString("package"), item.getString("app"),
                item.getString("title"), item.getString("body"), item.optString("channel").ifBlank { null },
                now - item.optLong("ageMinutes", 0) * 60_000, now, "synthetic-$id",
                interested = item.optBoolean("interested", false))
            val expected = item.getJSONArray("expected").let { array -> (0 until array.length()).map { array.getString(it) }.toSet() }
            val raw = LocalClassifier.classify(entry, now)
                ?: LocalClassifier.fallback(entry, now)
            val protected = CloudDecisionPolicy.protect(entry, raw, now)
            // User interest is a processor override. This test records it explicitly without networking.
            val result = if (protected.bucket == DecisionBucket.HIDDEN &&
                (entry.interested || item.optString("interests").isNotBlank())) protected.copy(bucket = DecisionBucket.LATER)
                else protected
            val match = result.bucket.name in expected
            if (match) matches++
            if (raw.bucket == DecisionBucket.HIDDEN && "HIDDEN" !in expected) rawFalseHidden++
            if (result.bucket == DecisionBucket.HIDDEN && "HIDDEN" !in expected) falseHidden++
            rows.put(JSONObject().put("id", id).put("expected", item.getJSONArray("expected"))
                .put("rawLocal", raw.bucket.name).put("protectedLocal", result.bucket.name).put("match", match))
        }
        val report = JSONObject().put("synthetic", true).put("networkRequests", 0)
            .put("fixtureCount", cases.length()).put("matchingCases", matches)
            .put("rawFalseHidden", rawFalseHidden).put("protectedFalseHidden", falseHidden)
            .put("cloudComparison", "NOT_RUN: no API request or real-message evaluation")
            .put("limitations", "手写合成集、宽松可接受分桶；不代表真实准确率。兴趣覆盖在此显式模拟，隐私前置门禁由处理器测试覆盖。")
            .put("cases", rows)
        File(file.parentFile, "local-report.json").writeText(report.toString(2) + "\n", Charsets.UTF_8)
        assertEquals("Protected policy must never hide a visible fixture", 0, falseHidden)
    }
}
