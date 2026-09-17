package cn.gbk.logbacklens.route

import cn.gbk.logbacklens.logback.AppenderDefinition
import cn.gbk.logbacklens.logback.AsyncAppenderDefinition
import cn.gbk.logbacklens.logback.ConsoleAppenderDefinition
import cn.gbk.logbacklens.logback.FileAppenderDefinition
import cn.gbk.logbacklens.logback.LogLevel
import cn.gbk.logbacklens.logback.LogbackSnapshot
import cn.gbk.logbacklens.logback.RollingFileAppenderDefinition
import cn.gbk.logbacklens.logback.UnsupportedAppenderDefinition

object LogbackRouteResolver {
    fun resolve(snapshot: LogbackSnapshot, loggerIdentity: LoggerIdentity, eventLevel: LogLevel): RouteOutcome {
        val loggerName = (loggerIdentity as? LoggerIdentity.Known)?.name
            ?: return RouteOutcome.Unknown(reasons = listOf((loggerIdentity as LoggerIdentity.Unknown).reason))
        if (snapshot.globalUncertainty) {
            return RouteOutcome.Unknown(reasons = listOf("Configuration contains unsupported global routing constructs."))
        }

        val matchingLoggers = snapshot.loggers.values
            .filter { definition -> loggerName == definition.name || loggerName.startsWith("${definition.name}.") }
            .sortedByDescending { definition -> definition.name.length }
        val unreliableMatch = matchingLoggers.firstOrNull { it.name in snapshot.unreliableLoggerNames || it.uncertain }
        if (unreliableMatch != null) {
            return RouteOutcome.Unknown(reasons = listOf("Logger '${unreliableMatch.name}' is ambiguous or invalid."))
        }

        val effectiveLevel = matchingLoggers.firstNotNullOfOrNull { definition -> definition.level }
            ?: snapshot.root?.level
            ?: LogLevel.DEBUG
        if (!isEnabled(eventLevel, effectiveLevel)) {
            return RouteOutcome.Disabled(eventLevel, effectiveLevel)
        }

        val appenderNames = mutableListOf<String>()
        var stopped = false
        matchingLoggers.forEach { logger ->
            if (!stopped) {
                appenderNames += logger.appenderRefs
                if (!logger.additive) stopped = true
            }
        }
        if (!stopped) {
            val root = snapshot.root
            if (root?.uncertain == true) {
                return RouteOutcome.Unknown(reasons = listOf("Root logger level is invalid."))
            }
            appenderNames += root?.appenderRefs.orEmpty()
        }

        val destinations = linkedMapOf<Pair<String, DestinationKind>, RouteDestination>()
        val unknownReasons = linkedSetOf<String>()
        var sawKnownNonFile = false

        appenderNames.forEach { name ->
            traverseAppender(
                snapshot = snapshot,
                appenderName = name,
                eventLevel = eventLevel,
                chain = emptyList(),
                visiting = emptySet(),
                destinations = destinations,
                unknownReasons = unknownReasons,
                markKnownNonFile = { sawKnownNonFile = true },
            )
        }

        return when {
            unknownReasons.isNotEmpty() -> RouteOutcome.Unknown(destinations.values.toList(), unknownReasons.toList())
            destinations.isNotEmpty() -> RouteOutcome.Destinations(destinations.values.toList())
            sawKnownNonFile || appenderNames.isEmpty() -> RouteOutcome.KnownNoFile
            else -> RouteOutcome.KnownNoFile
        }
    }

    private fun traverseAppender(
        snapshot: LogbackSnapshot,
        appenderName: String,
        eventLevel: LogLevel,
        chain: List<String>,
        visiting: Set<String>,
        destinations: LinkedHashMap<Pair<String, DestinationKind>, RouteDestination>,
        unknownReasons: MutableSet<String>,
        markKnownNonFile: () -> Unit,
    ) {
        if (appenderName in visiting) {
            unknownReasons += "Appender cycle reaches '$appenderName'."
            return
        }
        if (appenderName in snapshot.unreliableAppenderNames) {
            unknownReasons += "Appender '$appenderName' is ambiguous or cyclic."
            return
        }
        val appender = snapshot.appenders[appenderName]
        if (appender == null) {
            unknownReasons += "Appender '$appenderName' does not exist."
            return
        }
        if (appender.uncertain) {
            unknownReasons += "Appender '$appenderName' contains unsupported or unresolved routing data."
            return
        }
        if (appender.thresholds.any { threshold -> !isEnabled(eventLevel, threshold.level) }) {
            return
        }

        val nextChain = chain + appenderName
        when (appender) {
            is FileAppenderDefinition -> addDestination(
                destinations,
                RouteDestination(appender.file, DestinationKind.ACTIVE_FILE, nextChain, appender.source),
            )
            is RollingFileAppenderDefinition -> {
                val file = appender.file
                val pattern = appender.fileNamePattern
                when {
                    file != null -> addDestination(
                        destinations,
                        RouteDestination(file, DestinationKind.ACTIVE_FILE, nextChain, appender.source),
                    )
                    pattern != null -> addDestination(
                        destinations,
                        RouteDestination(pattern, DestinationKind.ROLLING_PATTERN, nextChain, appender.source),
                    )
                    else -> unknownReasons += "Rolling appender '$appenderName' has no destination."
                }
            }
            is AsyncAppenderDefinition -> appender.appenderRefs.forEach { child ->
                traverseAppender(
                    snapshot,
                    child,
                    eventLevel,
                    nextChain,
                    visiting + appenderName,
                    destinations,
                    unknownReasons,
                    markKnownNonFile,
                )
            }
            is ConsoleAppenderDefinition -> markKnownNonFile()
            is UnsupportedAppenderDefinition -> unknownReasons += "Appender '$appenderName' is unsupported."
        }
    }

    private fun addDestination(
        destinations: LinkedHashMap<Pair<String, DestinationKind>, RouteDestination>,
        destination: RouteDestination,
    ) {
        destinations.putIfAbsent(destination.value to destination.kind, destination)
    }

    private fun isEnabled(event: LogLevel, threshold: LogLevel): Boolean = when (threshold) {
        LogLevel.ALL -> true
        LogLevel.OFF -> false
        else -> event.priority >= threshold.priority
    }
}
