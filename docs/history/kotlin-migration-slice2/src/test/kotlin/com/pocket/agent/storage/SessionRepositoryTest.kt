package com.pocket.agent.storage

import android.content.Context
import androidx.test.core.app.ApplicationProvider
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
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class SessionRepositoryTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private lateinit var name: String
    private lateinit var store: SessionRepository

    @Before fun setup() {
        name = "test-${UUID.randomUUID()}.db"
        store = SessionRepository(context, name)
    }

    @After fun cleanup() {
        store.close()
        context.deleteDatabase(name)
    }

    @Test fun persistsSessionsMessagesAndStablePagination() {
        val a = store.createSession("合成 ' OR 1=1 --")
        val b = store.createSession()
        assertTrue(a.matches(Regex("[0-9a-f]{12}")))
        assertEquals("新会话", store.getSession(b)!!.title)
        store.addMessage(a, "user", "synthetic", "{\"x\":1}")
        store.addMessage(a, "tool", "synthetic tool")
        store.addMessage(a, "assistant", "synthetic response")
        assertEquals(2, store.listSessions().first { it.id == a }.messageCount)
        assertEquals(listOf("tool", "assistant"), store.getMessages(a, 2).map { it.role })
        assertEquals("{\"x\":1}", store.getMessages(a).first().meta)
        val ids = store.listSessions().map { it.id }
        assertEquals(ids, store.listSessions(1, 0).map { it.id } + store.listSessions(1, 1).map { it.id })
        store.close()
        store = SessionRepository(context, name)
        assertEquals("合成 ' OR 1=1 --", store.getSession(a)!!.title)
        assertEquals(3, store.getMessages(a).size)
        assertEquals(2, store.listSessions().size)
    }

    @Test fun nonexistentSessionAndSeparateDatabases() {
        assertNull(store.getSession("missing"))
        assertTrue(store.getMessages("missing").isEmpty())
        val sid = store.createSession()
        val otherName = "test-${UUID.randomUUID()}.db"
        val other = SessionRepository(context, otherName)
        try {
            assertNull(other.getSession(sid))
            assertTrue(other.listSessions().isEmpty())
        } finally {
            other.close()
            context.deleteDatabase(otherName)
        }
    }

    @Test fun rejectsMissingSessionWithoutOrphan() {
        assertThrows(IllegalArgumentException::class.java) { store.addMessage("missing", "user", "synthetic") }
        assertTrue(store.getMessages("missing").isEmpty())
        val sid = store.createSession()
        store.addMessage(sid, "user", "ok")
        assertTrue(store.getMessages("missing").isEmpty())
    }

    @Test fun reordersToolPairsAndRecoversInvalidMeta() {
        val sid = store.createSession()
        store.addMessage(sid, "assistant", "call", "{\"tool_calls\":[{\"id\":\"a\"},{\"id\":\"b\"}]}")
        store.addMessage(sid, "user", "middle", "not json")
        store.addMessage(sid, "tool", "B", "{\"tool_call_id\":\"b\"}")
        store.addMessage(sid, "tool", "A", "{\"tool_call_id\":\"a\"}")
        store.addMessage(sid, "tool", "duplicate A", "{\"tool_call_id\":\"a\"}")
        val messages = store.getMessages(sid)
        assertEquals(listOf("call", "A", "duplicate A", "B", "middle"), messages.map { it.content })
        assertEquals("{}", messages.last().meta)
        assertEquals(listOf("middle", "B", "A", "duplicate A"), store.getMessages(sid, 4).map { it.content })
    }

    @Test fun duplicateToolCallIdsKeepEveryDistinctRow() {
        val sid = store.createSession()
        val call = store.addMessage(sid, "assistant", "call", "{\"tool_calls\":[{\"id\":\"a\"}]}")
        val middle = store.addMessage(sid, "user", "middle")
        val first = store.addMessage(sid, "tool", "first", "{\"tool_call_id\":\"a\"}")
        val second = store.addMessage(sid, "tool", "second", "{\"tool_call_id\":\"a\"}")
        val actual = store.getMessages(sid).map { it.id }
        val original = listOf(call, middle, first, second)
        assertEquals(original.sorted(), actual.sorted())
        assertEquals(listOf(call, first, second, middle), actual)
    }

    @Test fun toolRowBeforeAssistantIsMovedNotDuplicated() {
        val sid = store.createSession()
        val tool = store.addMessage(sid, "tool", "early", "{\"tool_call_id\":\"a\"}")
        val middle = store.addMessage(sid, "user", "middle")
        val call = store.addMessage(sid, "assistant", "call", "{\"tool_calls\":[{\"id\":\"a\"}]}")
        val actual = store.getMessages(sid).map { it.id }
        assertEquals(listOf(tool, middle, call).sorted(), actual.sorted())
        assertEquals(listOf(middle, call, tool), actual)
    }

    @Test fun toolReorderingMatchesPythonForNonDuplicateRows() {
        val sid = store.createSession()
        val inputs = listOf(
            Triple("assistant", "call", "{\"tool_calls\":[{\"id\":\"a\"},{\"id\":\"b\"},{\"id\":\"a\"}]}"),
            Triple("user", "middle", "{}"),
            Triple("tool", "B", "{\"tool_call_id\":\"b\"}"),
            Triple("tool", "A", "{\"tool_call_id\":\"a\"}"),
            Triple("tool", "unknown", "{\"tool_call_id\":\"z\"}"),
        )
        inputs.forEach { (role, content, meta) -> store.addMessage(sid, role, content, meta) }
        val memory = generateSequence(java.io.File(System.getProperty("user.dir") ?: error("Working directory unavailable"))) { it.parentFile }
            .map { java.io.File(it, "agentd/memory.py") }.first { it.isFile }
        val script = """
import ast,json,sys
source = open(sys.argv[1], encoding='utf-8').read()
module = ast.parse(source)
store = next(n for n in module.body if isinstance(n, ast.ClassDef) and n.name == 'Store')
method = next(n for n in store.body if isinstance(n, ast.FunctionDef) and n.name == '_reorder_tool_pairs')
method.decorator_list = []
namespace = {}
exec(compile(ast.fix_missing_locations(ast.Module(body=[method], type_ignores=[])), sys.argv[1], 'exec'), namespace)
print(json.dumps(namespace['_reorder_tool_pairs'](json.load(sys.stdin)), ensure_ascii=True))
""".trimIndent()
        val process = ProcessBuilder("python", "-I", "-c", script, memory.absolutePath).redirectErrorStream(true).start()
        val data = JSONArray()
        inputs.forEach { (role, content, meta) -> data.put(JSONObject().put("role", role).put("content", content).put("meta", JSONObject(meta))) }
        process.outputStream.bufferedWriter(Charsets.UTF_8).use { it.write(data.toString()) }
        val output = process.inputStream.bufferedReader(Charsets.UTF_8).readText()
        assertEquals(output, 0, process.waitFor())
        val expected = JSONArray(output)
        val actual = store.getMessages(sid).map { it.content }
        assertEquals((0 until expected.length()).map { expected.getJSONObject(it).getString("content") }, actual)
    }

    @Test fun messageAndTouchRollbackTogetherOnFailure() {
        val sid = store.createSession()
        val db = context.openOrCreateDatabase(name, Context.MODE_PRIVATE, null)
        try {
            db.execSQL("CREATE TRIGGER reject_touch BEFORE UPDATE ON sessions BEGIN SELECT RAISE(ABORT, 'synthetic failure'); END")
            assertThrows(RuntimeException::class.java) { store.addMessage(sid, "user", "rollback") }
            assertTrue(store.getMessages(sid).isEmpty())
            db.execSQL("DROP TRIGGER reject_touch")
            store.addMessage(sid, "user", "after")
            assertEquals(listOf("after"), store.getMessages(sid).map { it.content })
        } finally { db.close() }
    }

    @Test fun concurrentWritesPreserveAllMessages() {
        val sid = store.createSession()
        val pool = Executors.newFixedThreadPool(4)
        try {
            val tasks = (0 until 40).map { i -> pool.submit { store.addMessage(sid, "user", "synthetic-$i") } }
            tasks.forEach { it.get(30, TimeUnit.SECONDS) }
            assertEquals(40, store.getMessages(sid).map { it.content }.toSet().size)
            assertEquals(40, store.listSessions().single().messageCount)
        } finally { pool.shutdownNow() }
    }
}
