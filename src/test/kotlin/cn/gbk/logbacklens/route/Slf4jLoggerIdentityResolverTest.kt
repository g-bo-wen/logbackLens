package cn.gbk.logbacklens.route

import com.intellij.openapi.application.ReadAction
import com.intellij.psi.PsiMethodCallExpression
import com.intellij.psi.util.PsiTreeUtil
import com.intellij.testFramework.fixtures.BasePlatformTestCase

class Slf4jLoggerIdentityResolverTest : BasePlatformTestCase() {
    fun testClassLiteralConstantStringLombokAndUnknownLoggerIdentity() {
        addSlf4jApi()
        myFixture.addFileToProject(
            "lombok/extern/slf4j/Slf4j.java",
            "package lombok.extern.slf4j; public @interface Slf4j {}",
        )
        val file = myFixture.configureByText(
            "DemoService.java",
            """package demo;
                class DemoService {
                    private static final String LOGGER_NAME = "business.audit";
                    private org.slf4j.Logger byClass = org.slf4j.LoggerFactory.getLogger(DemoService.class);
                    private org.slf4j.Logger byName = org.slf4j.LoggerFactory.getLogger(LOGGER_NAME);
                    private org.slf4j.Logger dynamic;
                    void write(Object value) {
                        byClass.info("class {}", value);
                        byName.info("name {}", value);
                        dynamic.info("dynamic {}", value);
                    }
                }
                @lombok.extern.slf4j.Slf4j
                class LombokService {
                    void write(Object value) { log.info("lombok {}", value); }
                }
            """.trimIndent(),
        )

        val identities = ReadAction.compute<List<LoggerIdentity>, RuntimeException> {
            PsiTreeUtil.findChildrenOfType(file, PsiMethodCallExpression::class.java)
                .filter { call -> call.methodExpression.referenceName == "info" }
                .sortedBy { call -> call.textOffset }
                .map(Slf4jLoggerIdentityResolver::resolve)
        }

        assertEquals(
            listOf(
                LoggerIdentity.Known("demo.DemoService"),
                LoggerIdentity.Known("business.audit"),
                LoggerIdentity.Unknown("Logger name cannot be derived from the receiver initializer."),
                LoggerIdentity.Known("demo.LombokService"),
            ),
            identities,
        )
    }

    fun testNonConstantFactoryArgumentStaysUnknown() {
        addSlf4jApi()
        val file = myFixture.configureByText(
            "Dynamic.java",
            """class Dynamic {
                private org.slf4j.Logger dynamic;
                Dynamic(String name) { dynamic = org.slf4j.LoggerFactory.getLogger(name); }
                void write(Object value) { dynamic.info("value {}", value); }
            }""",
        )
        val call = ReadAction.compute<PsiMethodCallExpression, RuntimeException> {
            PsiTreeUtil.findChildrenOfType(file, PsiMethodCallExpression::class.java)
                .single { it.methodExpression.referenceName == "info" }
        }

        val identity = ReadAction.compute<LoggerIdentity, RuntimeException> {
            Slf4jLoggerIdentityResolver.resolve(call)
        }

        assertTrue(identity is LoggerIdentity.Unknown)
    }

    private fun addSlf4jApi() {
        myFixture.addFileToProject(
            "org/slf4j/Logger.java",
            "package org.slf4j; public interface Logger { void info(String template, Object... args); }",
        )
        myFixture.addFileToProject(
            "org/slf4j/LoggerFactory.java",
            """package org.slf4j;
                public final class LoggerFactory {
                    public static Logger getLogger(Class<?> type) { return null; }
                    public static Logger getLogger(String name) { return null; }
                }
            """.trimIndent(),
        )
    }
}
