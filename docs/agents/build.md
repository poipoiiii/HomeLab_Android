# 构建、安装、IDE 与排错

需要编译、安装、调 Live Edit 或排查构建报错时读本文件。

## 命令行构建

统一使用项目内工具链，不要依赖系统 JDK / Gradle：

```powershell
$root = 'D:\taoxi_download_for_use\no_use_do\android'
$env:JAVA_HOME = Join-Path $root '.cache\toolchain\jdk\jdk-21.0.13.0.2+1'
$env:ANDROID_HOME = Join-Path $root '.cache\toolchain\android-sdk'
$env:ANDROID_SDK_ROOT = $env:ANDROID_HOME
$env:ANDROID_USER_HOME = Join-Path $root '.cache\android-user'
$env:GRADLE_USER_HOME = Join-Path $root '.cache\toolchain\gradle-home'
$env:TEMP = Join-Path $root '.cache\tmp'
$env:TMP = $env:TEMP
& (Join-Path $root '.cache\toolchain\gradle-8.13\bin\gradle.bat') :app:compileDebugKotlin --offline
```

- 当前工具链：Gradle 8.13、AGP 8.13.0、Kotlin 2.3.21、KSP 2.3.12、compileSdk 37（`buildToolsVersion = "37.0.0"`）。Gradle wrapper 已指向 8.13；`gradlew.bat` 与命令行使用同一版本。
- ADK 的 Android AAR 要求 compileSdk ≥ 37，必须安装 SDK 包 `platforms;android-37.0` 与 `build-tools;37.0.0`。`build.gradle.kts` 使用新的 `compileSdk { version = release(37) }` DSL；不要再写旧的 `compileSdk = 37`，旧 DSL 会去找不存在的 `platforms/android-37`。
- ADK 依赖会带入 Google Auth，`packaging.resources.excludes` 已排除 `META-INF/INDEX.LIST` 和 `META-INF/DEPENDENCIES`；删除这两个排除会导致 `mergeDebugJavaResource` 失败。
- 运行 `sdkmanager` / `adb` 前也要设置 `ANDROID_USER_HOME` 到项目内目录，否则它们会往 `C:\Users\<用户>\.android\cache`、`metrics` 写文件。

## 安装与 IDE

- 安装到手机走 `apply-to-phone.ps1`（构建 + `installDebug` + 启动）；改包名后必须同步修改脚本里的 `am force-stop` / `am start` 包名。
- `powershell -ExecutionPolicy Bypass -File .\apply-to-phone.ps1` 会增量构建、安装并重启；它不是热更新。UI 小改动优先用 Android Studio Live Edit / Compose Preview。
- 不要在 Android Studio 运行时删除 `HomelabApp/.gradle`、`app/build` 或 `.cache/android-studio/system/projects`：会中断同步 / 索引。需要清理时先关闭 IDE，清理后重启让 IDE 重新同步（首次同步约 2–3 分钟）。
- 命令行 `installDebug` / adb 安装不是热更新，每次都会重新打包 APK（增量构建）。需要热更新体验时用 Android Studio 的 Live Edit / Apply Changes。
- Live Edit：`File > Settings > Editor > Live Edit`，勾选 Live Edit，模式选 Automatic；实际存储在 `.cache/android-studio/config/options/other.xml` 的 `<component name="LiveEditConfiguration">`，`leTriggerMode=AUTOMATIC`，`mode` 默认 `LIVE_EDIT`。
- Live Edit 前提：AGP ≥ 8.1、Compose Runtime ≥ 1.3、设备 API ≥ 30、debug 构建；必须从 Android Studio 点 Run 启动（经脚本 / adb 安装不会加载 Live Edit 代理）；仅支持 Composable UI 改动，方法签名、新增类等结构变更需重新 Run。
- 实机验证截图只落电脑端：`adb exec-out screencap -p > .cache\tmp\x.png`（PowerShell 注意用 `[IO.File]::WriteAllBytes` 或 `cmd /c` 包装以免二进制被改写），不要 `adb shell screencap -p /sdcard/x.png`，也不要在 C 盘留文件。

## 实测排错

- **Run ≠ Build**：只点锤子 Build 只会执行 `:app:assembleDebug`，不会部署；必须点绿色 Run，并在日志里确认出现 `Launching on device` / `Activating Activity`。只有 `assembleDebug` 结束、手机仍是旧 UI，就是没有部署。
- **旧包共存**：改 `applicationId` 后旧包不会自动消失。本机历史包有 `com.rong.billcapture`（记账采集）和旧 `com.rong.homelab`；看到旧 UI 时先确认当前前台包名，再按需 `adb uninstall <旧包>`。`apply-to-phone.ps1` 里的 `am force-stop` / `am start` 必须指向当前包 `com.poi.homelab`。
- **installDebug 的 adb 版本检测失败**：报 `Unable to detect adb version, exit value: 0x1000000a` 时，通常是 Gradle daemon 沿用了旧的进程环境（没有 `ANDROID_USER_HOME`）。先 `gradlew --stop`，再重新运行 `apply-to-phone.ps1`；脚本现在用 `--no-daemon`、显式 `adb start-server`，不会再复用环境不一致的 daemon。
- **设备状态**：`adb devices` 必须是 `device`；出现 `offline` / `unauthorized` 时重插 USB 并在手机上确认调试授权。设备断开后 `installDebug` 会失败或挂起。
- **AAPT2 daemon 启动失败**：`processDebugResources` 报 `Aapt2 Daemon startup failed` 时，先 `gradle --stop`，确认没有 Android Studio 同时在构建，再重试；不要 IDE 和命令行同时构建。本机实测 daemon 残留或并发构建时会触发。
- **Gradle JDK**：Android Studio 默认可能使用 `C:\Users\...\.jdks\jbr-21.0.11`。要严格遵守“不写 C 盘”，在 `Settings → Build, Execution, Deployment → Build Tools → Gradle` 把 Gradle JDK 改成项目内 JDK 21。
