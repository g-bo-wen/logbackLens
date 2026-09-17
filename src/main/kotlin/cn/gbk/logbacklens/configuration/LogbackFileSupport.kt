package cn.gbk.logbacklens.configuration

import cn.gbk.logbacklens.settings.BindingPathKind
import cn.gbk.logbacklens.settings.LogbackBinding
import java.nio.file.Files
import java.nio.file.FileVisitResult
import java.nio.file.Path
import java.nio.file.SimpleFileVisitor
import java.nio.file.attribute.BasicFileAttributes
import kotlin.io.path.isRegularFile
import kotlin.io.path.name

data class LogbackCandidate(
    val relativePath: String,
    val absolutePath: Path,
) {
    override fun toString(): String = relativePath

    fun bindingInput(projectBase: Path?): String {
        val base = projectBase?.normalize()?.toAbsolutePath()
        return if (base != null && absolutePath.startsWith(base)) {
            base.relativize(absolutePath).joinToString("/") { segment -> segment.toString() }
        } else {
            absolutePath.toString()
        }
    }
}

sealed interface BindingValidation {
    data class Valid(val binding: LogbackBinding, val absolutePath: Path) : BindingValidation
    data class Invalid(val message: String) : BindingValidation
}

object LogbackFileSupport {
    private val standardNames = setOf("logback.xml", "logback-test.xml", "logback-spring.xml")
    private val excludedDirectories = setOf(
        ".git", ".idea", ".gradle", "build", "out", "target", "node_modules", ".cache",
    )

    fun validateBinding(projectBase: Path?, input: String): BindingValidation {
        val trimmed = input.trim()
        if (trimmed.isEmpty()) return BindingValidation.Invalid("Logback path must not be blank.")
        val rawPath = runCatching { Path.of(trimmed) }.getOrElse {
            return BindingValidation.Invalid("Logback path is invalid.")
        }
        val kind = if (rawPath.isAbsolute) BindingPathKind.ABSOLUTE else BindingPathKind.PROJECT_RELATIVE
        val resolved = when (kind) {
            BindingPathKind.ABSOLUTE -> rawPath.normalize().toAbsolutePath()
            BindingPathKind.PROJECT_RELATIVE -> {
                val base = projectBase?.normalize()?.toAbsolutePath()
                    ?: return BindingValidation.Invalid("Project base path is unavailable.")
                val candidate = base.resolve(rawPath).normalize()
                if (!candidate.startsWith(base)) {
                    return BindingValidation.Invalid("Project-relative Logback path must stay inside the project.")
                }
                candidate
            }
        }
        if (!resolved.isRegularFile() || !Files.isReadable(resolved)) {
            return BindingValidation.Invalid("Logback path must reference a readable regular file.")
        }
        val storedPath = when (kind) {
            BindingPathKind.ABSOLUTE -> resolved.toString()
            BindingPathKind.PROJECT_RELATIVE -> projectBase!!.normalize().toAbsolutePath()
                .relativize(resolved)
                .joinToString("/") { segment -> segment.toString() }
        }
        return BindingValidation.Valid(LogbackBinding(storedPath, kind), resolved)
    }

    fun resolveStoredBinding(projectBase: Path?, binding: LogbackBinding): Path? {
        val rawPath = runCatching { Path.of(binding.path) }.getOrNull() ?: return null
        return when (binding.kind) {
            BindingPathKind.ABSOLUTE -> rawPath.normalize().toAbsolutePath()
            BindingPathKind.PROJECT_RELATIVE -> projectBase?.normalize()?.toAbsolutePath()?.resolve(rawPath)?.normalize()
        }
    }

    fun discover(contentRoots: List<Path>): List<LogbackCandidate> {
        val candidates = mutableListOf<LogbackCandidate>()
        contentRoots.map(Path::toAbsolutePath).map(Path::normalize).distinct().forEach { root ->
            if (!Files.isDirectory(root)) return@forEach
            var visited = 0
            Files.walkFileTree(root, emptySet<java.nio.file.FileVisitOption>(), MAX_DEPTH, object : SimpleFileVisitor<Path>() {
                override fun preVisitDirectory(dir: Path, attrs: BasicFileAttributes): FileVisitResult {
                    if (dir != root && dir.fileName.toString().lowercase() in excludedDirectories) {
                        return FileVisitResult.SKIP_SUBTREE
                    }
                    return if (++visited > MAX_VISITED) FileVisitResult.TERMINATE else FileVisitResult.CONTINUE
                }

                override fun visitFile(file: Path, attrs: BasicFileAttributes): FileVisitResult {
                    if (++visited > MAX_VISITED) return FileVisitResult.TERMINATE
                    if (attrs.isRegularFile && file.name.lowercase() in standardNames) {
                        val relative = root.relativize(file)
                        candidates += LogbackCandidate(
                            relativePath = relative.joinToString("/") { segment -> segment.toString() },
                            absolutePath = file.normalize().toAbsolutePath(),
                        )
                    }
                    return if (candidates.size >= MAX_CANDIDATES * contentRoots.size.coerceAtLeast(1)) {
                        FileVisitResult.TERMINATE
                    } else {
                        FileVisitResult.CONTINUE
                    }
                }
            })
        }
        return candidates.distinctBy(LogbackCandidate::absolutePath)
            .sortedWith(compareBy(LogbackCandidate::relativePath, { it.absolutePath.toString() }))
            .take(MAX_CANDIDATES)
    }

    private const val MAX_DEPTH = 12
    private const val MAX_VISITED = 10_000
    private const val MAX_CANDIDATES = 50
}
