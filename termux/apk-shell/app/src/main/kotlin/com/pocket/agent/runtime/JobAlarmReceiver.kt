package com.pocket.agent.runtime

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.pocket.agent.agent.AgentLoop
import com.pocket.agent.agent.ApprovalCenter
import com.pocket.agent.data.SettingsStore
import com.pocket.agent.data.Store
import org.json.JSONObject

/**
 * 定时任务触发入口：AlarmManager 只能拉起一个 BroadcastReceiver，
 * 而真正的执行要 Agent 与数据库，所以这里装配一份临时引擎组件再跑。
 *
 * 为什么要自建而不是复用 NativeEngineService 的实例：广播可能在前台服务被系统
 * 回收后独立到达（进程已冷启动）。走 Service 绑定会引入「服务还没起来」的竞态；
 * 直接构造 Store/Settings/Approval 的成本很低（都是本地文件/SQLite），
 * 且 AgentLoop 的会话锁是进程内 ConcurrentHashMap，两个实例同时跑同一会话时
 * 由 Run 排队语义兜住，不会并发写坏消息。
 */
class JobAlarmReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != ACTION_FIRE) return
        val jobId = intent.getStringExtra(EXTRA_JOB_ID) ?: return
        val app = context.applicationContext

        // goAsync：广播接收器必须在 10 秒内返回，但任务执行可能更久。
        // 申请一段后台时间，让 Thread 在 onReceive 返回后还能继续跑。
        val pending = goAsync()
        Thread {
            try {
                val store = Store(app)
                val job = store.getJob(jobId) ?: return@Thread
                if (!job.optBoolean("enabled")) return@Thread
                store.markJobRun(jobId)
                val settings = SettingsStore(app)
                val approval = ApprovalCenter(app)
                AgentLoop.appContext = { app }
                val loop = AgentLoop(store, settings, approval)
                val scheduler = NativeScheduler(app, store, loop)
                scheduler.fire(job)
                // 触发后立即排下一次：闹钟是一次性的，不重排就只跑一次。
                if (job.optString("trigger_type") != "date") {
                    scheduler.armJob(jobId)
                } else {
                    store.updateJob(jobId, JSONObject().put("enabled", false))
                }
            } catch (_: Exception) {
                // 后台线程异常必须吞掉：崩溃会连带杀掉整个进程的闹钟接收能力。
            } finally {
                pending.finish()
            }
        }.start()
    }

    companion object {
        const val ACTION_FIRE = "com.pocket.agent.ACTION_JOB_FIRE"
        const val EXTRA_JOB_ID = "job_id"
    }
}
