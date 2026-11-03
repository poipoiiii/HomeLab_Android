package com.poi.homelab.feature.watch

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 价格监控的核心：按规则拉一次藏宝阁，把没提醒过的商品发成本地通知。
 * 首次运行会把当前命中的商品全部提醒一次，之后只提醒新增条目。
 */
class WatchEngine(
    private val context: Context,
    private val store: WatchStore = WatchStore(context),
    private val cbg: CbgRepository = CbgRepository(),
    private val rail: RailRepository = RailRepository(),
) {

    private data class RuleRun(
        val at: Long,
        val seen: List<String>,
        val result: String,
    )

    suspend fun run(ruleId: String? = null, force: Boolean = false): WatchReport =
        withContext(Dispatchers.IO) {
            val current = store.load()
            val now = System.currentTimeMillis()
            val targets = current.rules.filter { rule ->
                rule.enabled &&
                    (ruleId == null || rule.id == ruleId) &&
                    (if (rule.isTrain) rule.train.valid else rule.target.equipType.isNotBlank()) &&
                    (force || isDue(rule, now))
            }
            if (targets.isEmpty()) {
                WatchAlarm.scheduleNext(context)
                return@withContext WatchReport(0, 0, 0, "没有到检测时间的规则")
            }

            val errors = mutableListOf<String>()
            val runs = mutableMapOf<String, RuleRun>()
            val totals = mutableMapOf<String, Int>()
            val hits = mutableListOf<Pair<WatchRule, List<WatchItem>>>()
            val trainAlerts = mutableListOf<Pair<WatchRule, List<String>>>()

            for (rule in targets) {
                if (rule.isTrain) {
                    val result = queryTrain(rule, now, errors)
                    runs[rule.id] = result.first
                    result.second?.let { trainAlerts += rule to it }
                    continue
                }
                val items = try {
                    cbg.query(rule)
                } catch (error: Exception) {
                    runs[rule.id] = RuleRun(now, rule.seen, "请求失败")
                    errors += "${rule.displayTitle}：${error.message ?: "请求失败"}"
                    continue
                }
                // 首次运行同样通知：只要当前在售命中条件就先提醒一次，之后只提醒新增
                totals[rule.id] = items.size
                val seen = rule.seen.toSet()
                val fresh = items.filter { it.eid !in seen }
                runs[rule.id] = RuleRun(
                    at = now,
                    seen = (rule.seen + fresh.map { it.eid }).takeLast(2000),
                    result = when {
                        items.isEmpty() -> "暂无在售"
                        fresh.isEmpty() -> "无新增，在售 ${items.size} 件"
                        else -> "新增 ${fresh.size} 件，在售 ${items.size} 件"
                    },
                )
                if (fresh.isNotEmpty()) hits += rule to fresh
            }

            var notified = 0
            if (hits.isNotEmpty() || trainAlerts.isNotEmpty()) {
                if (!WatchNotifier.canNotify(context)) {
                    errors += "通知权限未开启，结果未提醒"
                } else {
                    hits.forEach { (rule, items) ->
                        WatchNotifier.notifyHits(
                            context = context,
                            rule = rule,
                            items = items,
                            total = totals[rule.id] ?: items.size,
                        )
                        notified += items.size
                    }
                    trainAlerts.forEach { (rule, lines) ->
                        WatchNotifier.notifyTrain(context, rule, lines)
                        notified += 1
                    }
                }
            }

            val updated = current.rules.map { rule ->
                val run = runs[rule.id] ?: return@map rule
                val hit = hits.any { it.first.id == rule.id } || trainAlerts.any { it.first.id == rule.id }
                rule.copy(
                    lastRunAt = run.at,
                    lastHitAt = if (hit) run.at else rule.lastHitAt,
                    seen = run.seen,
                    lastResult = run.result,
                )
            }
            store.save(current.copy(rules = updated))
            WatchAlarm.scheduleNext(context)

            val text = buildString {
                append("检查 ${targets.size} 条规则")
                if (hits.isNotEmpty()) append("，命中 ${hits.sumOf { it.second.size }} 件")
                if (trainAlerts.isNotEmpty()) append("，${trainAlerts.size} 条车票")
                if (notified > 0) append("，已发送通知")
                if (errors.isNotEmpty()) append("；").append(errors.first())
            }
            WatchReport(targets.size, hits.sumOf { it.second.size }, notified, text)
        }

    /** 固定每天几点跑的规则，用 12 小时间隔判断是否已经跑过，避免手动检测后当天重跑。 */
    private fun isDue(rule: WatchRule, now: Long): Boolean {
        if (rule.dailyHour == null) {
            return rule.lastRunAt <= 0L || now - rule.lastRunAt >= rule.intervalMinutes * 60_000L
        }
        return rule.lastRunAt <= 0L || now - rule.lastRunAt >= 12 * 60L * 60_000L
    }

    /** 火车票：查最近假期首日前一天和末日次日，各取最低价。 */
    private suspend fun queryTrain(
        rule: WatchRule,
        now: Long,
        errors: MutableList<String>,
    ): Pair<RuleRun, List<String>?> {
        val holiday = HolidayCalendar.nearest()
        if (holiday == null) {
            errors += "${rule.displayTitle}：节假日数据获取失败"
            return RuleRun(now, rule.seen, "节假日数据获取失败") to null
        }
        val dates = listOf(holiday.start.minusDays(1), holiday.end.plusDays(1))
        val lines = mutableListOf<String>()
        var queried = false
        dates.forEach { date ->
            val quotes = runCatching { rail.quotes(rule.train, date) }.getOrElse { error ->
                errors += "${rule.displayTitle} ${date}：${error.message ?: "查询失败"}"
                emptyList()
            }
            if (quotes.isNotEmpty()) queried = true
            val best = quotes.minByOrNull { it.priceYuan }
            lines += if (best == null) {
                "${date.monthValue}月${date.dayOfMonth}日 暂无可售"
            } else {
                "${date.monthValue}月${date.dayOfMonth}日 ¥${best.priceYuan}（${best.trainCode} ${best.startTime}）"
            }
        }
        val summary = "${holiday.name}：" + lines.joinToString("；")
        return if (queried) {
            RuleRun(now, rule.seen, summary) to lines
        } else {
            RuleRun(now, rule.seen, "查询失败") to null
        }
    }

    companion object {
        /** 闹钟和开机广播里没有 AppContainer，这里按默认依赖重新组装。 */
        fun from(context: Context): WatchEngine = WatchEngine(context.applicationContext)
    }
}

/**
 * 排下一次检测：所有规则里最早到期的那条。
 * 一次只挂一个闹钟，触发后按当时的时间重新计算，规则增删改都只是重排同一条。
 */
object WatchAlarm {

    private const val REQUEST_CODE = 0x5748

    suspend fun scheduleNext(context: Context, settings: WatchSettings? = null) {
        val appContext = context.applicationContext
        val rules = (settings ?: WatchStore(appContext).load()).rules
            .filter { it.enabled && (if (it.isTrain) it.train.valid else it.target.equipType.isNotBlank()) }
        val alarm = appContext.getSystemService(AlarmManager::class.java) ?: return
        val pending = pendingIntent(appContext)
        alarm.cancel(pending)
        if (rules.isEmpty()) return

        val now = System.currentTimeMillis()
        val due = rules.minOf { rule -> nextTrigger(rule, now) }
        val trigger = maxOf(due, now + 30_000L)
        val exact = Build.VERSION.SDK_INT < Build.VERSION_CODES.S || alarm.canScheduleExactAlarms()
        if (exact) {
            alarm.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, trigger, pending)
        } else {
            // 没有精确闹钟权限时交给系统合并，可能晚几十分钟，但省电
            alarm.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, trigger, pending)
        }
    }

    fun cancel(context: Context) {
        val alarm = context.getSystemService(AlarmManager::class.java) ?: return
        alarm.cancel(pendingIntent(context.applicationContext))
    }

    /** 固定时间的规则排到下一个整点，其余按上次检测 + 间隔。 */
    private fun nextTrigger(rule: WatchRule, now: Long): Long {
        val hour = rule.dailyHour
        if (hour != null) {
            val calendar = java.util.Calendar.getInstance().apply {
                set(java.util.Calendar.HOUR_OF_DAY, hour.coerceIn(0, 23))
                set(java.util.Calendar.MINUTE, 0)
                set(java.util.Calendar.SECOND, 0)
                set(java.util.Calendar.MILLISECOND, 0)
                if (timeInMillis <= now) add(java.util.Calendar.DAY_OF_YEAR, 1)
            }
            return calendar.timeInMillis
        }
        return if (rule.lastRunAt <= 0L) now + 60_000L else rule.lastRunAt + rule.intervalMinutes * 60_000L
    }

    private fun pendingIntent(context: Context): PendingIntent = PendingIntent.getBroadcast(
        context,
        REQUEST_CODE,
        Intent(context, WatchAlarmReceiver::class.java),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
    )
}

/** 闹钟到点：跑一次检测，结束时会自动排下一条。 */
class WatchAlarmReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val appContext = context.applicationContext
        val pending = goAsync()
        CoroutineScope(Dispatchers.IO).launch {
            try {
                WatchEngine.from(appContext).run()
            } finally {
                pending.finish()
            }
        }
    }
}

/** 重启或覆盖安装后重新排闹钟；规则和上次检测时间都在 DataStore 里。 */
class WatchBootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val appContext = context.applicationContext
        val pending = goAsync()
        CoroutineScope(Dispatchers.IO).launch {
            try {
                WatchAlarm.scheduleNext(appContext)
            } finally {
                pending.finish()
            }
        }
    }
}
