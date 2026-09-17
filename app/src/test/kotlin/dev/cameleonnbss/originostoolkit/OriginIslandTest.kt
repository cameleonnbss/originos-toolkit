package dev.cameleonnbss.originostoolkit

import android.graphics.drawable.Icon
import dev.cameleonnbss.originostoolkit.core.OriginIsland
import dev.cameleonnbss.originostoolkit.core.OriginIsland.SuperXSink
import dev.cameleonnbss.originostoolkit.core.OriginIsland.OriginIslandTemplate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The invariants of the `notification.superx.*` payload, mirrored against the
 * upstream CunnyPlayground implementation so a refactor cannot silently drift
 * from the wire format the OriginOS 6 framework expects.
 *
 * `android.os.Bundle` is a no-op stub on the JVM, so the tests drive the
 * payload through a recording [SuperXSink] — the same writer the device runs,
 * with a map instead of a Bundle at the far end.
 */
class OriginIslandTest {

    /** Records every write the builder makes, nested included. */
    private class RecordingSink : SuperXSink {
        val values = mutableMapOf<String, Any?>()
        val children = mutableMapOf<String, RecordingSink>()

        override fun child(): RecordingSink = RecordingSink()

        override fun putInt(key: String, value: Int) {
            values[key] = value
        }

        override fun putBoolean(key: String, value: Boolean) {
            values[key] = value
        }

        override fun putString(key: String, value: String?) {
            values[key] = value
        }

        override fun putCharSequence(key: String, value: CharSequence?) {
            values[key] = value?.toString()
        }

        override fun putIntList(key: String, value: List<Int>?) {
            values[key] = value
        }

        override fun putIcon(key: String, value: Icon?) {
            values[key] = value
        }

        override fun putIconList(key: String, value: List<Icon>?) {
            values[key] = value
        }

        override fun putBundle(key: String, value: SuperXSink) {
            children[key] = value as RecordingSink
        }

        fun int(key: String): Int = values[key] as Int
        fun string(key: String): String = values[key] as String
        fun child(key: String): RecordingSink = children.getValue(key)
    }

    private fun write(
        template: OriginIslandTemplate = OriginIslandTemplate.CAPSULE,
        transform: (OriginIsland.Request) -> OriginIsland.Request = { it },
    ): RecordingSink {
        val request = transform(baseRequest(template))
        // The request's own accent color flows through, exactly like the
        // production call path (omitted arguments pick up their defaults; an
        // explicit null would suppress the color keys).
        return RecordingSink().also { OriginIsland.writeExtras(request, null, request.accentColor, it) }
    }

    private fun baseRequest(template: OriginIslandTemplate = OriginIslandTemplate.CAPSULE) =
        OriginIsland.Request(
            id = 1,
            title = "Title",
            content = "Body",
            subText = "Sub",
            template = template,
            progress = 50,
            progressMax = 100,
            leftText = "Left",
            rightText = "Right",
            accentColor = 0xFF112233.toInt(),
        )

    @Test
    fun `show operation uses the upstream scene and base template`() {
        val extras = write()

        assertEquals(OriginIsland.OP_SHOW, extras.int("notification.superx.operation"))
        assertEquals(true, extras.values["notification.superx.showNotify"])
        assertEquals(OriginIsland.SCENE, extras.string("notification.superx.scene"))
        assertEquals(1, extras.int("notification.superx.template"))
        assertEquals(0, extras.int("notification.superx.changedRecord"))
    }

    @Test
    fun `baseInfos and capsule carry the payload the template resolver needs`() {
        val extras = write()

        val baseInfos = extras.child("notification.superx.baseInfos")
        assertEquals("Title", baseInfos.string("notification.superx.baseInfos.title"))
        assertEquals("Body", baseInfos.string("notification.superx.baseInfos.content"))
        assertEquals(1, baseInfos.int("notification.superx.baseInfos.subInfo"))
        assertEquals("Sub", baseInfos.string("notification.superx.baseInfos.subText"))

        val capsule = extras.child("notification.superx.capsule")
        assertEquals(1, capsule.int("notification.superx.capsule.state"))
        assertEquals("Title", capsule.string("notification.superx.capsule.content"))
    }

    @Test
    fun `non-progress template fills infos and shortInfos symmetrically`() {
        val extras = write()

        val infos = extras.child("notification.superx.infos")
        assertEquals("Sub", infos.string("notification.superx.infos.describe"))
        assertEquals("Body", infos.string("notification.superx.infos.coreInfo"))

        val shortInfos = extras.child("notification.superx.shortInfos")
        assertEquals("Sub", shortInfos.string("notification.superx.shortInfos.describeShort"))
        assertEquals("Body", shortInfos.string("notification.superx.shortInfos.coreInfoShort"))
    }

    @Test
    fun `island keeps the fixed left template and the chosen right one`() {
        val extras = write(OriginIslandTemplate.PROGRESS)
        val island = extras.child("notification.superx.island")

        assertEquals(1, island.int("island.superx.leftTemplate"))
        assertEquals(2, island.int("island.superx.rightTemplate"))

        assertEquals(
            "Left",
            island.child("island.superx.leftInfo").string("island.superx.leftInfo.content"),
        )
    }

    @Test
    fun `each right template writes its own keys`() {
        val rhythm = write(OriginIslandTemplate.RHYTHM).child("notification.superx.island")
            .child("island.superx.rightInfo")
        assertEquals(1, rhythm.int("island.superx.rightInfo.waveState"))
        assertEquals(listOf(0xFF112233.toInt()), rhythm.values["island.superx.rightInfo.waveColor"])

        val progress = write(OriginIslandTemplate.PROGRESS).child("notification.superx.island")
            .child("island.superx.rightInfo")
        assertEquals(50, progress.int("island.superx.rightInfo.progressValue"))
        assertEquals(0, progress.int("island.superx.rightInfo.progressState"))
        assertEquals(0xFF112233.toInt(), progress.int("island.superx.rightInfo.progressColor"))

        val loading = write(OriginIslandTemplate.LOADING).child("notification.superx.island")
            .child("island.superx.rightInfo")
        assertEquals(0xFF112233.toInt(), loading.int("island.superx.rightInfo.loadingColor"))

        val textIcon = write(OriginIslandTemplate.TEXT_ICON).child("notification.superx.island")
            .child("island.superx.rightInfo")
        assertEquals("Right", textIcon.string("island.superx.rightInfo.content"))

        val capsule = write(OriginIslandTemplate.CAPSULE).child("notification.superx.island")
            .child("island.superx.rightInfo")
        assertEquals("Right", capsule.string("island.superx.rightInfo.capsuleContent"))
        assertEquals(0xFF112233.toInt(), capsule.int("island.superx.rightInfo.capsuleBgColor"))
    }

    @Test
    fun `progress mode switches the card template and fills progress keys`() {
        val extras = write(OriginIslandTemplate.PROGRESS) { it.copy(progress = 25, progressMax = 100) }

        assertEquals(2, extras.int("notification.superx.template"))
        assertEquals(
            25,
            extras.child("notification.superx.infos").int("notification.superx.infos.progress"),
        )
    }

    @Test
    fun `progress at or beyond max falls back to the base template like upstream`() {
        // Upstream's isProgressMode is `0 < progress < progressMax`: at 150 of
        // 100 the island shows the base card, which writes no progress keys.
        val done = write(OriginIslandTemplate.PROGRESS) { it.copy(progress = 150, progressMax = 100) }
        assertEquals(1, done.int("notification.superx.template"))
        val infos = done.child("notification.superx.infos")
        assertFalse(infos.values.containsKey("notification.superx.infos.progress"))
        assertEquals("Sub", infos.string("notification.superx.infos.describe"))

        // The clamped percent still exists for the templates that do render it.
        assertEquals(
            100,
            baseRequest(OriginIslandTemplate.PROGRESS)
                .copy(progress = 150, progressMax = 100)
                .progressPercent,
        )

        val empty = write(OriginIslandTemplate.PROGRESS) { it.copy(progress = 0, progressMax = 0) }
        assertEquals(1, empty.int("notification.superx.template"))
    }

    @Test
    fun `left and right text fall back like upstream`() {
        val extras = write { it.copy(leftText = "", rightText = null) }
        val island = extras.child("notification.superx.island")

        assertEquals(
            "Title",
            island.child("island.superx.leftInfo").string("island.superx.leftInfo.content"),
        )
        assertEquals(
            "Body",
            island.child("island.superx.rightInfo").string("island.superx.rightInfo.capsuleContent"),
        )
    }

    @Test
    fun `blank subtext omits the subInfo keys instead of sending empties`() {
        val extras = write { it.copy(subText = null) }
        val baseInfos = extras.child("notification.superx.baseInfos")

        assertFalse(baseInfos.values.containsKey("notification.superx.baseInfos.subInfo"))
        assertFalse(baseInfos.values.containsKey("notification.superx.baseInfos.subText"))
        assertEquals(
            "Title",
            baseInfos.string("notification.superx.baseInfos.title"),
        )
        assertTrue(baseInfos.values.isNotEmpty())
    }

    @Test
    fun `end extras carry only the unmount operation`() {
        val end = RecordingSink().also { OriginIsland.writeEndExtras(it) }
        assertEquals(OriginIsland.OP_END, end.int("notification.superx.operation"))
        assertEquals(1, end.values.size)
    }

    @Test
    fun `template ids match the upstream wire values`() {
        assertEquals(1, OriginIslandTemplate.RHYTHM.id)
        assertEquals(2, OriginIslandTemplate.PROGRESS.id)
        assertEquals(3, OriginIslandTemplate.LOADING.id)
        assertEquals(4, OriginIslandTemplate.TEXT_ICON.id)
        assertEquals(5, OriginIslandTemplate.ICON_TEXT.id)
        assertEquals(6, OriginIslandTemplate.CAPSULE.id)
        assertEquals(OriginIslandTemplate.CAPSULE, OriginIslandTemplate.fromId(99))
    }

    @Test
    fun `brand probe is null-safe and answers false off vivo hardware`() {
        // On the JVM the Build fields are null: the probe must not throw and
        // must not claim support it cannot see.
        assertFalse(OriginIsland.vivoBrand())
    }

    @Test
    fun `the device path returns real bundles without throwing`() {
        // On the JVM `android.os.Bundle` is a stub, so the values cannot be
        // asserted here — the recording-sink tests above pin the wire format.
        // This is the smoke test that the production sink path builds cleanly.
        val request = baseRequest(OriginIslandTemplate.PROGRESS).copy(progress = 25)
        assertNotNull(OriginIsland.buildExtras(request, smallIcon = null))
        assertNotNull(OriginIsland.buildEndExtras())
    }
}
