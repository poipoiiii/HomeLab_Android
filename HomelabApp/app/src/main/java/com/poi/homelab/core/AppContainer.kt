package com.poi.homelab.core

import com.poi.homelab.core.settings.SettingsStore
import com.poi.homelab.feature.ai.AiAgent
import com.poi.homelab.feature.ai.AiClient
import com.poi.homelab.feature.ai.ChatStore
import com.poi.homelab.feature.ai.DeviceTools
import com.poi.homelab.feature.bills.BillRepository
import com.poi.homelab.feature.calendar.CalendarRepository
import com.poi.homelab.feature.calendar.CalendarStore
import com.poi.homelab.feature.files.FileInsightRepository
import com.poi.homelab.feature.homarr.HomarrRepository
import com.poi.homelab.feature.home.ChecklistStore
import com.poi.homelab.feature.home.TodoStore
import com.poi.homelab.feature.lab.LabRepository
import com.poi.homelab.feature.lab.McpClient
import com.poi.homelab.feature.photos.CloudreveRepository
import java.util.concurrent.TimeUnit
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient

class AppContainer(context: android.content.Context) {
    val appContext = context.applicationContext

    val json: Json = Json {
        ignoreUnknownKeys = true
        isLenient = true
        coerceInputValues = true
    }

    val http: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(200, TimeUnit.SECONDS)
        .writeTimeout(30, TimeUnit.SECONDS)
        .build()

    val settings = SettingsStore(appContext)
    val chat = ChatStore(appContext)
    val checklist = ChecklistStore(appContext)
    val todo = TodoStore(appContext)
    val calendar = CalendarRepository(CalendarStore(appContext))
    val deviceTools = DeviceTools(appContext)
    val files = FileInsightRepository(appContext)
    val aiClient = AiClient(http, json)
    val aiAgent = AiAgent()
    private val mcp = McpClient(http, json)
    val lab = LabRepository(mcp, json)
    val bill = BillRepository(http, json)
    val homarr = HomarrRepository(http, json)
    val cloudreve = CloudreveRepository(http)
}
