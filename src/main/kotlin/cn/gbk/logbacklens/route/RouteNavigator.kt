package cn.gbk.logbacklens.route

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.fileEditor.OpenFileDescriptor
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.LocalFileSystem
import java.nio.file.Path

fun interface RouteNavigator {
    fun navigate(destination: RouteDestination)
}

internal class XmlSourceNavigator(private val project: Project) : RouteNavigator {
    override fun navigate(destination: RouteDestination) {
        val path = runCatching { Path.of(destination.source.path) }.getOrNull() ?: return
        ApplicationManager.getApplication().executeOnPooledThread {
            val file = LocalFileSystem.getInstance().refreshAndFindFileByNioFile(path)
            ApplicationManager.getApplication().invokeLater {
                if (project.isDisposed || file == null || !file.isValid) return@invokeLater
                val offset = destination.source.offset?.coerceIn(0, file.length.toInt()) ?: 0
                OpenFileDescriptor(project, file, offset).navigate(true)
            }
        }
    }
}
