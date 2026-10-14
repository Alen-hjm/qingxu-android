# 许可、素材与致谢

项目自行编写的源代码与文档采用根目录 [MIT License](LICENSE)。第三方代码、依赖、商标和品牌图片不因本仓库的 MIT 声明而改变原有权利。

## Gradle Wrapper

`gradlew`、`gradlew.bat` 与 `gradle/wrapper/gradle-wrapper.jar` 来自 Gradle 8.9，采用 Apache License 2.0。启动脚本保留原始版权头。附带 [Gradle 发行版 LICENSE](licenses/GRADLE-LICENSE.txt) 和 [NOTICE](licenses/GRADLE-NOTICE.txt)，包括发行版内组件的许可说明。Gradle 本体与 Android SDK 不随本仓库分发。

应用通过 Gradle 获取 Kotlin、AndroidX、Jetpack Compose、Material、Room、WorkManager 等依赖；各依赖仍遵循其上游许可证，具体版本见 `app/build.gradle.kts`。Material 图标来自声明的 Compose 图标依赖。设备上其他应用的图标在运行时由 Android 提供，未打包微信等第三方品牌图标。

## 品牌图片

`app/src/main/res/drawable-nodpi/qingxu_face.png`、`qingxu_mascot.png`、`app/src/main/res/drawable-night-nodpi/qingxu_face.png` 及文档中的对应图片，是项目发起人提供的轻序品牌素材，当前用于应用和产品展示。它们不包含在源码 MIT 授权范围内；独立再分发、商业品牌使用或衍生品牌授权请联系维护者。源码复用者可以替换同名资源构建自己的版本。

`docs/images/` 中的界面截图由项目 Compose 代码使用虚构通知数据渲染，不是真机截图；其中包含的品牌素材仍遵循上述说明。轻序名称及标识不表示对第三方衍生产品的背书。

## 灵感与设计参考

- [qq-live-digest](https://github.com/wc985732-lang/qq-live-digest)：通知过滤与摘要的产品讨论起点。此仓库是独立的 Android 实现，不是该项目的 Git fork。
- [Things](https://culturedcode.com/things/) 与 [Material 3](https://m3.material.io/)：信息层级与原生交互参考。
- [Android Compose Samples](https://github.com/android/compose-samples)、[compose-intro-showcase](https://github.com/canopas/compose-intro-showcase) 和 [Reveal](https://github.com/svenjacobs/reveal)：消息界面、动效与贴近控件的引导参考。
- [CC Switch](https://github.com/farion1231/cc-switch)：0.4.3 的协议选择、基础 URL／完整 URL 与 Claude 鉴权配置参考。其项目采用 [MIT 许可证](https://github.com/farion1231/cc-switch/blob/main/LICENSE)；轻序独立实现连接与解析逻辑，未复制其实现代码，也不读取本机 CC Switch 的配置或凭据。

参考链接不表示品牌合作或授权背书。当前项目的界面实现没有复制上述参考项目的源码。轻序的连接测试会提交固定虚构通知并校验分类响应，网址可达本身不算测试通过。
