package cn.gbk.logbacklens.settings

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.PersistentStateComponent
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.State
import com.intellij.openapi.components.Storage

data class LogLensApplicationState(
    var messageLensEnabled: Boolean = true,
    var routeLensEnabled: Boolean = true,
    var messageExpressionColor: String = "#7A7A7A",
    var routeColor: String = "#6A8759",
)

fun interface LogLensSettingsListener {
    fun settingsChanged(state: LogLensApplicationState)
}

@Service(Service.Level.APP)
@State(name = "LogLensApplicationSettings", storages = [Storage("logLens.xml")])
class LogLensApplicationSettings : PersistentStateComponent<LogLensApplicationState> {
    private var state = LogLensApplicationState()

    override fun getState(): LogLensApplicationState = state.copy()

    override fun loadState(state: LogLensApplicationState) {
        this.state = state.copy()
        publishChange()
    }

    fun snapshot(): LogLensApplicationState = state.copy()

    fun update(newState: LogLensApplicationState) {
        val normalized = newState.copy(
            messageExpressionColor = normalizeColor(newState.messageExpressionColor, DEFAULT_MESSAGE_COLOR),
            routeColor = normalizeColor(newState.routeColor, DEFAULT_ROUTE_COLOR),
        )
        if (normalized == state) return
        state = normalized
        publishChange()
    }

    private fun publishChange() {
        ApplicationManager.getApplication()
            ?.messageBus
            ?.syncPublisher(LOG_LENS_SETTINGS_TOPIC)
            ?.settingsChanged(snapshot())
    }

    companion object {
        const val DEFAULT_MESSAGE_COLOR = "#7A7A7A"
        const val DEFAULT_ROUTE_COLOR = "#6A8759"

        fun getInstance(): LogLensApplicationSettings =
            ApplicationManager.getApplication().getService(LogLensApplicationSettings::class.java)

        internal fun normalizeColor(value: String, fallback: String): String {
            val candidate = value.trim().uppercase()
            return if (HEX_COLOR.matches(candidate)) candidate else fallback
        }

        private val HEX_COLOR = Regex("#[0-9A-F]{6}")
    }
}
