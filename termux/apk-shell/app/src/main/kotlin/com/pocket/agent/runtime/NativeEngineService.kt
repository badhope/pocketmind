package com.pocket.agent.runtime

import android.app.Service
import android.content.Intent
import android.os.Binder
import android.os.IBinder
import android.util.Log
import com.pocket.agent.agent.AgentLoop
import com.pocket.agent.agent.ApprovalCenter
import com.pocket.agent.data.SettingsStore
import com.pocket.agent.data.Store
import com.pocket.agent.tools.TtsPool

/**
 * 原生引擎前台服务：装配 存储 → 设置 → 审批 → Agent 循环 → HTTP 路由。
 *
 * 这是「单 APK 可用」的核心：不依赖 Termux / Python / Node，普通 Android
 * 安装即得全部核心功能（对话、审批、原生工具、会话持久化、备份恢复）。
 */
class NativeEngineService : Service() {

    inner class LocalBinder : Binder() {
        fun instanceId(): String = instanceId
        fun isRunning(): Boolean = engine?.state == EngineState.RUNNING
        fun newDocumentUrl(): String? = engine?.newDocumentUrl()
        fun port(): Int = engine?.port ?: -1
    }

    private val binder = LocalBinder()
    private var engine: NativeEngine? = null
    private var router: ApiRouter? = null
    private var instanceId: String = ""

    override fun onCreate() {
        super.onCreate()
        instanceId = newHex(8)
        val store = Store(this)
        val settings = SettingsStore(this)
        val approval = ApprovalCenter(this)
        AgentLoop.appContext = { applicationContext }
        val agent = AgentLoop(store, settings, approval)
        val scheduler = NativeScheduler(this, store, agent)
        val router = ApiRouter(this, store, settings, approval, agent, {
            engine?.takeIf { it.state == EngineState.RUNNING }
        }, scheduler)
        this.router = router
        // 服务起来就把闹钟全量重排一次：进程可能刚被系统杀掉重启，
        // 而 AlarmManager 里的旧闹钟指向的 job 可能已被删除或停用。
        try {
            scheduler.rescheduleAll()
        } catch (e: Exception) {
            Log.w(TAG, "reschedule failed: ${e.message}")
        }
        val engine = NativeEngine(0, router)
        this.engine = engine
        if (!engine.start()) {
            Log.e(TAG, "native engine failed to start")
            engine.stop()
            this.engine = null
        } else {
            Log.i(TAG, "native engine listening on 127.0.0.1:${engine.port}")
        }
        startForegroundCompat()
    }

    private fun startForegroundCompat() {
        val nm = getSystemService(NOTIFICATION_SERVICE) as? android.app.NotificationManager ?: return
        if (android.os.Build.VERSION.SDK_INT >= 26) {
            nm.createNotificationChannel(
                android.app.NotificationChannel(CHANNEL, "口袋智灵引擎", android.app.NotificationManager.IMPORTANCE_LOW)
            )
        }
        val n = if (android.os.Build.VERSION.SDK_INT >= 26) {
            android.app.Notification.Builder(this, CHANNEL)
                .setContentTitle("口袋智灵")
                .setContentText("本地引擎运行中")
                .setSmallIcon(android.R.drawable.ic_dialog_info)
                .setOngoing(true)
                .build()
        } else {
            @Suppress("DEPRECATION")
            android.app.Notification.Builder(this)
                .setContentTitle("口袋智灵")
                .setContentText("本地引擎运行中")
                .setSmallIcon(android.R.drawable.ic_dialog_info)
                .setOngoing(true)
                .build()
        }
        if (android.os.Build.VERSION.SDK_INT >= 29) {
            // 类型由 manifest 的 foregroundServiceType 声明（specialUse，见 AndroidManifest）
            startForeground(NOTIF_ID, n)
        } else {
            startForeground(NOTIF_ID, n)
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int = START_STICKY

    override fun onBind(intent: Intent?): IBinder = binder

    override fun onDestroy() {
        TtsPool.shutdown()
        engine?.stop()
        engine = null
        router = null
        super.onDestroy()
    }

    private fun newHex(bytes: Int): String {
        val buffer = ByteArray(bytes)
        java.security.SecureRandom().nextBytes(buffer)
        val sb = StringBuilder(bytes * 2)
        for (b in buffer) sb.append("%02x".format(b))
        return sb.toString()
    }

    companion object {
        private const val TAG = "PocketAgent"
        private const val CHANNEL = "pocketmind-engine"
        private const val NOTIF_ID = 1001
    }
}
