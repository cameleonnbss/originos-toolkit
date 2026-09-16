package dev.cameleonnbss.originostoolkit.core.model

import org.json.JSONArray
import org.json.JSONObject

/**
 * Mirror of `catalog/tweaks.json`. The CLI implements the exact same model in
 * Python; both are tested against the same JSON so they cannot drift.
 */
enum class Risk(val id: String) {
    LOW("low"),
    MEDIUM("medium"),
    HIGH("high");

    companion object {
        fun fromId(id: String?): Risk = entries.firstOrNull { it.id == id } ?: LOW
    }
}

enum class Requirement(val id: String) {
    SHIZUKU("shizuku"),
    ADB("adb"),
    EITHER("either");

    companion object {
        fun fromId(id: String?): Requirement = entries.firstOrNull { it.id == id } ?: SHIZUKU
    }
}

/**
 * One atomic step of a tweak.
 *
 * Keys are read through typed accessors because the catalog is data, not code:
 * an unknown key must never crash the app, it must simply read as absent.
 */
data class Action(
    val op: String,
    private val values: Map<String, Any?> = emptyMap(),
) {
    fun string(key: String): String? = values[key]?.let { if (it is String) it else it.toString() }

    fun int(key: String): Int? = when (val raw = values[key]) {
        is Number -> raw.toInt()
        is String -> raw.toIntOrNull()
        else -> null
    }

    fun bool(key: String): Boolean? = when (val raw = values[key]) {
        is Boolean -> raw
        is String -> raw.toBooleanStrictOrNull()
        else -> null
    }

    fun stringList(key: String): List<String> =
        (values[key] as? List<*>)?.map { it.toString() } ?: emptyList()

    val namespace: String? get() = string("ns")
    val settingKey: String? get() = string("key")
    val settingValue: String? get() = string("value")
    val packageName: String? get() = string("pkg")

    /** Stable identity of whatever this action mutates; used to match probes. */
    val target: String
        get() = when {
            op.startsWith("settings_") -> "settings/${namespace}/${settingKey}"
            op.startsWith("device_config_") -> "device_config/${namespace}/${settingKey}"
            op == "pm_disable" || op == "pm_enable" -> "package/$packageName"
            op.startsWith("overlay_") -> "overlay/$packageName"
            op == "svc" -> "svc/${string("service")}"
            op.startsWith("wm_density") -> "display/density"
            op == "cmd" -> "cmd/" + stringList("args").joinToString(" ")
            op == "shell" -> "shell/" + (string("command") ?: "")
            else -> op
        }

    fun toJson(): JSONObject {
        val json = JSONObject()
        json.put("op", op)
        values.forEach { (key, value) ->
            when (value) {
                null -> Unit
                is List<*> -> json.put(key, JSONArray(value))
                else -> json.put(key, value)
            }
        }
        return json
    }

    companion object {
        fun fromJson(json: JSONObject): Action {
            val values = mutableMapOf<String, Any?>()
            json.keys().forEach { key ->
                if (key == "op") return@forEach
                val value: Any? = json.opt(key)
                values[key] = when (value) {
                    null, JSONObject.NULL -> null
                    is JSONArray -> List(value.length()) { index -> value.opt(index).toString() }
                    else -> value
                }
            }
            return Action(json.getString("op"), values)
        }
    }
}

data class Tweak(
    val id: String,
    val name: String,
    val category: String,
    val summary: String,
    val details: String,
    val risk: Risk,
    val requires: Requirement,
    val verified: Boolean,
    /** `false` when the author declared the action cannot be undone. */
    val declaredOneShot: Boolean,
    val originOs: List<String>,
    val actions: List<Action>,
    val revert: List<Action>,
    val verify: List<Action>,
) {
    /** True when *something* can undo this tweak, either derived or explicit. */
    val reversible: Boolean
        get() = when {
            declaredOneShot -> false
            revert.isNotEmpty() -> true
            else -> actions.all { it.op in AUTO_INVERTIBLE }
        }

    val isOneShot: Boolean get() = !reversible

    companion object {
        /** Ops whose inverse the engine can derive without an explicit revert. */
        val AUTO_INVERTIBLE = setOf(
            "settings_put",
            "settings_delete",
            "device_config_put",
            "device_config_delete",
            "pm_disable",
            "pm_enable",
            "overlay_enable",
            "overlay_disable",
            "svc",
            "wm_density_delta",
        )

        fun fromJson(json: JSONObject): Tweak = Tweak(
            id = json.getString("id"),
            name = json.getString("name"),
            category = json.getString("category"),
            summary = json.optString("summary"),
            details = json.optString("details"),
            risk = Risk.fromId(json.optString("risk")),
            requires = Requirement.fromId(json.optString("requires")),
            verified = json.optBoolean("verified", true),
            declaredOneShot = json.has("reversible") && !json.optBoolean("reversible", true),
            originOs = json.optJSONArray("originOs")?.let { array ->
                List(array.length()) { array.optString(it) }
            } ?: emptyList(),
            actions = json.optJSONArray("actions").toActions(),
            revert = json.optJSONArray("revert").toActions(),
            verify = json.optJSONArray("verify").toActions(),
        )

        private fun JSONArray?.toActions(): List<Action> {
            if (this == null) return emptyList()
            return List(length()) { index -> Action.fromJson(optJSONObject(index) ?: JSONObject()) }
        }
    }
}

data class Category(
    val id: String,
    val name: String,
    val blurb: String,
    val icon: String,
)

data class Profile(
    val id: String,
    val name: String,
    val blurb: String,
    val icon: String,
    val tweaks: List<String>,
)

data class AwesomeEntry(
    val name: String,
    val repo: String,
    val url: String,
    val access: String,
    val description: String,
    val tags: List<String>,
)

data class AwesomeSection(
    val id: String,
    val name: String,
    val blurb: String,
    val entries: List<AwesomeEntry>,
)

data class Catalog(
    val version: Int,
    val updatedAt: String,
    val categories: List<Category>,
    val tweaks: List<Tweak>,
    val profiles: List<Profile>,
    val awesome: List<AwesomeSection>,
) {
    fun tweak(id: String): Tweak? = tweaks.firstOrNull { it.id == id }

    fun categoryName(id: String): String = categories.firstOrNull { it.id == id }?.name ?: id

    fun byCategory(id: String): List<Tweak> = tweaks.filter { it.category == id }

    fun profile(id: String): Profile? = profiles.firstOrNull { it.id == id }

    /** Expands a mix of profile ids and tweak ids, preserving order, no duplicates. */
    fun resolve(ids: List<String>): List<Tweak> {
        val resolved = LinkedHashMap<String, Tweak>()
        ids.forEach { reference ->
            val members = profile(reference)?.tweaks ?: listOf(reference)
            members.forEach { memberId ->
                tweak(memberId)?.let { resolved[memberId] = it }
            }
        }
        return resolved.values.toList()
    }
}
