package cn.gbk.logbacklens.configuration

import cn.gbk.logbacklens.logback.LogbackXmlParser
import cn.gbk.logbacklens.model.ConfigurationHealth
import cn.gbk.logbacklens.settings.BindingPathKind
import cn.gbk.logbacklens.settings.LogbackBinding
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class LogbackGenerationStoreTest {
    private val binding = LogbackBinding("config/logback.xml", BindingPathKind.PROJECT_RELATIVE)

    @Test
    fun `new generation clears old snapshot and stale result cannot win`() {
        val store = LogbackGenerationStore()
        val firstGeneration = store.begin(binding)
        val valid = LogbackXmlParser.parse(
            """<configuration><root level="INFO"/></configuration>""",
            "config/logback.xml",
        )
        assertTrue(store.publish(firstGeneration, binding, valid))
        assertTrue(store.current().snapshot != null)

        val secondGeneration = store.begin(binding)
        assertEquals(ConfigurationHealth.LOADING, store.current().summary.health)
        assertNull(store.current().snapshot)
        assertFalse(store.publish(firstGeneration, binding, valid))
        assertEquals(secondGeneration, store.current().generation)
        assertNull(store.current().snapshot)
    }

    @Test
    fun `fatal parse atomically clears route snapshot but retains binding and diagnostics`() {
        val store = LogbackGenerationStore()
        val generation = store.begin(binding)
        val fatal = LogbackXmlParser.parse("<configuration>", "config/logback.xml")

        assertTrue(store.publish(generation, binding, fatal))

        val state = store.current()
        assertEquals(binding, state.binding)
        assertNull(state.snapshot)
        assertEquals(ConfigurationHealth.ERROR, state.summary.health)
        assertTrue(state.summary.diagnostics.isNotEmpty())
    }

    @Test
    fun `unavailable and clear preserve their distinct states`() {
        val store = LogbackGenerationStore()
        val generation = store.begin(binding)
        assertTrue(store.publishUnavailable(generation, binding, "config/logback.xml"))
        assertEquals(ConfigurationHealth.ERROR, store.current().summary.health)
        assertEquals(binding, store.current().binding)
        assertNull(store.current().snapshot)

        val cleared = store.clear()
        assertEquals(ConfigurationHealth.UNBOUND, cleared.summary.health)
        assertNull(cleared.binding)
        assertNull(cleared.snapshot)
    }
}
