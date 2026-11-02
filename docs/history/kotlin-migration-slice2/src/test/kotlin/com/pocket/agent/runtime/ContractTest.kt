package com.pocket.agent.runtime

import java.util.Properties
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class ContractTest {
    private val vectors = Properties().apply {
        ContractTest::class.java.getResourceAsStream("/contract-vectors.properties")!!.use { load(it) }
    }

    @Test
    fun pathsAndEventsMatchReferenceVectors() {
        assertEquals(vectors.getProperty("health"), ApiContract.HEALTH)
        assertEquals(vectors.getProperty("chat"), ApiContract.CHAT)
        assertEquals(vectors.getProperty("tools"), ApiContract.TOOLS)
        assertEquals(vectors.getProperty("export"), ApiContract.EXPORT)
        assertEquals(vectors.getProperty("import"), ApiContract.IMPORT)
        assertEquals(vectors.getProperty("settings"), ApiContract.SETTINGS)
        assertEquals(vectors.getProperty("sessions"), ApiContract.SESSIONS)
        assertEquals(vectors.getProperty("approval"), ApiContract.APPROVAL)
        assertEquals(vectors.getProperty("jobs"), ApiContract.JOBS)
        assertEquals(vectors.getProperty("status"), ApiContract.STATUS)
        assertEquals(vectors.getProperty("sse"), ApiContract.SSE_CONTENT_TYPE)
        assertEquals(vectors.getProperty("events").split(",").toSet(), ApiContract.events)
        assertEquals(vectors.getProperty("risks").split(","), ToolRisk.values().map { it.wireValue })
    }

    @Test
    fun implementedSurfaceIsExactlyWhatKotlinAnswers() {
        val implemented = vectors.getProperty("native.implemented").split(",").toSet()
        val unimplemented = vectors.getProperty("native.unimplemented").split(",").toSet()
        assertEquals(implemented, ApiContract.implemented)
        assertEquals(unimplemented, ApiContract.unimplemented)
        assertEquals("可用与不可用集合必须互斥且不重叠",
            emptySet<String>(), implemented intersect unimplemented)
        assertFalse("健康检查是唯一免鉴权接口，不应混入未实现集合",
            ApiContract.HEALTH in unimplemented)
        assertFalse("状态接口已实现，不应混入未实现集合",
            ApiContract.STATUS in unimplemented)
    }

    @Test
    fun sharedRestCasesRemainStable() {
        val cases = mapOf(
            "session_list" to "GET|/api/sessions|200|@array",
            "session_create" to "POST|/api/sessions|200|session_id,title",
            "session_missing" to "GET|/api/sessions/missing/messages|404|detail",
            "settings_get" to "GET|/api/settings|200|llm,permission_mode,server",
            "settings_put" to "PUT|/api/settings|200|ok",
            "approval_invalid" to "POST|/api/approval|400|detail",
            "approval_missing" to "POST|/api/approval|404|detail",
            "approval_pending" to "GET|/api/approvals/pending|200|pending",
            "approval_rules" to "GET|/api/approvals|200|global,deny,sessions",
            "jobs_list" to "GET|/api/jobs|200|jobs",
            "jobs_invalid" to "POST|/api/jobs|400|detail",
            "jobs_delete_missing" to "DELETE|/api/jobs/missing|200|ok",
            "export" to "GET|/api/export|200|app,version,settings,sessions,knowledge,jobs,exported_session_count,export_truncated",
            "import_invalid" to "POST|/api/import|400|detail",
            "import_empty" to "POST|/api/import|200|ok,imported",
        )
        assertEquals(cases.keys, vectors.stringPropertyNames().filter { it.startsWith("case.") && it != "case.import_fields" }
            .map { it.removePrefix("case.") }.toSet())
        cases.forEach { (name, expected) -> assertEquals(name, expected, vectors.getProperty("case.$name")) }
        assertEquals("sessions,sessions_skipped,messages,knowledge,todos,jobs", vectors.getProperty("case.import_fields"))
    }

    @Test
    fun toolDescriptorDefaultsMatchReferenceVector() {
        val tool = ToolDescriptor(vectors.getProperty("tool.name"), vectors.getProperty("tool.description"), ToolRisk.WRITE)
        assertEquals(vectors.getProperty("tool.risk"), tool.risk.wireValue)
        assertEquals(vectors.getProperty("tool.summary"), tool.summary)
        assertEquals(vectors.getProperty("tool.timeout").toInt(), tool.timeout)
        assertEquals(vectors.getProperty("tool.group"), tool.group)
    }
}
