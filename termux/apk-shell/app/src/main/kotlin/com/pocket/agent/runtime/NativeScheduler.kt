package com.pocket.agent.runtime

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import com.pocket.agent.agent.AgentLoop
import com.pocket.agent.data.Store
import org.json.JSONArray
import org.json.JSONObject
import java.util.Calendar

/**
 * 原生定时任务调度器：AlarmManager + 5 字段 cron / 间隔 / 一次性。
 *
 * 为什么用 AlarmManager 而不是进程内 Handler 轮询：
 * 前台服务虽然常驻，但 Doze 会在息屏后冻结进程内的时钟回调。只有
 * `setExactAndAllowWhileIdle` 能在 Doze 下把进程叫醒——这正是「定时任务
 * 在锁屏后还能触发」的唯一系统级保证。进程内轮询看起来更简单，但会静默漏跑。
 *
 * 精度取舍：
 *  - Android 12+ 的 exact alarm 需要 SCHEDULE_EXACT_ALARM 权限；未授予时
 *    退回 `setAndAllowWhileIdle`（可能延迟数分钟），并在 /api/jobs 的 note 里说明。
 *  - 触发后立即重排下一个时间点，因此进程被杀也不会丢调度——闹钟由系统持有。
 */
class NativeScheduler(
    private val context: Context,
    private val store: Store,
    private val agent: AgentLoop,
) {
    private val am = context.getSystemService(Context.ALARM_SERVICE) as? AlarmManager

    /** 是否有精确闹钟权限（Android 12+）；低版本恒为 true。 */
    fun canScheduleExact(): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return true
        return am?.canScheduleExactAlarms() == true
    }

    /** 启动时全量重排：清掉所有旧闹钟，再按当前 enabled 任务重新下发。 */
    @Synchronized
    fun rescheduleAll() {
        val jobs = store.listJobs()
        for (i in 0 until jobs.length()) {
            val job = jobs.optJSONObject(i) ?: continue
            val id = job.optString("id")
            cancel(id)
            if (!job.optBoolean("enabled")) continue
            val next = nextRunAt(job)
            if (next == null) {
                // date 型任务已经过期：停用而不是留着每轮算不出来。
                store.updateJob(id, JSONObject().put("enabled", false))
                continue
            }
            arm(job, next)
        }
    }

    @Synchronized
    fun armJob(id: String) {
        val job = store.getJob(id) ?: return
        cancel(id)
        if (!job.optBoolean("enabled")) return
        val next = nextRunAt(job) ?: return
        arm(job, next)
    }

    private fun arm(job: JSONObject, atMillis: Long) {
        val am = am ?: return
        val pi = pendingIntent(job.optString("id"), PendingIntent.FLAG_UPDATE_CURRENT) ?: return
        try {
            if (canScheduleExact()) {
                am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, atMillis, pi)
            } else {
                am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, atMillis, pi)
            }
        } catch (_: SecurityException) {
            // 权限在运行中被撤销：降级为不精确闹钟，不让调度整体失效。
            try {
                am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, atMillis, pi)
            } catch (_: Exception) {
            }
        }
    }

    @Synchronized
    fun cancel(id: String) {
        val am = am ?: return
        try {
            val pi = pendingIntent(id, PendingIntent.FLAG_NO_CREATE) ?: return
            am.cancel(pi)
        } catch (_: Exception) {
        }
    }

    private fun pendingIntent(id: String, flags: Int): PendingIntent? {
        val intent = Intent(context, JobAlarmReceiver::class.java).apply {
            action = JobAlarmReceiver.ACTION_FIRE
            putExtra(JobAlarmReceiver.EXTRA_JOB_ID, id)
        }
        val base = PendingIntent.FLAG_IMMUTABLE or flags
        return PendingIntent.getBroadcast(context, id.hashCode(), intent, base)
    }

    /** 任务对外 JSON：补齐前端 Job 契约里的 next_run 字段。 */
    fun listJson(): JSONArray {
        val jobs = store.listJobs()
        val out = JSONArray()
        for (i in 0 until jobs.length()) {
            val j = jobs.optJSONObject(i) ?: continue
            val copy = JSONObject(j.toString())
            val next = if (j.optBoolean("enabled")) nextRunAt(j) else null
            copy.put("next_run", if (next != null) isoAt(next) else JSONObject.NULL)
            out.put(copy)
        }
        return out
    }

    /**
     * 立即执行一次。仍在后台线程跑，HTTP 立刻返回——与 Python 的
     * `/api/jobs/{id}/run` 语义一致（跑一次可能需要几十秒，不能让请求挂着）。
     */
    fun runNow(job: JSONObject) {
        val id = job.optString("id")
        store.markJobRun(id)
        Thread {
            fire(job)
        }.start()
    }

    /** 真正执行：把 message 当成一条用户消息投给 Agent，结果落在目标会话里。 */
    fun fire(job: JSONObject) {
        var sessionId = job.optString("session_id")
        if (sessionId.isEmpty() || store.getSession(sessionId) == null) {
            sessionId = store.createSession("定时任务 · ${job.optString("name")}").optString("id")
        }
        val message = job.optString("message")
        if (message.isBlank()) return
        if (!conditionHolds(job.optString("condition"))) {
            store.addMessage(sessionId, "system",
                "（条件未满足，本次跳过：${job.optString("condition")}）")
            return
        }
        store.addMessage(sessionId, "system",
            "（定时任务触发：${job.optString("name")}）")
        val sink = SseWriter(object : java.io.OutputStream() {
            override fun write(b: Int) {}
            override fun write(b: ByteArray, off: Int, len: Int) {}
        })
        try {
            agent.chat(sessionId, message, sink)
        } catch (_: Exception) {
            // 调度线程不能因为一次任务失败而退出。
        }
    }

    /** 条件触发：目前只支持 battery < 20 这一族（与 Python parse_condition 对齐）。 */
    private fun conditionHolds(cond: String): Boolean {
        if (cond.isBlank()) return true
        val m = Regex("^\\s*battery\\s*(<=|>=|==|<|>)\\s*([0-9.]+)\\s*$", RegexOption.IGNORE_CASE)
            .find(cond) ?: return true
        val op = m.groupValues[1]
        val threshold = m.groupValues[2].toDoubleOrNull() ?: return true
        val pct = try {
            val bm = context.getSystemService(Context.BATTERY_SERVICE) as android.os.BatteryManager
            bm.getIntProperty(android.os.BatteryManager.BATTERY_PROPERTY_CAPACITY).toDouble()
        } catch (_: Exception) {
            // fail-closed：读不到电量就不跑，避免低电量时反而跑耗电任务。
            return false
        }
        return when (op) {
            "<" -> pct < threshold
            ">" -> pct > threshold
            "<=" -> pct <= threshold
            ">=" -> pct >= threshold
            else -> kotlin.math.abs(pct - threshold) < 0.5
        }
    }

    /** 计算下一次触发时刻；已过期的一次性任务返回 null。 */
    fun nextRunAt(job: JSONObject, from: Long = System.currentTimeMillis()): Long? {
        return when (job.optString("trigger_type")) {
            "interval" -> {
                val sec = job.optString("expr").toDoubleOrNull()?.toLong() ?: return null
                if (sec <= 0) null else from + sec * 1000
            }
            "date" -> {
                val t = parseDate(job.optString("expr")) ?: return null
                if (t <= from) null else t
            }
            "cron" -> CronExpr.parse(job.optString("expr"))?.next(from)
            else -> null
        }
    }

    private fun parseDate(expr: String): Long? = try {
        val fmt = java.text.SimpleDateFormat("yyyy-MM-dd HH:mm", java.util.Locale.US)
        fmt.isLenient = false
        fmt.parse(expr)?.time
    } catch (_: Exception) {
        null
    }

    private fun isoAt(millis: Long): String =
        java.text.SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss", java.util.Locale.US)
            .format(java.util.Date(millis))
}

/**
 * 最小 cron 解析：5 字段（分 时 日 月 周），支持星号、星号斜杠步长、a-b 区间、逗号列表与单值。
 *
 * 不实现 Quartz 的 ? L W # 语法：本项目配方与 UI 只会产出标准 crontab 语法，
 * 多余语法静默接受反而会掩盖用户写错。解析失败返回 null → 上层把任务判为无法调度。
 */
class CronExpr private constructor(
    private val minutes: Set<Int>,
    private val hours: Set<Int>,
    private val days: Set<Int>,
    private val months: Set<Int>,
    private val weekdays: Set<Int>,
    private val domRestricted: Boolean,
    private val dowRestricted: Boolean,
) {
    /** 从 from 之后的下一分钟开始逐分钟找，最多看 4 年（覆盖 2 月 29 日这类边界）。 */
    fun next(from: Long): Long? {
        val cal = Calendar.getInstance()
        cal.timeInMillis = from
        cal.set(Calendar.SECOND, 0)
        cal.set(Calendar.MILLISECOND, 0)
        cal.add(Calendar.MINUTE, 1)
        val limit = from + 4L * 366 * 24 * 60 * 60 * 1000
        while (cal.timeInMillis <= limit) {
            val mi = cal.get(Calendar.MINUTE)
            val h = cal.get(Calendar.HOUR_OF_DAY)
            val d = cal.get(Calendar.DAY_OF_MONTH)
            val mo = cal.get(Calendar.MONTH) + 1
            val wd = cal.get(Calendar.DAY_OF_WEEK) - 1 // 0=周日，与 crontab 一致
            if (mi in minutes && h in hours && mo in months && dayMatches(d, wd)) {
                return cal.timeInMillis
            }
            cal.add(Calendar.MINUTE, 1)
        }
        return null
    }

    /** crontab 语义：日与周都受限时是「或」，只受限一个时只看那一个。 */
    private fun dayMatches(dom: Int, dow: Int): Boolean = when {
        domRestricted && dowRestricted -> dom in days || dow in weekdays
        domRestricted -> dom in days
        dowRestricted -> dow in weekdays
        else -> true
    }

    companion object {
        fun parse(expr: String): CronExpr? {
            val parts = expr.trim().split(Regex("\\s+"))
            if (parts.size != 5) return null
            val minutes = field(parts[0], 0, 59) ?: return null
            val hours = field(parts[1], 0, 23) ?: return null
            val days = field(parts[2], 1, 31) ?: return null
            val months = field(parts[3], 1, 12) ?: return null
            val weekdays = field(parts[4], 0, 7)?.map { if (it == 7) 0 else it }?.toSet() ?: return null
            return CronExpr(
                minutes, hours, days, months, weekdays,
                domRestricted = parts[2] != "*",
                dowRestricted = parts[4] != "*",
            )
        }

        private fun field(raw: String, min: Int, max: Int): Set<Int>? {
            val out = HashSet<Int>()
            for (piece in raw.split(',')) {
                val p = piece.trim()
                if (p.isEmpty()) return null
                val stepSplit = p.split('/')
                if (stepSplit.size > 2) return null
                val step = if (stepSplit.size == 2) {
                    stepSplit[1].toIntOrNull()?.takeIf { it > 0 } ?: return null
                } else 1
                val range = stepSplit[0]
                val (lo, hi) = when {
                    range == "*" -> min to max
                    '-' in range -> {
                        val ab = range.split('-')
                        if (ab.size != 2) return null
                        val a = ab[0].toIntOrNull() ?: return null
                        val b = ab[1].toIntOrNull() ?: return null
                        a to b
                    }
                    else -> {
                        val v = range.toIntOrNull() ?: return null
                        // `5/15` 在 crontab 里的含义是「从 5 起每 15」
                        if (stepSplit.size == 2) v to max else v to v
                    }
                }
                if (lo < min || hi > max || lo > hi) return null
                var v = lo
                while (v <= hi) {
                    out.add(v)
                    v += step
                }
            }
            return out.ifEmpty { null }
        }
    }
}
