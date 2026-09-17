package dev.cameleonnbss.originostoolkit

import dev.cameleonnbss.originostoolkit.core.IslandRecast
import dev.cameleonnbss.originostoolkit.core.OriginIsland.OriginIslandTemplate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Test

/** The recast rules: progress overrides, title fallback, text cleaning. */
class IslandRecastTest {

    @Test
    fun `a live progress bar forces the progress template`() {
        val decision = IslandRecast.decide(OriginIslandTemplate.CAPSULE, 40, 100)
        assertEquals(OriginIslandTemplate.PROGRESS, decision.template)
        assertEquals(40, decision.progress)
        assertEquals(100, decision.progressMax)
        assertFalse(decision.skip)
    }

    @Test
    fun `an app without progress keeps its configured template`() {
        val decision = IslandRecast.decide(OriginIslandTemplate.TEXT_ICON, 0, 0)
        assertEquals(OriginIslandTemplate.TEXT_ICON, decision.template)
        assertEquals(0, decision.progress)
        assertEquals(0, decision.progressMax)
    }

    @Test
    fun `finished and not-started progress do not count as live`() {
        // 0/max and max/max are not running bars; keep the configured template.
        assertEquals(
            OriginIslandTemplate.RHYTHM,
            IslandRecast.decide(OriginIslandTemplate.RHYTHM, 0, 100).template,
        )
        assertEquals(
            OriginIslandTemplate.RHYTHM,
            IslandRecast.decide(OriginIslandTemplate.RHYTHM, 100, 100).template,
        )
    }

    @Test
    fun `display title prefers the friendly label`() {
        assertEquals("Spotify", IslandRecast.displayTitle("Spotify", "com.spotify.music"))
        assertEquals("com.unknown.app", IslandRecast.displayTitle(null, "com.unknown.app"))
        assertEquals("com.blank.app", IslandRecast.displayTitle("  ", "com.blank.app"))
    }

    @Test
    fun `text cleaning flattens spans and drops blanks`() {
        assertEquals("Playing", IslandRecast.cleanText("  Playing "))
        assertNull(IslandRecast.cleanText("   "))
        assertNull(IslandRecast.cleanText(null))
    }
}
