# 照片与 Cloudreve

改照片上传、Cloudreve、自动同步前先读本文件。

## 上传

- 设置页「连接 → Cloudreve（WebDAV）」保存 `cloudreveUrl` / `cloudreveUser` / `cloudrevePassword` / `cloudreveDir`（默认 `手机照片`），DataStore 键前缀 `cloudreve_`。
- `feature/photos/CloudreveRepository.kt` 只做 PROPFIND 测连接/列目录、MKCOL 逐级建目录、HEAD 查重、PUT 上传；站点根地址会自动补 `/dav/`。
- `upload_recent_photos` 只负责用 MediaStore 找最近 N 天照片（`DATE_ADDED`，默认 3 天，单次最多 200 张），然后通过 `AgentTools` 的 `onUploadRequested` 回调让 AI 页弹出文件夹选择；用户选定后由 `MainViewModel.startPendingUpload` 调 `CloudreveRepository.uploadAll` 真正上传（UI 可列 Cloudreve 子目录，也可手填新目录），同名文件跳过，不删除任何文件。
- 相册权限仍是 `READ_MEDIA_IMAGES` / `READ_EXTERNAL_STORAGE`。

## 上传目录分层

`cloudreveDir/设备名/yyyy-MM/yyyy-MM-dd/`，设备名取 `AppSettings.deviceName`（空则跳过这一级），日期用 `PhotoFile.capturedAt`（`DATE_TAKEN`，缺省回退 `DATE_ADDED`）。分组在 `CloudreveRepository.uploadAll` 内完成，同一目录只 MKCOL 一次；同名去重仍是逐目录 HEAD。多设备靠设备名隔离，同名截图不会互相覆盖。

## 自动同步

- 设置页「连接 → Cloudreve」卡片里的「照片自动同步」只显示一行摘要 + 「设置」按钮，所有参数在一个弹窗里改（开关、每天几点 0-23、最近 N 天 1-30、单次最多 N 张 1-500，存 `AppSettings.photoAutoSync` / `photoSyncHour` / `photoSyncDays` / `photoSyncLimit`）。
- 开启后 `feature/photos/PhotoAutoSync.kt` 用 AlarmManager 每天在设定整点排一次性闹钟（`USE_EXACT_ALARM` + `SCHEDULE_EXACT_ALARM`，有权限时 `setExactAndAllowWhileIdle`，没有则 `setAndAllowWhileIdle` 并在弹窗提示），到点由 `PhotoSyncService` 以前台服务（`dataSync`）+ 常驻通知跑上传，服务持 partial wakelock，通知里有「取消」；上传逻辑在 `PhotoSync.run`，手动和定时共用。
- 闹钟触发后与开机/覆盖安装时都会重新排下一天。另有每 6 小时的持久化 JobScheduler 看门狗（`PhotoSyncJobService`），超过 20 小时没有成功同步就静默补跑；前台服务被系统拒绝时也退回它。弹窗里还有「允许后台不限电」入口（`REQUEST_IGNORE_BATTERY_OPTIMIZATIONS`）。
- 结果只把时间与摘要写进 SharedPreferences `photo_sync_status`（不含地址、账密）；没配 Cloudreve 或没相册权限时只记录原因，不重试。

## 隐私约束

只上传用户明确指定的时间范围/关键词命中的照片，不默认扫全相册；上传前先复述时间范围、张数和目标目录，用户已经说清就直接执行；同名文件跳过，绝不删除云端文件；账号密码只存本机 DataStore（`allowBackup=false`），不要写进日志、回答或截图。
