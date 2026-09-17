package cn.gbk.logbacklens.message

import cn.gbk.logbacklens.settings.LOG_LENS_SETTINGS_TOPIC
import cn.gbk.logbacklens.settings.LogLensApplicationSettings
import cn.gbk.logbacklens.settings.LogLensSettingsListener
import com.intellij.openapi.Disposable
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.Service
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.editor.EditorFactory
import com.intellij.openapi.editor.event.EditorFactoryEvent
import com.intellij.openapi.editor.event.EditorFactoryListener
import com.intellij.openapi.project.Project
import com.intellij.openapi.startup.ProjectActivity
import com.intellij.openapi.util.Disposer
import java.util.IdentityHashMap

@Service(Service.Level.PROJECT)
internal class MessageLensProjectService(private val project: Project) : Disposable {
    private val controllers = IdentityHashMap<Editor, MessageLensEditorController>()
    private var started = false

    fun start() {
        if (started || project.isDisposed) return
        started = true

        ApplicationManager.getApplication().messageBus.connect(this).subscribe(
            LOG_LENS_SETTINGS_TOPIC,
            LogLensSettingsListener { refreshAll() },
        )
        EditorFactory.getInstance().addEditorFactoryListener(object : EditorFactoryListener {
            override fun editorCreated(event: EditorFactoryEvent) = attach(event.editor)

            override fun editorReleased(event: EditorFactoryEvent) {
                controllers.remove(event.editor)?.let { controller ->
                    if (!controller.isDisposed()) Disposer.dispose(controller)
                }
            }
        }, this)

        EditorFactory.getInstance().allEditors.forEach(::attach)
    }

    private fun attach(editor: Editor) {
        if (editor.project !== project || editor.isDisposed || controllers.containsKey(editor)) return
        controllers[editor] = MessageLensEditorController.attach(project, editor, this)
    }

    private fun refreshAll() {
        controllers.entries.removeIf { (editor, controller) -> editor.isDisposed || controller.isDisposed() }
        controllers.values.forEach(MessageLensEditorController::invalidate)
    }

    internal fun controllerForTest(editor: Editor): MessageLensEditorController? = controllers[editor]

    override fun dispose() {
        controllers.clear()
    }

    companion object {
        fun getInstance(project: Project): MessageLensProjectService =
            project.getService(MessageLensProjectService::class.java)
    }
}

internal class MessageLensStartupActivity : ProjectActivity {
    override suspend fun execute(project: Project) {
        MessageLensProjectService.getInstance(project).start()
    }
}
