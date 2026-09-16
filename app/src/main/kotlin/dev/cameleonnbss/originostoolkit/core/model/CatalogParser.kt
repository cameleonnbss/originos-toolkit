package dev.cameleonnbss.originostoolkit.core.model

import android.content.res.AssetManager
import org.json.JSONArray
import org.json.JSONObject

/**
 * Parses the catalog that ships in `assets/` — which is exactly the JSON in the
 * repository's `catalog` directory, wired in through Gradle's `assets.srcDirs`.
 * (No glob is written here on purpose: a literal slash-star inside a Kotlin block
 * comment opens a nested comment, which Kotlin happily reports as "unclosed".)
 *
 * The parser is deliberately forgiving: a catalog entry with an unexpected
 * shape is skipped rather than taking the whole app down, because a malformed
 * catalog should never be able to brick a UI on someone's phone.
 */
object CatalogParser {

    const val TWEAKS_ASSET = "tweaks.json"
    const val PROFILES_ASSET = "profiles.json"
    const val AWESOME_ASSET = "awesome.json"

    fun parseAssets(assets: AssetManager): Catalog {
        val tweaksDoc = JSONObject(readAsset(assets, TWEAKS_ASSET))
        val profilesDoc = runCatching { JSONObject(readAsset(assets, PROFILES_ASSET)) }.getOrNull()
        val awesomeDoc = runCatching { JSONObject(readAsset(assets, AWESOME_ASSET)) }.getOrNull()
        return parse(tweaksDoc, profilesDoc, awesomeDoc)
    }

    fun readAsset(assets: AssetManager, name: String): String =
        assets.open(name).bufferedReader().use { it.readText() }

    fun parse(tweaksDoc: JSONObject, profilesDoc: JSONObject?, awesomeDoc: JSONObject?): Catalog =
        Catalog(
            version = tweaksDoc.optInt("catalogVersion", 1),
            updatedAt = tweaksDoc.optString("updatedAt"),
            categories = tweaksDoc.optJSONArray("categories").mapObjects { json ->
                Category(
                    id = json.getString("id"),
                    name = json.optString("name", json.getString("id")),
                    blurb = json.optString("blurb"),
                    icon = json.optString("icon"),
                )
            },
            // A single malformed entry must not take the whole catalog down.
            tweaks = tweaksDoc.optJSONArray("tweaks")
                .mapObjects { json -> runCatching { Tweak.fromJson(json) }.getOrNull() }
                .filterNotNull(),
            profiles = profilesDoc?.optJSONArray("profiles").mapObjects { json ->
                Profile(
                    id = json.getString("id"),
                    name = json.optString("name", json.getString("id")),
                    blurb = json.optString("blurb"),
                    icon = json.optString("icon"),
                    tweaks = json.optJSONArray("tweaks").strings(),
                )
            } ?: emptyList(),
            awesome = awesomeDoc?.optJSONArray("sections").mapObjects { json ->
                AwesomeSection(
                    id = json.getString("id"),
                    name = json.optString("name", json.getString("id")),
                    blurb = json.optString("blurb"),
                    entries = json.optJSONArray("entries").mapObjects { entry ->
                        AwesomeEntry(
                            name = entry.optString("name"),
                            repo = entry.optString("repo"),
                            url = entry.optString("url"),
                            access = entry.optString("access", "no-root"),
                            description = entry.optString("description"),
                            tags = entry.optJSONArray("tags").strings(),
                        )
                    },
                )
            } ?: emptyList(),
        )

    private fun JSONArray?.strings(): List<String> {
        if (this == null) return emptyList()
        return List(length()) { optString(it) }
    }

    private inline fun <T> JSONArray?.mapObjects(transform: (JSONObject) -> T): List<T> {
        if (this == null) return emptyList()
        val out = ArrayList<T>(length())
        for (index in 0 until length()) {
            optJSONObject(index)?.let { out.add(transform(it)) }
        }
        return out
    }
}
