package cn.gbk.logbacklens.configuration

import cn.gbk.logbacklens.logback.LogbackXmlParser
import cn.gbk.logbacklens.settings.LOG_LENS_BINDING_TOPIC
import cn.gbk.logbacklens.settings.LogLensBindingListener
import cn.gbk.logbacklens.settings.LogLensProjectSettings
import cn.gbk.logbacklens.settings.LogbackBinding
import com.intellij.openapi.Disposable
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.ReadAction
import com.intellij.openapi.components.Service
import com.intellij.openapi.project.Project
import com.intellij.openapi.roots.ProjectRootManager
import com.intellij.openapi.startup.ProjectActivity
import com.intellij.openapi.vfs.newvfs.BulkFileListener
import com.intellij.openapi.vfs.newvfs.events.VFileEvent
import com.intellij.openapi.vfs.VirtualFileManager
import com.intellij.util.Alarm
import com.intellij.util.messages.Topic
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path

@Service(Service.Level.PROJECT)
class LogbackConfigurationService(private val project: Project) : Disposable {
    private val generationStore = LogbackGenerationStore()
    private val refreshAlarm = Alarm(Alarm.ThreadToUse.POOLED_THREAD, this)
    private var started = false

    fun start() {
        if (started || project.isDisposed) return
        started = true
        project.messageBus.connect(this).subscribe(
            LOG_LENS_BINDING_TOPIC,
            LogLensBindingListener { binding ->
                if (binding == null) clearRuntime() else scheduleRefresh(binding)
            },
        )
        ApplicationManager.getApplication().messageBus.connect(this).subscribe(
            VirtualFileManager.VFS_CHANGES,
            object : BulkFileListener {
                override fun after(events: List<VFileEvent>) {
                    if (eventsAffectBinding(events)) currentState().binding?.let(::scheduleRefresh)
                }
            },
        )
        LogLensProjectSettings.getInstance(project).binding()?.let(::scheduleRefresh)
    }

    fun currentState(): LogbackConfigurationState = generationStore.current()

    fun bind(input: String, completion: (BindingValidation) -> Unit = {}) {
        start()
        val base = project.basePath?.let(Path::of)
        ApplicationManager.getApplication().executeOnPooledThread {
            val validation = LogbackFileSupport.validateBinding(base, input)
            ApplicationManager.getApplication().invokeLater {
                if (project.isDisposed) return@invokeLater
                if (validation is BindingValidation.Valid) {
                    LogLensProjectSettings.getInstance(project).bind(validation.binding)
                }
                completion(validation)
            }
        }
    }

    fun clear() {
        start()
        val settings = LogLensProjectSettings.getInstance(project)
        if (settings.binding() == null) clearRuntime() else settings.clear()
    }

    fun refresh() {
        start()
        currentState().binding?.let(::scheduleRefresh)
    }

    fun discoverCandidates(completion: (List<LogbackCandidate>) -> Unit) {
        start()
        val roots = ReadAction.compute<List<Path>, RuntimeException> {
            ProjectRootManager.getInstance(project).contentRoots.mapNotNull { file ->
                runCatching { Path.of(file.path) }.getOrNull()
            }
        }
        ApplicationManager.getApplication().executeOnPooledThread {
            val candidates = LogbackFileSupport.discover(roots)
            ApplicationManager.getApplication().invokeLater {
                if (!project.isDisposed) completion(candidates)
            }
        }
    }

    private fun scheduleRefresh(binding: LogbackBinding) {
        if (project.isDisposed) return
        val generation = generationStore.begin(binding)
        publishState()
        refreshAlarm.cancelAllRequests()
        refreshAlarm.addRequest({ parseAndPublish(generation, binding) }, REFRESH_DELAY_MS)
    }

    private fun parseAndPublish(generation: Long, binding: LogbackBinding) {
        val path = LogbackFileSupport.resolveStoredBinding(project.basePath?.let(Path::of), binding)
        val result = if (path != null && Files.isRegularFile(path) && Files.isReadable(path)) {
            runCatching {
                LogbackXmlParser.parse(Files.readString(path, StandardCharsets.UTF_8), path.toString())
            }.getOrNull()
        } else {
            null
        }

        val published = when (result) {
            null -> generationStore.publishUnavailable(generation, binding, path?.toString() ?: binding.path)
            else -> generationStore.publish(generation, binding, result)
        }
        if (published) publishState()
    }

    private fun eventsAffectBinding(events: List<VFileEvent>): Boolean {
        val binding = currentState().binding ?: return false
        val active = LogbackFileSupport.resolveStoredBinding(project.basePath?.let(Path::of), binding) ?: return false
        val parent = active.parent
        return events.any { event ->
            val eventPath = runCatching { Path.of(event.path).normalize().toAbsolutePath() }.getOrNull()
            eventPath == active || eventPath?.parent == parent
        }
    }

    private fun clearRuntime() {
        refreshAlarm.cancelAllRequests()
        generationStore.clear()
        publishState()
    }

    private fun publishState() {
        val state = currentState()
        ApplicationManager.getApplication().invokeLater {
            if (!project.isDisposed) {
                project.messageBus.syncPublisher(LOGBACK_CONFIGURATION_TOPIC).configurationChanged(state)
            }
        }
    }

    override fun dispose() = Unit

    companion object {
        private const val REFRESH_DELAY_MS = 150

        fun getInstance(project: Project): LogbackConfigurationService =
            project.getService(LogbackConfigurationService::class.java)
    }
}

@JvmField
val LOGBACK_CONFIGURATION_TOPIC: Topic<LogbackConfigurationListener> = Topic.create(
    "Log Lens Logback configuration changed",
    LogbackConfigurationListener::class.java,
)

internal class LogbackConfigurationStartupActivity : ProjectActivity {
    override suspend fun execute(project: Project) {
        LogbackConfigurationService.getInstance(project).start()
    }
}
