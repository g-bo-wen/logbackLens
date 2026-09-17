package cn.gbk.logbacklens.logback

import cn.gbk.logbacklens.model.ConfigurationHealth
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class LogbackXmlParserTest {
    @Test
    fun `parses supported logger appender property rolling async and threshold model`() {
        val result = LogbackXmlParser.parse(
            """<configuration>
                <property name="LOG_DIR" value="logs"/>
                <appender name="FILE" class="ch.qos.logback.core.FileAppender">
                    <file>${'$'}{LOG_DIR}/app.log</file>
                    <filter class="ch.qos.logback.classic.filter.ThresholdFilter"><level>INFO</level></filter>
                </appender>
                <appender name="ROLL" class="ch.qos.logback.core.rolling.RollingFileAppender">
                    <rollingPolicy><fileNamePattern>${'$'}{LOG_DIR}/app.%d.log</fileNamePattern></rollingPolicy>
                </appender>
                <appender name="ASYNC" class="ch.qos.logback.classic.AsyncAppender">
                    <appender-ref ref="FILE"/><appender-ref ref="ROLL"/>
                </appender>
                <appender name="CONSOLE" class="ch.qos.logback.core.ConsoleAppender"/>
                <logger name="com.acme" level="debug" additivity="false"><appender-ref ref="ASYNC"/></logger>
                <root level="warn"><appender-ref ref="CONSOLE"/></root>
            </configuration>""".trimIndent(),
            "config/logback.xml",
        )

        val snapshot = requireNotNull(result.snapshot)
        assertFalse(result.isFatal)
        assertEquals("logs", snapshot.properties["LOG_DIR"])
        assertEquals(LogLevel.DEBUG, snapshot.loggers.getValue("com.acme").level)
        assertFalse(snapshot.loggers.getValue("com.acme").additive)
        assertEquals(LogLevel.WARN, snapshot.root?.level)
        assertEquals("logs/app.log", (snapshot.appenders.getValue("FILE") as FileAppenderDefinition).file)
        assertEquals(LogLevel.INFO, (snapshot.appenders.getValue("FILE") as FileAppenderDefinition).thresholds.single().level)
        val rolling = snapshot.appenders.getValue("ROLL") as RollingFileAppenderDefinition
        assertNull(rolling.file)
        assertEquals("logs/app.%d.log", rolling.fileNamePattern)
        assertEquals(listOf("FILE", "ROLL"), snapshot.appenders.getValue("ASYNC").appenderRefs)
        assertEquals(ConfigurationHealth.READY, snapshot.summary().health)
        assertTrue(snapshot.appenders.getValue("FILE").source.offset != null)
    }

    @Test
    fun `reports duplicate missing references and async cycles without discarding snapshot`() {
        val result = parse(
            """<appender name="A" class="ch.qos.logback.classic.AsyncAppender"><appender-ref ref="B"/></appender>
               <appender name="B" class="ch.qos.logback.classic.AsyncAppender"><appender-ref ref="A"/></appender>
               <appender name="A" class="ch.qos.logback.core.ConsoleAppender"/>
               <logger name="x"><appender-ref ref="MISSING"/></logger>
               <logger name="x" level="INFO"/>""",
        )

        val snapshot = requireNotNull(result.snapshot)
        val codes = result.diagnostics.map { it.code }.toSet()
        assertTrue("DUPLICATE_APPENDER" in codes)
        assertTrue("DUPLICATE_LOGGER" in codes)
        assertTrue("MISSING_APPENDER_REFERENCE" in codes)
        assertTrue("APPENDER_CYCLE" in codes)
        assertTrue("A" in snapshot.unreliableAppenderNames)
        assertTrue("x" in snapshot.unreliableLoggerNames)
        assertEquals(ConfigurationHealth.WARNING, snapshot.summary().health)
    }

    @Test
    fun `marks unsupported dynamic configuration filter and custom appender conservatively`() {
        val result = parse(
            """<include resource="other.xml"/>
               <springProfile name="dev"><root level="DEBUG"/></springProfile>
               <turboFilter class="example.CustomTurbo"/>
               <appender name="CUSTOM" class="example.CustomAppender"/>
               <appender name="FILE" class="ch.qos.logback.core.FileAppender">
                   <file>${'$'}{UNKNOWN}/app.log</file>
                   <filter class="example.CustomFilter"/>
               </appender>""",
        )

        val snapshot = requireNotNull(result.snapshot)
        val codes = result.diagnostics.map { it.code }.toSet()
        assertTrue(snapshot.globalUncertainty)
        assertTrue("UNSUPPORTED_INCLUDE" in codes)
        assertTrue("UNSUPPORTED_SPRING_PROFILE" in codes)
        assertTrue("UNSUPPORTED_TURBO_FILTER" in codes)
        assertTrue("UNSUPPORTED_APPENDER" in codes)
        assertTrue("UNSUPPORTED_FILTER" in codes)
        assertTrue("UNRESOLVED_PROPERTY" in codes)
        assertTrue(snapshot.appenders.getValue("FILE").uncertain)
    }

    @Test
    fun `rejects doctype and external entities without producing a snapshot`() {
        val result = LogbackXmlParser.parse(
            """<!DOCTYPE configuration [<!ENTITY xxe SYSTEM "file:///definitely-not-readable">]>
               <configuration><property name="X" value="&xxe;"/></configuration>""",
            "unsafe.xml",
        )

        assertTrue(result.isFatal)
        assertNull(result.snapshot)
        assertEquals(ConfigurationHealth.ERROR, result.summary().health)
        assertEquals("XML_PARSE_ERROR", result.diagnostics.single().code)
    }

    @Test
    fun `reports malformed XML and invalid root as fatal`() {
        assertTrue(LogbackXmlParser.parse("<configuration>", "broken.xml").isFatal)
        val wrongRoot = LogbackXmlParser.parse("<logback/>", "wrong.xml")
        assertTrue(wrongRoot.isFatal)
        assertEquals("INVALID_ROOT", wrongRoot.diagnostics.single().code)
    }

    @Test
    fun `keeps unresolved and cyclic properties explicit`() {
        val result = parse(
            """<property name="A" value="${'$'}{B}"/>
               <property name="B" value="${'$'}{A}"/>
               <appender name="FILE" class="ch.qos.logback.core.FileAppender">
                   <file>${'$'}{MISSING:-fallback}/${'$'}{A}.log</file>
               </appender>""",
        )

        val snapshot = requireNotNull(result.snapshot)
        assertTrue(result.diagnostics.any { it.code == "PROPERTY_CYCLE" })
        assertEquals("fallback/\${A}.log", (snapshot.appenders.getValue("FILE") as FileAppenderDefinition).file)
    }

    private fun parse(body: String): LogbackParseResult =
        LogbackXmlParser.parse("<configuration>$body</configuration>", "logback.xml")
}
