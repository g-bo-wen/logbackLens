package cn.gbk.logbacklens.route

import cn.gbk.logbacklens.configuration.LogbackConfigurationState
import cn.gbk.logbacklens.logback.LogbackSnapshot
import cn.gbk.logbacklens.settings.LogLensApplicationState
import com.intellij.openapi.Disposable
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.ModalityState
import com.intellij.openapi.application.ReadAction
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.editor.Inlay
import com.intellij.openapi.editor.event.DocumentEvent
import com.intellij.openapi.editor.event.DocumentListener
import com.intellij.openapi.editor.event.EditorMouseEvent
import com.intellij.openapi.editor.event.EditorMouseListener
import com.intellij.openapi.editor.event.EditorMouseMotionListener
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.Disposer
import com.intellij.psi.PsiDocumentManager
import com.intellij.util.Alarm
import com.intellij.util.concurrency.AppExecutorUtil
import javax.swing.SwingUtilities

internal data class RouteLensTarget(
    val call: Slf4jRouteCall,
    val outcome: RouteOutcome,
)

internal class RouteLensEditorController private constructor(
    private val project: Project,
    editor: Editor,
    owner: Disposable,
    private val settingsProvider: () -> LogLensApplicationState,
    private val configurationProvider: () -> LogbackConfigurationState,
    private val navigator: RouteNavigator,
) : Disposable {
    private var editor: Editor? = editor
    private val refreshAlarm = Alarm(Alarm.ThreadToUse.SWING_THREAD, this)
    private val inlays = mutableListOf<Inlay<RouteLensRenderer>>()
    private var targets = emptyList<RouteLensTarget>()
    private var disposed = false
    private var ownsTooltip = false
    private var refreshGeneration = 0L

    init {
        Disposer.register(owner, this)
        editor.document.addDocumentListener(object : DocumentListener {
            override fun documentChanged(event: DocumentEvent) {
                clearPresentation()
                requestRefresh(REFRESH_DELAY_MS)
            }
        }, this)
        editor.addEditorMouseMotionListener(object : EditorMouseMotionListener {
            override fun mouseMoved(event: EditorMouseEvent) {
                val inlay = event.inlay
                val renderer = inlay?.renderer as? RouteLensRenderer
                val tooltip = renderer?.tooltipAt(inlay, event.mouseEvent.x)
                updateTooltip(event.editor, tooltip)
            }
        }, this)
        editor.addEditorMouseListener(object : EditorMouseListener {
            override fun mouseClicked(event: EditorMouseEvent) {
                if (!SwingUtilities.isLeftMouseButton(event.mouseEvent)) return
                val inlay = event.inlay ?: return
                if (navigateAt(inlay, event.mouseEvent.x)) event.consume()
            }

            override fun mouseExited(event: EditorMouseEvent) = updateTooltip(event.editor, null)
        }, this)
        requestRefresh(0)
    }

    fun invalidate() {
        if (disposed) return
        clearPresentation()
        requestRefresh(0)
    }

    fun refreshNow() {
        val generation = ++refreshGeneration
        refreshAlarm.cancelAllRequests()
        val context = prepareRefresh() ?: return
        val newTargets = ReadAction.compute<List<RouteLensTarget>, RuntimeException> {
            analyzeTargets(context)
        }
        applyTargets(generation, context, newTargets)
    }

    private fun refreshAsync(generation: Long) {
        if (generation != refreshGeneration) return
        val context = prepareRefresh() ?: return
        ReadAction.nonBlocking<List<RouteLensTarget>> { analyzeTargets(context) }
            .expireWith(this)
            .coalesceBy(this)
            .finishOnUiThread(ModalityState.any()) { targets -> applyTargets(generation, context, targets) }
            .submit(AppExecutorUtil.getAppExecutorService())
    }

    private fun prepareRefresh(): RefreshContext? {
        if (disposed || project.isDisposed) return null
        ApplicationManager.getApplication().assertIsDispatchThread()
        val activeEditor = editor?.takeUnless(Editor::isDisposed) ?: return null
        val settings = settingsProvider()
        val configuration = configurationProvider()
        val snapshot = configuration.snapshot
        if (!settings.routeLensEnabled || snapshot == null) {
            clearPresentation()
            return null
        }
        val psiDocuments = PsiDocumentManager.getInstance(project)
        if (!psiDocuments.isCommitted(activeEditor.document)) {
            clearPresentation()
            psiDocuments.performForCommittedDocument(activeEditor.document) { requestRefresh(0) }
            return null
        }

        return RefreshContext(activeEditor, settings, snapshot, psiDocuments)
    }

    private fun analyzeTargets(context: RefreshContext): List<RouteLensTarget> {
        val calls = context.psiDocumentManager.getPsiFile(context.editor.document)
            ?.let(Slf4jRouteCallAnalyzer::analyze)
            .orEmpty()
        return calls.map { call ->
            RouteLensTarget(call, LogbackRouteResolver.resolve(context.snapshot, call.loggerIdentity, call.eventLevel))
        }
    }

    private fun applyTargets(
        generation: Long,
        context: RefreshContext,
        newTargets: List<RouteLensTarget>,
    ) {
        if (generation != refreshGeneration || disposed || project.isDisposed || context.editor.isDisposed) return
        if (context.editor.document.modificationStamp != context.modificationStamp) return
        if (newTargets == targets && inlays.all(Inlay<*>::isValid)) return

        clearPresentation()
        newTargets.forEach { target ->
            val renderer = RouteLensPresentationFactory.create(
                target.call.loggerIdentity,
                target.call.eventLevel,
                target.outcome,
                context.settings.routeColor,
            ) ?: return@forEach
            context.editor.inlayModel.addInlineElement(
                target.call.presentationOffset,
                true,
                renderer,
            )?.let(inlays::add)
        }
        targets = newTargets
    }

    fun requestRefresh(delayMillis: Int = REFRESH_DELAY_MS) {
        if (disposed) return
        val generation = ++refreshGeneration
        refreshAlarm.cancelAllRequests()
        refreshAlarm.addRequest({ refreshAsync(generation) }, delayMillis)
    }

    private data class RefreshContext(
        val editor: Editor,
        val settings: LogLensApplicationState,
        val snapshot: LogbackSnapshot,
        val psiDocumentManager: PsiDocumentManager,
        val modificationStamp: Long = editor.document.modificationStamp,
    )

    internal fun targetsForTest(): List<RouteLensTarget> = targets
    internal fun inlaysForTest(): List<Inlay<RouteLensRenderer>> = inlays.toList()
    internal fun isDisposed(): Boolean = disposed
    internal fun navigateAtForTest(inlay: Inlay<*>, mouseX: Int): Boolean = navigateAt(inlay, mouseX)

    private fun navigateAt(inlay: Inlay<*>, mouseX: Int): Boolean {
        val renderer = inlay.renderer as? RouteLensRenderer ?: return false
        val destination = renderer.destinationAt(inlay, mouseX) ?: return false
        navigator.navigate(destination)
        return true
    }

    private fun updateTooltip(editor: Editor, tooltip: String?) {
        if (tooltip != null) {
            editor.contentComponent.toolTipText = "<html>${escapeHtml(tooltip)}</html>"
            ownsTooltip = true
        } else if (ownsTooltip) {
            editor.contentComponent.toolTipText = null
            ownsTooltip = false
        }
    }

    private fun clearPresentation() {
        inlays.toList().forEach { inlay -> if (inlay.isValid) inlay.dispose() }
        inlays.clear()
        targets = emptyList()
        editor?.takeUnless(Editor::isDisposed)?.let { activeEditor -> updateTooltip(activeEditor, null) }
    }

    override fun dispose() {
        if (disposed) return
        disposed = true
        clearPresentation()
        editor = null
    }

    companion object {
        private const val REFRESH_DELAY_MS = 100

        fun attach(
            project: Project,
            editor: Editor,
            owner: Disposable = project,
            settingsProvider: () -> LogLensApplicationState,
            configurationProvider: () -> LogbackConfigurationState,
            navigator: RouteNavigator = XmlSourceNavigator(project),
        ) = RouteLensEditorController(
            project,
            editor,
            owner,
            settingsProvider,
            configurationProvider,
            navigator,
        )
    }
}

private fun escapeHtml(value: String): String = value
    .replace("&", "&amp;")
    .replace("<", "&lt;")
    .replace(">", "&gt;")
    .replace("\"", "&quot;")
