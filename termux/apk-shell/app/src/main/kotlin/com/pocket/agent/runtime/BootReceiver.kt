package com.pocket.agent.runtime

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.pocket.agent.agent.AgentLoop
import com.pocket.agent.data.Store

/**
 * 开机 / 应用更新后的恢复入口。
 *
 * 为什么必须有它：AlarmManager 的闹钟在设备重启后会全部清空，前台服务也不会
 * 自动重启——不接 BOOT_COMPLETED 的话，用户重启一次手机，「定时任务」就永久
 * 静默失效，而且没有任何提示，这是最难排查的一类问题。
 *
 * Android 8+ 的后台启动限制：BOOT_COMPLETED 是系统给出的少数豁免场景之一，
 * 可以直接 startForegroundService，但服务必须在 5 秒内调用 startForeground，
 * 否则系统抛 ForegroundServiceDidNotStartInTimeException。NativeEngineService
 * 在 onCreate 里同步 startForegroundCompat()，满足这个约束。
 */
class BootReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED &&
            intent.action != Intent.ACTION_MY_PACKAGE_REPLACED
        ) return

        val app = context.applicationContext

        // 1) 拉起前台引擎服务（minSdk 26，startForegroundService 恒可用）
        try {
            app.startForegroundService(Intent(app, NativeEngineService::class.java))
        } catch (_: Exception) {
            // 厂商 ROM 可能拦截自启；失败不能让广播崩溃，闹钟部分仍要继续重排。
        }

        // 2) 重排全部定时任务：闹钟在重启时被系统清空，必须重建
        val pending = goAsync()
        Thread {
            try {
                val store = Store(app)
                AgentLoop.appContext = { app }
                val settings = com.pocket.agent.data.SettingsStore(app)
                val approval = com.pocket.agent.agent.ApprovalCenter(app)
                val loop = AgentLoop(store, settings, approval)
                NativeScheduler(app, store, loop).rescheduleAll()
            } catch (_: Exception) {
            } finally {
                pending.finish()
            }
        }.start()
    }
}
