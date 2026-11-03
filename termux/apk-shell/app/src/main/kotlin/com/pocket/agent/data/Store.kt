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
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        // v1 是首个 schema；未来迁移在此按 oldVersion 分支。
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

    fun deleteSession(id: String): Boolean {
        val db = writableDatabase
        db.beginTransaction()
        return try {
            db.delete("messages", "session_id = ?", arrayOf(id))
            db.delete("todos", "session_id = ?", arrayOf(id))
            val n = db.delete("sessions", "id = ?", arrayOf(id))
            db.setTransactionSuccessful()
            n > 0
        } finally {
            db.endTransaction()
        }
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

    companion object {
        private const val DB_NAME = "pocketmind.db"
        private const val DB_VERSION = 1

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
