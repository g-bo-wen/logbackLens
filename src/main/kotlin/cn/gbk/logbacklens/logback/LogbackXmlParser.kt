package cn.gbk.logbacklens.logback

import cn.gbk.logbacklens.model.DiagnosticSeverity
import cn.gbk.logbacklens.model.LogLensDiagnostic
import cn.gbk.logbacklens.model.SourceLocation
import org.w3c.dom.Element
import org.xml.sax.InputSource
import java.io.StringReader
import javax.xml.XMLConstants
import javax.xml.parsers.DocumentBuilderFactory

object LogbackXmlParser {
    fun parse(xml: String, sourcePath: String): LogbackParseResult {
        val document = try {
            secureFactory().newDocumentBuilder().parse(InputSource(StringReader(xml)))
        } catch (error: Exception) {
            return LogbackParseResult(
                snapshot = null,
                diagnostics = listOf(
                    diagnostic(
                        code = "XML_PARSE_ERROR",
                        message = "Logback XML could not be parsed safely: ${error.javaClass.simpleName}",
                        severity = DiagnosticSeverity.ERROR,
                        source = SourceLocation(sourcePath),
                    ),
                ),
            )
        }

        val rootElement = document.documentElement
        if (rootElement == null || rootElement.tagName != "configuration") {
            return LogbackParseResult(
                snapshot = null,
                diagnostics = listOf(
                    diagnostic(
                        "INVALID_ROOT",
                        "The root element must be <configuration>.",
                        DiagnosticSeverity.ERROR,
                        SourceLocation(sourcePath),
                    ),
                ),
            )
        }

        val diagnostics = mutableListOf<LogLensDiagnostic>()
        val rawProperties = linkedMapOf<String, String>()
        rootElement.childElements("property").forEach { property ->
            val name = property.getAttribute("name").trim()
            val value = property.getAttribute("value")
            if (name.isNotEmpty() && property.hasAttribute("value")) {
                rawProperties[name] = value
            } else {
                diagnostics += diagnostic(
                    "UNSUPPORTED_PROPERTY_SOURCE",
                    "Only inline <property name=\"...\" value=\"...\"/> is supported.",
                    source = locate(xml, sourcePath, "property", name.ifEmpty { null }),
                )
            }
        }
        val propertyResolver = PropertyResolver(rawProperties, diagnostics, sourcePath)
        val properties = rawProperties.keys.associateWithTo(linkedMapOf()) { key -> propertyResolver.resolveKey(key).value }

        var globalUncertainty = diagnostics.any { it.code == "UNSUPPORTED_PROPERTY_SOURCE" }
        GLOBAL_UNSUPPORTED_TAGS.forEach { (tag, code) ->
            rootElement.descendantElements(tag).forEach {
                globalUncertainty = true
                diagnostics += diagnostic(
                    code,
                    "<$tag> is not evaluated by Log Lens V0.1.",
                    source = locate(xml, sourcePath, tag),
                )
            }
        }

        val appenders = linkedMapOf<String, AppenderDefinition>()
        val unreliableAppenders = linkedSetOf<String>()
        rootElement.childElements("appender").forEach { element ->
            val name = element.getAttribute("name").trim()
            val source = locate(xml, sourcePath, "appender", name.ifEmpty { null })
            if (name.isEmpty()) {
                diagnostics += diagnostic("APPENDER_NAME_MISSING", "Appender without a name is ignored.", source = source)
                return@forEach
            }
            if (name in appenders) {
                unreliableAppenders += name
                diagnostics += diagnostic("DUPLICATE_APPENDER", "Appender '$name' is declared more than once.", source = source)
                return@forEach
            }
            appenders[name] = parseAppender(element, name, source, propertyResolver, diagnostics, xml, sourcePath)
        }

        val loggers = linkedMapOf<String, LoggerDefinition>()
        val unreliableLoggers = linkedSetOf<String>()
        rootElement.childElements("logger").forEach { element ->
            val name = element.getAttribute("name").trim()
            val source = locate(xml, sourcePath, "logger", name.ifEmpty { null })
            if (name.isEmpty()) {
                diagnostics += diagnostic("LOGGER_NAME_MISSING", "Logger without a name is ignored.", source = source)
                return@forEach
            }
            if (name in loggers) {
                unreliableLoggers += name
                diagnostics += diagnostic("DUPLICATE_LOGGER", "Logger '$name' is declared more than once.", source = source)
                return@forEach
            }
            val levelText = element.getAttribute("level")
            val level = parseOptionalLevel(levelText, "logger '$name'", source, diagnostics)
            val (additive, additivityValid) = parseAdditivity(element.getAttribute("additivity"), name, source, diagnostics)
            loggers[name] = LoggerDefinition(
                name,
                level,
                additive,
                element.appenderRefs(),
                uncertain = (levelText.isNotBlank() && level == null) || !additivityValid,
                source,
            )
        }

        val rootElements = rootElement.childElements("root")
        if (rootElements.size > 1) {
            globalUncertainty = true
            diagnostics += diagnostic(
                "DUPLICATE_ROOT",
                "More than one <root> declaration makes root routing uncertain.",
                source = locate(xml, sourcePath, "root"),
            )
        }
        val root = rootElements.singleOrNull()?.let { element ->
            val source = locate(xml, sourcePath, "root")
            val levelText = element.getAttribute("level")
            val level = parseOptionalLevel(levelText, "root", source, diagnostics)
            RootDefinition(
                level = level,
                appenderRefs = element.appenderRefs(),
                uncertain = levelText.isNotBlank() && level == null,
                source = source,
            )
        }

        val allRefs = buildList {
            loggers.values.forEach { logger -> logger.appenderRefs.forEach { add(it to logger.source) } }
            root?.appenderRefs?.forEach { add(it to root.source) }
            appenders.values.forEach { appender -> appender.appenderRefs.forEach { add(it to appender.source) } }
        }
        allRefs.filter { (ref, _) -> ref !in appenders }.forEach { (ref, source) ->
            diagnostics += diagnostic("MISSING_APPENDER_REFERENCE", "Appender reference '$ref' does not exist.", source = source)
        }
        findAppenderCycles(appenders).forEach { cycleName ->
            unreliableAppenders += cycleName
            diagnostics += diagnostic(
                "APPENDER_CYCLE",
                "Appender graph contains a cycle through '$cycleName'.",
                source = appenders.getValue(cycleName).source,
            )
        }

        val snapshot = LogbackSnapshot(
            sourcePath = sourcePath,
            properties = properties,
            loggers = loggers,
            root = root,
            appenders = appenders,
            unreliableLoggerNames = unreliableLoggers,
            unreliableAppenderNames = unreliableAppenders,
            globalUncertainty = globalUncertainty,
            diagnostics = diagnostics.toList(),
        )
        return LogbackParseResult(snapshot, snapshot.diagnostics)
    }

    private fun parseAppender(
        element: Element,
        name: String,
        source: SourceLocation,
        properties: PropertyResolver,
        diagnostics: MutableList<LogLensDiagnostic>,
        xml: String,
        sourcePath: String,
    ): AppenderDefinition {
        val className = element.getAttribute("class").trim()
        var uncertain = false
        val thresholds = mutableListOf<ThresholdDefinition>()
        element.childElements("filter").forEach { filter ->
            val filterClass = filter.getAttribute("class")
            val filterSource = locate(xml, sourcePath, "filter")
            if (filterClass.endsWith("ThresholdFilter")) {
                val levelText = filter.firstChildText("level")
                val level = parseOptionalLevel(levelText, "ThresholdFilter on '$name'", filterSource, diagnostics)
                if (level == null) {
                    uncertain = true
                } else {
                    thresholds += ThresholdDefinition(level, filterSource)
                }
            } else {
                uncertain = true
                diagnostics += diagnostic(
                    "UNSUPPORTED_FILTER",
                    "Filter '$filterClass' on appender '$name' is not evaluated.",
                    source = filterSource,
                )
            }
        }

        return when {
            className.endsWith("RollingFileAppender") -> {
                val file = element.firstChildText("file")?.let(properties::resolveValue)
                val pattern = element.descendantElements("fileNamePattern").firstOrNull()?.textContent
                    ?.trim()?.takeIf(String::isNotEmpty)?.let(properties::resolveValue)
                if (file == null && pattern == null) {
                    diagnostics += diagnostic(
                        "ROLLING_DESTINATION_MISSING",
                        "RollingFileAppender '$name' has neither <file> nor <fileNamePattern>.",
                        source = source,
                    )
                }
                RollingFileAppenderDefinition(
                    name = name,
                    file = file?.value,
                    fileNamePattern = pattern?.value,
                    thresholds = thresholds,
                    uncertain = uncertain || file?.complete == false || pattern?.complete == false ||
                        (file == null && pattern == null),
                    source = source,
                )
            }
            className.endsWith("FileAppender") -> {
                val file = element.firstChildText("file")?.let(properties::resolveValue)
                if (file == null) {
                    uncertain = true
                    diagnostics += diagnostic("FILE_DESTINATION_MISSING", "FileAppender '$name' has no <file>.", source = source)
                }
                FileAppenderDefinition(name, file?.value.orEmpty(), thresholds, uncertain || file?.complete == false, source)
            }
            className.endsWith("AsyncAppender") -> AsyncAppenderDefinition(
                name, element.appenderRefs(), thresholds, uncertain, source,
            )
            className.endsWith("ConsoleAppender") -> ConsoleAppenderDefinition(name, thresholds, uncertain, source)
            else -> {
                diagnostics += diagnostic(
                    "UNSUPPORTED_APPENDER",
                    "Appender '$name' uses unsupported class '$className'.",
                    source = source,
                )
                UnsupportedAppenderDefinition(name, className, source)
            }
        }
    }

    private fun parseOptionalLevel(
        value: String?,
        owner: String,
        source: SourceLocation,
        diagnostics: MutableList<LogLensDiagnostic>,
    ): LogLevel? {
        val normalized = value?.trim()?.takeIf(String::isNotEmpty) ?: return null
        return LogLevel.entries.firstOrNull { level -> level.name.equals(normalized, ignoreCase = true) }
            ?: run {
                diagnostics += diagnostic("INVALID_LEVEL", "Invalid level '$normalized' on $owner.", source = source)
                null
            }
    }

    private fun parseAdditivity(
        value: String,
        loggerName: String,
        source: SourceLocation,
        diagnostics: MutableList<LogLensDiagnostic>,
    ): Pair<Boolean, Boolean> {
        if (value.isBlank()) return true to true
        return when (value.lowercase()) {
            "true" -> true to true
            "false" -> false to true
            else -> {
                diagnostics += diagnostic(
                    "INVALID_ADDITIVITY",
                    "Invalid additivity '$value' on logger '$loggerName'; routing is uncertain.",
                    source = source,
                )
                false to false
            }
        }
    }

    private fun findAppenderCycles(appenders: Map<String, AppenderDefinition>): Set<String> {
        val visiting = linkedSetOf<String>()
        val visited = hashSetOf<String>()
        val cyclic = linkedSetOf<String>()

        fun visit(name: String) {
            if (name in visiting) {
                cyclic += name
                return
            }
            if (!visited.add(name)) return
            visiting += name
            appenders[name]?.appenderRefs.orEmpty().forEach(::visit)
            visiting -= name
        }
        appenders.keys.forEach(::visit)
        return cyclic
    }

    private fun secureFactory(): DocumentBuilderFactory = DocumentBuilderFactory.newInstance().apply {
        isNamespaceAware = false
        isXIncludeAware = false
        setExpandEntityReferences(false)
        setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true)
        setFeature("http://apache.org/xml/features/disallow-doctype-decl", true)
        setFeature("http://xml.org/sax/features/external-general-entities", false)
        setFeature("http://xml.org/sax/features/external-parameter-entities", false)
        setFeature("http://apache.org/xml/features/nonvalidating/load-external-dtd", false)
        setAttribute(XMLConstants.ACCESS_EXTERNAL_DTD, "")
        setAttribute(XMLConstants.ACCESS_EXTERNAL_SCHEMA, "")
    }

    private fun diagnostic(
        code: String,
        message: String,
        severity: DiagnosticSeverity = DiagnosticSeverity.WARNING,
        source: SourceLocation? = null,
    ) = LogLensDiagnostic(code, message, severity, source)

    private fun locate(xml: String, path: String, tag: String, name: String? = null): SourceLocation {
        val tagStart = Regex("<$tag\\b", RegexOption.IGNORE_CASE).find(xml)?.range?.first
        val namedStart = name?.let { value ->
            Regex("<$tag\\b[^>]*\\bname\\s*=\\s*(['\"])${Regex.escape(value)}\\1", RegexOption.IGNORE_CASE)
                .find(xml)?.range?.first
        }
        return SourceLocation(path, namedStart ?: tagStart, name)
    }

    private val GLOBAL_UNSUPPORTED_TAGS = linkedMapOf(
        "include" to "UNSUPPORTED_INCLUDE",
        "springProfile" to "UNSUPPORTED_SPRING_PROFILE",
        "if" to "UNSUPPORTED_CONDITIONAL",
        "condition" to "UNSUPPORTED_CONDITIONAL",
        "turboFilter" to "UNSUPPORTED_TURBO_FILTER",
    )
}

private class PropertyResolver(
    private val raw: Map<String, String>,
    private val diagnostics: MutableList<LogLensDiagnostic>,
    private val sourcePath: String,
) {
    private val cache = mutableMapOf<String, PropertyResolution>()
    private val resolving = linkedSetOf<String>()

    fun resolveKey(key: String): PropertyResolution = cache.getOrPut(key) {
        if (!resolving.add(key)) {
            diagnostics += LogLensDiagnostic(
                "PROPERTY_CYCLE",
                "Property '$key' participates in a cycle.",
                DiagnosticSeverity.WARNING,
                SourceLocation(sourcePath, elementName = key),
            )
            return@getOrPut PropertyResolution("\${$key}", complete = false)
        }
        try {
            resolveValue(raw.getValue(key))
        } finally {
            resolving -= key
        }
    }

    fun resolveValue(value: String): PropertyResolution {
        var complete = true
        val resolved = PROPERTY_PATTERN.replace(value) { match ->
            val key = match.groupValues[1]
            val default = match.groupValues[2].takeIf(String::isNotEmpty)
            val replacement = when {
                key in raw -> resolveKey(key)
                default != null -> resolveValue(default)
                else -> {
                    diagnostics += LogLensDiagnostic(
                        "UNRESOLVED_PROPERTY",
                        "Property '$key' cannot be resolved statically.",
                        DiagnosticSeverity.WARNING,
                        SourceLocation(sourcePath, elementName = key),
                    )
                    PropertyResolution(match.value, complete = false)
                }
            }
            complete = complete && replacement.complete
            replacement.value
        }
        return PropertyResolution(resolved, complete)
    }

    private companion object {
        val PROPERTY_PATTERN = Regex("\\$\\{([^}:]+)(?::-([^}]*))?}")
    }
}

private data class PropertyResolution(val value: String, val complete: Boolean)

private fun Element.childElements(tagName: String): List<Element> = buildList {
    val nodes = childNodes
    for (index in 0 until nodes.length) {
        val child = nodes.item(index)
        if (child is Element && child.tagName == tagName) add(child)
    }
}

private fun Element.descendantElements(tagName: String): List<Element> = buildList {
    val nodes = getElementsByTagName(tagName)
    for (index in 0 until nodes.length) {
        (nodes.item(index) as? Element)?.let(::add)
    }
}

private fun Element.firstChildText(tagName: String): String? =
    childElements(tagName).firstOrNull()?.textContent?.trim()?.takeIf(String::isNotEmpty)

private fun Element.appenderRefs(): List<String> = childElements("appender-ref")
    .map { element -> element.getAttribute("ref").trim() }
    .filter(String::isNotEmpty)
