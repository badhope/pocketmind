package com.pocket.agent.runtime

import com.pocket.agent.data.Store
import org.json.JSONArray
import org.json.JSONObject

/**
 * 预设配方：与仓库 `config/recipes.json` 同源同内容。
 *
 * 为什么不运行时读 assets 里的 JSON：配方是产品预设（4 条），随版本走。
 * 打进代码可以避免「assets 目录没同步导致配方为空」的静默故障，
 * 也让 check_version 那类门禁能直接比对两边字段。改动时两处同步。
 */
object Recipes {

    val ALL: List<JSONObject> = listOf(
        JSONObject()
            .put("id", "battery_check").put("name", "低电量提醒")
            .put("description", "每 30 分钟巡检一次，电量低于 20% 时提醒充电")
            .put("trigger_type", "interval").put("expr", "1800")
            .put("message", "查看电池电量，如果低于20%就发通知提醒充电")
            .put("condition", "battery < 20").put("enabled", true),
        JSONObject()
            .put("id", "daily_summary").put("name", "每日总结")
            .put("description", "每天早上 8 点总结昨天的对话和待办")
            .put("trigger_type", "cron").put("expr", "0 8 * * *")
            .put("message", "总结一下昨天的对话和待办")
            .put("condition", "").put("enabled", true),
        JSONObject()
            .put("id", "weather").put("name", "每日天气")
            .put("description", "每天早上 7:30 播报今天天气")
            .put("trigger_type", "cron").put("expr", "30 7 * * *")
            .put("message", "查一下今天天气")
            .put("condition", "").put("enabled", false),
        JSONObject()
            .put("id", "lunch_reminder").put("name", "午饭提醒")
            .put("description", "每个工作日 12 点提醒吃饭")
            .put("trigger_type", "cron").put("expr", "0 12 * * 1-5")
            .put("message", "发个通知提醒我该吃午饭了")
            .put("condition", "").put("enabled", false),
    )

    fun get(id: String): JSONObject? {
        val r = ALL.firstOrNull { it.optString("id") == id } ?: return null
        return JSONObject(r.toString())
    }

    /**
     * 列表 + applied 状态。
     *
     * 「已应用」的判定：存在同名且 message 相同的任务。用内容而不是 id 比对，
     * 是因为应用配方时会新建一个独立 job（用户可改），重复应用应被识别出来，
     * 而不是每次点击都堆一条重复任务。
     */
    fun list(store: Store): JSONArray {
        val jobs = store.listJobs()
        val out = JSONArray()
        for (r in ALL) {
            val copy = JSONObject(r.toString())
            var applied = false
            for (i in 0 until jobs.length()) {
                val j = jobs.optJSONObject(i) ?: continue
                if (j.optString("name") == r.optString("name") &&
                    j.optString("message") == r.optString("message")
                ) {
                    applied = true
                    break
                }
            }
            copy.put("applied", applied)
            out.put(copy)
        }
        return out
    }
}
