package com.pocket.agent.storage

import android.content.Context
import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import org.json.JSONException
import org.json.JSONObject
import org.json.JSONTokener
import java.io.Closeable
import java.util.UUID

data class StoredSession(
    val id: String,
    val title: String,
    val createdAt: Double,
    val updatedAt: Double,
    val archivedUpto: Long,
    val pinned: Long,
    val messageCount: Int = 0,
)

data class StoredMessage(
    val id: Long,
    val role: String,
    val content: String,
    val meta: String,
    val createdAt: Double,
)

class SessionRepository(context: Context, databaseName: String = "native-sessions.db") : Closeable {
    private val helper = object : SQLiteOpenHelper(context.applicationContext, databaseName, null, 1) {
        override fun onCreate(db: SQLiteDatabase) {
            db.execSQL("CREATE TABLE sessions (id TEXT PRIMARY KEY, title TEXT NOT NULL DEFAULT '新会话', created_at REAL NOT NULL, updated_at REAL NOT NULL, archived_upto INTEGER NOT NULL DEFAULT 0, pinned INTEGER NOT NULL DEFAULT 0)")
            db.execSQL("CREATE TABLE messages (id INTEGER PRIMARY KEY AUTOINCREMENT, session_id TEXT NOT NULL, role TEXT NOT NULL, content TEXT NOT NULL DEFAULT '', meta TEXT NOT NULL DEFAULT '{}', created_at REAL NOT NULL)")
            db.execSQL("CREATE INDEX idx_msg_sid ON messages(session_id, id)")
        }

        override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
            throw IllegalStateException("Unsupported session database version $oldVersion -> $newVersion")
        }
    }

    @Synchronized fun createSession(title: String = "新会话"): String {
        val id = UUID.randomUUID().toString().replace("-", "").take(12)
        val now = System.currentTimeMillis() / 1000.0
        val db = helper.writableDatabase
        db.beginTransaction()
        try {
            db.execSQL("INSERT INTO sessions(id,title,created_at,updated_at) VALUES (?,?,?,?)", arrayOf(id, title, now, now))
            db.setTransactionSuccessful()
        } finally { db.endTransaction() }
        return id
    }

    @Synchronized fun getSession(id: String): StoredSession? = helper.readableDatabase.rawQuery(
        "SELECT id,title,created_at,updated_at,COALESCE(archived_upto,0),COALESCE(pinned,0) FROM sessions WHERE id=?",
        arrayOf(id),
    ).use { if (it.moveToFirst()) it.session() else null }

    @Synchronized fun listSessions(limit: Int = 50, offset: Int = 0): List<StoredSession> = helper.readableDatabase.rawQuery(
        "SELECT s.id,s.title,s.created_at,s.updated_at,COALESCE(s.archived_upto,0),COALESCE(s.pinned,0),COUNT(m.id) FROM sessions s LEFT JOIN messages m ON m.session_id=s.id AND m.role IN ('user','assistant') GROUP BY s.id,s.title,s.created_at,s.updated_at,s.archived_upto,s.pinned ORDER BY COALESCE(s.pinned,0) DESC,s.updated_at DESC,s.id DESC LIMIT ? OFFSET ?",
        arrayOf(limit.toString(), offset.coerceAtLeast(0).toString()),
    ).use { cursor -> buildList { while (cursor.moveToNext()) add(cursor.session(cursor.getInt(6))) } }

    @Synchronized fun addMessage(sessionId: String, role: String, content: String, meta: String = "{}"): Long {
        val db = helper.writableDatabase
        val now = System.currentTimeMillis() / 1000.0
        db.beginTransaction()
        try {
            val exists = db.rawQuery("SELECT 1 FROM sessions WHERE id=?", arrayOf(sessionId)).use { it.moveToFirst() }
            require(exists) { "Session does not exist" }
            val id = db.compileStatement("INSERT INTO messages(session_id,role,content,meta,created_at) VALUES (?,?,?,?,?)").use { stmt ->
                stmt.bindString(1, sessionId)
                stmt.bindString(2, role)
                stmt.bindString(3, content)
                stmt.bindString(4, meta)
                stmt.bindDouble(5, now)
                stmt.executeInsert()
            }
            db.execSQL("UPDATE sessions SET updated_at=? WHERE id=?", arrayOf(now, sessionId))
            db.setTransactionSuccessful()
            return id
        } finally { db.endTransaction() }
    }

    @Synchronized fun getMessages(sessionId: String, limit: Int = 200): List<StoredMessage> = helper.readableDatabase.rawQuery(
        "SELECT id,role,content,meta,created_at FROM messages WHERE session_id=? ORDER BY id DESC LIMIT ?",
        arrayOf(sessionId, limit.toString()),
    ).use { cursor ->
        buildList {
            while (cursor.moveToNext()) add(StoredMessage(cursor.getLong(0), cursor.getString(1), cursor.getString(2), validMeta(cursor.getString(3)), cursor.getDouble(4)))
        }.reversed().let(::reorderToolPairs)
    }

    private fun validMeta(raw: String?): String {
        if (raw.isNullOrEmpty()) return "{}"
        return try {
            val tokenizer = JSONTokener(raw)
            tokenizer.nextValue()
            if (tokenizer.nextClean() != '\u0000') "{}" else raw
        } catch (_: JSONException) { "{}" }
    }

    private fun reorderToolPairs(items: List<StoredMessage>): List<StoredMessage> {
        val tools = LinkedHashMap<String, MutableList<StoredMessage>>()
        for (message in items) {
            if (message.role == "tool") {
                val id = metaObject(message.meta).optString("tool_call_id", "")
                if (id.isNotEmpty()) tools.getOrPut(id) { mutableListOf() }.add(message)
            }
        }
        val assigned = HashSet<String>()
        val after = HashMap<Long, MutableList<StoredMessage>>()
        val moved = HashSet<Long>()
        for (message in items) {
            if (message.role != "assistant") continue
            val calls = metaObject(message.meta).optJSONArray("tool_calls") ?: continue
            for (i in 0 until calls.length()) {
                val id = calls.optJSONObject(i)?.optString("id", "") ?: ""
                val matches = tools[id] ?: continue
                if (assigned.add(id)) {
                    after.getOrPut(message.id) { mutableListOf() }.addAll(matches)
                    moved.addAll(matches.map { it.id })
                }
            }
        }
        return buildList {
            for (message in items) {
                if (message.id !in moved) add(message)
                after[message.id]?.let { addAll(it) }
            }
        }
    }

    private fun metaObject(meta: String): JSONObject = try { JSONObject(meta) } catch (_: JSONException) { JSONObject() }

    @Synchronized override fun close() = helper.close()

    private fun Cursor.session(messageCount: Int = 0) = StoredSession(
        getString(0), getString(1), getDouble(2), getDouble(3), getLong(4), getLong(5), messageCount,
    )
}
