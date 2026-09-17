package cn.gbk.logbacklens.ui

import cn.gbk.logbacklens.configuration.LogbackConfigurationState
import cn.gbk.logbacklens.model.ConfigurationHealth
import cn.gbk.logbacklens.model.ConfigurationSummary
import cn.gbk.logbacklens.model.DiagnosticSeverity
import cn.gbk.logbacklens.model.LogLensDiagnostic
import cn.gbk.logbacklens.settings.BindingPathKind
import cn.gbk.logbacklens.settings.LogLensApplicationState
import cn.gbk.logbacklens.settings.LogbackBinding
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LogLensUiTextTest {
    @Test
    fun `status text distinguishes unbound healthy loading warning and error`() {
        assertEquals("○ Log Lens", LogLensUiText.statusText(state(ConfigurationHealth.UNBOUND, bound = false)))
        assertEquals("● Log Lens", LogLensUiText.statusText(state(ConfigurationHealth.LOADING)))
        assertEquals("● Log Lens", LogLensUiText.statusText(state(ConfigurationHealth.READY)))
        assertEquals("⚠ Log Lens", LogLensUiText.statusText(state(ConfigurationHealth.WARNING)))
        assertEquals("⚠ Log Lens", LogLensUiText.statusText(state(ConfigurationHealth.ERROR)))
    }

    @Test
    fun `preview follows independent message route and color settings without claiming project data`() {
        val enabled = LogLensApplicationState(
            messageExpressionColor = "#112233",
            routeColor = "#445566",
        )
        val enabledPreview = LogLensUiText.previewHtml(enabled)
        assertTrue(enabledPreview.contains("‹orderId›"))
        assertTrue(enabledPreview.contains("sample/app.log"))
        assertTrue(enabledPreview.contains("#112233"))
        assertTrue(enabledPreview.contains("#445566"))
        assertTrue(enabledPreview.contains("Sample preview"))

        val disabledPreview = LogLensUiText.previewHtml(
            enabled.copy(messageLensEnabled = false, routeLensEnabled = false),
        )
        assertTrue(disabledPreview.contains("order {}"))
        assertFalse(disabledPreview.contains("sample/app.log"))
    }

    private fun state(health: ConfigurationHealth, bound: Boolean = true): LogbackConfigurationState =
        LogbackConfigurationState(
            generation = 1,
            binding = if (bound) LogbackBinding("logback.xml", BindingPathKind.PROJECT_RELATIVE) else null,
            snapshot = null,
            summary = ConfigurationSummary(
                health,
                diagnostics = if (health == ConfigurationHealth.WARNING) {
                    listOf(LogLensDiagnostic("WARN", "warning", DiagnosticSeverity.WARNING))
                } else {
                    emptyList()
                },
            ),
        )
}
