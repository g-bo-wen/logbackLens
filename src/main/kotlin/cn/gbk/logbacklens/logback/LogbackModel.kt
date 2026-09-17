package cn.gbk.logbacklens.logback

import cn.gbk.logbacklens.model.ConfigurationHealth
import cn.gbk.logbacklens.model.ConfigurationSummary
import cn.gbk.logbacklens.model.LogLensDiagnostic
import cn.gbk.logbacklens.model.SourceLocation

enum class LogLevel(val priority: Int) {
    ALL(Int.MIN_VALUE),
    TRACE(0),
    DEBUG(10),
    INFO(20),
    WARN(30),
    ERROR(40),
    OFF(Int.MAX_VALUE),
}

data class LoggerDefinition(
    val name: String,
    val level: LogLevel?,
    val additive: Boolean,
    val appenderRefs: List<String>,
    val uncertain: Boolean,
    val source: SourceLocation,
)

data class RootDefinition(
    val level: LogLevel?,
    val appenderRefs: List<String>,
    val uncertain: Boolean,
    val source: SourceLocation,
)

data class ThresholdDefinition(
    val level: LogLevel,
    val source: SourceLocation,
)

sealed interface AppenderDefinition {
    val name: String
    val appenderRefs: List<String>
    val thresholds: List<ThresholdDefinition>
    val uncertain: Boolean
    val source: SourceLocation
}

data class FileAppenderDefinition(
    override val name: String,
    val file: String,
    override val thresholds: List<ThresholdDefinition>,
    override val uncertain: Boolean,
    override val source: SourceLocation,
) : AppenderDefinition {
    override val appenderRefs: List<String> = emptyList()
}

data class RollingFileAppenderDefinition(
    override val name: String,
    val file: String?,
    val fileNamePattern: String?,
    override val thresholds: List<ThresholdDefinition>,
    override val uncertain: Boolean,
    override val source: SourceLocation,
) : AppenderDefinition {
    override val appenderRefs: List<String> = emptyList()
}

data class AsyncAppenderDefinition(
    override val name: String,
    override val appenderRefs: List<String>,
    override val thresholds: List<ThresholdDefinition>,
    override val uncertain: Boolean,
    override val source: SourceLocation,
) : AppenderDefinition

data class ConsoleAppenderDefinition(
    override val name: String,
    override val thresholds: List<ThresholdDefinition>,
    override val uncertain: Boolean,
    override val source: SourceLocation,
) : AppenderDefinition {
    override val appenderRefs: List<String> = emptyList()
}

data class UnsupportedAppenderDefinition(
    override val name: String,
    val className: String,
    override val source: SourceLocation,
) : AppenderDefinition {
    override val appenderRefs: List<String> = emptyList()
    override val thresholds: List<ThresholdDefinition> = emptyList()
    override val uncertain: Boolean = true
}

data class LogbackSnapshot(
    val sourcePath: String,
    val properties: Map<String, String>,
    val loggers: Map<String, LoggerDefinition>,
    val root: RootDefinition?,
    val appenders: Map<String, AppenderDefinition>,
    val unreliableLoggerNames: Set<String>,
    val unreliableAppenderNames: Set<String>,
    val globalUncertainty: Boolean,
    val diagnostics: List<LogLensDiagnostic>,
) {
    fun summary(): ConfigurationSummary {
        val destinations = appenders.values.mapNotNull { appender ->
            when (appender) {
                is FileAppenderDefinition -> appender.file
                is RollingFileAppenderDefinition -> appender.file ?: appender.fileNamePattern
                else -> null
            }
        }.toSet()
        return ConfigurationSummary(
            health = if (diagnostics.isEmpty()) ConfigurationHealth.READY else ConfigurationHealth.WARNING,
            loggerCount = loggers.size + if (root == null) 0 else 1,
            appenderCount = appenders.size,
            destinationCount = destinations.size,
            diagnostics = diagnostics,
        )
    }
}

data class LogbackParseResult(
    val snapshot: LogbackSnapshot?,
    val diagnostics: List<LogLensDiagnostic>,
) {
    val isFatal: Boolean get() = snapshot == null

    fun summary(): ConfigurationSummary = snapshot?.summary() ?: ConfigurationSummary(
        health = ConfigurationHealth.ERROR,
        diagnostics = diagnostics,
    )
}
