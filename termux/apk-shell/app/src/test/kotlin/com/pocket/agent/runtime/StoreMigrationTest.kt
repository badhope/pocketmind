package com.pocket.agent.runtime

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * 数据库迁移测试：v1 建的库升级到当前版本，用户数据不能丢。
 *
 * 为什么必须有：`Store.onUpgrade` 里每一行都是「用户装了新版本、打开旧数据」时
 * 跑的代码。写错一个列名，表现是升级后所有会话消失——而单元测试全绿，
 * 因为新库是新库。同类项目（Letta 的 alembic-validation）也把这条单列成门禁。
 *
 * 这里不测「新装」（那是其他用例的事），只测「旧库 → 新库」这一跳。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class StoreMigrationTest {

    private val context: Context = ApplicationProvider.getApplicationContext()

    /** 用 v1 的 schema 建一个只有 sessions/messages 的旧库，写入数据。 */
    private fun seedV1Database(sessionId: String, message: String) {
        val f = context.getDatabasePath("pocketmind.db")
        f.parentFile?.mkdirs()
        val db = android.database.sqlite.SQLiteDatabase.openOrCreateDatabase(f, null)
        db.execSQL(
            "CREATE TABLE sessions(id TEXT PRIMARY KEY, title TEXT NOT NULL DEFAULT '新会话', " +
                "created_at REAL NOT NULL, updated_at REAL NOT NULL, " +
                "archived_upto INTEGER NOT NULL DEFAULT 0, pinned INTEGER NOT NULL DEFAULT 0)"
        )
        db.execSQL(
            "CREATE TABLE messages(id INTEGER PRIMARY KEY AUTOINCREMENT, session_id TEXT NOT NULL, " +
                "role TEXT NOT NULL, content TEXT NOT NULL, meta TEXT NOT NULL DEFAULT '{}', " +
                "created_at REAL NOT NULL)"
        )
        db.execSQL(
            "CREATE TABLE knowledge(id INTEGER PRIMARY KEY AUTOINCREMENT, title TEXT NOT NULL, " +
                "content TEXT NOT NULL, created_at REAL NOT NULL)"
        )
        db.execSQL(
            "CREATE TABLE todos(session_id TEXT NOT NULL, position INTEGER NOT NULL, " +
                "content TEXT NOT NULL, status TEXT NOT NULL, updated_at REAL NOT NULL, " +
                "PRIMARY KEY(session_id, position))"
        )
        db.execSQL(
            "CREATE TABLE facts(id INTEGER PRIMARY KEY AUTOINCREMENT, content TEXT NOT NULL, created_at REAL NOT NULL)"
        )
        db.execSQL(
            "INSERT INTO sessions(id,title,created_at,updated_at) VALUES('$sessionId','旧会话',1.0,1.0)"
        )
        db.execSQL(
            "INSERT INTO messages(session_id,role,content,meta,created_at) " +
                "VALUES('$sessionId','user','$message','{}',1.0)"
        )
        db.version = 1
        db.close()
    }

    @Test
    fun `v1 旧库升级到当前版本后 会话与消息都还在`() {
        val sid = "legacy001"
        seedV1Database(sid, "升级前写下的消息")

        // 用当前 Store 打开同一个文件 → 触发 onUpgrade
        val store = com.pocket.agent.data.Store(context)
        // 强制触发一次读写（SQLiteOpenHelper 在首次 getWritableDatabase 时升级）
        val session = store.getSession(sid)
        assertTrue("升级后旧会话必须还在", session != null)
        assertEquals("旧会话", session!!.optString("title"))

        val msgs = store.getMessages(sid, 10)
        assertEquals("升级后旧消息必须还在", 1, msgs.length())
        assertEquals("升级前写下的消息", msgs.getJSONObject(0).optString("content"))
    }

    @Test
    fun `升级后新增的表可用 且默认值为空`() {
        val sid = "legacy002"
        seedV1Database(sid, "hi")
        val store = com.pocket.agent.data.Store(context)
        // 触发升级
        store.getSession(sid)

        // v2 加的 jobs / checkpoints、v3 加的 archives：升级后应能直接读写
        assertEquals(0, store.listJobs().length())
        assertEquals(0, store.getArchives(sid, 10).length())
        store.appendArchive(sid, "升级后写入的摘要")
        assertEquals(1, store.getArchives(sid, 10).length())

        val job = store.insertJob(JSONObject().put("name", "新任务")
            .put("trigger_type", "interval").put("expr", "60").put("message", "m"))
        assertTrue("升级后应能创建任务", job.optString("id").isNotEmpty())
    }

    @Test
    fun `重复打开不会重复执行升级`() {
        val sid = "legacy003"
        seedV1Database(sid, "只有一条")
        val s1 = com.pocket.agent.data.Store(context)
        s1.getSession(sid)
        val s2 = com.pocket.agent.data.Store(context)
        // 第二次打开不应抛「table already exists」
        assertEquals(1, s2.getMessages(sid, 10).length())
    }
}
