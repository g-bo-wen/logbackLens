package cn.gbk.logbacklens.settings

import com.intellij.openapi.components.PersistentStateComponent
import com.intellij.openapi.components.RoamingType
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.State
import com.intellij.openapi.components.Storage
import com.intellij.openapi.components.StoragePathMacros
import com.intellij.openapi.project.Project

enum class BindingPathKind {
    PROJECT_RELATIVE,
    ABSOLUTE,
}

data class LogbackBinding(
    var path: String = "",
    var kind: BindingPathKind = BindingPathKind.PROJECT_RELATIVE,
) {
    fun isConfigured(): Boolean = path.isNotBlank()
}

data class LogLensProjectState(
    var binding: LogbackBinding? = null,
)

fun interface LogLensBindingListener {
    fun bindingChanged(binding: LogbackBinding?)
}

@Service(Service.Level.PROJECT)
@State(
    name = "LogLensProjectSettings",
    storages = [Storage(value = StoragePathMacros.WORKSPACE_FILE, roamingType = RoamingType.DISABLED)],
)
class LogLensProjectSettings(private val project: Project) : PersistentStateComponent<LogLensProjectState> {
    private var state = LogLensProjectState()

    override fun getState(): LogLensProjectState = state.deepCopy()

    override fun loadState(state: LogLensProjectState) {
        this.state = state.deepCopy()
        publishChange()
    }

    fun binding(): LogbackBinding? = state.binding?.copy()

    fun bind(binding: LogbackBinding) {
        val normalized = binding.copy(path = binding.path.trim())
        require(normalized.isConfigured()) { "Logback binding path must not be blank" }
        if (state.binding == normalized) return
        state = LogLensProjectState(normalized)
        publishChange()
    }

    fun clear() {
        if (state.binding == null) return
        state = LogLensProjectState()
        publishChange()
    }

    private fun publishChange() {
        if (!project.isDisposed) {
            project.messageBus.syncPublisher(LOG_LENS_BINDING_TOPIC).bindingChanged(binding())
        }
    }

    companion object {
        fun getInstance(project: Project): LogLensProjectSettings =
            project.getService(LogLensProjectSettings::class.java)
    }
}

private fun LogLensProjectState.deepCopy() = copy(binding = binding?.copy())
