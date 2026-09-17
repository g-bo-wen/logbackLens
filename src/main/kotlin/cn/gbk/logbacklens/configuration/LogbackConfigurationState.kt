package cn.gbk.logbacklens.configuration

import cn.gbk.logbacklens.logback.LogbackParseResult
import cn.gbk.logbacklens.logback.LogbackSnapshot
import cn.gbk.logbacklens.model.ConfigurationHealth
import cn.gbk.logbacklens.model.ConfigurationSummary
import cn.gbk.logbacklens.model.DiagnosticSeverity
import cn.gbk.logbacklens.model.LogLensDiagnostic
import cn.gbk.logbacklens.model.SourceLocation
import cn.gbk.logbacklens.settings.LogbackBinding

data class LogbackConfigurationState(
    val generation: Long,
    val binding: LogbackBinding?,
    val snapshot: LogbackSnapshot?,
    val summary: ConfigurationSummary,
)

internal class LogbackGenerationStore {
    @Volatile
    private var state = unbound(0)

    fun current(): LogbackConfigurationState = state

    @Synchronized
    fun begin(binding: LogbackBinding): Long {
        val generation = state.generation + 1
        state = LogbackConfigurationState(
            generation,
            binding.copy(),
            snapshot = null,
            summary = ConfigurationSummary(ConfigurationHealth.LOADING),
        )
        return generation
    }

    @Synchronized
    fun publish(generation: Long, binding: LogbackBinding, result: LogbackParseResult): Boolean {
        if (generation != state.generation || binding != state.binding) return false
        state = LogbackConfigurationState(generation, binding.copy(), result.snapshot, result.summary())
        return true
    }

    @Synchronized
    fun publishUnavailable(generation: Long, binding: LogbackBinding, path: String): Boolean {
        if (generation != state.generation || binding != state.binding) return false
        val diagnostic = LogLensDiagnostic(
            code = "BOUND_FILE_UNAVAILABLE",
            message = "The bound Logback file is missing or unreadable.",
            severity = DiagnosticSeverity.ERROR,
            source = SourceLocation(path),
        )
        state = LogbackConfigurationState(
            generation,
            binding.copy(),
            snapshot = null,
            summary = ConfigurationSummary(ConfigurationHealth.ERROR, diagnostics = listOf(diagnostic)),
        )
        return true
    }

    @Synchronized
    fun clear(): LogbackConfigurationState {
        state = unbound(state.generation + 1)
        return state
    }

    companion object {
        private fun unbound(generation: Long) = LogbackConfigurationState(
            generation,
            binding = null,
            snapshot = null,
            summary = ConfigurationSummary(ConfigurationHealth.UNBOUND),
        )
    }
}

fun interface LogbackConfigurationListener {
    fun configurationChanged(state: LogbackConfigurationState)
}
