package dev.cameleonnbss.originostoolkit.core

import android.content.Context
import dev.cameleonnbss.originostoolkit.core.model.Action
import org.json.JSONArray
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * One applied tweak and the exact commands that undo it.
 *
 * The inverse is computed *before* anything is written, and stored on disk, so
 * a revert works even if the catalog changes later or the phone reboots.
 */
data class JournalEntry(
    val tweakId: String,
    val name: String,
    val appliedAt: String,
    val inverse: List<Action>,
    val note: String = "",
) {
    fun toJson(): JSONObject {
        val json = JSONObject()
        json.put("tweakId", tweakId)
        json.put("name", name)
        json.put("appliedAt", appliedAt)
        json.put("note", note)
        val actions = JSONArray()
        inverse.forEach { actions.put(it.toJson()) }
        json.put("inverse", actions)
        return json
    }

    companion object {
        fun fromJson(json: JSONObject): JournalEntry {
            val actions = json.optJSONArray("inverse") ?: JSONArray()
            val inverse = List(actions.length()) { index ->
                Action.fromJson(actions.optJSONObject(index) ?: JSONObject())
            }
            return JournalEntry(
                tweakId = json.getString("tweakId"),
                name = json.optString("name", json.optString("tweakId")),
                appliedAt = json.optString("appliedAt"),
                inverse = inverse,
                note = json.optString("note"),
            )
        }
    }
}

interface JournalStore {
    fun load(): List<JournalEntry>
    fun save(entries: List<JournalEntry>)

    fun now(): String = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss", Locale.US).format(Date())
}

/**
 * SharedPreferences-backed journal.
 *
 * A corrupt or truncated payload is dropped instead of crashing: being unable
 * to revert is bad, being unable to *launch* is worse.
 */
class SharedPrefsJournalStore(context: Context) : JournalStore {

    private val prefs = context.applicationContext.getSharedPreferences(FILE, Context.MODE_PRIVATE)

    override fun load(): List<JournalEntry> {
        val raw = prefs.getString(KEY, null) ?: return emptyList()
        return runCatching {
            val doc = JSONObject(raw)
            val entries = doc.optJSONArray("entries") ?: JSONArray()
            List(entries.length()) { index ->
                JournalEntry.fromJson(entries.optJSONObject(index) ?: JSONObject())
            }
        }.getOrElse {
            prefs.edit().remove(KEY).apply()
            emptyList()
        }
    }

    override fun save(entries: List<JournalEntry>) {
        val doc = JSONObject()
        doc.put("journalVersion", 1)
        val array = JSONArray()
        entries.forEach { array.put(it.toJson()) }
        doc.put("entries", array)
        prefs.edit().putString(KEY, doc.toString()).apply()
    }

    companion object {
        const val FILE = "journal"
        private const val KEY = "payload"
    }
}
