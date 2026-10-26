package com.rong.homelab.ui.preview

import android.content.res.Configuration
import androidx.compose.runtime.Composable
import androidx.compose.ui.tooling.preview.Preview
import com.rong.homelab.data.AppSettings
import com.rong.homelab.data.BillAccount
import com.rong.homelab.data.BillOverview
import com.rong.homelab.data.BillTransaction
import com.rong.homelab.data.CategoryAmount
import com.rong.homelab.data.ContainerInfo
import com.rong.homelab.data.DiskInfo
import com.rong.homelab.data.DiskSection
import com.rong.homelab.data.HealthSnapshot
import com.rong.homelab.data.LabSummary
import com.rong.homelab.data.MonthStats
import com.rong.homelab.ui.UiState
import com.rong.homelab.ui.screens.BillsScreen
import com.rong.homelab.ui.screens.DownloadsScreen
import com.rong.homelab.ui.screens.HomeScreen
import com.rong.homelab.ui.screens.ServicesScreen
import com.rong.homelab.ui.screens.SettingsScreen
import com.rong.homelab.ui.theme.HomelabTheme

private val previewSettings = AppSettings(
    labUrl = "http://100.64.0.10:8765",
    billUrl = "http://100.64.0.10:8091",
    token = "",
    deviceName = "Pixel 9",
    themeMode = "light",
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
            name = "rong-ledger",
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
)

private val previewEmptyState = UiState(settings = previewSettings)

@Preview(name = "总览 · 浅色", showBackground = true, widthDp = 390, heightDp = 844)
@Composable
private fun HomePreview() {
    HomelabTheme("light") {
        HomeScreen(previewState, {}, {}, {}, {}, {})
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
        HomeScreen(previewState, {}, {}, {}, {}, {})
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

@Preview(name = "设置", showBackground = true, widthDp = 390, heightDp = 844)
@Composable
private fun SettingsPreview() {
    HomelabTheme("light") {
        SettingsScreen(previewState, {}, {}, {})
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
        )
    }
}
