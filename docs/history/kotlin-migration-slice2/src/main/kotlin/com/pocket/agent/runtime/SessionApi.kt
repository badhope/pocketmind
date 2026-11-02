package com.pocket.agent.runtime

import android.database.sqlite.SQLiteException
import com.pocket.agent.storage.SessionRepository
import org.json.JSONArray
import org.json.JSONObject
import org.json.JSONTokener

data class SessionApiResponse(val status: Int, val body: String)

class SessionApi(private val repository: SessionRepository?) {
    fun handle(authenticated: Boolean, method: String, path: String): SessionApiResponse {
        if (!authenticated) return SessionApiResponse(401, "{\"error\":\"unauthorized\"}")
        val store = repository ?: return unavailable()
        return try {
            when {
                method == "POST" && path == "/api/sessions" -> {
                    val id = store.createSession()
                    SessionApiResponse(200, JSONObject().put("session_id", id).put("title", "新会话").toString())
                }
                method == "GET" && path == "/api/sessions" -> {
                    val result = JSONArray()
                    store.listSessions().forEach { session ->
                        result.put(JSONObject().put("id", session.id).put("title", session.title)
                            .put("created_at", session.createdAt).put("updated_at", session.updatedAt)
                            .put("archived_upto", session.archivedUpto).put("pinned", session.pinned)
                            .put("message_count", session.messageCount))
                    }
                    SessionApiResponse(200, result.toString())
                }
                method == "GET" && path.matches(Regex("^/api/sessions/[^/]+/messages$")) -> {
                    val id = path.removePrefix("/api/sessions/").removeSuffix("/messages")
                    if (store.getSession(id) == null) {
                        SessionApiResponse(404, JSONObject().put("detail", "会话不存在").toString())
                    } else {
                        val result = JSONArray()
                        store.getMessages(id).forEach { message ->
                            result.put(JSONObject().put("id", message.id).put("role", message.role)
                                .put("content", message.content).put("meta", JSONTokener(message.meta).nextValue())
                                .put("created_at", message.createdAt))
                        }
                        SessionApiResponse(200, result.toString())
                    }
                }
                else -> unavailable()
            }
        } catch (_: SQLiteException) {
            unavailable()
        } catch (_: IllegalStateException) {
            unavailable()
        }
    }

    private fun unavailable() = SessionApiResponse(503, "{\"error\":\"unavailable\",\"migration\":\"not_implemented\"}")
}
