package dev.cameleonnbss.originostoolkit

import dev.cameleonnbss.originostoolkit.core.model.Catalog
import dev.cameleonnbss.originostoolkit.core.model.CatalogParser
import dev.cameleonnbss.originostoolkit.core.model.Tweak
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Validates the *real* catalog — the same files the app ships in its assets and
 * the CLI reads from disk. A bad entry fails `./gradlew test` instead of
 * reaching a phone.
 */
class CatalogTest {

    private val catalog: Catalog = run {
        val dir = requireNotNull(System.getProperty("originos.catalog.dir")) {
            "originos.catalog.dir must be set by the build (see app/build.gradle.kts)"
        }
        CatalogParser.parse(
            tweaksDoc = JSONObject(File(dir, "tweaks.json").readText()),
            profilesDoc = JSONObject(File(dir, "profiles.json").readText()),
            awesomeDoc = JSONObject(File(dir, "awesome.json").readText()),
        )
    }

    @Test
    fun `catalog version is supported`() {
        assertEquals(1, catalog.version)
    }

    @Test
    fun `contains a useful number of tweaks`() {
        assertTrue("expected >= 25 tweaks, got ${catalog.tweaks.size}", catalog.tweaks.size >= 25)
    }

    @Test
    fun `tweak ids are unique and kebab-cased`() {
        val ids = catalog.tweaks.map { it.id }
        assertEquals(ids.size, ids.distinct().size)
        ids.forEach { id -> assertTrue("bad id: $id", Regex("^[a-z0-9]+(-[a-z0-9]+)*$").matches(id)) }
    }

    @Test
    fun `every tweak is undoable or declares that it is not`() {
        val broken = catalog.tweaks.filter { tweak ->
            !tweak.reversible && !tweak.declaredOneShot
        }
        assertTrue("not revertible: ${broken.map { it.id }}", broken.isEmpty())
    }

    @Test
    fun `one shot tweaks are declared`() {
        val notDeclared = catalog.tweaks.filter { tweak ->
            tweak.revert.isEmpty() &&
                tweak.actions.any { it.op !in Tweak.AUTO_INVERTIBLE } &&
                !tweak.declaredOneShot
        }
        assertTrue("undeclared one-shot: ${notDeclared.map { it.id }}", notDeclared.isEmpty())
    }

    @Test
    fun `every tweak has documentation and an OriginOS list`() {
        catalog.tweaks.forEach { tweak ->
            assertTrue("${tweak.id}: summary too short", tweak.summary.length > 30)
            assertTrue("${tweak.id}: details too short", tweak.details.length > 40)
            assertTrue("${tweak.id}: no originOs list", tweak.originOs.isNotEmpty())
        }
    }

    @Test
    fun `every category is used`() {
        val used = catalog.tweaks.map { it.category }.toSet()
        catalog.categories.forEach { category ->
            assertTrue("empty category ${category.id}", used.contains(category.id))
        }
    }

    @Test
    fun `profiles reference real tweaks`() {
        assertTrue(catalog.profiles.size >= 5)
        catalog.profiles.forEach { profile ->
            assertTrue("empty profile ${profile.id}", profile.tweaks.isNotEmpty())
            profile.tweaks.forEach { id -> assertNotNull("profile ${profile.id} -> $id", catalog.tweak(id)) }
        }
    }

    @Test
    fun `no profile bundles a high risk tweak`() {
        catalog.profiles.forEach { profile ->
            profile.tweaks.forEach { id ->
                val tweak = catalog.tweak(id)
                assertFalse(
                    "${profile.id} bundles the high-risk tweak $id",
                    tweak?.risk?.id == "high",
                )
            }
        }
    }

    @Test
    fun `density tweak uses a relative delta`() {
        val tweak = catalog.tweak("density-compact")
        assertNotNull(tweak)
        assertEquals(listOf("wm_density_delta"), tweak!!.actions.map { it.op })
    }

    @Test
    fun `resolve expands profiles without duplicates`() {
        val resolved = catalog.resolve(listOf("battery-max", "disable-nfc", "daily-balanced"))
        val ids = resolved.map { it.id }
        assertEquals(ids.size, ids.distinct().size)
        assertTrue(resolved.any { it.id == "disable-nfc" })
    }

    @Test
    fun `the curated list contains the repositories this project was inspired by`() {
        val repos = catalog.awesome.flatMap { section -> section.entries.map { it.repo } }.toSet()
        listOf(
            "ewfawfasdf/VivoIQOO144FPSUnlocker",
            "Astreas-Core/otweak",
            "timschneeb/awesome-shizuku",
            "0x192/universal-android-debloater",
            "RikkaApps/Shizuku",
        ).forEach { expected -> assertTrue("missing $expected", repos.contains(expected)) }
    }

    @Test
    fun `curated entries use https`() {
        catalog.awesome.forEach { section ->
            section.entries.forEach { entry ->
                assertTrue(entry.name, entry.url.startsWith("https://"))
            }
        }
    }

    @Test
    fun `a malformed tweak is skipped rather than fatal`() {
        val doc = JSONObject(
            """
            {
              "catalogVersion": 1,
              "categories": [{"id": "display", "name": "Display"}],
              "tweaks": [
                {"id": "ok", "name": "Ok", "category": "display", "summary": "s", "actions": []},
                {"name": "no id", "category": "display"}
              ]
            }
            """.trimIndent(),
        )
        val parsed = CatalogParser.parse(doc, null, null)
        assertEquals(1, parsed.tweaks.size)
        assertEquals("ok", parsed.tweaks.first().id)
    }
}
