# 项目结构与界面（HomelabApp）

改 UI、导航、首页、壁纸、设置页、Homarr、应用内浏览器前先读本文件。

## 基本标识

- 包名 / `namespace` / `applicationId`：`com.poi.homelab`；源码目录 `HomelabApp/app/src/main/java/com/poi/homelab`。
- 应用名：`poi's home`（`res/values/strings.xml`）。
- 图标与头像：源图 `static/pictures/2.jpeg`；启动图标在 `res/mipmap-*/ic_launcher*.png` + `mipmap-anydpi-v26/ic_launcher.xml`；界面头像 `res/drawable-nodpi/app_avatar.png`，用于主页标题左侧和启动动效。
- 默认 Home 地址：`DEFAULT_LAB_URL = ""`（`core/settings/SettingsStore.kt`），不写死真实实例地址；用户在设置页「连接」填写后存本机 DataStore。不要把真实 tailnet 域名、内网 IP、Token 提交进仓库或文档。

## 源码分包

根包只保留 `HomelabApplication.kt` / `MainActivity.kt`：

- `core/`：`AppContainer.kt`、`settings/SettingsStore.kt`、`util/`（格式化、相册/使用情况权限）、`ui/`（`theme/`、通用 `components/`、`InAppBrowserScreen.kt`）
- `feature/<功能>/`：`ai`、`bills`、`calendar`、`homarr`、`home`、`lab`、`photos`、`settings`、`notify`、`automation`；该功能的界面统一放 `ui/` 子包
- `navigation/`：`AppRoot.kt`（导航骨架）与 `MainViewModel.kt`（全局 `UiState`、跨功能协调）
- `preview/`：`ScreenPreviews.kt`

新增代码按「功能优先」放进对应 `feature/<功能>/`，不要恢复平铺的 `data/`、`ui/screens/`、`ui/components/`。

## 导航与全局外观

- 导航：不再用底部导航。左上角头像（总览页复用标题头像，其余页面顶部单独一行）打开左侧 `HomelabDrawer`，抽屉里有全部页面入口和「今日清单」；返回键在非总览页先回总览，只有在总览页连按两次才退出。
- 首页壁纸：资源 `res/drawable-nodpi/home_wallpaper.webp`（源 `static/pictures/4.jpeg`，文件实际是 WebP）。是否显示与参数存 `AppSettings.showWallpaper` / `wallpaperOpacity`（0.05..0.9，默认 0.22）/ `wallpaperDim`（0..0.8，默认 0.5）。壁纸是全局的：由 `AppRoot.WallpaperBackdrop` 铺在所有页面（含 AI 配置页）之后，`Scaffold` 用透明底色；不要再在单个页面里画壁纸。卡片与左侧导航抽屉统一用 `CardSurfaceAlpha`（0.78，`core/ui/theme/Color.kt`）透出背景。设置页「外观与首页」卡片提供开关与两个滑杆；`showLedgerOnHome` 控制首页是否展示账本区。
- 启动动效：`core/ui/components/LaunchSplash.kt`，由 `MainActivity` 包裹主界面。启动页支持全屏「轻触跳过」，3 秒后自动结束；遮罩淡出时用 `contentAlpha`（650ms）让主界面内容从透明缓缓浮现。

## 首页

`feature/home/ui/HomeScreen.kt` 依次是 header → 「今天」清单 → Homarr 看板（未配置时回退快捷入口）→ `LabGlanceCard`（本月支出/收入/结余 + 容器状态合并成一张深色卡，`UiState.healthError` 单独承载失败原因）→ 「最近流水」（受 `showLedgerOnHome` 控制）。

- Todo：列表存 `feature/home/TodoStore.kt`（DataStore `homelab_todo`，带 `initialized` 标记，未初始化时回填默认 3 项）；勾选状态仍由 `ChecklistStore` 按天保存。编辑用 `feature/home/ui/TodoEditorSheet.kt`（增删改 + 保存），勾选不会再改动全局背景色；首页卡片标题为「今天」。
- 快捷入口：`feature/home/ui/QuickEntryPager.kt`，`HorizontalPager` 左右滑动切换卡片，配 `graphicsLayer` 缩放/淡出与动态圆点；只在没配置 Homarr 时作首页兜底，不要再恢复已删除的静态 `StackDeck`。

## 设置页

分组顺序：`外观与首页`（主题 + 介绍模式同卡、全局壁纸、显示账本、首页天气城市）→ `连接`（Lab Tools / 账本 / Homarr / Cloudreve / Token / 设备名 + 保存、测试并排；已填好的连接项默认折叠成一行摘要，点「修改」再展开）→ `小砂糖`（AI 配置、可搜索目录）→ `权限`（自动化 / 使用情况 / 相册 / 通知与短信采集）→ `关于`。新增设置项按这个分组放，别再平铺新卡片。

介绍模式：`AppSettings.settingsDetailed`（默认 true）控制「详细 / 简略」；简略模式下 `SettingHint` 全部隐藏且不留空行，只保留标题、输入框与开关。

## Homarr 看板

`feature/homarr/HomarrRepository.kt` + `HomarrModels.kt` + `feature/homarr/ui/HomarrBoardGrid.kt`。设置页「连接」填 `homarrUrl` / `homarrApiKey` / `homarrBoard`（看板名留空时取 `isHome` 或列表第一个）。仓库不写死实例地址：未手动保存过时用 `local.properties` 的 `homarr.url` 构建期注入 `BuildConfig.DEFAULT_HOMARR_URL`，保存后以 DataStore 为准（`homarr_url_touched` 区分「没配过」与「主动清空」）。`/api/boards`、`/api/apps` 是 REST + `ApiKey` 头；看板内容没有 REST GET，只能读 tRPC `board.getBoardByName`（query 带 `batch=1` 信封）。网格只原生还原 app / clock / iframe，其他 widget 降级成「在 Homarr 中查看」，空白行折叠。

## 应用内浏览器

`core/ui/InAppBrowserScreen.kt`，由 `AppRoot` 的 `browserUrl` 状态以全屏 overlay 盖在 `Scaffold` 之上（含抽屉与内容），磁贴和 iframe 不再把用户丢给系统浏览器。iframe 磁贴在网格内用 `InlinePage` 内嵌预览：`isEnabled = false` 不抢外层滑动手势，`LocalInspectionMode` 下跳过 WebView，点底栏「打开」才进完整浏览器。WebView 需 `@SuppressLint("SetJavaScriptEnabled")`，`onRelease` 里 `stopLoading` + `loadUrl("about:blank")` + `destroy`。

## UI 预览

静态 UI 迭代看 `preview/ScreenPreviews.kt`（Split / Design），不需要设备。给可预览页面新增 SAF / 权限系统选择器时，Compose Preview 用 `isPreview=true` 跳过 `rememberLauncherForActivityResult` / `LifecycleEventEffect`，否则预览会崩。
