package cn.gbk.logbacklens.model

enum class DiagnosticSeverity {
    WARNING,
    ERROR,
}

data class SourceLocation(
    val path: String,
    val offset: Int? = null,
    val elementName: String? = null,
)

data class LogLensDiagnostic(
    val code: String,
    val message: String,
    val severity: DiagnosticSeverity,
    val source: SourceLocation? = null,
)

enum class ConfigurationHealth {
    UNBOUND,
    LOADING,
    READY,
    WARNING,
    ERROR,
}

data class ConfigurationSummary(
    val health: ConfigurationHealth,
    val loggerCount: Int = 0,
    val appenderCount: Int = 0,
    val destinationCount: Int = 0,
    val diagnostics: List<LogLensDiagnostic> = emptyList(),
)
