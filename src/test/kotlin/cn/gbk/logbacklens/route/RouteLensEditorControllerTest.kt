package cn.gbk.logbacklens.route

import cn.gbk.logbacklens.configuration.LogbackConfigurationState
import cn.gbk.logbacklens.logback.LogbackXmlParser
import cn.gbk.logbacklens.model.ConfigurationHealth
import cn.gbk.logbacklens.model.ConfigurationSummary
import cn.gbk.logbacklens.settings.LogLensApplicationState
import com.intellij.openapi.editor.Editor
import com.intellij.psi.PsiDocumentManager
import com.intellij.testFramework.fixtures.BasePlatformTestCase

class RouteLensEditorControllerTest : BasePlatformTestCase() {
    fun testDestinationDisabledAndUnknownCallsRenderIndependently() {
        val editor = configure(
            """class DemoService {
                private org.slf4j.Logger known = org.slf4j.LoggerFactory.getLogger(DemoService.class);
                private org.slf4j.Logger dynamic;
                void write(Object value) {
                    known.info("info {}", value);
                    known.debug("debug {}", value);
                    dynamic.info("dynamic {}", value);
                }
            }""".trimIndent(),
        )
        val original = editor.document.text
        val state = configuration(
            """<appender name="FILE" class="ch.qos.logback.core.FileAppender"><file>app.log</file></appender>
               <root level="INFO"><appender-ref ref="FILE"/></root>""",
        )
        val controller = attach(editor, state)

        controller.refreshNow()

        assertEquals(3, controller.targetsForTest().size)
        assertEquals(
            listOf(" → app.log", " ⊘ disabled", " → ?"),
            controller.inlaysForTest().map { inlay -> inlay.renderer.text },
        )
        assertEquals(original, editor.document.text)
    }

    fun testAtMostTwoDestinationsAreVisibleAndDestinationSegmentsHaveExactHitTargets() {
        val editor = configure(singleCallSource())
        val navigated = mutableListOf<RouteDestination>()
        val state = configuration(
            """<appender name="A" class="ch.qos.logback.core.FileAppender"><file>a.log</file></appender>
               <appender name="B" class="ch.qos.logback.core.FileAppender"><file>b.log</file></appender>
               <appender name="C" class="ch.qos.logback.core.FileAppender"><file>c.log</file></appender>
               <root level="INFO"><appender-ref ref="A"/><appender-ref ref="B"/><appender-ref ref="C"/></root>""",
        )
        val controller = RouteLensEditorController.attach(
            project,
            editor,
            settingsProvider = { LogLensApplicationState() },
            configurationProvider = { state },
            navigator = RouteNavigator(navigated::add),
        )

        controller.refreshNow()

        val inlay = controller.inlaysForTest().single()
        val renderer = inlay.renderer
        assertEquals(" → a.log, b.log +1", renderer.text)
        assertEquals(listOf("a.log", "b.log"), renderer.segments.mapNotNull(RouteSegment::destination).map(RouteDestination::value))

        val bounds = requireNotNull(inlay.bounds)
        val font = editor.colorsScheme.getFont(com.intellij.openapi.editor.colors.EditorFontType.PLAIN)
        val prefixWidth = editor.contentComponent.getFontMetrics(font).stringWidth(" → ")
        val destination = renderer.destinationAt(inlay, bounds.x + prefixWidth + 1)
        assertEquals("a.log", destination?.value)
        assertTrue(renderer.tooltipAt(inlay, bounds.x + prefixWidth + 1)?.contains("A") == true)
        assertTrue(controller.navigateAtForTest(inlay, bounds.x + prefixWidth + 1))
        assertEquals(listOf("a.log"), navigated.map(RouteDestination::value))
        assertFalse(controller.navigateAtForTest(inlay, bounds.x - 1))
    }

    fun testConsoleOnlyRouteSettingOffAndFatalConfigurationProduceNoSuffix() {
        val editor = configure(singleCallSource())
        var settings = LogLensApplicationState()
        var state = configuration(
            """<appender name="CONSOLE" class="ch.qos.logback.core.ConsoleAppender"/>
               <root level="INFO"><appender-ref ref="CONSOLE"/></root>""",
        )
        val controller = RouteLensEditorController.attach(
            project,
            editor,
            settingsProvider = { settings.copy() },
            configurationProvider = { state },
        )

        controller.refreshNow()
        assertEquals(0, controller.inlaysForTest().size)
        assertTrue(controller.targetsForTest().single().outcome is RouteOutcome.KnownNoFile)

        state = configuration(
            """<appender name="FILE" class="ch.qos.logback.core.FileAppender"><file>app.log</file></appender>
               <root level="INFO"><appender-ref ref="FILE"/></root>""",
        )
        controller.invalidate()
        controller.refreshNow()
        assertEquals(1, controller.inlaysForTest().size)

        settings = settings.copy(routeLensEnabled = false)
        controller.invalidate()
        controller.refreshNow()
        assertEquals(0, controller.inlaysForTest().size)

        settings = settings.copy(routeLensEnabled = true)
        state = LogbackConfigurationState(
            generation = 2,
            binding = null,
            snapshot = null,
            summary = ConfigurationSummary(ConfigurationHealth.ERROR),
        )
        controller.invalidate()
        controller.refreshNow()
        assertEquals(0, controller.inlaysForTest().size)
        assertEquals(0, controller.targetsForTest().size)
    }

    fun testLombokSlf4jCallIsAnalyzedEvenWhenGeneratedFieldPsiIsUnavailable() {
        myFixture.addFileToProject(
            "lombok/extern/slf4j/Slf4j.java",
            "package lombok.extern.slf4j; public @interface Slf4j {}",
        )
        val editor = configure(
            """package demo;
                @lombok.extern.slf4j.Slf4j
                class LombokService {
                    void write(Object value) { log.info("value {}", value); }
                }
            """.trimIndent(),
        )
        val controller = attach(editor, configuration("<root level=\"INFO\"/>"))

        controller.refreshNow()

        assertEquals(LoggerIdentity.Known("demo.LombokService"), controller.targetsForTest().single().call.loggerIdentity)
    }

    private fun attach(editor: Editor, state: LogbackConfigurationState): RouteLensEditorController =
        RouteLensEditorController.attach(
            project,
            editor,
            settingsProvider = { LogLensApplicationState() },
            configurationProvider = { state },
        )

    private fun configure(source: String): Editor {
        myFixture.addFileToProject(
            "org/slf4j/Logger.java",
            """package org.slf4j; public interface Logger {
                void trace(String text, Object... args); void debug(String text, Object... args);
                void info(String text, Object... args); void warn(String text, Object... args);
                void error(String text, Object... args);
            }""",
        )
        myFixture.addFileToProject(
            "org/slf4j/LoggerFactory.java",
            """package org.slf4j; public final class LoggerFactory {
                public static Logger getLogger(Class<?> type) { return null; }
                public static Logger getLogger(String name) { return null; }
            }""",
        )
        myFixture.configureByText("DemoService.java", source)
        PsiDocumentManager.getInstance(project).commitDocument(myFixture.editor.document)
        return myFixture.editor
    }

    private fun configuration(body: String): LogbackConfigurationState {
        val snapshot = requireNotNull(
            LogbackXmlParser.parse("<configuration>$body</configuration>", "logback.xml").snapshot,
        )
        return LogbackConfigurationState(
            generation = 1,
            binding = null,
            snapshot = snapshot,
            summary = snapshot.summary(),
        )
    }

    private fun singleCallSource() = """class DemoService {
        private org.slf4j.Logger log = org.slf4j.LoggerFactory.getLogger(DemoService.class);
        void write(Object value) { log.info("value {}", value); }
    }""".trimIndent()
}
