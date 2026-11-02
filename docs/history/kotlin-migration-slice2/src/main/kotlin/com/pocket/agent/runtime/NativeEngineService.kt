package com.pocket.agent.runtime

import android.app.Service
import android.content.Intent
import android.os.Binder
import android.os.IBinder
import android.util.Log
import java.security.SecureRandom

/**
 * 持有本机原生服务的进程内 Service。
 *
 * 引擎不接受任何外部凭据：调用方只能拿到不含令牌的文档 URL，会话由引擎在
 * 校验一次性 nonce 后通过 HttpOnly cookie 下发。因此 Binder 上也没有任何 getter
 * 能读出会话标识或密文——同机其他应用即使绑定成功也拿不到可用凭证。
 */
class NativeEngineService : Service() {

    inner class LocalBinder : Binder() {
        /** 服务实例标识：Activity 用它识别服务重启后的陈旧 Binder。 */
        fun instanceId(): String = instanceId

        fun isRunning(): Boolean = engine?.state == EngineState.RUNNING

        /** 为下一次主文档加载签发一次性导航许可；未运行或不可读 assets 时返回 null。 */
        fun newDocumentUrl(): String? = engine?.newDocumentUrl()

        /** 供测试与状态页读取的引擎句柄，不暴露任何会话材料。 */
        fun port(): Int = engine?.port ?: -1
    }

    private val binder = LocalBinder()
    private var engine: NativeEngine? = null
    private var instanceId: String = ""

    override fun onCreate() {
        super.onCreate()
        instanceId = randomHex(8)
        val page = try {
            assets.open(BOOTSTRAP_ASSET).bufferedReader().use { it.readText() }
        } catch (e: Exception) {
            // 内置页面缺失时不能退化成空白页：那会让用户以为服务正常。
            Log.e(TAG, "bootstrap asset missing", e)
            null
        }
        if (page.isNullOrBlank()) return
        engine = NativeEngine(0, page)
        if (!engine!!.start()) {
            engine?.stop()
            engine = null
        }
    }

    override fun onBind(intent: Intent?): IBinder = binder

    override fun onDestroy() {
        engine?.stop()
        engine = null
        super.onDestroy()
    }

    private fun randomHex(bytes: Int): String {
        val buffer = ByteArray(bytes)
        SECURE_RANDOM.nextBytes(buffer)
        val out = StringBuilder(bytes * 2)
        for (b in buffer) {
            val v = b.toInt() and 0xff
            out.append(HEX[v ushr 4]).append(HEX[v and 0x0f])
        }
        return out.toString()
    }

    companion object {
        private const val TAG = "PocketAgent"
        private const val BOOTSTRAP_ASSET = "native_bootstrap.html"
        private val SECURE_RANDOM = SecureRandom()
        private const val HEX = "0123456789abcdef"
    }
}
