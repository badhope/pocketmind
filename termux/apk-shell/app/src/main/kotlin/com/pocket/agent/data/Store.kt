package com.pocket.agent.data

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import org.json.JSONArray
import org.json.JSONObject

/**
 * 本机存储：会话 / 消息 / 知识库 / 任务清单，全部落 app-private SQLite。
 *
 * 与 Python agentd 的数据契约（前端 restore() 依赖，见 partReducer.ts）：
 *  - assistant 消息的 meta.tool_calls = [{id, name, args}]（args 是 JSON 字符串）；
 *  - tool 消息的 content = 工具结果 JSON 文本，meta = {tool_call_id, name, status}；
 *  - usage 落在 assistant 消息的 meta.usage。
 * role ∈ user / assistant / tool / system。
 */
class Store(context: Context) : SQLiteOpenHelper(context, DB_NAME, null, DB_VERSION) {

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL(
            "CREATE TABLE sessions(" +
                "id TEXT PRIMARY KEY, title TEXT NOT NULL DEFAULT '新会话', " +
                "created_at REAL NOT NULL, updated_at REAL NOT NULL, " +
                "archived_upto INTEGER NOT NULL DEFAULT 0, pinned INTEGER NOT NULL DEFAULT 0)"
        )
        db.execSQL(
            "CREATE TABLE messages(" +
                "id INTEGER PRIMARY KEY AUTOINCREMENT, session_id TEXT NOT NULL, " +
                "role TEXT NOT NULL, content TEXT NOT NULL, meta TEXT NOT NULL DEFAULT '{}', " +
                "created_at REAL NOT NULL)"
        )
        db.execSQL("CREATE INDEX idx_messages_session ON messages(session_id, id)")
        db.execSQL(
            "CREATE TABLE knowledge(" +
                "id INTEGER PRIMARY KEY AUTOINCREMENT, title TEXT NOT NULL, " +
                "content TEXT NOT NULL, created_at REAL NOT NULL)"
        )
        db.execSQL(
            "CREATE TABLE todos(" +
                "session_id TEXT NOT NULL, position INTEGER NOT NULL, " +
                "content TEXT NOT NULL, status TEXT NOT NULL, updated_at REAL NOT NULL, " +
                "PRIMARY KEY(session_id, position))"
        )
        db.execSQL(
            "CREATE TABLE facts(" +
                "id INTEGER PRIMARY KEY AUTOINCREMENT, content TEXT NOT NULL, created_at REAL NOT NULL)"
        )
        db.execSQL(
            "CREATE TABLE jobs(" +
                "id TEXT PRIMARY KEY, name TEXT NOT NULL, trigger_type TEXT NOT NULL, " +
                "expr TEXT NOT NULL, message TEXT NOT NULL, session_id TEXT NOT NULL DEFAULT '', " +
                "condition TEXT NOT NULL DEFAULT '', enabled INTEGER NOT NULL DEFAULT 1, " +
                "created_at REAL NOT NULL, last_run REAL NOT NULL DEFAULT 0)"
        )
        db.execSQL(
            "CREATE TABLE checkpoints(" +
                "id INTEGER PRIMARY KEY AUTOINCREMENT, session_id TEXT NOT NULL, " +
                "tool_call_id TEXT NOT NULL, tool_name TEXT NOT NULL, path TEXT NOT NULL, " +
                "backup TEXT NOT NULL, kind TEXT NOT NULL, undone INTEGER NOT NULL DEFAULT 0, " +
                "created_at REAL NOT NULL)"
        )
        db.execSQL("CREATE INDEX idx_ckpt_call ON checkpoints(session_id, tool_call_id)")
        db.execSQL(
            "CREATE TABLE archives(" +
                "id INTEGER PRIMARY KEY AUTOINCREMENT, session_id TEXT NOT NULL, " +
                "content TEXT NOT NULL, created_at REAL NOT NULL)"
        )
        db.execSQL("CREATE INDEX idx_archives_session ON archives(session_id, id)")
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        // v1 → v2：定时任务（原生调度器）与撤销检查点两处功能各加一张表。
        if (oldVersion < 2) {
            db.execSQL(
                "CREATE TABLE IF NOT EXISTS jobs(" +
                    "id TEXT PRIMARY KEY, name TEXT NOT NULL, trigger_type TEXT NOT NULL, " +
                    "expr TEXT NOT NULL, message TEXT NOT NULL, session_id TEXT NOT NULL DEFAULT '', " +
                    "condition TEXT NOT NULL DEFAULT '', enabled INTEGER NOT NULL DEFAULT 1, " +
                    "created_at REAL NOT NULL, last_run REAL NOT NULL DEFAULT 0)"
            )
            db.execSQL(
                "CREATE TABLE IF NOT EXISTS checkpoints(" +
                    "id INTEGER PRIMARY KEY AUTOINCREMENT, session_id TEXT NOT NULL, " +
                    "tool_call_id TEXT NOT NULL, tool_name TEXT NOT NULL, path TEXT NOT NULL, " +
                    "backup TEXT NOT NULL, kind TEXT NOT NULL, undone INTEGER NOT NULL DEFAULT 0, " +
                    "created_at REAL NOT NULL)"
            )
            db.execSQL("CREATE INDEX IF NOT EXISTS idx_ckpt_call ON checkpoints(session_id, tool_call_id)")
        }
        // v2 → v3：会话压缩需要归档表（滚动摘要），否则 compact 只能是假动作。
        if (oldVersion < 3) {
            db.execSQL(
                "CREATE TABLE IF NOT EXISTS archives(" +
                    "id INTEGER PRIMARY KEY AUTOINCREMENT, session_id TEXT NOT NULL, " +
                    "content TEXT NOT NULL, created_at REAL NOT NULL)"
            )
            db.execSQL("CREATE INDEX IF NOT EXISTS idx_archives_session ON archives(session_id, id)")
        }
    }

    // ---------- 会话 ----------

    fun createSession(title: String = "新会话"): JSONObject {
        val id = newId()
        val now = now()
        val cv = android.content.ContentValues().apply {
            put("id", id); put("title", title); put("created_at", now); put("updated_at", now)
        }
        writableDatabase.insert("sessions", null, cv)
        return JSONObject().put("id", id).put("title", title).put("message_count", 0)
            .put("created_at", now).put("updated_at", now).put("pinned", 0)
    }

    fun listSessions(): JSONArray {
        val out = JSONArray()
        readableDatabase.rawQuery(
            "SELECT s.id, s.title, s.created_at, s.updated_at, s.pinned, " +
                "(SELECT COUNT(*) FROM messages m WHERE m.session_id = s.id) AS cnt " +
                "FROM sessions s ORDER BY s.pinned DESC, s.updated_at DESC", null
        ).use { c ->
            while (c.moveToNext()) {
                out.put(
                    JSONObject()
                        .put("id", c.getString(0)).put("title", c.getString(1))
                        .put("created_at", c.getDouble(2)).put("updated_at", c.getDouble(3))
                        .put("pinned", c.getInt(4)).put("message_count", c.getInt(5))
                )
            }
        }
        return out
    }

    fun getSession(id: String): JSONObject? =
        readableDatabase.rawQuery(
            "SELECT id, title, created_at, updated_at, pinned, " +
                "(SELECT COUNT(*) FROM messages m WHERE m.session_id = s.id) AS cnt " +
                "FROM sessions s WHERE id = ?", arrayOf(id)
        ).use { c ->
            if (c.moveToFirst()) {
                JSONObject()
                    .put("id", c.getString(0)).put("title", c.getString(1))
                    .put("created_at", c.getDouble(2)).put("updated_at", c.getDouble(3))
                    .put("pinned", c.getInt(4)).put("message_count", c.getInt(5))
            } else null
        }

    fun touchSession(id: String, title: String? = null) {
        val cv = android.content.ContentValues().apply { put("updated_at", now()) }
        if (!title.isNullOrEmpty()) cv.put("title", title.take(120))
        writableDatabase.update("sessions", cv, "id = ?", arrayOf(id))
    }

    fun updateSession(id: String, title: String?, pinned: Boolean?): Boolean {
        val cv = android.content.ContentValues()
        title?.let { cv.put("title", it.take(120)) }
        pinned?.let { cv.put("pinned", if (it) 1 else 0) }
        if (cv.size() == 0) return getSession(id) != null
        return writableDatabase.update("sessions", cv, "id = ?", arrayOf(id)) > 0
    }

    /**
     * 删除会话及其全部从属数据。
     *
     * 只删 messages/todos 会留下三类残留：archives（滚动摘要）、checkpoints
     * （撤销备份的磁盘文件）、jobs（还会继续触发并重建会话）。前两者是
     * 「用户以为删了、磁盘上还在」，后两者是「删了又复活」——都必须一起清。
     *
     * 返回被删掉的备份文件路径，由调用方负责删盘（Store 不碰文件系统之外的路径）。
     */
    fun deleteSession(id: String): Boolean {
        val db = writableDatabase
        db.beginTransaction()
        return try {
            db.delete("messages", "session_id = ?", arrayOf(id))
            db.delete("todos", "session_id = ?", arrayOf(id))
            db.delete("archives", "session_id = ?", arrayOf(id))
            db.delete("jobs", "session_id = ?", arrayOf(id))
            // 检查点记录先取出备份路径，交给上层删文件（见 checkpointBackupsOf）
            db.delete("checkpoints", "session_id = ?", arrayOf(id))
            val n = db.delete("sessions", "id = ?", arrayOf(id))
            db.setTransactionSuccessful()
            n > 0
        } finally {
            db.endTransaction()
        }
    }

    /** 删除前取出该会话所有备份文件的绝对路径，供上层删盘。 */
    fun checkpointBackupsOf(sessionId: String): List<String> {
        val out = ArrayList<String>()
        readableDatabase.rawQuery(
            "SELECT backup FROM checkpoints WHERE session_id = ?", arrayOf(sessionId)
        ).use { c -> while (c.moveToNext()) out.add(c.getString(0)) }
        return out
    }

    // ---------- 消息 ----------

    fun addMessage(sessionId: String, role: String, content: String, meta: JSONObject = JSONObject()): JSONObject {
        val cv = android.content.ContentValues().apply {
            put("session_id", sessionId); put("role", role); put("content", content)
            put("meta", meta.toString()); put("created_at", now())
        }
        val id = writableDatabase.insert("messages", null, cv)
        touchSession(sessionId)
        return JSONObject().put("id", id).put("role", role).put("content", content)
            .put("meta", meta)
    }

    fun getMessages(sessionId: String, limit: Int = 400): JSONArray {
        // 与 Python 侧一致：按 id 倒序取最近 limit 条再翻回正序，
        // 保证超长会话的最新消息不丢。
        val rows = ArrayList<JSONObject>()
        readableDatabase.rawQuery(
            "SELECT id, role, content, meta, created_at FROM messages " +
                "WHERE session_id = ? ORDER BY id DESC LIMIT ?",
            arrayOf(sessionId, limit.toString())
        ).use { c ->
            while (c.moveToNext()) {
                val meta = try { JSONObject(c.getString(3)) } catch (_: Exception) { JSONObject() }
                rows.add(
                    JSONObject()
                        .put("id", c.getLong(0)).put("role", c.getString(1))
                        .put("content", c.getString(2)).put("meta", meta)
                        .put("created_at", c.getDouble(4))
                )
            }
        }
        rows.reverse()
        val out = JSONArray()
        rows.forEach { out.put(it) }
        return out
    }

    /** 截断：删除 id >= fromId 的全部消息（前端「编辑重发 / 重新生成」用）。 */
    fun truncateFrom(sessionId: String, fromId: Long): Int =
        writableDatabase.delete("messages", "session_id = ? AND id >= ?", arrayOf(sessionId, fromId.toString()))

    // ---------- 任务清单 ----------

    fun getTodos(sessionId: String): JSONArray {
        val out = JSONArray()
        readableDatabase.rawQuery(
            "SELECT content, status FROM todos WHERE session_id = ? ORDER BY position", arrayOf(sessionId)
        ).use { c ->
            while (c.moveToNext()) {
                out.put(JSONObject().put("content", c.getString(0)).put("status", c.getString(1)))
            }
        }
        return out
    }

    fun setTodos(sessionId: String, todos: JSONArray) {
        val db = writableDatabase
        db.beginTransaction()
        try {
            db.delete("todos", "session_id = ?", arrayOf(sessionId))
            for (i in 0 until todos.length()) {
                val t = todos.optJSONObject(i) ?: continue
                val cv = android.content.ContentValues().apply {
                    put("session_id", sessionId); put("position", i)
                    put("content", t.optString("content").take(200))
                    put("status", t.optString("status", "pending")); put("updated_at", now())
                }
                db.insert("todos", null, cv)
            }
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
    }

    fun clearTodos(sessionId: String) {
        writableDatabase.delete("todos", "session_id = ?", arrayOf(sessionId))
    }

    // ---------- 知识库 ----------

    fun kbAdd(title: String, content: String): Long {
        val cv = android.content.ContentValues().apply {
            put("title", title.take(120)); put("content", content.take(20000)); put("created_at", now())
        }
        return writableDatabase.insert("knowledge", null, cv)
    }

    fun kbList(): JSONArray {
        val out = JSONArray()
        readableDatabase.rawQuery("SELECT id, title, length(content), created_at FROM knowledge ORDER BY id DESC", null)
            .use { c ->
                while (c.moveToNext()) {
                    out.put(
                        JSONObject().put("id", c.getLong(0)).put("title", c.getString(1))
                            .put("chars", c.getInt(2)).put("created_at", c.getDouble(3))
                    )
                }
            }
        return out
    }

    /** 关键词检索：LIKE 子串（v1 不依赖 FTS，行为与 Python 的 LIKE 回退路径一致）。 */
    fun kbSearch(query: String, limit: Int = 5): JSONArray {
        val out = JSONArray()
        val words = query.trim().split(Regex("\\s+")).filter { it.isNotEmpty() }.take(6)
        if (words.isEmpty()) return out
        val where = words.joinToString(" OR ") { "(title LIKE ? OR content LIKE ?)" }
        val args = words.flatMap { listOf("%$it%", "%$it%") }.toTypedArray()
        readableDatabase.rawQuery(
            "SELECT id, title, content FROM knowledge WHERE $where ORDER BY id DESC LIMIT ?",
            args + limit.toString()
        ).use { c ->
            while (c.moveToNext()) {
                out.put(
                    JSONObject().put("id", c.getLong(0)).put("title", c.getString(1))
                        .put("content", c.getString(2).take(2000))
                )
            }
        }
        return out
    }

    fun kbDelete(id: Long): Boolean = writableDatabase.delete("knowledge", "id = ?", arrayOf(id.toString())) > 0

    // ---------- 长期事实（remember_fact） ----------

    fun addFact(content: String): Long {
        val cv = android.content.ContentValues().apply { put("content", content.take(500)); put("created_at", now()) }
        return writableDatabase.insert("facts", null, cv)
    }

    fun listFacts(): JSONArray {
        val out = JSONArray()
        readableDatabase.rawQuery("SELECT id, content, created_at FROM facts ORDER BY id DESC LIMIT 200", null).use { c ->
            while (c.moveToNext()) {
                out.put(JSONObject().put("id", c.getLong(0)).put("content", c.getString(1)).put("created_at", c.getDouble(2)))
            }
        }
        return out
    }

    // ---------- 导出 / 导入 ----------

    fun exportAll(): JSONObject {
        val sessions = JSONArray()
        val ids = ArrayList<String>()
        readableDatabase.rawQuery("SELECT id FROM sessions ORDER BY id", null).use { c ->
            while (c.moveToNext()) ids.add(c.getString(0))
        }
        for (id in ids) {
            val s = getSession(id) ?: continue
            val sObj = JSONObject()
                .put("id", id).put("title", s.optString("title"))
                .put("created_at", s.optDouble("created_at")).put("archived_upto", 0)
                .put("pinned", s.optInt("pinned", 0))
            sObj.put("messages", getMessages(id, 1000))
            sObj.put("todos", getTodos(id))
            sessions.put(sObj)
        }
        return JSONObject()
            .put("app", "口袋智灵")
            .put("version", "1.4.0-native")
            .put("format_version", 1)
            .put("sessions", sessions)
            .put("knowledge", kbList())
    }

    /** 合并导入：同 id 会话跳过、知识库按 (title, content) 去重。返回统计。 */
    fun importAll(payload: JSONObject): JSONObject {
        var sessions = 0
        var skipped = 0
        var messages = 0
        var knowledge = 0
        val arr = payload.optJSONArray("sessions") ?: JSONArray()
        for (i in 0 until arr.length()) {
            val s = arr.optJSONObject(i) ?: continue
            val sid = s.optString("id").trim()
            if (sid.isEmpty()) continue
            if (getSession(sid) != null) {
                skipped += 1
                continue
            }
            val cv = android.content.ContentValues().apply {
                put("id", sid)
                put("title", s.optString("title", "导入的会话").take(120))
                put("created_at", s.optDouble("created_at", now()))
                put("updated_at", s.optDouble("created_at", now()))
                put("archived_upto", s.optInt("archived_upto", 0))
                put("pinned", s.optInt("pinned", 0))
            }
            writableDatabase.insert("sessions", null, cv)
            val msgs = s.optJSONArray("messages") ?: JSONArray()
            for (j in 0 until msgs.length()) {
                val m = msgs.optJSONObject(j) ?: continue
                val role = m.optString("role", "assistant")
                if (role !in setOf("user", "assistant", "tool", "system")) continue
                val meta = m.optJSONObject("meta") ?: JSONObject()
                addMessage(sid, role, m.optString("content"), meta)
                messages += 1
            }
            val todos = s.optJSONArray("todos")
            if (todos != null && todos.length() > 0) setTodos(sid, todos)
            sessions += 1
        }
        val kb = payload.optJSONArray("knowledge") ?: JSONArray()
        val existing = HashSet<String>()
        readableDatabase.rawQuery("SELECT title, content FROM knowledge", null).use { c ->
            while (c.moveToNext()) existing.add(c.getString(0) + "\u0000" + c.getString(1))
        }
        for (i in 0 until kb.length()) {
            val k = kb.optJSONObject(i) ?: continue
            val title = k.optString("title").take(120)
            val content = k.optString("content")
            if (content.isEmpty()) continue
            val key = title + "\u0000" + content
            if (key in existing) continue
            kbAdd(title, content)
            existing.add(key)
            knowledge += 1
        }
        return JSONObject()
            .put("ok", true)
            .put("imported", JSONObject()
                .put("sessions", sessions).put("sessions_skipped", skipped)
                .put("messages", messages).put("knowledge", knowledge).put("todos", 0))
    }

    // ---------- 定时任务 ----------

    fun listJobs(): JSONArray {
        val out = JSONArray()
        readableDatabase.rawQuery(
            "SELECT id, name, trigger_type, expr, message, session_id, condition, enabled, created_at, last_run " +
                "FROM jobs ORDER BY created_at", null
        ).use { c ->
            while (c.moveToNext()) {
                out.put(
                    JSONObject()
                        .put("id", c.getString(0)).put("name", c.getString(1))
                        .put("trigger_type", c.getString(2)).put("expr", c.getString(3))
                        .put("message", c.getString(4)).put("session_id", c.getString(5))
                        .put("condition", c.getString(6)).put("enabled", c.getInt(7) == 1)
                        .put("created_at", c.getDouble(8)).put("last_run", c.getDouble(9))
                )
            }
        }
        return out
    }

    fun getJob(id: String): JSONObject? {
        val arr = listJobs()
        for (i in 0 until arr.length()) {
            val j = arr.optJSONObject(i) ?: continue
            if (j.optString("id") == id) return j
        }
        return null
    }

    fun insertJob(job: JSONObject): JSONObject {
        val id = job.optString("id").ifEmpty { newId() }
        val cv = android.content.ContentValues().apply {
            put("id", id)
            put("name", job.optString("name").take(60))
            put("trigger_type", job.optString("trigger_type", "interval"))
            put("expr", job.optString("expr"))
            put("message", job.optString("message"))
            put("session_id", job.optString("session_id"))
            put("condition", job.optString("condition"))
            put("enabled", if (job.optBoolean("enabled", true)) 1 else 0)
            put("created_at", now())
            put("last_run", 0.0)
        }
        writableDatabase.insert("jobs", null, cv)
        return getJob(id) ?: job.put("id", id)
    }

    /** 部分更新：只写传入的字段，避免 PUT 只带 enabled 时把 expr 清空。 */
    fun updateJob(id: String, patch: JSONObject): JSONObject? {
        val cv = android.content.ContentValues()
        for (k in arrayOf("name", "trigger_type", "expr", "message", "session_id", "condition")) {
            if (patch.has(k)) cv.put(k, patch.optString(k))
        }
        if (patch.has("enabled")) cv.put("enabled", if (patch.optBoolean("enabled")) 1 else 0)
        if (cv.size() > 0) writableDatabase.update("jobs", cv, "id = ?", arrayOf(id))
        return getJob(id)
    }

    fun markJobRun(id: String) {
        val cv = android.content.ContentValues().apply { put("last_run", now()) }
        writableDatabase.update("jobs", cv, "id = ?", arrayOf(id))
    }

    fun deleteJob(id: String): Boolean = writableDatabase.delete("jobs", "id = ?", arrayOf(id)) > 0

    // ---------- 检查点（撤销） ----------

    fun recordCheckpoint(sessionId: String, toolCallId: String, toolName: String,
                         path: String, backup: String, kind: String) {
        val cv = android.content.ContentValues().apply {
            put("session_id", sessionId); put("tool_call_id", toolCallId); put("tool_name", toolName)
            put("path", path); put("backup", backup); put("kind", kind)
            put("undone", 0); put("created_at", now())
        }
        writableDatabase.insert("checkpoints", null, cv)
    }

    /** 找某次工具调用的最新未撤销备份；没有则 null。 */
    fun findCheckpoint(sessionId: String, toolCallId: String): JSONObject? =
        readableDatabase.rawQuery(
            "SELECT id, tool_name, path, backup, kind, undone FROM checkpoints " +
                "WHERE session_id = ? AND tool_call_id = ? ORDER BY id DESC LIMIT 1",
            arrayOf(sessionId, toolCallId)
        ).use { c ->
            if (c.moveToFirst()) {
                JSONObject()
                    .put("id", c.getLong(0)).put("tool", c.getString(1)).put("path", c.getString(2))
                    .put("backup", c.getString(3)).put("kind", c.getString(4))
                    .put("undone", c.getInt(5) == 1)
            } else null
        }

    fun markCheckpointUndone(id: Long) {
        val cv = android.content.ContentValues().apply { put("undone", 1) }
        writableDatabase.update("checkpoints", cv, "id = ?", arrayOf(id.toString()))
    }

    // ---------- 会话搜索（跨会话，正文 + 工具输出 + 工具调用参数） ----------

    /**
     * 搜索消息：与 Python `search_messages` 同语义，LIKE 子串而非 FTS。
     *
     * `matched` 字段前端用来打标签：
     *  - message：user/assistant 正文命中
     *  - tool_output：role='tool' 的正文命中
     *  - tool_call：assistant 的 meta（含 tool_calls.arguments）命中
     * 同一行两个条件都命中时按 message 优先，与 Python 的判定顺序一致。
     */
    fun searchMessages(query: String, limit: Int = 20): JSONArray {
        val words = query.trim().split(Regex("\\s+")).filter { it.isNotEmpty() }.take(6)
        val out = JSONArray()
        if (words.isEmpty()) return out
        val contentConds = words.joinToString(" OR ") { "(m.content LIKE ?)" }
        val metaConds = words.joinToString(" OR ") { "(m.meta LIKE ?)" }
        val args = ArrayList<String>()
        // 正文条件参数
        words.forEach { args.add("%$it%") }
        // 参数条件参数
        words.forEach { args.add("%$it%") }
        args.add((limit.coerceIn(1, 50)).toString())
        readableDatabase.rawQuery(
            "SELECT m.id, m.session_id, m.role, m.content, m.meta, m.created_at, s.title " +
                "FROM messages m JOIN sessions s ON s.id = m.session_id " +
                "WHERE (m.role IN ('user','assistant','tool') AND ($contentConds)) " +
                "   OR (m.role='assistant' AND ($metaConds)) " +
                "ORDER BY m.id DESC LIMIT ?",
            args.toTypedArray()
        ).use { c ->
            while (c.moveToNext()) {
                val content = c.getString(3)
                val meta = c.getString(4) ?: ""
                val role = c.getString(2)
                val lowered = words.map { it.lowercase() }
                val hitContent = lowered.any { content.lowercase().contains(it) }
                val hitMeta = lowered.any { meta.lowercase().contains(it) }
                val matched = when {
                    role == "tool" && hitContent -> "tool_output"
                    hitContent -> "message"
                    hitMeta -> "tool_call"
                    else -> "message"
                }
                out.put(
                    JSONObject()
                        .put("id", c.getLong(0)).put("session_id", c.getString(1))
                        .put("title", c.getString(6)).put("role", role)
                        .put("matched", matched)
                        .put("snippet", snippetOf(content, lowered))
                        .put("created_at", c.getDouble(5))
                )
            }
        }
        return out
    }

    /** 命中位置前后各截一段，避免把整条长消息塞给前端。 */
    private fun snippetOf(text: String, words: List<String>): String {
        if (text.isEmpty()) return ""
        val lower = text.lowercase()
        var at = -1
        for (w in words) {
            val i = lower.indexOf(w)
            if (i >= 0 && (at < 0 || i < at)) at = i
        }
        if (at < 0) return text.take(120)
        val start = (at - 40).coerceAtLeast(0)
        val end = (at + 80).coerceAtMost(text.length)
        val prefix = if (start > 0) "…" else ""
        val suffix = if (end < text.length) "…" else ""
        return prefix + text.substring(start, end).replace('\n', ' ') + suffix
    }

    // ---------- 会话压缩（滚动摘要归档） ----------

    fun appendArchive(sessionId: String, content: String) {
        val text = content.trim()
        if (text.isEmpty()) return
        val cv = android.content.ContentValues().apply {
            put("session_id", sessionId); put("content", text.take(4000)); put("created_at", now())
        }
        writableDatabase.insert("archives", null, cv)
    }

    /** 最近 N 段归档，按时间正序（旧→新），供上下文注入。 */
    fun getArchives(sessionId: String, limit: Int = 5): JSONArray {
        val rows = ArrayList<String>()
        readableDatabase.rawQuery(
            "SELECT content FROM archives WHERE session_id = ? ORDER BY id DESC LIMIT ?",
            arrayOf(sessionId, limit.toString())
        ).use { c -> while (c.moveToNext()) rows.add(c.getString(0)) }
        rows.reverse()
        val out = JSONArray()
        rows.forEach { out.put(it) }
        return out
    }

    fun setArchivedUpto(sessionId: String, msgId: Long) {
        val cv = android.content.ContentValues().apply { put("archived_upto", msgId) }
        writableDatabase.update("sessions", cv, "id = ?", arrayOf(sessionId))
    }

    fun archivedUpto(sessionId: String): Long =
        readableDatabase.rawQuery("SELECT archived_upto FROM sessions WHERE id = ?", arrayOf(sessionId))
            .use { c -> if (c.moveToFirst()) c.getLong(0) else 0L }

    companion object {
        private const val DB_NAME = "pocketmind.db"
        private const val DB_VERSION = 3

        fun newId(): String {
            val chars = "abcdefghijklmnopqrstuvwxyz0123456789"
            val b = java.security.SecureRandom()
            val sb = StringBuilder(16)
            repeat(16) { sb.append(chars[b.nextInt(chars.length)]) }
            return sb.toString()
        }

        fun now(): Double = System.currentTimeMillis() / 1000.0
    }
}
