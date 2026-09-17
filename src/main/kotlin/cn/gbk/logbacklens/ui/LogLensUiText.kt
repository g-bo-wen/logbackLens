package cn.gbk.logbacklens.ui

import cn.gbk.logbacklens.configuration.LogbackConfigurationState
import cn.gbk.logbacklens.model.ConfigurationHealth
import cn.gbk.logbacklens.settings.LogLensApplicationState

internal object LogLensUiText {
    fun statusText(state: LogbackConfigurationState): String = when {
        state.binding == null -> "○ Log Lens"
        state.summary.health == ConfigurationHealth.ERROR || state.summary.health == ConfigurationHealth.WARNING ->
            "⚠ Log Lens"
        else -> "● Log Lens"
    }

    fun tooltip(state: LogbackConfigurationState): String = when (state.summary.health) {
        ConfigurationHealth.UNBOUND -> "Log Lens: no Logback file bound. Message Lens remains available."
        ConfigurationHealth.LOADING -> "Log Lens: parsing the bound Logback file."
        ConfigurationHealth.READY ->
            "Log Lens: ${state.summary.loggerCount} logger(s), ${state.summary.appenderCount} appender(s), ${state.summary.destinationCount} destination(s)."
        ConfigurationHealth.WARNING ->
            "Log Lens: parsed with ${state.summary.diagnostics.size} warning(s)."
        ConfigurationHealth.ERROR -> "Log Lens: the bound Logback file is unavailable or invalid."
    }

    fun previewHtml(settings: LogLensApplicationState): String {
        val message = if (settings.messageLensEnabled) {
            "order <font color='${settings.messageExpressionColor}'>‹orderId›</font>"
        } else {
            "order {}"
        }
        val route = if (settings.routeLensEnabled) {
            " <font color='${settings.routeColor}'>→ sample/app.log</font>"
        } else {
            ""
        }
        return "<html><b>Sample preview</b><br/>log.info(&quot;$message&quot;, orderId);$route</html>"
    }
}
