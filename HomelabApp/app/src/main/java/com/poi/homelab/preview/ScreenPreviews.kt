package com.poi.homelab.preview

import android.content.res.Configuration
import androidx.compose.runtime.Composable
import androidx.compose.ui.tooling.preview.Preview
import com.poi.homelab.core.settings.AppSettings
import com.poi.homelab.core.ui.theme.HomelabTheme
import com.poi.homelab.feature.ai.ChatMessage
import com.poi.homelab.feature.ai.defaultAiProviders
import com.poi.homelab.feature.ai.ui.AiScreen
import com.poi.homelab.feature.bills.BillAccount
import com.poi.homelab.feature.bills.BillOverview
import com.poi.homelab.feature.bills.BillTransaction
import com.poi.homelab.feature.bills.CategoryAmount
import com.poi.homelab.feature.bills.MonthStats
import com.poi.homelab.feature.bills.ui.BillsScreen
import com.poi.homelab.feature.calendar.CALENDAR_IMPORTANT
import com.poi.homelab.feature.calendar.CALENDAR_TRAVEL
import com.poi.homelab.feature.calendar.CalendarEntry
import com.poi.homelab.feature.calendar.ui.CalendarScreen
import com.poi.homelab.feature.files.CategoryStat
import com.poi.homelab.feature.files.DirStat
import com.poi.homelab.feature.files.DuplicateGroup
import com.poi.homelab.feature.files.FileCategory
import com.poi.homelab.feature.files.FileEntry
import com.poi.homelab.feature.files.FileInsight
import com.poi.homelab.feature.files.ui.FileInsightScreen
import com.poi.homelab.feature.home.ui.HomeScreen
import com.poi.homelab.feature.lab.ContainerInfo
import com.poi.homelab.feature.lab.DiskInfo
import com.poi.homelab.feature.lab.DiskSection
import com.poi.homelab.feature.lab.HealthSnapshot
import com.poi.homelab.feature.lab.LabSummary
import com.poi.homelab.feature.lab.ui.DownloadsScreen
import com.poi.homelab.feature.lab.ui.ServicesScreen
import com.poi.homelab.feature.settings.ui.SettingsScreen
import com.poi.homelab.navigation.FileUiState
import com.poi.homelab.navigation.UiState
import java.time.LocalDate
import java.time.YearMonth

private val previewSettings = AppSettings(
    labUrl = "http://100.64.0.10:8765",
    billUrl = "http://100.64.0.10:8091",
    token = "",
    deviceName = "Pixel 9",
    themeMode = "light",
    aiProviders = defaultAiProviders().map {
        if (it.id == "deepseek") it.copy(apiKey = "sk-preview") else it
    },
    aiSelectedId = "deepseek",
)

private val previewHealth = HealthSnapshot(
    summary = LabSummary(
        total = 6,
        running = 4,
        stopped = 2,
        unhealthy = 0,
        attention = 1,
        checkedAt = "2026-10-26T09:30:00+08:00",
    ),
    disk = DiskInfo(
        images = DiskSection(count = 18, size = "12.4 GB", unusedSize = "2.1 GB"),
        containers = DiskSection(count = 6, size = "1.8 GB"),
        volumes = DiskSection(count = 9, size = "8.6 GB", unusedSize = "1.2 GB"),
        buildCache = DiskSection(count = 12, size = "3.2 GB", unusedSize = "3.0 GB"),
        totalReclaimable = "6.3 GB",
    ),
    attention = listOf(
        ContainerInfo(
            name = "jellyfin",
            state = "exited",
            exitCode = 0,
            ports = listOf("127.0.0.1:8096 -> 8096/tcp"),
        ),
    ),
    containers = listOf(
        ContainerInfo(
            name = "poi-ledger",
            state = "running",
            health = "healthy",
            ports = listOf("127.0.0.1:8091 -> 8091/tcp"),
        ),
        ContainerInfo(
            name = "lab-tools",
            state = "running",
            health = "healthy",
            ports = listOf("127.0.0.1:8765 -> 8765/tcp"),
        ),
        ContainerInfo(
            name = "metube",
            state = "running",
            ports = listOf("127.0.0.1:8081 -> 8081/tcp"),
        ),
        ContainerInfo(
            name = "librechat",
            state = "running",
            ports = listOf("127.0.0.1:3080 -> 3080/tcp"),
        ),
        ContainerInfo(name = "jellyfin", state = "exited", exitCode = 0),
        ContainerInfo(name = "cloudreve", state = "exited", exitCode = 0),
    ),
)

private val previewTransactions = listOf(
    BillTransaction(
        id = 1,
        date = "2026-10-26",
        time = "09:12",
        type = "expense",
        amount = "32.50",
        amountCents = 3250,
        account = "微信零钱",
        category = "餐饮",
        merchant = "瑞幸咖啡",
    ),
    BillTransaction(
        id = 2,
        date = "2026-10-25",
        time = "18:40",
        type = "expense",
        amount = "86.00",
        amountCents = 8600,
        account = "中国银行",
        category = "餐饮",
        merchant = "海底捞",
    ),
    BillTransaction(
        id = 3,
        date = "2026-10-25",
        time = "09:05",
        type = "transfer",
        amount = "500.00",
        amountCents = 50000,
        account = "中国银行",
        toAccount = "微信零钱",
        category = "转账",
        merchant = "转账到微信零钱",
    ),
    BillTransaction(
        id = 4,
        date = "2026-10-24",
        time = "20:18",
        type = "income",
        amount = "12000.00",
        amountCents = 1200000,
        account = "中国银行",
        category = "工资",
        merchant = "工资入账",
    ),
    BillTransaction(
        id = 5,
        date = "2026-10-24",
        time = "12:30",
        type = "expense",
        amount = "26.80",
        amountCents = 2680,
        account = "支付宝",
        category = "交通",
        merchant = "滴滴出行",
    ),
)

private val previewOverview = BillOverview(
    month = "2026-10",
    accounts = listOf(
        BillAccount(id = 1, name = "中国银行", balance = "12890.42"),
        BillAccount(id = 2, name = "微信零钱", balance = "236.80"),
        BillAccount(id = 3, name = "支付宝", balance = "1024.00"),
    ),
    netWorth = "14151.22",
    monthStats = MonthStats(
        expense = "3284.60",
        income = "12000.00",
        net = "8715.40",
        countOut = 42,
        dailyAvg = "126.33",
        activeDays = 26,
    ),
    byCategory = listOf(
        CategoryAmount(name = "餐饮", amount = "986.40", count = 18),
        CategoryAmount(name = "购物", amount = "742.00", count = 9),
        CategoryAmount(name = "交通", amount = "418.50", count = 11),
        CategoryAmount(name = "居住", amount = "620.00", count = 2),
        CategoryAmount(name = "娱乐", amount = "318.70", count = 3),
        CategoryAmount(name = "医疗", amount = "199.00", count = 1),
    ),
    recent = previewTransactions,
)

private val previewState = UiState(
    settings = previewSettings,
    health = previewHealth,
    overview = previewOverview,
    transactions = previewTransactions,
    billMonth = "2026-10",
    checkedToday = setOf("lab", "bill"),
    calendarEntries = listOf(
        CalendarEntry(
            id = 1,
            date = YearMonth.now().atDay(12).toString(),
            title = "去京都看枫叶",
            type = CALENDAR_TRAVEL,
            location = "京都",
            note = "岚山、清水寺、鸭川",
        ),
        CalendarEntry(
            id = 2,
            date = LocalDate.now().toString(),
            title = "交房租",
            type = CALENDAR_IMPORTANT,
            note = "记得转账",
        ),
    ),
    chatMessages = listOf(
        ChatMessage(1, "user", "看下手机资源，给点优化建议"),
        ChatMessage(
            2,
            "tool",
            "{\"memory\":{\"total_mb\":11902.0,\"available_mb\":4210.0},\"usage_access\":true}",
            "get_system_resources",
        ),
        ChatMessage(
            3,
            "assistant",
            "当前可用内存约 4.1 GB，整体不算紧张。最近 24 小时前台使用最多的是微信和浏览器，" +
                "存储占用前列的是相册和微信缓存。建议先清理不常用应用的缓存，再考虑卸载长期不用的应用。\n\n" +
                "```mermaid\nflowchart TD\nA[查看存储] --> B{缓存是否超过 1 GB}\n" +
                "B -->|是| C[清理缓存]\nB -->|否| D[保留应用]\nC --> E[观察一周]\n```",
        ),
    ),
)

private val previewEmptyState = UiState(settings = previewSettings)

private val previewFileEntries = listOf(
    FileEntry(
        path = "DCIM/Camera/2024_trip.mp4",
        name = "2024_trip.mp4",
        size = 4_200_000_000,
        modified = System.currentTimeMillis() - 40L * 86400_000,
        mime = "video/mp4",
    ),
    FileEntry(
        path = "Download/backup.zip",
        name = "backup.zip",
        size = 3_800_000_000,
        modified = System.currentTimeMillis() - 8L * 86400_000,
        mime = "application/zip",
    ),
    FileEntry(
        path = "Documents/租房合同.pdf",
        name = "租房合同.pdf",
        size = 2_400_000,
        modified = System.currentTimeMillis() - 2L * 86400_000,
        mime = "application/pdf",
    ),
    FileEntry(
        path = "Pictures/Screenshots/Screenshot_20261001.png",
        name = "Screenshot_20261001.png",
        size = 1_200_000,
        modified = System.currentTimeMillis() - 86400_000,
        mime = "image/png",
    ),
)

private val previewFileInsight = FileInsight(
    rootName = "DCIM",
    deviceName = "Pixel 9",
    scannedAt = System.currentTimeMillis() - 3_600_000,
    durationMs = 86_000,
    totalFiles = 31_204,
    totalDirs = 2_418,
    totalBytes = 128L * 1024 * 1024 * 1024,
    freeBytes = 127L * 1024 * 1024 * 1024,
    storageTotalBytes = 256L * 1024 * 1024 * 1024,
    categories = listOf(
        CategoryStat(FileCategory.Image, 12_400, 54L * 1024 * 1024 * 1024),
        CategoryStat(FileCategory.Video, 320, 60L * 1024 * 1024 * 1024),
        CategoryStat(FileCategory.Document, 8_000, 8L * 1024 * 1024 * 1024),
        CategoryStat(FileCategory.Other, 10_484, 6L * 1024 * 1024 * 1024),
    ),
    topDirs = listOf(
        DirStat("DCIM", 12_000, 82L * 1024 * 1024 * 1024),
        DirStat("Download", 900, 21L * 1024 * 1024 * 1024),
        DirStat("Documents", 1_200, 9L * 1024 * 1024 * 1024),
    ),
    largest = previewFileEntries,
    duplicates = listOf(
        DuplicateGroup(
            name = "IMG_0001.jpg",
            size = 2_400_000,
            files = previewFileEntries.take(2),
        ),
    ),
    duplicateCount = 23,
    duplicateBytes = 1_800_000_000,
    tempFiles = previewFileEntries.drop(1).take(1),
    tempCount = 38,
    tempBytes = 640_000_000,
    oldLarge = previewFileEntries.take(1),
    oldLargeCount = 4,
    oldLargeBytes = 4_200_000_000,
    sensitive = previewFileEntries.drop(2).take(1),
    sensitiveCount = 6,
    truncated = false,
    skippedDirs = emptyList(),
)

private val previewFileState = previewState.copy(
    settings = previewSettings.copy(fileTreeUris = listOf("content://preview/dcim")),
    files = FileUiState(
        insight = previewFileInsight,
        indexLoaded = true,
        scannedFiles = previewFileInsight.totalFiles,
        rows = previewFileEntries,
        totalMatches = previewFileInsight.totalFiles,
        shown = previewFileEntries.size,
    ),
)

@Preview(name = "总览 · 浅色", showBackground = true, widthDp = 390, heightDp = 844)
@Composable
private fun HomePreview() {
    HomelabTheme("light") {
        HomeScreen(previewState, {}, {}, {}, {}, {}, {}, {}, {})
    }
}

@Preview(
    name = "总览 · 深色",
    showBackground = true,
    widthDp = 390,
    heightDp = 844,
    uiMode = Configuration.UI_MODE_NIGHT_YES,
)
@Composable
private fun HomeDarkPreview() {
    HomelabTheme("dark") {
        HomeScreen(previewState, {}, {}, {}, {}, {}, {}, {}, {})
    }
}

@Preview(name = "日历", showBackground = true, widthDp = 390, heightDp = 900)
@Composable
private fun CalendarPreview() {
    HomelabTheme("light") {
        CalendarScreen(previewState, {}, {})
    }
}

@Preview(name = "服务", showBackground = true, widthDp = 390, heightDp = 844)
@Composable
private fun ServicesPreview() {
    HomelabTheme("light") {
        ServicesScreen(previewState, {}, { _, _ -> }, {})
    }
}

@Preview(name = "账单", showBackground = true, widthDp = 390, heightDp = 844)
@Composable
private fun BillsPreview() {
    HomelabTheme("light") {
        BillsScreen(previewState, {}, {}, {}, {})
    }
}

@Preview(name = "下载", showBackground = true, widthDp = 390, heightDp = 844)
@Composable
private fun DownloadsPreview() {
    HomelabTheme("light") {
        DownloadsScreen(previewState) { _, _, _, _, _, _, _ -> }
    }
}

@Preview(name = "小砂糖", showBackground = true, widthDp = 390, heightDp = 844)
@Composable
private fun AiPreview() {
    HomelabTheme("light") {
        AiScreen(previewState, {}, {}, {})
    }
}

@Preview(name = "设置", showBackground = true, widthDp = 390, heightDp = 900)
@Composable
private fun SettingsPreview() {
    HomelabTheme("light") {
        SettingsScreen(
            state = previewState,
            onSave = {},
            onTest = {},
            onTheme = {},
            onOpenAiSettings = {},
            onFileTreeSelected = {},
            onClearFileTree = {},
            onShowLedger = {},
            onShowWallpaper = {},
            onWallpaperOpacity = {},
            onWallpaperDim = {},
            onSettingsDetailed = {},
            isPreview = true,
        )
    }
}

@Preview(name = "文件洞察", showBackground = true, widthDp = 390, heightDp = 900)
@Composable
private fun FileInsightPreview() {
    HomelabTheme("light") {
        FileInsightScreen(
            state = previewFileState,
            onLoad = {},
            onClose = {},
            onRootSelected = {},
            onRootRemoved = {},
            onScan = {},
            onCancelScan = {},
            onViewChange = {},
            onCategoryChange = {},
            onQueryChange = {},
            onSortChange = {},
            onLoadMore = {},
            onAnalyze = {},
            onShowAnalysis = {},
            onCloseAnalysis = {},
            isPreview = true,
        )
    }
}

@Preview(name = "总览 · 未配置", showBackground = true, widthDp = 390, heightDp = 844)
@Composable
private fun HomeEmptyPreview() {
    HomelabTheme("light") {
        HomeScreen(
            state = previewEmptyState.copy(
                settings = AppSettings(themeMode = "light"),
            ),
            onRefresh = {},
            onOpenSettings = {},
            onOpenBills = {},
            onOpenServices = {},
            onOpenDownloads = {},
            onOpenLink = {},
            onToggleChecklist = {},
            onSaveTodos = {},
        )
    }
}
