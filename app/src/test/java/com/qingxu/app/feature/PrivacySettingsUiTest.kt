package com.qingxu.app.feature

import android.app.Application
import android.graphics.Bitmap
import android.graphics.Canvas
import androidx.activity.ComponentActivity
import androidx.compose.material3.Surface
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.unit.Density
import com.qingxu.app.core.privacy.PrivacySettings
import com.qingxu.app.core.ui.QingxuTheme
import com.qingxu.app.feature.settings.PrivacySettingsScreen
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], application = Application::class, qualifiers = "w393dp-h851dp-xxhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class PrivacySettingsUiTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    private val apps = listOf(SourceApp("com.tencent.mm", "微信"), SourceApp("shop.example", "购物应用"))
    private val selected = apps.map { it.packageName }.toSet()

    @Test fun privacyDraftRequiresSaveAndPreservesUnlistedLocalOnlySources() {
        var saved: SavedPrivacy? = null
        val privateSources = setOf("com.tencent.mm", "com.android.mms")
        compose.setContent { QingxuTheme { Surface {
            PrivacySettingsScreen(PrivacySettings(localOnlyPackages = privateSources), apps, selected, "", onSave = { hide, locked, local, days ->
                saved = SavedPrivacy(hide, locked, local, days)
            }, onDeleteAll = {})
        } } }
        compose.onNodeWithContentDescription("保护通知正文").assertIsOn()
        compose.onNodeWithContentDescription("锁屏时阻止云端分析").assertDoesNotExist()
        compose.onNodeWithText("微信").assertDoesNotExist()
        compose.onNodeWithText("购物应用").assertDoesNotExist()
        compose.onNodeWithText("云端未启用 · 查看上传限制").assertExists()
        compose.onNodeWithText("保存设置").assertIsDisplayed().assertIsNotEnabled()
        capture("privacy-settings")
        scrollToText("云端隐私")
        compose.onNodeWithText("云端隐私").performClick()
        scrollToText("锁屏时不上传")
        compose.onNodeWithContentDescription("锁屏时阻止云端分析").assertIsOn()
        compose.onNodeWithText("保存设置").assertIsNotEnabled()
        scrollToText("微信")
        compose.onNodeWithContentDescription("微信仅本机处理").assertIsOn()
        compose.onNodeWithContentDescription("购物应用仅本机处理").assertIsOff().performClick()
        scrollToText("历史保留")
        compose.onNodeWithText("历史保留").performClick()
        compose.onNode(hasText("不自动清理") and isSelected()).assertExists()
        compose.onNodeWithText("30 天").performClick()
        compose.runOnIdle { assertNull(saved) }
        compose.onNodeWithText("保存设置").assertIsDisplayed().performClick()
        compose.runOnIdle {
            assertTrue(saved!!.hide)
            assertTrue(saved!!.locked)
            assertEquals(privateSources + "shop.example", saved!!.local)
            assertEquals(30, saved!!.days)
        }
    }

    @Test fun deleteHistoryRequiresExplicitSecondActionAndCanBeCancelled() {
        var deleteCalls = 0
        compose.setContent { QingxuTheme { Surface {
            PrivacySettingsScreen(PrivacySettings(), apps, selected, "", { _, _, _, _ -> }, { deleteCalls++ })
        } } }
        scrollToText("删除全部本机历史")
        compose.onNodeWithText("删除全部本机历史").performClick()
        compose.runOnIdle { assertEquals(0, deleteCalls) }
        compose.onNodeWithText("删除轻序内的全部历史？").assertExists()
        compose.onNodeWithText("保留历史").performClick()
        compose.runOnIdle { assertEquals(0, deleteCalls) }
        compose.onNodeWithText("删除全部本机历史").performClick()
        compose.onNodeWithText("确认删除历史").performClick()
        compose.runOnIdle { assertEquals(1, deleteCalls) }
    }

    @Test fun largeTextPrivacyOptionsAndSaveRemainReachable() {
        var saved: SavedPrivacy? = null
        compose.setContent {
            val density = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(density.density, 1.8f)) {
                QingxuTheme { Surface {
                    PrivacySettingsScreen(PrivacySettings(localOnlyPackages = setOf("com.tencent.mm")), apps, selected, "",
                        { hide, locked, local, days -> saved = SavedPrivacy(hide, locked, local, days) }, {}, cloudEnabled = true)
                } }
            }
        }
        scrollToText("云端隐私")
        compose.onNodeWithText("云端已启用 · 查看上传限制").assertExists()
        compose.onNodeWithText("云端隐私").performClick()
        scrollToText("锁屏时不上传")
        compose.onNodeWithContentDescription("锁屏时阻止云端分析").performScrollTo().performClick()
        scrollToText("微信")
        compose.onNodeWithContentDescription("微信仅本机处理").assertIsDisplayed()
        scrollToText("历史保留")
        compose.onNodeWithText("历史保留").performClick()
        compose.onNodeWithText("7 天").performClick()
        scrollToText("了解隐私保护")
        compose.onNodeWithTag("privacy-details").assertDoesNotExist()
        compose.onNodeWithText("了解隐私保护").performClick()
        compose.onNodeWithTag("privacy-details").assertExists()
        compose.onNodeWithText("了解隐私保护").performClick()
        compose.onNodeWithTag("privacy-details").assertDoesNotExist()
        compose.onNodeWithText("保存设置").assertIsDisplayed().performClick()
        compose.runOnIdle {
            assertFalse(saved!!.locked)
            assertEquals(7, saved!!.days)
            assertTrue("com.tencent.mm" in saved!!.local)
        }
    }

    private fun scrollToText(text: String) {
        compose.onNode(hasScrollToIndexAction()).performScrollToNode(hasText(text))
    }

    private fun capture(name: String) {
        compose.waitForIdle()
        compose.runOnIdle {
            val view = compose.activity.window.decorView
            val bitmap = Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888)
            view.draw(Canvas(bitmap))
            val output = File("build/visual-checks/$name.png")
            output.parentFile!!.mkdirs()
            output.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        }
    }

    private data class SavedPrivacy(val hide: Boolean, val locked: Boolean, val local: Set<String>, val days: Int)
}
