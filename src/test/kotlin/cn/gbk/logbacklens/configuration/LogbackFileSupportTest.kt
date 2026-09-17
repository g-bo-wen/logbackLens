package cn.gbk.logbacklens.configuration

import cn.gbk.logbacklens.settings.BindingPathKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.file.Files

class LogbackFileSupportTest {
    @Test
    fun `discovers standard files deterministically and excludes build and cache directories`() {
        val root = Files.createTempDirectory("log-lens-discovery-")
        try {
            val first = root.resolve("config/logback.xml")
            val second = root.resolve("src/main/resources/logback-spring.xml")
            val excludedBuild = root.resolve("build/resources/logback.xml")
            val excludedCache = root.resolve(".gradle/logback-test.xml")
            val wrongName = root.resolve("config/logger.xml")
            listOf(first, second, excludedBuild, excludedCache, wrongName).forEach { path ->
                Files.createDirectories(path.parent)
                Files.writeString(path, "<configuration/>")
            }

            val candidates = LogbackFileSupport.discover(listOf(root))

            assertEquals(listOf("config/logback.xml", "src/main/resources/logback-spring.xml"), candidates.map { it.relativePath })
        } finally {
            root.toFile().deleteRecursively()
        }
    }

    @Test
    fun `validates relative and absolute files while rejecting escape missing and directories`() {
        val root = Files.createTempDirectory("log-lens-binding-")
        val externalRoot = Files.createTempDirectory("log-lens-external-")
        try {
            val relativeFile = root.resolve("config/logback.xml")
            val externalFile = externalRoot.resolve("external.xml")
            Files.createDirectories(relativeFile.parent)
            Files.writeString(relativeFile, "<configuration/>")
            Files.writeString(externalFile, "<configuration/>")

            val relative = LogbackFileSupport.validateBinding(root, "config/logback.xml") as BindingValidation.Valid
            assertEquals(BindingPathKind.PROJECT_RELATIVE, relative.binding.kind)
            assertEquals("config/logback.xml", relative.binding.path)
            assertEquals(relativeFile.toAbsolutePath(), relative.absolutePath)

            val absolute = LogbackFileSupport.validateBinding(root, externalFile.toString()) as BindingValidation.Valid
            assertEquals(BindingPathKind.ABSOLUTE, absolute.binding.kind)
            assertEquals(externalFile.toAbsolutePath().toString(), absolute.binding.path)

            assertTrue(LogbackFileSupport.validateBinding(root, "../outside.xml") is BindingValidation.Invalid)
            assertTrue(LogbackFileSupport.validateBinding(root, "missing.xml") is BindingValidation.Invalid)
            assertTrue(LogbackFileSupport.validateBinding(root, "config") is BindingValidation.Invalid)
        } finally {
            root.toFile().deleteRecursively()
            externalRoot.toFile().deleteRecursively()
        }
    }

    @Test
    fun `candidate binding input is project relative across nested content roots`() {
        val project = Files.createTempDirectory("log-lens-project-")
        try {
            val moduleRoot = project.resolve("modules/orders")
            val file = moduleRoot.resolve("src/main/resources/logback.xml")
            Files.createDirectories(file.parent)
            Files.writeString(file, "<configuration/>")

            val candidate = LogbackFileSupport.discover(listOf(moduleRoot)).single()

            assertEquals("src/main/resources/logback.xml", candidate.relativePath)
            assertEquals("modules/orders/src/main/resources/logback.xml", candidate.bindingInput(project))
            assertTrue(LogbackFileSupport.validateBinding(project, candidate.bindingInput(project)) is BindingValidation.Valid)
        } finally {
            project.toFile().deleteRecursively()
        }
    }
}
