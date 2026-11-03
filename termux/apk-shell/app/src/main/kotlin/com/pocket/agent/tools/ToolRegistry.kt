package com.pocket.agent.tools

import android.Manifest
import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.pm.PackageManager
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.media.AudioManager
import android.net.wifi.WifiManager
import android.os.BatteryManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.VibrationEffect
import android.os.Vibrator
import android.speech.tts.TextToSpeech
import android.widget.Toast
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.InetAddress
import java.net.URL
import java.util.Locale
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import android.provider.Settings

/**
 * 工具执行环境：Android Context + Store 句柄 + 当前会话。
 * Robolectric 下大部分系统服务有 shadow，可直接驱动单测。
 */
class DeviceEnv(val context: Context, val store: com.pocket.agent.data.Store, val sessionId: String)

/** 工具定义：风险分级语义与 Python 侧完全一致（safe/write/danger）。 */
class ToolDef(
    val name: String,
    val description: String,
    val risk: String,
    val summary: String,
    val group: String,
    val params: JSONObject,
    val handler: (args: JSONObject, env: DeviceEnv) -> JSONObject,
)

/** 工具内部的短路径返回：与 Python 的 return@register 语义一致。 */
class ToolAbort(val result: JSONObject) : RuntimeException(null, null, false, false)

private fun err(msg: String, code: String = "tool_error"): JSONObject =
    JSONObject().put("error", msg).put("error_code", code)

private fun needPermission(hint: String): JSONObject =
    JSONObject().put("error", "缺少系统权限：$hint")
        .put("error_code", "permission_required")
        .put("hint", "请在系统设置 → 应用 → 口袋智灵 中授予该权限后重试")

private fun unavailable(what: String, why: String): JSONObject =
    JSONObject().put("error", "$what 在原生引擎上不可用").put("error_code", "capability_unavailable")
        .put("hint", why)

private fun optString(args: JSONObject, key: String, fallback: String = ""): String =
    if (args.has(key) && !args.isNull(key)) args.optString(key, fallback) else fallback

private fun optInt(args: JSONObject, key: String, fallback: Int = 0): Int =
    if (args.has(key) && !args.isNull(key)) args.optInt(key, fallback) else fallback

private fun optBool(args: JSONObject, key: String, fallback: Boolean): Boolean =
    if (args.has(key) && !args.isNull(key)) args.optBoolean(key, fallback) else fallback

/** 万能参数容错：LLM 偶尔把布尔值输出成 "true"/"false" 字符串。 */
private fun asBool(v: Any?): Boolean = when (v) {
    is Boolean -> v
    is String -> v.equals("true", true) || v == "1" || v == "on"
    is Int -> v != 0
    else -> false
}

/**
 * 原生工具注册表：与 Python 侧同名同险的工具迁移为 Android API 直调；
 * Termux 专属能力（shell、Python 扩展）**不伪迁移**，返回结构化 unavailable。
 */
object ToolRegistry {

    private val tools = ArrayList<ToolDef>()

    fun all(): List<ToolDef> = tools
    fun byName(name: String): ToolDef? = tools.firstOrNull { it.name == name }

    /** /api/tools 的 wire 格式（前端能力页契约）。 */
    fun listJson(): JSONArray {
        val arr = JSONArray()
        all().forEach { t ->
            arr.put(
                JSONObject()
                    .put("name", t.name).put("description", t.description)
                    .put("risk", t.risk).put("summary", t.summary)
                    .put("timeout", 120).put("group", t.group)
            )
        }
        return arr
    }

    /** LLM 的 tools schema（OpenAI function calling 格式）。 */
    fun schemaJson(): JSONArray {
        val arr = JSONArray()
        all().forEach { t ->
            arr.put(
                JSONObject()
                    .put("type", "function")
                    .put(
                        "function", JSONObject()
                            .put("name", t.name)
                            .put("description", t.description + "（风险：" + t.risk + "）")
                            .put("parameters", t.params)
                    )
            )
        }
        return arr
    }

    private fun register(t: ToolDef) = tools.add(t)

    private fun schema(vararg fields: Pair<String, Pair<String, Boolean>>): JSONObject {
        val props = JSONObject()
        val required = JSONArray()
        for ((name, spec) in fields) {
            props.put(name, JSONObject().put("type", spec.first).put("description", name))
            if (spec.second) required.put(name)
        }
        return JSONObject().put("type", "object").put("properties", props).put("required", required)
    }

    private val emptySchema = JSONObject().put("type", "object").put("properties", JSONObject())

    // ═══════════════ 文件（app 私有目录内，路径穿越不可能） ═══════════════

    private fun safeFile(env: DeviceEnv, path: String): File? {
        val root = env.context.filesDir.canonicalFile
        val f = File(root, path.trimStart('/')).canonicalFile
        return if (f.path.startsWith(root.path)) f else null
    }

    init {
        register(
            ToolDef(
                "list_dir", "列出应用数据目录（或其子目录）的内容。", "safe", "列出目录", "files",
                schema("path" to ("string" to false))
            ) { args, env ->
                val dir = safeFile(env, optString(args, "path", ".")) ?: throw ToolAbort(err("路径越界"))
                if (!dir.exists()) throw ToolAbort(err("目录不存在：${dir.name}"))
                val arr = JSONArray()
                dir.listFiles()?.sortedBy { it.name }?.forEach { f ->
                    arr.put(
                        JSONObject().put("name", f.name)
                            .put("type", if (f.isDirectory) "dir" else "file")
                            .put("size", if (f.isFile) f.length() else 0)
                    )
                }
                JSONObject().put("path", dir.name).put("entries", arr).put("count", arr.length())
            }
        )
        register(
            ToolDef(
                "read_file", "读取应用数据目录下的一个文本文件（上限 200KB）。", "safe", "读取文件", "files",
                schema("path" to ("string" to true))
            ) { args, env ->
                val f = safeFile(env, optString(args, "path")) ?: throw ToolAbort(err("路径越界"))
                if (!f.isFile) throw ToolAbort(err("文件不存在：${f.name}"))
                val bytes = f.readBytes()
                if (bytes.size > 200 * 1024) throw ToolAbort(err("文件超过 200KB，请用 download_file 或分段处理"))
                JSONObject().put("path", f.name).put("content", String(bytes))
            }
        )
        register(
            ToolDef(
                "write_file", "把文本写入应用数据目录（覆盖）。", "write", "写入文件", "files",
                schema("path" to ("string" to true), "content" to ("string" to true))
            ) { args, env ->
                val f = safeFile(env, optString(args, "path")) ?: throw ToolAbort(err("路径越界"))
                f.parentFile?.mkdirs()
                f.writeText(optString(args, "content"))
                JSONObject().put("ok", true).put("path", f.name).put("chars", f.length())
            }
        )
        register(
            ToolDef(
                "delete_file", "删除应用数据目录下的一个文件。", "danger", "删除文件", "files",
                schema("path" to ("string" to true))
            ) { args, env ->
                val f = safeFile(env, optString(args, "path")) ?: throw ToolAbort(err("路径越界"))
                if (!f.isFile) throw ToolAbort(err("文件不存在：${f.name}"))
                JSONObject().put("ok", f.delete()).put("path", f.name)
            }
        )
        register(
            ToolDef(
                "download_file", "下载一个 http(s) 文件到应用数据目录（仅 80/443 端口）。", "write", "下载文件", "files",
                schema("url" to ("string" to true), "path" to ("string" to false))
            ) { args, env ->
                val url = optString(args, "url")
                val guard = SsrfGuard.check(url) ?: throw ToolAbort(err("URL 不合法：$url"))
                val name = optString(args, "path", "download-${System.currentTimeMillis()}")
                val f = safeFile(env, name) ?: throw ToolAbort(err("路径越界"))
                val conn = guard.open()
                conn.connectTimeout = 15000
                conn.readTimeout = 60000
                try {
                    if (conn.responseCode !in 200..299) throw ToolAbort(err("下载失败：HTTP ${conn.responseCode}"))
                    conn.inputStream.use { input -> f.outputStream().use { input.copyTo(it, 64 * 1024) } }
                    JSONObject().put("ok", true).put("path", f.name).put("bytes", f.length())
                } finally {
                    conn.disconnect()
                }
            }
        )

        // ═══════════════ 知识库 / 记忆 / 任务清单 ═══════════════

        register(
            ToolDef(
                "kb_add", "把一段资料存入本机知识库（长期保存）。", "write", "存入知识库", "knowledge",
                schema("title" to ("string" to false), "content" to ("string" to true))
            ) { args, env ->
                val content = optString(args, "content").trim()
                if (content.isEmpty()) throw ToolAbort(err("content 不能为空"))
                if (content.length > 20000) throw ToolAbort(err("单条内容过长（>20000 字）"))
                val title = optString(args, "title").trim().take(120).ifEmpty { "未命名" }
                val id = env.storeRef().kbAdd(title, content)
                JSONObject().put("ok", true).put("id", id).put("title", title).put("chars", content.length)
            }
        )
        register(
            ToolDef(
                "kb_search", "在本机知识库中检索资料。", "safe", "检索知识库", "knowledge",
                schema("query" to ("string" to true), "limit" to ("integer" to false))
            ) { args, env ->
                val hits = env.storeRef().kbSearch(optString(args, "query"), optInt(args, "limit", 5).coerceIn(1, 20))
                JSONObject().put("ok", true).put("count", hits.length()).put("results", hits)
            }
        )
        register(
            ToolDef(
                "kb_list", "列出知识库全部条目（标题与长度）。", "safe", "列出知识库条目", "knowledge",
                emptySchema
            ) { _, env ->
                val items = env.storeRef().kbList()
                JSONObject().put("ok", true).put("count", items.length()).put("items", items)
            }
        )
        register(
            ToolDef(
                "remember_fact", "记住一条关于用户的事实（长期记忆）。", "write", "记住一件事", "memory",
                schema("fact" to ("string" to true))
            ) { args, env ->
                val fact = optString(args, "fact").trim()
                if (fact.isEmpty()) throw ToolAbort(err("fact 不能为空"))
                val id = env.storeRef().addFact(fact)
                JSONObject().put("ok", true).put("id", id).put("fact", fact)
            }
        )
        register(
            ToolDef(
                "todo_write", "整体覆盖当前会话的任务清单。", "safe", "更新任务清单", "tasks",
                schema("todos" to ("array" to true))
            ) { args, env ->
                val arr = args.optJSONArray("todos") ?: throw ToolAbort(err("todos 必须是数组"))
                if (arr.length() > 50) throw ToolAbort(err("任务最多 50 条"))
                env.storeRef().setTodos(env.sessionId, arr)
                JSONObject().put("ok", true).put("count", arr.length())
            }
        )
        register(
            ToolDef(
                "todo_clear", "清空当前会话的任务清单。", "safe", "清空任务清单", "tasks",
                emptySchema
            ) { _, env ->
                env.storeRef().clearTodos(env.sessionId)
                JSONObject().put("ok", true)
            }
        )

        // ═══════════════ 手机：系统 ═══════════════

        register(
            ToolDef(
                "get_clipboard", "读取剪贴板当前文本。", "safe", "读取剪贴板", "phone",
                emptySchema
            ) { _, env ->
                val cm = env.context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
                    ?: throw ToolAbort(err("剪贴板服务不可用"))
                val item = cm.primaryClip
                if (item == null || item.itemCount == 0) {
                    JSONObject().put("ok", true).put("text", "")
                } else {
                    JSONObject().put("ok", true).put("text", item.getItemAt(0).coerceToText(env.context)?.toString() ?: "")
                }
            }
        )
        register(
            ToolDef(
                "set_clipboard", "把文本写入剪贴板。", "write", "写入剪贴板", "phone",
                schema("text" to ("string" to true))
            ) { args, env ->
                val text = optString(args, "text")
                if (text.isEmpty()) throw ToolAbort(err("text 不能为空"))
                val cm = env.context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
                    ?: throw ToolAbort(err("剪贴板服务不可用"))
                cm.setPrimaryClip(ClipData.newPlainText("pocketmind", text))
                JSONObject().put("ok", true).put("chars", text.length)
            }
        )
        register(
            ToolDef(
                "get_battery", "获取电池状态：电量、充电状态、温度。", "safe", "查看电池电量", "phone",
                emptySchema
            ) { _, env ->
                val bm = env.context.getSystemService(Context.BATTERY_SERVICE) as? BatteryManager
                    ?: throw ToolAbort(err("电池服务不可用"))
                val level = bm.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY)
                val status = bm.getIntProperty(BatteryManager.BATTERY_PROPERTY_STATUS)
                val charging = status == BatteryManager.BATTERY_STATUS_CHARGING ||
                    status == BatteryManager.BATTERY_STATUS_FULL
                // 温度只在 ACTION_BATTERY_CHANGED 粘性广播的 extra 里
                val sticky = env.context.registerReceiver(null,
                    android.content.IntentFilter(android.content.Intent.ACTION_BATTERY_CHANGED))
                val temp = sticky?.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, 0) ?: 0
                JSONObject()
                    .put("level", level)
                    .put("charging", charging)
                    .put("temperature", temp / 10.0)
            }
        )
        register(
            ToolDef(
                "send_notification", "在系统通知栏发一条通知。", "write", "发送通知", "phone",
                schema("title" to ("string" to true), "body" to ("string" to false))
            ) { args, env ->
                if (Build.VERSION.SDK_INT >= 33 &&
                    env.context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
                ) throw ToolAbort(needPermission("通知"))
                val nm = env.context.getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager
                    ?: throw ToolAbort(err("通知服务不可用"))
                nm.createNotificationChannel(
                    NotificationChannel("agent", "口袋智灵", NotificationManager.IMPORTANCE_DEFAULT)
                )
                val title = optString(args, "title", "口袋智灵")
                val body = optString(args, "body")
                val n: Notification = Notification.Builder(env.context, "agent")
                    .setContentTitle(title.take(80))
                    .setContentText(body.take(200))
                    .setStyle(Notification.BigTextStyle().bigText(body.take(2000)))
                    .setSmallIcon(android.R.drawable.ic_dialog_info)
                    .build()
                nm.notify(System.nanoTime().toInt(), n)
                JSONObject().put("ok", true).put("title", title)
            }
        )
        register(
            ToolDef(
                "show_toast", "在屏幕上弹一条短暂的悬浮提示。", "write", "显示提示", "phone",
                schema("text" to ("string" to true))
            ) { args, env ->
                val text = optString(args, "text").take(200)
                if (text.isEmpty()) throw ToolAbort(err("text 不能为空"))
                Handler(Looper.getMainLooper()).post {
                    Toast.makeText(env.context, text, Toast.LENGTH_LONG).show()
                }
                JSONObject().put("ok", true)
            }
        )
        register(
            ToolDef(
                "vibrate", "让手机震动指定时长。", "write", "震动", "phone",
                schema("ms" to ("integer" to false))
            ) { args, env ->
                val ms = optInt(args, "ms", 500).coerceIn(50, 10_000)
                val v = env.context.getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator
                    ?: throw ToolAbort(err("震动服务不可用"))
                if (Build.VERSION.SDK_INT >= 31 && !v.hasVibrator()) throw ToolAbort(err("设备不支持震动"))
                v.vibrate(VibrationEffect.createOneShot(ms.toLong(), VibrationEffect.DEFAULT_AMPLITUDE))
                JSONObject().put("ok", true).put("ms", ms)
            }
        )
        register(
            ToolDef(
                "set_torch", "打开或关闭手电筒。", "write", "手电筒", "phone",
                schema("on" to ("boolean" to true))
            ) { args, env ->
                val on = asBool(args.opt("on"))
                val cm = env.context.getSystemService(Context.CAMERA_SERVICE) as? android.hardware.camera2.CameraManager
                    ?: throw ToolAbort(err("相机服务不可用"))
                val cameraId = cm.cameraIdList.firstOrNull { id ->
                    cm.getCameraCharacteristics(id)
                        .get(android.hardware.camera2.CameraCharacteristics.FLASH_INFO_AVAILABLE) == true
                } ?: throw ToolAbort(err("没有可用的闪光灯"))
                cm.setTorchMode(cameraId, on)
                JSONObject().put("ok", true).put("on", on)
            }
        )
        register(
            ToolDef(
                "get_volume", "查询某个音频流的音量。", "safe", "查看音量", "phone",
                schema("stream" to ("string" to false))
            ) { args, env ->
                val am = env.context.getSystemService(Context.AUDIO_SERVICE) as? AudioManager
                    ?: throw ToolAbort(err("音频服务不可用"))
                val stream = streamOf(optString(args, "stream", "music"))
                JSONObject()
                    .put("stream", streamName(stream))
                    .put("volume", am.getStreamVolume(stream))
                    .put("max", am.getStreamMaxVolume(stream))
            }
        )
        register(
            ToolDef(
                "set_volume", "设置某个音频流的音量（0-100）。", "write", "调节音量", "phone",
                schema("volume" to ("integer" to true), "stream" to ("string" to false))
            ) { args, env ->
                val am = env.context.getSystemService(Context.AUDIO_SERVICE) as? AudioManager
                    ?: throw ToolAbort(err("音频服务不可用"))
                val stream = streamOf(optString(args, "stream", "music"))
                val vol = optInt(args, "volume").coerceIn(0, 100)
                am.setStreamVolume(stream, vol * am.getStreamMaxVolume(stream) / 100, 0)
                JSONObject().put("ok", true).put("volume", vol)
            }
        )
        register(
            ToolDef(
                "set_brightness", "设置屏幕亮度（0-255，需「修改系统设置」权限）。", "write", "调节亮度", "phone",
                schema("value" to ("integer" to true))
            ) { args, env ->
                if (!Settings.System.canWrite(env.context)) {
                    throw ToolAbort(
                        JSONObject()
                            .put("error", "缺少「修改系统设置」权限")
                            .put("error_code", "permission_required")
                            .put("hint", "请在系统设置中允许口袋智灵「修改系统设置」")
                    )
                }
                val v = optInt(args, "value").coerceIn(0, 255)
                Settings.System.putInt(
                    env.context.contentResolver, Settings.System.SCREEN_BRIGHTNESS, v
                )
                JSONObject().put("ok", true).put("value", v)
            }
        )

        // ═══════════════ 手机：传感器 / 网络 ═══════════════

        register(
            ToolDef(
                "list_sensors", "列出设备可用传感器。", "safe", "列出传感器", "phone",
                emptySchema
            ) { _, env ->
                val sm = env.context.getSystemService(Context.SENSOR_SERVICE) as? SensorManager
                    ?: throw ToolAbort(err("传感器服务不可用"))
                val arr = JSONArray()
                sm.getSensorList(Sensor.TYPE_ALL).forEach { s ->
                    arr.put(
                        JSONObject().put("name", s.name).put("type", s.stringType)
                            .put("vendor", s.vendor)
                    )
                }
                JSONObject().put("ok", true).put("count", arr.length()).put("sensors", arr)
            }
        )
        register(
            ToolDef(
                "read_sensor", "读取指定传感器的一次读数（约 1 秒）。", "safe", "读取传感器", "phone",
                schema("type" to ("string" to true))
            ) { args, env ->
                val sm = env.context.getSystemService(Context.SENSOR_SERVICE) as? SensorManager
                    ?: throw ToolAbort(err("传感器服务不可用"))
                val typeStr = optString(args, "type", "accelerometer")
                val type = when (typeStr.lowercase(Locale.ROOT)) {
                    "accelerometer" -> Sensor.TYPE_ACCELEROMETER
                    "gyroscope" -> Sensor.TYPE_GYROSCOPE
                    "magnetic", "magnetometer" -> Sensor.TYPE_MAGNETIC_FIELD
                    "light", "lux" -> Sensor.TYPE_LIGHT
                    "proximity" -> Sensor.TYPE_PROXIMITY
                    "pressure", "barometer" -> Sensor.TYPE_PRESSURE
                    "temperature", "ambient_temperature" -> Sensor.TYPE_AMBIENT_TEMPERATURE
                    "humidity" -> Sensor.TYPE_RELATIVE_HUMIDITY
                    else -> throw ToolAbort(err("未知传感器类型：$typeStr（可用：accelerometer/gyroscope/magnetic/light/proximity/pressure/temperature/humidity）"))
                }
                val sensor = sm.getDefaultSensor(type) ?: throw ToolAbort(err("设备没有该传感器：$typeStr"))
                val latch = CountDownLatch(1)
                val values = FloatArray(6)
                val listener = object : SensorEventListener {
                    override fun onSensorChanged(e: SensorEvent) {
                        for (i in 0 until minOf(e.values.size, values.size)) values[i] = e.values[i]
                        latch.countDown()
                    }

                    override fun onAccuracyChanged(s: Sensor?, a: Int) {}
                }
                sm.registerListener(listener, sensor, SensorManager.SENSOR_DELAY_NORMAL)
                val got = latch.await(2, TimeUnit.SECONDS)
                sm.unregisterListener(listener)
                if (!got) throw ToolAbort(err("传感器读数超时：$typeStr"))
                val arr = JSONArray()
                for (v in values.take(3)) arr.put(v.toDouble())
                JSONObject().put("ok", true).put("type", typeStr).put("values", arr)
            }
        )
        register(
            ToolDef(
                "get_wifi_info", "查询当前 WiFi 信息（SSID/信号/速率）。", "safe", "WiFi 信息", "phone",
                emptySchema
            ) { _, env ->
                if (env.context.checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
                    throw ToolAbort(needPermission("精确位置（读取 WiFi 信息在 Android 8+ 需要）"))
                }
                @Suppress("DEPRECATION")
                val wm = env.context.applicationContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager
                    ?: throw ToolAbort(err("WiFi 服务不可用"))
                val info = wm.connectionInfo
                if (info == null || info.ssid == null || info.ssid == "<unknown ssid>") {
                    throw ToolAbort(JSONObject().put("ok", true).put("connected", false))
                }
                JSONObject()
                    .put("ok", true).put("connected", true)
                    .put("ssid", info.ssid.removeSurrounding("\""))
                    .put("rssi", info.rssi).put("link_speed_mbps", info.linkSpeed)
                    .put("ip", intToIp(info.ipAddress))
            }
        )
        register(
            ToolDef(
                "scan_wifi", "扫描附近 WiFi 网络（最多 15 个）。", "safe", "扫描 WiFi", "phone",
                emptySchema
            ) { _, env ->
                if (env.context.checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
                    throw ToolAbort(needPermission("精确位置（WiFi 扫描在 Android 8+ 需要）"))
                }
                @Suppress("DEPRECATION")
                val wm = env.context.applicationContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager
                    ?: throw ToolAbort(err("WiFi 服务不可用"))
                val results = wm.scanResults.take(15)
                val arr = JSONArray()
                results.forEach { r ->
                    arr.put(
                        JSONObject().put("ssid", r.SSID).put("bssid", r.BSSID)
                            .put("rssi", r.level).put("capabilities", r.capabilities)
                    )
                }
                JSONObject().put("ok", true).put("count", arr.length()).put("networks", arr)
            }
        )
        register(
            ToolDef(
                "get_location", "获取当前位置（经纬度，使用最后已知位置，秒回）。", "danger", "获取定位", "phone",
                emptySchema
            ) { _, env ->
                if (env.context.checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED &&
                    env.context.checkSelfPermission(Manifest.permission.ACCESS_COARSE_LOCATION) != PackageManager.PERMISSION_GRANTED
                ) throw ToolAbort(needPermission("位置"))
                val lm = env.context.getSystemService(Context.LOCATION_SERVICE) as? android.location.LocationManager
                    ?: throw ToolAbort(err("定位服务不可用"))
                val providers = lm.getProviders(true)
                val best = providers.mapNotNull { p -> lm.getLastKnownLocation(p) }
                    .maxByOrNull { it.time }
                    ?: throw ToolAbort(err("暂无位置数据：请先打开系统定位并让地图类应用获取一次位置"))
                JSONObject()
                    .put("ok", true)
                    .put("lat", best.latitude).put("lon", best.longitude)
                    .put("provider", best.provider)
                    .put("accuracy_m", best.accuracy)
                    .put("time", best.time / 1000.0)
            }
        )

        // ═══════════════ 手机：通信 ═══════════════

        register(
            ToolDef(
                "send_sms", "发送短信给指定号码。", "danger", "发送短信", "phone",
                schema("to" to ("string" to true), "text" to ("string" to true))
            ) { args, env ->
                if (env.context.checkSelfPermission(Manifest.permission.SEND_SMS) != PackageManager.PERMISSION_GRANTED) {
                    throw ToolAbort(needPermission("短信"))
                }
                val to = optString(args, "to")
                val text = optString(args, "text")
                if (to.isEmpty() || text.isEmpty()) throw ToolAbort(err("to 和 text 都不能为空"))
                if (text.length > 1000) throw ToolAbort(err("短信内容过长（>1000 字）"))
                val sm = if (Build.VERSION.SDK_INT >= 31) {
                    env.context.getSystemService(android.telephony.SmsManager::class.java)
                } else {
                    @Suppress("DEPRECATION") android.telephony.SmsManager.getDefault()
                } ?: throw ToolAbort(err("短信服务不可用"))
                sm.sendMultipartTextMessage(to, null, sm.divideMessage(text), null, null)
                JSONObject().put("ok", true).put("to", to).put("chars", text.length)
            }
        )
        register(
            ToolDef(
                "read_sms", "读取最近收到的短信（涉及隐私）。", "danger", "读取短信", "phone",
                schema("limit" to ("integer" to false))
            ) { args, env ->
                if (env.context.checkSelfPermission(Manifest.permission.READ_SMS) != PackageManager.PERMISSION_GRANTED) {
                    throw ToolAbort(needPermission("短信"))
                }
                val limit = optInt(args, "limit", 10).coerceIn(1, 50)
                val arr = JSONArray()
                env.context.contentResolver.query(
                    android.net.Uri.parse("content://sms/inbox"),
                    arrayOf("_id", "address", "body", "date"), null, null, "date DESC"
                )?.use { c ->
                    while (c.moveToNext() && arr.length() < limit) {
                        arr.put(
                            JSONObject().put("from", c.getString(1))
                                .put("body", c.getString(2))
                                .put("time", c.getLong(3) / 1000.0)
                        )
                    }
                }
                JSONObject().put("ok", true).put("count", arr.length()).put("messages", arr)
            }
        )
        register(
            ToolDef(
                "make_call", "拨打电话（调起系统拨号确认，不会自动拨出）。", "danger", "拨打电话", "phone",
                schema("number" to ("string" to true))
            ) { args, env ->
                val number = optString(args, "number")
                if (number.isEmpty()) throw ToolAbort(err("number 不能为空"))
                // 用 ACTION_DIAL 而非 ACTION_CALL：用户保留最后一道确认，
                // 引擎也不需要 CALL_PHONE 权限。这是刻意的安全选择。
                val intent = android.content.Intent(android.content.Intent.ACTION_DIAL, android.net.Uri.parse("tel:$number"))
                intent.addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
                env.context.startActivity(intent)
                JSONObject().put("ok", true).put("number", number).put("mode", "dial_pad")
            }
        )

        // ═══════════════ 手机：媒体 / 语音 ═══════════════

        register(
            ToolDef(
                "tts_speak", "用 TTS 朗读一段文字。", "write", "语音朗读", "phone",
                schema("text" to ("string" to true))
            ) { args, env ->
                val text = optString(args, "text")
                if (text.isEmpty()) throw ToolAbort(err("text 不能为空"))
                if (text.length > 3000) throw ToolAbort(err("朗读内容过长（>3000 字）"))
                TtsPool.speak(env.context, text)
                JSONObject().put("ok", true).put("chars", text.length)
            }
        )
        register(
            ToolDef(
                "speech_to_text", "语音转文字。", "safe", "语音转文字", "phone",
                emptySchema
            ) { _, _ ->
                unavailable("语音识别", "原生引擎 v1 未内置识别器；可改用输入法的语音输入")
            }
        )
        register(
            ToolDef(
                "take_photo", "用相机拍一张照片。", "write", "拍照", "phone",
                schema("path" to ("string" to false))
            ) { args, env ->
                if (env.context.checkSelfPermission(Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
                    throw ToolAbort(needPermission("相机"))
                }
                throw ToolAbort(unavailable("拍照", "原生引擎 v1 未内置相机管线，将在后续版本提供"))
            }
        )

        // ═══════════════ 联网（SSRF 防护与 Python 侧同规则） ═══════════════

        register(
            ToolDef(
                "read_url", "读取一个网页并提取正文文本（仅 http/https，公网地址）。", "write", "读取网页", "net",
                schema("url" to ("string" to true))
            ) { args, _ ->
                val url = optString(args, "url")
                val guard = SsrfGuard.check(url) ?: throw ToolAbort(err("URL 不合法或指向内网/保留地址：$url"))
                val conn = guard.open()
                conn.connectTimeout = 15000
                conn.readTimeout = 30000
                try {
                    if (conn.responseCode !in 200..299) throw ToolAbort(err("HTTP ${conn.responseCode}"))
                    val contentType = conn.contentType ?: "text/html"
                    val text = conn.inputStream.use { String(it.readBytes(), charsetOf(contentType)) }
                    if (text.length > 2_000_000) throw ToolAbort(err("页面过大（>2MB）"))
                    JSONObject().put("ok", true).put("url", url).put("content", HtmlText.extract(text).take(20000))
                } finally {
                    conn.disconnect()
                }
            }
        )
        register(
            ToolDef(
                "web_search", "联网搜索（DuckDuckGo HTML 端点，无需 API Key）。", "write", "联网搜索", "net",
                schema("query" to ("string" to true), "limit" to ("integer" to false))
            ) { args, _ ->
                val q = optString(args, "query").trim()
                if (q.isEmpty()) throw ToolAbort(err("query 不能为空"))
                val limit = optInt(args, "limit", 5).coerceIn(1, 10)
                val conn = SsrfGuard.check("https://html.duckduckgo.com/html/?q=" +
                    java.net.URLEncoder.encode(q, "UTF-8")) ?: throw ToolAbort(err("搜索端点不可用"))
                val c = conn.open()
                c.connectTimeout = 15000
                c.readTimeout = 30000
                c.setRequestProperty("User-Agent", "Mozilla/5.0 (PocketMind)")
                try {
                    if (c.responseCode !in 200..299) throw ToolAbort(err("搜索失败：HTTP ${c.responseCode}"))
                    val html = c.inputStream.use { String(it.readBytes(), Charsets.UTF_8) }
                    val results = HtmlText.searchResults(html, limit)
                    JSONObject().put("ok", true).put("query", q).put("count", results.length()).put("results", results)
                } finally {
                    c.disconnect()
                }
            }
        )

        // ═══════════════ Termux 专属：显式不可用（不伪迁移） ═══════════════

        register(
            ToolDef(
                "run_shell", "执行一条 shell 命令。", "danger", "执行命令", "system",
                schema("command" to ("string" to true))
            ) { _, _ ->
                unavailable(
                    "run_shell",
                    "原生引擎没有 shell；需要 shell 时请安装 Termux 版（仓库 termux/install.sh）"
                )
            }
        )
        register(
            ToolDef(
                "share_text", "调起系统分享面板分享一段文字。", "write", "分享文本", "phone",
                schema("text" to ("string" to true))
            ) { args, env ->
                val text = optString(args, "text")
                if (text.isEmpty()) throw ToolAbort(err("text 不能为空"))
                val intent = android.content.Intent(android.content.Intent.ACTION_SEND).apply {
                    type = "text/plain"
                    putExtra(android.content.Intent.EXTRA_TEXT, text)
                    addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                env.context.startActivity(android.content.Intent.createChooser(intent, "分享").addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK))
                JSONObject().put("ok", true)
            }
        )
        register(
            ToolDef(
                "open_target", "用系统默认应用打开一个链接。", "write", "打开链接", "phone",
                schema("url" to ("string" to true))
            ) { args, env ->
                val url = optString(args, "url")
                if (!url.startsWith("http://") && !url.startsWith("https://")) {
                    throw ToolAbort(err("只支持 http/https 链接"))
                }
                val intent = android.content.Intent(android.content.Intent.ACTION_VIEW, android.net.Uri.parse(url))
                intent.addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
                env.context.startActivity(intent)
                JSONObject().put("ok", true).put("url", url)
            }
        )
    }

    // ---------- helpers ----------

    private fun DeviceEnv.storeRef(): com.pocket.agent.data.Store = store

    private fun streamOf(name: String): Int = when (name.lowercase(Locale.ROOT)) {
        "ring" -> AudioManager.STREAM_RING
        "notification" -> AudioManager.STREAM_NOTIFICATION
        "alarm" -> AudioManager.STREAM_ALARM
        "call" -> AudioManager.STREAM_VOICE_CALL
        "system" -> AudioManager.STREAM_SYSTEM
        else -> AudioManager.STREAM_MUSIC
    }

    private fun streamName(stream: Int): String = when (stream) {
        AudioManager.STREAM_RING -> "ring"
        AudioManager.STREAM_NOTIFICATION -> "notification"
        AudioManager.STREAM_ALARM -> "alarm"
        AudioManager.STREAM_VOICE_CALL -> "call"
        AudioManager.STREAM_SYSTEM -> "system"
        else -> "music"
    }

    private fun intToIp(ip: Int): String =
        if (ip == 0) "" else "${ip and 0xff}.${ip shr 8 and 0xff}.${ip shr 16 and 0xff}.${ip shr 24 and 0xff}"

    private fun charsetOf(contentType: String): java.nio.charset.Charset {
        val m = Regex("charset=([\\w-]+)", RegexOption.IGNORE_CASE).find(contentType)?.groupValues?.get(1)
        return try { java.nio.charset.Charset.forName(m ?: "UTF-8") } catch (_: Exception) { Charsets.UTF_8 }
    }
}

/** * SSRF 防护（与 Python 侧语义一致）：仅 http/https；解析后拒绝回环 / 私有 /
 * 保留网段；端口仅允许 80/443/8080/8443。返回已配置好的连接或 null。
 */
object SsrfGuard {
    val ALLOWED_PORTS = setOf(80, 443, 8080, 8443)

    fun check(url: String): GuardedConnection? {
        val u = try { URL(url) } catch (_: Exception) { return null }
        if (u.protocol != "http" && u.protocol != "https") return null
        // 默认端口按协议补齐；显式端口必须在白名单
        val defaultPort = if (u.protocol == "https") 443 else 80
        val port = if (u.port == -1) defaultPort else u.port
        if (port !in ALLOWED_PORTS) return null
        val host = u.host ?: return null
        val addrs = try { InetAddress.getAllByName(host) } catch (_: Exception) { return null }
        for (a in addrs) {
            if (a.isLoopbackAddress || a.isAnyLocalAddress || a.isLinkLocalAddress ||
                a.isSiteLocalAddress || a.isMulticastAddress
            ) return null
            val b = a.address
            if (b.size == 4) {
                val o1 = b[0].toInt() and 0xff
                // 198.18.0.0/15（基准测试保留）、100.64.0.0/10（CGNAT）、0.0.0.0/8
                if (o1 == 198 || o1 == 198 + 1 || o1 == 100 || o1 == 0) return null
                // 169.254 link-local 已由 isLinkLocalAddress 覆盖
            }
        }
        return GuardedConnection(u)
    }

    class GuardedConnection(private val url: URL) {
        fun open(): HttpURLConnection = url.openConnection() as HttpURLConnection
    }
}

/** 极简 HTML → 文本抽取与 DDG 结果解析（不引入第三方解析器）。 */
object HtmlText {
    fun extract(html: String): String {
        var t = html
            .replace(Regex("(?is)<(script|style|noscript|svg|head)[^>]*>.*?</\\1>"), " ")
            .replace(Regex("(?is)<!--.*?-->"), " ")
        t = Regex("(?i)<(br|/p|/div|/li|/h[1-6]|/tr)[^>]*>").replace(t, "\n")
        t = Regex("<[^>]+>").replace(t, " ")
        t = android.text.Html.fromHtml(t, android.text.Html.FROM_HTML_MODE_LEGACY).toString()
        return t.replace(Regex("[ \\t\\x0B\\f\\r]+"), " ").replace(Regex("\n{3,}"), "\n\n").trim()
    }

    fun searchResults(html: String, limit: Int): JSONArray {
        val arr = JSONArray()
        val re = Regex("(?is)<a[^>]+class=\"result__a\"[^>]*href=\"([^\"]+)\"[^>]*>(.*?)</a>")
        for (m in re.findAll(html)) {
            if (arr.length() >= limit) break
            var url = m.groupValues[1]
            // DDG 用 //duckduckgo.com/l/?uddg=<encoded> 做跳转，解出真实链接
            val uddg = Regex("uddg=([^&]+)").find(url)?.groupValues?.get(1)
            if (uddg != null) url = java.net.URLDecoder.decode(uddg, "UTF-8")
            val title = android.text.Html.fromHtml(m.groupValues[2], android.text.Html.FROM_HTML_MODE_LEGACY)
                .toString().trim()
            if (title.isEmpty() || !url.startsWith("http")) continue
            arr.put(JSONObject().put("title", title).put("url", url))
        }
        return arr
    }
}

/** 进程级 TTS 单例：引擎生命周期内复用，服务销毁时 shutdown。 */
object TtsPool {
    @Volatile private var tts: TextToSpeech? = null
    @Volatile private var ready = false

    @SuppressLint("SuspendTrampolineSuspension")
    fun speak(context: Context, text: String) {
        val existing = tts
        if (existing != null && ready) {
            existing.speak(text, TextToSpeech.QUEUE_ADD, null, "pm-${System.nanoTime()}")
            return
        }
        synchronized(this) {
            if (tts == null) {
                tts = TextToSpeech(context.applicationContext) { status ->
                    ready = status == TextToSpeech.SUCCESS
                    if (ready) {
                        tts?.language = Locale.getDefault()
                        tts?.speak(text, TextToSpeech.QUEUE_ADD, null, "pm-${System.nanoTime()}")
                    }
                }
            }
        }
    }

    fun shutdown() {
        synchronized(this) {
            tts?.stop()
            tts?.shutdown()
            tts = null
            ready = false
        }
    }
}
