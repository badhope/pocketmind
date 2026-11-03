package com.pocket.agent.llm

import com.pocket.agent.data.SettingsStore
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedReader
import java.io.IOException
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.URL
import java.nio.charset.StandardCharsets

/**
 * OpenAI 兼容流式客户端（HttpURLConnection，无第三方依赖）。
 *
 * 契约与 Python litellm_client 对齐：
 *  - POST {base_url}/chat/completions，stream=true，流式解析 `data:` 帧；
 *  - content 增量 → onText；tool_calls 分片按 index 聚合（id/name 只在首片出现）；
 *  - 结束帧的 usage 若端点未回报则 estimated 标记；
 *  - 非 200 直接抛 LlmException（含端点返回的错误文本）。
 */
open class LlmClient(val cfg: SettingsStore.LlmConfig) {

    class ToolCallAccumulator {
        val parts = HashMap<Int, StringBuilder>()
        val names = HashMap<Int, String>()
        val ids = HashMap<Int, String>()

        fun apply(delta: JSONObject) {
            val arr = delta.optJSONArray("tool_calls") ?: return
            for (i in 0 until arr.length()) {
                val tc = arr.optJSONObject(i) ?: continue
                val index = tc.optInt("index", i)
                tc.optString("id", "").takeIf { it.isNotEmpty() }?.let { ids[index] = it }
                tc.optJSONObject("function")?.let { fn ->
                    fn.optString("name", "").takeIf { it.isNotEmpty() }?.let { names[index] = it }
                    fn.optString("arguments", "").takeIf { it.isNotEmpty() }?.let {
                        parts.getOrPut(index) { StringBuilder() }.append(it)
                    }
                }
            }
        }

        /** 聚合完成：转成 Python/前端契约的 call 列表 [{id, name, arguments}]。 */
        fun build(): List<Triple<String, String, String>> {
            val all = (parts.keys + names.keys + ids.keys).toSortedSet()
            return all.map { i ->
                Triple(
                    ids[i] ?: "call_${i}_${System.nanoTime().toString(36)}",
                    names[i] ?: "",
                    parts[i]?.toString() ?: "{}",
                )
            }.filter { it.second.isNotEmpty() }
        }
    }

    interface StreamCallbacks {
        fun onText(delta: String) {}
        fun onReasoning(delta: String) {}
        fun onDone(usage: Usage?, toolCalls: List<Triple<String, String, String>>) {}
    }

    data class Usage(val promptTokens: Int, val completionTokens: Int, val estimated: Boolean) {
        val totalTokens: Int get() = promptTokens + completionTokens
        fun json(): JSONObject =
            JSONObject().put("prompt_tokens", promptTokens).put("completion_tokens", completionTokens)
                .put("total_tokens", totalTokens).put("estimated", estimated)
    }

    class LlmException(message: String) : IOException(message)

    /** 流式对话。返回聚合后的 tool_calls；文本与思考增量通过回调推送。 */
    open fun streamChat(
        messages: JSONArray,
        tools: JSONArray?,
        maxDepthTokens: Int = cfg.maxTokens,
        callbacks: StreamCallbacks,
        abortSignal: () -> Boolean,
    ): List<Triple<String, String, String>> {
        val body = JSONObject()
            .put("model", cfg.model)
            .put("messages", messages)
            .put("stream", true)
            .put("temperature", cfg.temperature)
            .put("max_tokens", maxDepthTokens)
            .put("stream_options", JSONObject().put("include_usage", true))
        if (tools != null && tools.length() > 0) body.put("tools", tools)

        val conn = open("/chat/completions")
        try {
            conn.outputStream.use { it.write(body.toString().toByteArray(StandardCharsets.UTF_8)) }
            val code = conn.responseCode
            if (code != 200) {
                val err = conn.errorStream?.bufferedReader()?.use { it.readText() }?.take(500) ?: "HTTP $code"
                throw LlmException("端点返回 $code：$err")
            }
            val reader = BufferedReader(InputStreamReader(conn.inputStream, StandardCharsets.UTF_8))
            val acc = ToolCallAccumulator()
            var usage: Usage? = null
            var finishReason: String? = null
            reader.forEachLine { raw ->
                if (abortSignal()) return@forEachLine
                val line = raw.trim()
                if (line.isEmpty() || !(line.startsWith("data:"))) return@forEachLine
                val payload = line.removePrefix("data:").trim()
                if (payload == "[DONE]") return@forEachLine
                val frame = try { JSONObject(payload) } catch (_: Exception) { return@forEachLine }
                frame.optJSONObject("usage")?.let { u ->
                    usage = Usage(
                        u.optInt("prompt_tokens", 0), u.optInt("completion_tokens", 0), false
                    )
                }
                val choice = frame.optJSONArray("choices")?.optJSONObject(0) ?: return@forEachLine
                choice.optString("finish_reason", "").takeIf { it.isNotEmpty() }?.let { finishReason = it }
                val delta = choice.optJSONObject("delta") ?: return@forEachLine
                delta.optString("reasoning_content", "").takeIf { it.isNotEmpty() }?.let { callbacks.onReasoning(it) }
                delta.optString("content", "").takeIf { it.isNotEmpty() }?.let { callbacks.onText(it) }
                acc.apply(delta)
            }
            if (abortSignal()) throw LlmException("已停止")
            callbacks.onDone(usage, acc.build())
            return acc.build()
        } finally {
            conn.disconnect()
        }
    }

    /** 非流式最小请求（连接测试 / 会话命名）。返回首个文本回复。 */
    open fun complete(messages: JSONArray, maxTokens: Int = 20, temperature: Double = 0.2): String {
        val body = JSONObject()
            .put("model", cfg.model)
            .put("messages", messages)
            .put("max_tokens", maxTokens)
            .put("temperature", temperature)
        val conn = open("/chat/completions")
        try {
            conn.outputStream.use { it.write(body.toString().toByteArray(StandardCharsets.UTF_8)) }
            val code = conn.responseCode
            if (code != 200) {
                val err = conn.errorStream?.bufferedReader()?.use { it.readText() }?.take(500) ?: "HTTP $code"
                throw LlmException("端点返回 $code：$err")
            }
            val resp = JSONObject(conn.inputStream.bufferedReader().use { it.readText() })
            return resp.optJSONArray("choices")?.optJSONObject(0)
                ?.optJSONObject("message")?.optString("content").orEmpty()
        } finally {
            conn.disconnect()
        }
    }

    private fun open(path: String): HttpURLConnection {
        val conn = URL(cfg.baseUrl + path).openConnection() as HttpURLConnection
        conn.requestMethod = "POST"
        conn.connectTimeout = 15_000
        conn.readTimeout = 300_000
        conn.doOutput = true
        conn.setRequestProperty("Content-Type", "application/json")
        conn.setRequestProperty("Authorization", "Bearer ${cfg.apiKey}")
        conn.setRequestProperty("Accept", "text/event-stream, application/json")
        return conn
    }
}
