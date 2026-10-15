package com.qingxu.app.navigation

import androidx.compose.animation.*
import androidx.compose.animation.core.tween
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.qingxu.app.core.ui.BrandFace
import com.qingxu.app.feature.QingxuViewModel
import com.qingxu.app.feature.filtered.FilteredScreen
import com.qingxu.app.feature.inbox.InboxScreen
import com.qingxu.app.feature.assistant.AssistantScreen
import com.qingxu.app.feature.settings.SettingsScreen
import com.qingxu.app.feature.settings.SourceSettingsScreen
import com.qingxu.app.feature.settings.AiSettingsScreen
import com.qingxu.app.feature.settings.PrivacySettingsScreen
import com.qingxu.app.feature.settings.PriorityPreferencesDialog
import androidx.activity.compose.BackHandler
import com.qingxu.app.feature.onboarding.GuideDock
import com.qingxu.app.feature.onboarding.OnboardingExample

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun QingxuApp(viewModel: QingxuViewModel, onUnlock: () -> Unit = {}, onPermissionSettings: () -> Unit,
    onNotificationPermission: (Boolean, () -> Unit) -> Unit = { _, action -> action() },
    onAuthenticate: (() -> Unit) -> Unit = { onUnlock() }) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val onboarding by viewModel.onboarding.collectAsStateWithLifecycle()
    val ai by viewModel.aiSettings.collectAsStateWithLifecycle()
    val aiStatus by viewModel.aiStatus.collectAsStateWithLifecycle()
    val connectionTest by viewModel.connectionTest.collectAsStateWithLifecycle()
    val processingActivity by viewModel.processingActivity.collectAsStateWithLifecycle()
    val assistant by viewModel.assistant.state.collectAsStateWithLifecycle()
    val rules by viewModel.rules.collectAsStateWithLifecycle()
    val priorityRules by viewModel.prioritySenders.collectAsStateWithLifecycle()
    val surfaceSettings by viewModel.surfaceSettings.collectAsStateWithLifecycle()
    val privacy by viewModel.privacySettings.collectAsStateWithLifecycle()
    val unlocked by viewModel.contentUnlocked.collectAsStateWithLifecycle()
    val privacyStatus by viewModel.privacyStatus.collectAsStateWithLifecycle()
    val concealed = privacy.hideContent && !unlocked
    var page by rememberSaveable { mutableIntStateOf(0) }
    var managingPriority by rememberSaveable { mutableStateOf(false) }
    val guideStep = onboarding.step(state.selectedPackages.isNotEmpty(), state.permissionGranted, state.listenerConnected)
    val guiding = !onboarding.completed && !state.loading
    val targetPage = when (guideStep) { 0, 1 -> 5; else -> 0 }
    val targetTab = if (targetPage >= 2) 2 else targetPage
    val snackbar = remember { SnackbarHostState() }
    val labels = listOf("收件箱", "已收起", "设置")
    val icons = listOf(Icons.Outlined.Inbox, Icons.Outlined.FilterAlt, Icons.Outlined.Tune)
    LaunchedEffect(viewModel) { viewModel.messages.collect { snackbar.showSnackbar(it) } }
    LaunchedEffect(page, concealed) {
        if (page != 3 || concealed) viewModel.connectionDraftChanged()
    }
    BackHandler(page >= 3) { page = if (page == 6) 0 else 2 }
    val tutorial: (@Composable () -> Unit)? = if (guiding && guideStep >= 2) {
        { OnboardingExample(onboarding, viewModel::classifyExample, viewModel::starExample, viewModel::restoreExample, onDemo = viewModel::demoExample) }
    } else null
    Scaffold(
        topBar = {
            TopAppBar(title = { Text(when(page) { 0 -> "轻序"; 3 -> "高级设置"; 4 -> "隐私保护"; 5 -> "整理哪些应用"; 6 -> "消息助手"; else -> labels[page] }, style = MaterialTheme.typography.titleLarge) },
                navigationIcon = { if (page >= 3) IconButton(onClick = { page = if (page == 6) 0 else 2 }) { Icon(Icons.AutoMirrored.Outlined.ArrowBack,
                    contentDescription = if (page == 6) "返回收件箱" else "返回设置") }
                    else BrandFace(Modifier.padding(start = 20.dp, end = 10.dp).size(36.dp)) },
                actions = { if (privacy.hideContent) TextButton(onClick = { if (unlocked) viewModel.lockContent() else onUnlock() }) {
                    Text(if (unlocked) "遮住内容" else "解锁查看")
                } else Text("由你掌控", modifier = Modifier.padding(end = 20.dp), style = MaterialTheme.typography.labelMedium) },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.background))
        },
        bottomBar = {
            Surface(color = MaterialTheme.colorScheme.background, shadowElevation = 6.dp) {
                Column {
                    if (guiding && (page < 3 || page == 5)) GuideDock(guideStep, page, onNavigate = { page = it; viewModel.refresh() },
                        onFinish = { viewModel.completeOnboarding(); page = 0 })
                    NavigationBar(containerColor = MaterialTheme.colorScheme.background, tonalElevation = 0.dp) {
                        labels.forEachIndexed { index, label ->
                            NavigationBarItem(selected = (if (page == 6) 0 else if (page >= 2) 2 else page) == index, onClick = { page = index; if (index == 2) viewModel.refresh() },
                                icon = { Icon(icons[index], contentDescription = null,
                                    modifier = if (guiding && index == targetTab && page != targetPage && page != index)
                                        Modifier.border(2.dp, MaterialTheme.colorScheme.primary, RoundedCornerShape(10.dp)).padding(5.dp)
                                    else Modifier.padding(5.dp)) }, label = { Text(label) })
                        }
                    }
                }
            }
        }, snackbarHost = { SnackbarHost(snackbar) },
    ) { padding ->
        AnimatedContent(targetState = page, modifier = Modifier.padding(padding).fillMaxSize(),
            transitionSpec = { (fadeIn(tween(220)) + slideInHorizontally(tween(260)) { if (targetState > initialState) it / 18 else -it / 18 })
                .togetherWith(fadeOut(tween(120))) }, label = "page-transition") { displayedPage ->
            when (displayedPage) {
                0 -> InboxScreen(state, onSettings = { page = 5; viewModel.refresh() },
                    onClassify = viewModel::classify, onInterest = viewModel::toggleInterest,
                    onOpen = viewModel::openOriginal, tutorial = if (targetPage == 0) tutorial else null,
                    onHideSimilar = viewModel::hideSimilar, concealContent = concealed, onUnlock = onUnlock,
                    onReconnect = viewModel::reconnectNotifications, onHandled = viewModel::setHandled,
                    onPrioritizeSender = viewModel::prioritizeSender, onTestNotification = { onNotificationPermission(false, viewModel::testNotification) },
                    aiSettings = ai, privacySettings = privacy, processingActivity = processingActivity,
                    onAssistant = { page = 6 }, onAiSettings = { page = 3 })
                1 -> FilteredScreen(state.entries, viewModel::restore, viewModel::toggleInterest,
                    tutorial = if (targetPage == 1) tutorial else null, onHideSimilar = viewModel::hideSimilar,
                    concealContent = concealed, onUnlock = onUnlock, onOpen = viewModel::openOriginal,
                    onHandled = viewModel::setHandled, onPrioritizeSender = viewModel::prioritizeSender)
                2 -> SettingsScreen(state, onSources = { page = 5; viewModel.refresh() },
                    onPrivacySettings = { page = 4 }, onAdvancedSettings = { page = 3 }, hideContent = privacy.hideContent,
                    onRestartGuide = { viewModel.restartOnboarding(); viewModel.refresh(); page = 0 },
                    guideStep = if (guiding) guideStep else null, surfaceSettings = surfaceSettings,
                    onSurfaceChange = { summary, remove ->
                        if (concealed) onUnlock()
                        else if (summary && !surfaceSettings.summary) onNotificationPermission(true) { viewModel.saveSurface(summary, remove) }
                        else viewModel.saveSurface(summary, remove)
                    }, priorityCount = priorityRules.size, onManagePriority = { if (concealed) onUnlock() else managingPriority = true })
                3 -> if (concealed) PrivateSettingsGate(onUnlock) else AiSettingsScreen(ai, state.sourceApps, state.selectedPackages, aiStatus, rules,
                    viewModel::saveAi, viewModel::testAi, viewModel::clearAiKey, viewModel::reanalyze, viewModel::removeRule,
                    onPrivacySettings = { page = 4 }, testStatus = connectionTest, onConnectionChanged = viewModel::connectionDraftChanged,
                    localOnlyPackages = privacy.localOnlyPackages)
                4 -> if (concealed) PrivateSettingsGate(onUnlock) else PrivacySettingsScreen(privacy, state.sourceApps,
                    state.selectedPackages, privacyStatus, { hide, locked, local, days, screenshots ->
                        val revision = privacy.revision
                        val save = {
                            if (viewModel.privacySettings.value.revision == revision)
                                viewModel.savePrivacy(hide, locked, local, days, screenshots)
                        }
                        if (hide && !privacy.hideContent) onAuthenticate(save) else save()
                    }, viewModel::deleteHistory, cloudEnabled = ai.enabled)
                5 -> SourceSettingsScreen(state, onPermissionSettings, { packageName, selected ->
                    if (selected && concealed) onUnlock() else viewModel.selectSource(packageName, selected)
                }, guideStep = if (guiding) guideStep else null, requiresUnlock = concealed, onUnlock = onUnlock,
                    onReconnect = viewModel::reconnectNotifications, privacySettings = privacy, aiSettings = ai,
                    onTestNotification = { onNotificationPermission(false, viewModel::testNotification) })
                6 -> AssistantScreen(assistant.copy(concealed = concealed), viewModel.assistant::updateDraft,
                    viewModel.assistant::send, viewModel.assistant::apply, viewModel.assistant::dismiss,
                    viewModel.assistant::undo, onSettings = { page = 3 }, onUnlock = onUnlock,
                    onClear = viewModel.assistant::clear)
            }
        }
    }
    if (managingPriority && !concealed) PriorityPreferencesDialog(priorityRules, state.entries,
        onRemove = viewModel::removePriority, onDismiss = { managingPriority = false })
}

@Composable
private fun PrivateSettingsGate(onUnlock: () -> Unit) {
    Column(Modifier.fillMaxSize().padding(24.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        Text("先验证，再查看与修改", style = MaterialTheme.typography.headlineSmall)
        Text("使用系统生物识别或锁屏密码验证，查看私密设置。")
        Button(onClick = onUnlock) { Text("验证身份") }
    }
}
