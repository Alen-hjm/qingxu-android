# 轻序形象：小耳

当前使用用户提供的薄荷绿倾听角色、奶油底头像和深绿底头像，自 0.2 起已接入应用。角色为静态 PNG 配合 Compose 入场与轻晃动效；没有骨骼或逐帧动画。0.3 中用于品牌、空状态及操作引导，服务于自动整理与用户纠正。

下文保留早期未采用的创意提案和提示词，仅供历史参考。奶白信使、挎包等描述不代表当前素材，也不应用于覆盖现有角色。

## 当前使用原则

- 在引导中解释「为何这样整理」和「怎么改回来」，不替代具体操作标签。
- 不因用户未处理消息而催促或责备，不把通知清空变成打卡任务。
- 不以形象表情表示云端已连接、判断成功或系统通知已拦截；这些状态使用准确文字。
- 保留用户提供的原图；当前素材未做新的 AI 生图或改画。

## 早期提案（未采用）

## 参考与取舍

- Duolingo：https://design.duolingo.com/ — 借鉴小尺寸可识别的轮廓、少量基础形状、统一的表情语言。原创角色不沿用猫头鹰外形、绿色身体或 Duo 面部比例。
- Finch：https://finchcare.com/ — 借鉴温和的陪伴感和细小的日常动作。避免复制鸟类造型和养成游戏机制。
- 轻序原有界面：暖白 #F8F9F5，墨绿 #285848，琥珀 #B47142。形象服务于通知内容。

## 角色

暂名「小耳」，奶白色的抽象倾听小信使。短圆身体，一只耳朵竖起倾听，另一只向外弯曲；耳内为浅鼠尾草绿，短小墨绿手脚，墨绿斜挎信袋，信袋上只有一枚抽象信封线条。眼睛为深墨绿短椭圆，嘴是小弧线。独特识别点：不对称倾听耳形 + 斜挎信袋。

角色气质：成年用户也愿意使用的温和、可靠、安静。不要机器人天线、发光眼、荧光配色、夸张泪眼或推销姿势。

## 状态

1. 安静待命：微笑，双手自然放下，一只耳朵倾听。
2. 已经整理：手拿一张整洁的小信纸，轻微满足的表情。
3. 需要确认：轻微侧头，递出信纸，无催促或焦虑。

## 首张展示板

主形象占左侧，右侧三个状态缩略图与 App 首页小结区域示意。包含 32/48/96px 小尺寸检验。主标语：「轻序，让你听到真正想听的。」标注原创概念稿，所有通知内容为虚构。界面内角色不占据大面积，不做常驻悬浮宠物。

## 图片生成提示词（如选择 API）

Use case: stylized-concept
Asset type: original Android app mascot character design sheet
Primary request: Create an original quiet listening messenger named 小耳 for the notification organizer 轻序. Use the existing warm-white, deep-forest-green, pale-sage and restrained amber palette. A compact cream-colored creature with two distinctive asymmetrical listening ears, one upright and one gently folded outward, tiny dark-green oval eyes, subtle smile, short limbs, and a forest-green cross-body mail satchel bearing a simple envelope mark. Clear memorable silhouette, no fur detail, polished flat 2D illustration, subtly rounded forms, friendly but not infantile.
Composition: editorial landscape design board with one large full-body hero, three consistent small expressions (quietly listening, sorting complete holding a letter, gently asking for clarification), and a small Android inbox header usage vignette. Generous whitespace, clear Chinese typography.
Text: "轻序，让你听到真正想听的。" / "小耳" / "安静待命" / "已经整理" / "需要确认" / "原创形象概念"
Constraints: all states must preserve the same anatomy and proportions; prioritize readability at app-header size; reference the general simplicity of successful consumer-app mascots without reproducing any existing character. No owl, no bird, no Duo face, no brand logos from other products. No 3D plush rendering, no fluorescent gradients, no needy or guilt-inducing expressions, no fake live app screenshot.
