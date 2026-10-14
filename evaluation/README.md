# 中文通知判断回归评测

`notifications.zh-CN.json` 是 40 条完全虚构、固定时间的中文通知，覆盖单纯营销、虚假紧急话术、交易混合促销、聊天引用、过期/未来时间、验证码、医疗、安全、指令注入、信息缺失和用户关注。没有读取或复制真实通知。

`expected` 是允许的分桶集合。含安全或上下文歧义的案例重点检查“不能隐藏”，并不把某一个可见分桶假装成唯一正确答案。这是工程回归集，不是独立盲测，不得将匹配率当作真实中文准确率。

## 本地基线

在项目目录运行 JUnit（不访问云端）：

```powershell
./gradlew.bat :app:testDebugUnitTest --tests com.qingxu.app.core.ai.NotificationEvaluationTest
```

会生成 `evaluation/local-report.json`，记录原始本地规则与加保护策略后的结果、误隐藏数量、每条对应结果。兴趣覆盖在测试里显式模拟；处理器的前置隐私门禁、来源授权与并发撤回行为由处理器测试覆盖。报告中的云端对比固定标记 `NOT_RUN`。报告由测试运行产生；文件不存在表示尚未运行，不能据此宣称通过。

## 可选云端对比：本次未执行

没有调用 Jev 或 Chat Completions 真实接口，没有购买额度，也没有使用现有用户密钥。可以仅检查合成集：

```powershell
python evaluation/compare_cloud.py --check-fixtures
```

如自行决定付费试验，可把密钥放入环境变量，脚本不接受真实消息路径，只读取上述固定集。必须显式传入 `--allow-cloud`，默认最多请求 10 次，无自动重试。标记为敏感的虚构案例也跳过，不上传。请求使用 HTTPS，不跟随重定向，读取响应上限 64 KiB；脚本的 40 秒超时是 Python socket 超时，不替代 Android 总请求时限。

- Jev：`QINGXU_JEV_KEY`；可选 `QINGXU_JEV_MODEL`（默认 `jev-latest`），官方接口固定。
- Chat Completions：`QINGXU_CHAT_ENDPOINT`、`QINGXU_CHAT_MODEL`、`QINGXU_CHAT_KEY`。

```powershell
python evaluation/compare_cloud.py --provider jev --allow-cloud --limit 10
python evaluation/compare_cloud.py --provider chat --allow-cloud --limit 10
```

结果写到 `cloud-report-jev.json` 或 `cloud-report-chat.json`，不记录密钥、原始响应或模型自由文本。此脚本比较模型原始结构化判断，提示词为研究版；它没有重放 Android 完整隐私门禁、校验、关注覆盖与可见性保护，因此不能当作 App 端到端验证。真实决策与回归测试以 Kotlin 实现为准。小样本、提示词版本、语言与服务版本都会影响结论；没有实跑就不能宣称 Jev 比现有方法更准确或更便宜。
