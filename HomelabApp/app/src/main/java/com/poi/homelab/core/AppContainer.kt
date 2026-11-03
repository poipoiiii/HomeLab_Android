package com.poi.homelab.core

import com.poi.homelab.core.settings.SettingsStore
import com.poi.homelab.feature.ai.AiAgent
import com.poi.homelab.feature.ai.AiClient
import com.poi.homelab.feature.ai.ChatStore
import com.poi.homelab.feature.ai.DeviceTools
import com.poi.homelab.feature.bills.BillRepository
import com.poi.homelab.feature.calendar.CalendarRepository
import com.poi.homelab.feature.calendar.CalendarStore
import com.poi.homelab.feature.device.MqttDeviceRepository
import com.poi.homelab.feature.files.FileInsightRepository
import com.poi.homelab.feature.homarr.HomarrRepository
import com.poi.homelab.feature.home.ChecklistStore
import com.poi.homelab.feature.home.TodoStore
import com.poi.homelab.feature.imageedit.ImageEditClient
import com.poi.homelab.feature.imageedit.ImageEditRepository
import com.poi.homelab.feature.imageedit.ImageEditStore
import com.poi.homelab.feature.lab.LabRepository
import com.poi.homelab.feature.lab.McpClient
import com.poi.homelab.feature.market.MarketMindStore
import com.poi.homelab.feature.market.MarketRepository
import com.poi.homelab.feature.motion.MotionClient
import com.poi.homelab.feature.motion.MotionDirector
import com.poi.homelab.feature.motion.MotionPlanStore
import com.poi.homelab.feature.motion.MotionRepository
import com.poi.homelab.feature.photos.CloudreveRepository
import com.poi.homelab.feature.weather.WeatherRepository
import com.poi.homelab.feature.watch.CbgRepository
import com.poi.homelab.feature.watch.RailRepository
import com.poi.homelab.feature.watch.WatchEngine
import com.poi.homelab.feature.watch.WatchStore
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
    val imageEditStore = ImageEditStore(appContext)
    val imageEdit = ImageEditRepository(
        ImageEditClient(http, json),
        deviceTools,
        imageEditStore,
        cloudreve,
    )
    val weather = WeatherRepository(http, json)
    val device = MqttDeviceRepository(appContext, json)
    val market = MarketRepository(http, json)
    val motion = MotionRepository(MotionClient(http, json), deviceTools, appContext)
    val motionDirector = MotionDirector(json)
    val motionPlans = MotionPlanStore()
    val marketMind = MarketMindStore(appContext)
    val watch = WatchStore(appContext)
    val cbg = CbgRepository()
    val rail = RailRepository()
    val watchEngine = WatchEngine(appContext, watch, cbg, rail)
}
