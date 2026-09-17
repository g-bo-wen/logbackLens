package cn.gbk.logbacklens.route

import cn.gbk.logbacklens.logback.LogLevel
import cn.gbk.logbacklens.model.SourceLocation

enum class DestinationKind {
    ACTIVE_FILE,
    ROLLING_PATTERN,
}

data class RouteDestination(
    val value: String,
    val kind: DestinationKind,
    val appenderChain: List<String>,
    val source: SourceLocation,
)

sealed interface RouteOutcome {
    data class Disabled(
        val eventLevel: LogLevel,
        val effectiveLevel: LogLevel,
    ) : RouteOutcome

    data class Destinations(val values: List<RouteDestination>) : RouteOutcome

    data object KnownNoFile : RouteOutcome

    data class Unknown(
        val partialDestinations: List<RouteDestination> = emptyList(),
        val reasons: List<String>,
    ) : RouteOutcome
}

sealed interface LoggerIdentity {
    data class Known(val name: String) : LoggerIdentity
    data class Unknown(val reason: String) : LoggerIdentity
}
