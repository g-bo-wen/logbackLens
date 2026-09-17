package cn.gbk.logbacklens.route

import cn.gbk.logbacklens.logback.LogLevel
import cn.gbk.logbacklens.logback.LogbackXmlParser
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class LogbackRouteResolverTest {
    @Test
    fun `effective level and additivity follow longest logger hierarchy`() {
        val snapshot = snapshot(
            """<appender name="ROOT" class="ch.qos.logback.core.FileAppender"><file>root.log</file></appender>
               <appender name="A" class="ch.qos.logback.core.FileAppender"><file>a.log</file></appender>
               <appender name="B" class="ch.qos.logback.core.FileAppender"><file>b.log</file></appender>
               <logger name="com" level="INFO"><appender-ref ref="A"/></logger>
               <logger name="com.acme" level="DEBUG" additivity="false"><appender-ref ref="B"/></logger>
               <root level="WARN"><appender-ref ref="ROOT"/></root>""",
        )

        assertEquals(
            listOf("b.log"),
            destinations(LogbackRouteResolver.resolve(snapshot, LoggerIdentity.Known("com.acme.Service"), LogLevel.DEBUG)),
        )
        assertEquals(
            RouteOutcome.Disabled(LogLevel.DEBUG, LogLevel.INFO),
            LogbackRouteResolver.resolve(snapshot, LoggerIdentity.Known("com.other.Service"), LogLevel.DEBUG),
        )
        assertEquals(
            listOf("a.log", "root.log"),
            destinations(LogbackRouteResolver.resolve(snapshot, LoggerIdentity.Known("com.other.Service"), LogLevel.ERROR)),
        )
    }

    @Test
    fun `async threshold rolling pattern console ordering and dedup are deterministic`() {
        val snapshot = snapshot(
            """<appender name="FILE" class="ch.qos.logback.core.FileAppender">
                   <file>app.log</file>
                   <filter class="ch.qos.logback.classic.filter.ThresholdFilter"><level>INFO</level></filter>
               </appender>
               <appender name="ROLL" class="ch.qos.logback.core.rolling.RollingFileAppender">
                   <rollingPolicy><fileNamePattern>archive.%d.log</fileNamePattern></rollingPolicy>
               </appender>
               <appender name="ASYNC" class="ch.qos.logback.classic.AsyncAppender">
                   <appender-ref ref="FILE"/><appender-ref ref="ROLL"/>
               </appender>
               <appender name="CONSOLE" class="ch.qos.logback.core.ConsoleAppender"/>
               <root level="TRACE">
                   <appender-ref ref="ASYNC"/><appender-ref ref="FILE"/><appender-ref ref="CONSOLE"/>
               </root>""",
        )

        val info = LogbackRouteResolver.resolve(snapshot, LoggerIdentity.Known("demo.Service"), LogLevel.INFO)
        assertEquals(listOf("app.log", "archive.%d.log"), destinations(info))
        val infoDestinations = (info as RouteOutcome.Destinations).values
        assertEquals(DestinationKind.ROLLING_PATTERN, infoDestinations[1].kind)
        assertEquals(listOf("ASYNC", "ROLL"), infoDestinations[1].appenderChain)

        val debug = LogbackRouteResolver.resolve(snapshot, LoggerIdentity.Known("demo.Service"), LogLevel.DEBUG)
        assertEquals(listOf("archive.%d.log"), destinations(debug))
    }

    @Test
    fun `console only and no appender are known no file outcomes`() {
        val console = snapshot(
            """<appender name="CONSOLE" class="ch.qos.logback.core.ConsoleAppender"/>
               <root level="INFO"><appender-ref ref="CONSOLE"/></root>""",
        )
        val empty = snapshot("""<root level="INFO"/>""")

        assertEquals(
            RouteOutcome.KnownNoFile,
            LogbackRouteResolver.resolve(console, LoggerIdentity.Known("x"), LogLevel.INFO),
        )
        assertEquals(
            RouteOutcome.KnownNoFile,
            LogbackRouteResolver.resolve(empty, LoggerIdentity.Known("x"), LogLevel.INFO),
        )
    }

    @Test
    fun `unknown identity global constructs missing and unsupported appenders stay explicit`() {
        val regular = snapshot("""<root level="INFO"><appender-ref ref="MISSING"/></root>""")
        assertTrue(LogbackRouteResolver.resolve(regular, LoggerIdentity.Unknown("dynamic"), LogLevel.INFO) is RouteOutcome.Unknown)
        assertTrue(LogbackRouteResolver.resolve(regular, LoggerIdentity.Known("x"), LogLevel.INFO) is RouteOutcome.Unknown)

        val global = snapshot("""<include resource="other.xml"/><root level="INFO"/>""")
        assertTrue(LogbackRouteResolver.resolve(global, LoggerIdentity.Known("x"), LogLevel.INFO) is RouteOutcome.Unknown)

        val custom = snapshot(
            """<appender name="CUSTOM" class="example.CustomAppender"/>
               <root level="INFO"><appender-ref ref="CUSTOM"/></root>""",
        )
        assertTrue(LogbackRouteResolver.resolve(custom, LoggerIdentity.Known("x"), LogLevel.INFO) is RouteOutcome.Unknown)
    }

    @Test
    fun `known destinations are retained as partial evidence when another branch is unknown`() {
        val snapshot = snapshot(
            """<appender name="FILE" class="ch.qos.logback.core.FileAppender"><file>app.log</file></appender>
               <root level="INFO"><appender-ref ref="FILE"/><appender-ref ref="MISSING"/></root>""",
        )

        val result = LogbackRouteResolver.resolve(snapshot, LoggerIdentity.Known("x"), LogLevel.INFO) as RouteOutcome.Unknown

        assertEquals(listOf("app.log"), result.partialDestinations.map(RouteDestination::value))
        assertTrue(result.reasons.single().contains("MISSING"))
    }

    private fun snapshot(body: String) = requireNotNull(
        LogbackXmlParser.parse("<configuration>$body</configuration>", "logback.xml").snapshot,
    )

    private fun destinations(outcome: RouteOutcome): List<String> =
        (outcome as RouteOutcome.Destinations).values.map(RouteDestination::value)
}
