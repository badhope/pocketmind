package com.pocket.agent.data

import android.content.Context
import org.json.JSONObject
import java.io.File

/**
 * 配置存储：app-private config.json（0600 由 Android 私有目录保证）。
 *
 * 与 Python 侧的契约：
 *  - GET 返回打码后的 api_key（占位符含 "****"）；
 *  - PUT 合并语义：字段级覆盖，api_key 为空串或打码占位符时**不覆盖**已存值；
 *  - llm_ready = provider/base_url/model/api_key 全部非空。
 */
class SettingsStore(context: Context) {

    private val file: File = File(context.filesDir, "config.json")
    private var data: JSONObject = load()

    @Synchronized private fun load(): JSONObject {
        if (!file.exists()) return JSONObject(DEFAULTS)
        val parsed = try {
            JSONObject(file.readText())
        } catch (_: Exception) {
            return JSONObject(DEFAULTS)
        }
        // 内存里始终存明文，只在落盘时加密；这样调用方（LlmClient 等）不用关心。
        parsed.optJSONObject("llm")?.let { llm ->
            val stored = llm.optString("api_key")
            if (stored.isNotEmpty()) llm.put("api_key", SecretsVault.open(stored))
        }
        return parsed
    }

    @Synchronized fun snapshot(): JSONObject {
        val out = JSONObject(data.toString())
        out.put("llm", maskLlm(out.optJSONObject("llm") ?: JSONObject()))
        // server 块：native 引擎没有访问令牌（cookie 会话鉴权），固定返回空配置
        val server = out.optJSONObject("server") ?: JSONObject()
        server.put("token", "")
        out.put("server", server)
        return out
    }

    /** LLM 是否已配置完整（用于 /api/health 的 ready 与 chat 的快速失败）。 */
    @Synchronized fun llmReady(): Boolean {
        val llm = data.optJSONObject("llm") ?: return false
        return llm.optString("api_key").isNotEmpty() &&
            llm.optString("base_url").isNotEmpty() &&
            llm.optString("model").isNotEmpty()
    }

    @Synchronized fun llmMissing(): List<String> {
        val llm = data.optJSONObject("llm") ?: JSONObject()
        val missing = ArrayList<String>()
        if (llm.optString("base_url").isEmpty()) missing.add("Base URL")
        if (llm.optString("model").isEmpty()) missing.add("模型名")
        if (llm.optString("api_key").isEmpty()) missing.add("API Key")
        return missing
    }

    @Synchronized fun llmConfig(): LlmConfig {
        val llm = data.optJSONObject("llm") ?: JSONObject()
        return LlmConfig(
            baseUrl = llm.optString("base_url").trimEnd('/'),
            apiKey = llm.optString("api_key"),
            model = llm.optString("model"),
            temperature = llm.optDouble("temperature", 0.7),
            maxTokens = llm.optInt("max_tokens", 2048),
        )
    }

    @Synchronized fun permissionMode(): String {
        val m = data.optString("permission_mode", "approve")
        return if (m in setOf("auto", "approve", "chat")) m else "approve"
    }

    @Synchronized fun agentMode(): String {
        val m = data.optString("agent_mode", "act")
        return if (m in setOf("plan", "act")) m else "act"
    }

    /**
     * 上下文预算（与 Python config.py 的 context 段同名字段）。
     *
     * 为什么原生必须也读它：工具结果原样进上下文时，一次 `run_shell` 的 30KB
     * 输出 ≈ 7500 token，会在之后几十轮里每轮重发一次。这不是"体验问题"，
     * 是按量计费的真金白银。budget_tokens=0 表示未配置，走固定硬截断。
     */
    @Synchronized fun contextCfg(): ContextCfg {
        val c = data.optJSONObject("context") ?: JSONObject()
        return ContextCfg(
            budgetTokens = c.optInt("budget_tokens", 0).coerceAtLeast(0),
            autoCompress = c.optBoolean("auto_compress", false),
            compressThreshold = c.optDouble("compress_threshold", 0.8).coerceIn(0.1, 0.95),
        )
    }

    class ContextCfg(
        val budgetTokens: Int,
        val autoCompress: Boolean,
        val compressThreshold: Double,
    )

    /** 合并保存；返回保存后的打码快照。api_key 占位符不覆盖已存值。 */
    @Synchronized fun save(patch: JSONObject): JSONObject {
        mergeInto(data, patch)
        // 范围收敛
        val llm = data.optJSONObject("llm") ?: JSONObject()
        data.put("llm", llm)
        val temp = llm.optDouble("temperature", 0.7)
        llm.put("temperature", temp.coerceIn(0.0, 2.0))
        llm.put("max_tokens", llm.optInt("max_tokens", 2048).coerceIn(64, 32768))
        val mode = data.optString("permission_mode", "approve")
        if (mode !in setOf("auto", "approve", "chat")) data.put("permission_mode", "approve")
        val amode = data.optString("agent_mode", "act")
        if (amode !in setOf("plan", "act")) data.put("agent_mode", "act")
        persist()
        return snapshot()
    }

    /**
     * 落盘：API Key 经 Android Keystore 加密后再写。
     *
     * 只在写盘这一刻加密，内存里保持明文——LlmClient / llmReady / 打码快照
     * 全都按明文工作，加密是纯粹的存储层关注点。
     */
    private fun persist() {
        file.parentFile?.mkdirs()
        val onDisk = JSONObject(data.toString())
        onDisk.optJSONObject("llm")?.let { llm ->
            val plain = llm.optString("api_key")
            if (plain.isNotEmpty()) llm.put("api_key", SecretsVault.seal(plain))
        }
        // 先写临时文件再 rename：写一半断电不会留下半个 JSON 导致配置全丢。
        val tmp = File(file.parentFile, file.name + ".tmp")
        tmp.writeText(onDisk.toString(2))
        if (!tmp.renameTo(file)) {
            file.writeText(onDisk.toString(2))
            tmp.delete()
        }
    }

    private fun maskLlm(llm: JSONObject): JSONObject {
        val key = llm.optString("api_key")
        if (key.isEmpty()) {
            llm.put("api_key", "")
        } else if (!key.contains("****")) {
            val head = key.take(4)
            val tail = if (key.length > 8) key.takeLast(4) else ""
            llm.put("api_key", "$head****$tail")
        }
        return llm
    }

    private fun mergeInto(target: JSONObject, patch: JSONObject) {
        for (key in patch.keys()) {
            val v = patch.opt(key) ?: continue
            if (v is JSONObject) {
                val sub = target.optJSONObject(key) ?: JSONObject()
                mergeInto(sub, v)
                target.put(key, sub)
            } else if (key == "api_key") {
                // 打码占位符 / 空串不覆盖（前端把 GET 回来的值原样 PUT 回来是正常操作）
                val s = v as? String ?: continue
                if (s.isNotEmpty() && !s.contains("****")) target.put(key, s)
            } else {
                target.put(key, v)
            }
        }
    }

    data class LlmConfig(
        val baseUrl: String,
        val apiKey: String,
        val model: String,
        val temperature: Double,
        val maxTokens: Int,
    )

    companion object {
        val DEFAULTS: String = """
            {
              "llm": {"provider": "", "base_url": "", "api_key": "", "model": "",
                      "temperature": 0.7, "max_tokens": 2048},
              "permission_mode": "approve",
              "agent_mode": "act",
              "server": {"approval_timeout": 120},
              "context": {"budget_tokens": 0, "auto_compress": false, "compress_threshold": 0.8}
            }
        """.trimIndent()

        /** 内置提供商预设（与 Python config.py 的 PRESETS 对齐，前端「设置/引导」共用）。 */
        val PROVIDERS: String = """
            [
              {"id": "doubao", "label": "豆包 · 火山方舟",
               "base_url": "https://ark.cn-beijing.volces.com/api/v3",
               "model": "doubao-seed-1-6-250615",
               "note": "火山方舟控制台创建推理接入点后，用接入点 ID 作为 model"},
              {"id": "deepseek", "label": "DeepSeek",
               "base_url": "https://api.deepseek.com", "model": "deepseek-flash",
               "note": "官方当前默认模型；legacy 名 deepseek-chat 已弃用"},
              {"id": "qwen", "label": "通义千问",
               "base_url": "https://dashscope.aliyuncs.com/compatible-mode/v1",
               "model": "qwen-plus", "note": "阿里云百炼开通后使用"},
              {"id": "kimi", "label": "Kimi · 月之暗面",
               "base_url": "https://api.moonshot.cn/v1", "model": "kimi-k3",
               "note": "官方当前默认 kimi-k3"},
              {"id": "glm", "label": "智谱 GLM",
               "base_url": "https://open.bigmodel.cn/api/paas/v4", "model": "glm-4-flash",
               "note": "glm-4-flash 有免费额度；最新为 glm-5.3"},
              {"id": "siliconflow", "label": "硅基流动 SiliconFlow",
               "base_url": "https://api.siliconflow.cn/v1",
               "model": "deepseek-ai/DeepSeek-V3", "note": ""},
              {"id": "custom", "label": "自定义 OpenAI 兼容端点",
               "base_url": "", "model": "", "note": "填你自己的 base_url 与模型名"}
            ]
        """.trimIndent()
    }
}
