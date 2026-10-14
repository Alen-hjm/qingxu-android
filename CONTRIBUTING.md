# 参与轻序

轻序希望减轻用户的注意力负担，让重要消息更容易被看到。欢迎提交问题、界面建议与代码改动；影响产品方向或数据处理方式的改动，建议先用 Issue 说明问题和预期行为。

## 改动原则

- 分类应保守、可解释、可恢复。拿不准时保持消息可见，人工纠正优先；不要为了减少通知数量而扩大自动收起范围。
- 云端处理必须遵守来源授权、仅本机限制和锁屏设置。不要默认开启上传或把云端服务描述为本机 AI。
- 用虚构通知复现问题。Issue、PR、截图、测试和日志中都不要提交真实通知正文、联系人、API 密钥或其他私人信息。安全问题请看 [SECURITY.md](SECURITY.md)。

## 本地构建与验证

准备 JDK 17、Android SDK Platform 35 和 Build Tools 34.0.0。项目使用 Gradle Wrapper 8.9；在 Android Studio 中配置 SDK，或配置 `JAVA_HOME`、`ANDROID_HOME` 后执行：

```powershell
.\gradlew.bat :app:assembleDebug :app:testDebugUnitTest :app:lintDebug
```

macOS / Linux 使用 `./gradlew` 执行相同任务。APK 位于 `app/build/outputs/apk/debug/app-debug.apk`。仓库不提供本地 `.tools` 工具链；`scripts/build-local.ps1` 仅供已配置该目录的开发环境使用。

先运行与改动相关的测试，提交前完成上述构建、单元测试和 Lint。测试位于 `app/src/test/java/com/qingxu/app`：分类与响应校验见 `core/decision`、`core/ai`，上传授权与旧请求竞态见 `core/processing`、`core/privacy`，数据迁移与加密见 `data`，界面交互见 `feature`。修改数据库结构时同步检查迁移和 Room schema。

UI 改动需检查小屏、1.8 倍字体、深色模式与隐私遮蔽状态，确认主要操作可见、控件可点击、正文不泄露。截图使用虚构内容，并说明是真机、模拟器还是测试渲染。测试渲染不能替代通知采集、系统解锁与后台行为的真机验证。

## 提交 PR

保持改动聚焦，不提交构建产物、本地 SDK、密钥或个人配置。PR 描述写清：

1. 用户遇到什么问题，如何用虚构数据复现。
2. 改动后的行为，以及涉及的隐私或兼容性影响。
3. 实际完成的验证；未验证的设备或云端行为也请明确说明。
