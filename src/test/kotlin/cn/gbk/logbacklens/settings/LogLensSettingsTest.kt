package cn.gbk.logbacklens.settings

import com.intellij.openapi.components.RoamingType
import com.intellij.openapi.components.State
import com.intellij.openapi.components.StoragePathMacros
import com.intellij.util.xmlb.XmlSerializer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class LogLensSettingsTest {
    @Test
    fun `application defaults enable both lenses and contain no project path`() {
        val state = LogLensApplicationState()

        assertTrue(state.messageLensEnabled)
        assertTrue(state.routeLensEnabled)
        assertEquals("#7A7A7A", state.messageExpressionColor)
        assertEquals("#6A8759", state.routeColor)
        assertFalse(state.toString().contains("path", ignoreCase = true))
    }

    @Test
    fun `colors are normalized without accepting malformed values`() {
        assertEquals("#A0B1C2", LogLensApplicationSettings.normalizeColor(" #a0b1c2 ", "#000000"))
        assertEquals("#000000", LogLensApplicationSettings.normalizeColor("red", "#000000"))
    }

    @Test
    fun `project binding round trips as a defensive copy`() {
        val original = LogLensProjectState(LogbackBinding("config/logback.xml", BindingPathKind.PROJECT_RELATIVE))
        val copied = original.copy(binding = original.binding?.copy())

        original.binding?.path = "changed.xml"

        assertEquals("config/logback.xml", copied.binding?.path)
        assertEquals(BindingPathKind.PROJECT_RELATIVE, copied.binding?.kind)
    }

    @Test
    fun `application state round trips through IntelliJ serialization`() {
        val original = LogLensApplicationState(
            messageLensEnabled = false,
            routeLensEnabled = true,
            messageExpressionColor = "#123456",
            routeColor = "#ABCDEF",
        )

        val restored = XmlSerializer.deserialize(XmlSerializer.serialize(original), LogLensApplicationState::class.java)

        assertEquals(original, restored)
    }

    @Test
    fun `project state round trips relative and absolute binding kinds`() {
        for (binding in listOf(
            LogbackBinding("config/logback.xml", BindingPathKind.PROJECT_RELATIVE),
            LogbackBinding("D:\\logs\\logback.xml", BindingPathKind.ABSOLUTE),
        )) {
            val original = LogLensProjectState(binding)

            val restored = XmlSerializer.deserialize(XmlSerializer.serialize(original), LogLensProjectState::class.java)

            assertEquals(original, restored)
        }
    }

    @Test
    fun `unbound project state stores no path`() {
        assertNull(LogLensProjectState().binding)
    }

    @Test
    fun `project state is workspace local and non roaming`() {
        val annotation = LogLensProjectSettings::class.java.getAnnotation(State::class.java)
        val storage = annotation.storages.single()

        assertEquals(StoragePathMacros.WORKSPACE_FILE, storage.value)
        assertEquals(RoamingType.DISABLED, storage.roamingType)
    }
}
