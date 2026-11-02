package com.pocket.agent.runtime

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.pocket.agent.storage.SessionRepository
import org.json.JSONArray
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.util.UUID

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class SessionApiTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private lateinit var name: String
    private lateinit var store: SessionRepository

    @Before fun setup() {
        name = "api-test-${UUID.randomUUID()}.db"
        store = SessionRepository(context, name)
    }

    @After fun cleanup() {
        store.close()
        context.deleteDatabase(name)
    }

    private fun call(repository: SessionRepository?, authenticated: Boolean, method: String, path: String): Pair<Int, String> {
        val result = SessionApi(repository).handle(authenticated, method, path)
        return result.status to result.body
    }

    @Test fun authenticationPrecedesAllRoutingAndStorage() {
        for (path in listOf("/api/sessions", "/api/sessions/missing/messages", "/api/chat")) {
            val result = call(null, false, "GET", path)
            assertEquals(401, result.first)
            assertEquals("unauthorized", JSONObject(result.second).getString("error"))
        }
        val unavailable = call(null, true, "POST", "/api/sessions")
        assertEquals(503, unavailable.first)
        assertEquals("unavailable", JSONObject(unavailable.second).getString("error"))
    }

    @Test fun createsListsAndReadsMessagesAcrossReopen() {
        val created = call(store, true, "POST", "/api/sessions")
        assertEquals(200, created.first)
        val payload = JSONObject(created.second)
        assertEquals("新会话", payload.getString("title"))
        val id = payload.getString("session_id")
        assertTrue(id.matches(Regex("[0-9a-f]{12}")))
        store.addMessage(id, "user", "synthetic", "{\"x\":1}")
        store.close()
        store = SessionRepository(context, name)
        val listed = call(store, true, "GET", "/api/sessions")
        assertEquals(200, listed.first)
        val session = JSONArray(listed.second).getJSONObject(0)
        assertEquals(id, session.getString("id"))
        assertEquals(1, session.getInt("message_count"))
        assertTrue(session.has("created_at") && session.has("updated_at") && session.has("archived_upto") && session.has("pinned"))
        val messages = call(store, true, "GET", "/api/sessions/$id/messages")
        assertEquals(200, messages.first)
        val message = JSONArray(messages.second).getJSONObject(0)
        assertEquals("synthetic", message.getString("content"))
        assertEquals("user", message.getString("role"))
        assertEquals(1, message.getJSONObject("meta").getInt("x"))
        assertTrue(message.has("id") && message.has("created_at"))
    }

    @Test fun missingAndUnavailableOperationsAreExplicit() {
        val missing = call(store, true, "GET", "/api/sessions/missing/messages")
        assertEquals(404, missing.first)
        assertEquals("会话不存在", JSONObject(missing.second).getString("detail"))
        for ((method, path) in listOf("DELETE" to "/api/sessions/x", "PUT" to "/api/sessions/x", "POST" to "/api/chat", "GET" to "/api/settings", "GET" to "/api/export", "POST" to "/api/import")) {
            val result = call(store, true, method, path)
            assertEquals("$method $path", 503, result.first)
            assertEquals("not_implemented", JSONObject(result.second).getString("migration"))
        }
    }

    @Test fun orphanAndFailedTouchLeaveNoMessage() {
        val id = store.createSession()
        assertThrows(IllegalArgumentException::class.java) { store.addMessage("missing", "user", "orphan") }
        val db = context.openOrCreateDatabase(name, Context.MODE_PRIVATE, null)
        try {
            db.execSQL("CREATE TRIGGER reject_api_touch BEFORE UPDATE ON sessions BEGIN SELECT RAISE(ABORT, 'failure'); END")
            assertThrows(RuntimeException::class.java) { store.addMessage(id, "user", "rollback") }
            assertEquals(0, JSONArray(call(store, true, "GET", "/api/sessions/$id/messages").second).length())
            assertTrue(store.getMessages("missing").isEmpty())
        } finally { db.close() }
    }
}
