package com.qingxu.app.core.ui

import android.content.pm.PackageManager
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Apps
import androidx.compose.material.icons.outlined.CalendarMonth
import androidx.compose.material.icons.outlined.ChatBubbleOutline
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import androidx.core.graphics.drawable.toBitmap
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Keep the installed app's artwork, including adaptive icons, without tinting or cropping it. */
@Composable
fun AppAvatar(packageName: String, label: String, modifier: Modifier = Modifier) {
    val context = LocalContext.current.applicationContext
    val configuration = LocalConfiguration.current
    val pixels = with(LocalDensity.current) { 40.dp.roundToPx() }
    var icon by remember(packageName, pixels, configuration) { mutableStateOf<ImageBitmap?>(null) }
    LaunchedEffect(packageName, pixels, configuration) {
        if (packageName.isNotBlank()) {
            icon = withContext(Dispatchers.IO) {
                try {
                    context.packageManager.getApplicationIcon(packageName)
                        .toBitmap(pixels, pixels).asImageBitmap()
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (_: PackageManager.NameNotFoundException) {
                    null
                } catch (_: RuntimeException) {
                    // Uninstalled, inaccessible or malformed artwork must not break the inbox.
                    null
                }
            }
        }
    }
    val loaded = icon
    if (loaded != null) {
        Image(loaded, contentDescription = null, modifier = modifier.size(40.dp))
    } else {
        val fallback = when {
            label.contains("日历") || label.contains("calendar", ignoreCase = true) -> Icons.Outlined.CalendarMonth
            label.contains("微信") || label.contains("聊天") || label.contains("chat", ignoreCase = true) -> Icons.Outlined.ChatBubbleOutline
            else -> Icons.Outlined.Apps
        }
        Box(modifier.size(40.dp).background(MaterialTheme.colorScheme.primaryContainer, RoundedCornerShape(12.dp)),
            contentAlignment = Alignment.Center) {
            Icon(fallback, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(23.dp))
        }
    }
}
