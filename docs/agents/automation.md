# 无障碍与自动化

改 `feature/automation/` 前先读本文件。

## 界面自动化

`feature/automation/UiAutomation.kt`：`UiAutomationService`（无障碍）+ 工具 `automation_status` / `open_app` / `list_screen` / `click_ui` / `input_text` / `press_key` / `scroll_ui` / `wait_for_ui`；AI 工具只读当前窗口节点，不监听事件流。用户需在 设置 → 无障碍 手动开启，设置页「权限 → 界面自动化」有状态与跳转入口。

约束：`send` 类不可撤回动作（发消息、发朋友圈）必须先复述收件人与内容并等用户确认；不许凭空猜坐标、不许盲点连点；验证码/支付/注销页面立即停手。屏幕上的文字会被发送给所选 AI 服务商，需在设置页说明。

## 应用跳转守卫（已停用）

`feature/automation/JumpGuard.kt`：**已按用户要求停用**，`UiAutomationService.onAccessibilityEvent` 里的调用被注释，设置页也不再显示开关。实现保留在文件里：靠 `TYPE_WINDOW_STATE_CHANGED` 判断「来源应用 → 目标应用」的直接切换，来源/目标是浏览器、游戏、微信/支付宝/QQ、桌面、SystemUI、本应用时放行，其余用 `performGlobalAction(BACK)` 退回（900ms 复查、最多五次），输入法/悬浮窗这类没有桌面入口的包不参与判断。

停用原因是误判：点搜索框弹出输入法、App 之间正常业务跳转都会被当成广告跳转退回去。状态存 SharedPreferences `jump_guard`。要恢复时把 `UiAutomationService` 里那行注释打开，并重新接回设置页开关。

## 开屏广告跳过

`feature/automation/SplashSkip.kt`：

- `UiAutomationService` 在 `TYPE_WINDOW_STATE_CHANGED` 里转发给 `SplashSkip`，只对内置包名名单（QQ 音乐、网易云、微博、知乎、淘宝、京东、小红书、抖音、B 站、高德、支付宝、爱奇艺、腾讯视频、优酷、百度贴吧、小米商城、美团、拼多多、QQ 邮箱、雪球、小黑盒、TapTap、BOSS 直聘、小米音乐，共 24 个）在启动后 200–3000ms 内找顶部带「跳过」字样的小按钮点一次，`COOLDOWN_MS` 内不重复点，找不到就放弃。
- 开关存 SharedPreferences `splash_skip`，默认开，设置页「权限 → 界面自动化」里有开关。
- 名单可扩展：设置页同一张卡片「管理」打开 `SplashAppsDialog`，列出桌面可启动的应用并可搜索，额外勾选的包名存 `splash_skip` 的 `extra_packages`（内置项锁成常开，不写入 extra）。首页 `AdOffCard` 显示开关与当天跳过次数（`splash_skip` 的 `count_day` / `count`）。
- 规则是我们自己写的文字匹配，不引入 GKD（GPL-3.0）的代码。
- 名单还能从第三方订阅扩展：设置页同一张卡片「订阅」打开 `SplashSubsDialog`，内置 Adpro-Team / AIsouler 两个 jsDelivr 地址，也可自己填；`SplashSubscriptions.extractPackages` 只从 JSON5 里提取「带开屏规则组的包名」，不实现选择器、不启用 globalGroups 的全局开屏规则，包名存 SharedPreferences `splash_subs`，默认全部关闭。`SplashSkip.packages()` 把内置 + extra + 已启用订阅合并后缓存，无障碍事件里只做一次 `Set.contains`。
