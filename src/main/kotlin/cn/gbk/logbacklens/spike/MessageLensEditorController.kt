package cn.gbk.logbacklens.spike

import cn.gbk.BlueExpressionRenderer
import com.intellij.openapi.Disposable
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.ReadAction
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.editor.EditorFactory
import com.intellij.openapi.editor.FoldRegion
import com.intellij.openapi.editor.Inlay
import com.intellij.openapi.editor.event.CaretEvent
import com.intellij.openapi.editor.event.CaretListener
import com.intellij.openapi.editor.event.DocumentEvent
import com.intellij.openapi.editor.event.DocumentListener
import com.intellij.openapi.editor.event.EditorFactoryEvent
import com.intellij.openapi.editor.event.EditorFactoryListener
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.Disposer
import com.intellij.psi.PsiDocumentManager
import com.intellij.util.Alarm

internal enum class MessageLensMode {
    LENS,
    RAW,
    UNAVAILABLE,
    DISPOSED,
}

internal class MessageLensEditorController private constructor(
    private val project: Project,
    editor: Editor,
    private val modeChanged: (MessageLensMode) -> Unit,
) : Disposable {
    private var editor: Editor? = editor
    private val refreshAlarm = Alarm(Alarm.ThreadToUse.SWING_THREAD, this)
    private val folds = mutableListOf<FoldRegion>()
    private val inlays = mutableListOf<Inlay<*>>()
    private var currentTargets = emptyList<MessageLensTarget>()
    private var installedTargets = emptyList<MessageLensTarget>()
    private var mode = MessageLensMode.UNAVAILABLE
    private var disposed = false

    init {
        Disposer.register(project, this)
        editor.document.addDocumentListener(object : DocumentListener {
            override fun documentChanged(event: DocumentEvent) {
                clearPresentation()
                setMode(MessageLensMode.RAW)
                requestRefresh(REFRESH_DELAY_MS)
            }
        }, this)
        editor.caretModel.addCaretListener(object : CaretListener {
            override fun caretPositionChanged(event: CaretEvent) = refreshNow()
            override fun caretAdded(event: CaretEvent) = refreshNow()
            override fun caretRemoved(event: CaretEvent) = refreshNow()
        }, this)
        EditorFactory.getInstance().addEditorFactoryListener(object : EditorFactoryListener {
            override fun editorReleased(event: EditorFactoryEvent) {
                if (event.editor === this@MessageLensEditorController.editor) {
                    Disposer.dispose(this@MessageLensEditorController)
                }
            }
        }, this)
        requestRefresh(0)
    }

    internal fun requestRefresh(delayMillis: Int = REFRESH_DELAY_MS) {
        if (disposed) return
        refreshAlarm.cancelAllRequests()
        refreshAlarm.addRequest(::refreshNow, delayMillis)
    }

    internal fun refreshNow() {
        if (disposed || project.isDisposed) return
        ApplicationManager.getApplication().assertIsDispatchThread()
        val activeEditor = editor?.takeUnless(Editor::isDisposed) ?: return
        val document = activeEditor.document
        val psiDocumentManager = PsiDocumentManager.getInstance(project)

        if (!psiDocumentManager.isCommitted(document)) {
            clearPresentation()
            setMode(MessageLensMode.RAW)
            psiDocumentManager.performForCommittedDocument(document) { requestRefresh(0) }
            return
        }

        val targets = ReadAction.compute<List<MessageLensTarget>, RuntimeException> {
            psiDocumentManager.getPsiFile(document)?.let { file ->
                MessageLensPsiAnalyzer.analyzeAll(file, document)
            }.orEmpty()
        }
        if (targets.isEmpty()) {
            clearPresentation()
            setMode(MessageLensMode.UNAVAILABLE)
            return
        }

        val lensTargets = targets.filterNot { target ->
            activeEditor.caretModel.allCarets.any { caret -> caret.intersects(target) }
        }

        if (currentTargets == targets && installedTargets == lensTargets && presentationIsValid()) return
        installPresentation(activeEditor, targets, lensTargets)
    }

    internal fun currentMode(): MessageLensMode = mode
    internal fun ownedFoldCount(): Int = folds.count(FoldRegion::isValid)
    internal fun ownedInlayCount(): Int = inlays.count(Inlay<*>::isValid)
    internal fun isDisposed(): Boolean = disposed
    internal fun targetForTest(): MessageLensTarget? = currentTargets.singleOrNull()
    internal fun targetsForTest(): List<MessageLensTarget> = currentTargets
    internal fun installedTargetsForTest(): List<MessageLensTarget> = installedTargets

    private fun installPresentation(
        editor: Editor,
        targets: List<MessageLensTarget>,
        lensTargets: List<MessageLensTarget>,
    ) {
        clearPresentation()
        removeClonedLensFolds(editor, targets)

        val successfullyInstalled = mutableListOf<MessageLensTarget>()
        lensTargets.forEach { target ->
            val targetFolds = mutableListOf<FoldRegion>()
            editor.foldingModel.runBatchFoldingOperation {
                target.hiddenRanges.forEach { range ->
                    editor.foldingModel.addFoldRegion(range.startOffset, range.endOffset, "")?.also { fold ->
                        fold.isExpanded = false
                        targetFolds += fold
                    }
                }
            }
            if (targetFolds.size != target.hiddenRanges.size) {
                removeFolds(editor, targetFolds)
                return@forEach
            }

            val targetInlays = target.placeholderRanges.zip(target.expressionTexts).mapNotNull { (range, expression) ->
                editor.inlayModel.addInlineElement(
                    range.endOffset,
                    true,
                    BlueExpressionRenderer("‹$expression›"),
                )
            }
            if (targetInlays.size != target.expressionTexts.size) {
                targetInlays.forEach(Inlay<*>::dispose)
                removeFolds(editor, targetFolds)
                return@forEach
            }

            folds += targetFolds
            inlays += targetInlays
            successfullyInstalled += target
        }

        currentTargets = targets
        installedTargets = successfullyInstalled
        setMode(if (successfullyInstalled.isNotEmpty()) MessageLensMode.LENS else MessageLensMode.RAW)
    }

    private fun presentationIsValid(): Boolean =
        folds.size == installedTargets.sumOf { target -> target.hiddenRanges.size } && folds.all(FoldRegion::isValid) &&
            inlays.size == installedTargets.sumOf { target -> target.expressionTexts.size } && inlays.all(Inlay<*>::isValid)

    private fun removeClonedLensFolds(editor: Editor, targets: List<MessageLensTarget>) {
        val hiddenRanges = targets.flatMap(MessageLensTarget::hiddenRanges)
            .map { range -> range.startOffset to range.endOffset }
            .toSet()
        val clonedFolds = editor.foldingModel.allFoldRegions.filter { fold ->
            fold.isValid && fold.placeholderText.isEmpty() &&
                (fold.startOffset to fold.endOffset) in hiddenRanges
        }
        removeFolds(editor, clonedFolds)
    }

    private fun removeFolds(editor: Editor, regions: Collection<FoldRegion>) {
        if (regions.isEmpty()) return
        editor.foldingModel.runBatchFoldingOperation {
            regions.forEach { fold ->
                if (fold.isValid) editor.foldingModel.removeFoldRegion(fold)
            }
        }
    }

    private fun clearPresentation() {
        inlays.toList().forEach { inlay ->
            if (inlay.isValid) inlay.dispose()
        }
        inlays.clear()

        val activeEditor = editor
        if (activeEditor != null && !activeEditor.isDisposed && folds.isNotEmpty()) {
            activeEditor.foldingModel.runBatchFoldingOperation {
                folds.toList().forEach { fold ->
                    if (fold.isValid) activeEditor.foldingModel.removeFoldRegion(fold)
                }
            }
        }
        folds.clear()
        installedTargets = emptyList()
        currentTargets = emptyList()
    }

    private fun com.intellij.openapi.editor.Caret.intersects(target: MessageLensTarget): Boolean {
        if (target.callRange.containsOffset(offset)) return true
        if (!hasSelection()) return false
        return selectionStart < target.callRange.endOffset && selectionEnd > target.callRange.startOffset
    }

    private fun setMode(newMode: MessageLensMode) {
        if (mode == newMode) return
        mode = newMode
        modeChanged(newMode)
    }

    override fun dispose() {
        if (disposed) return
        disposed = true
        clearPresentation()
        editor = null
        setMode(MessageLensMode.DISPOSED)
    }

    companion object {
        private const val REFRESH_DELAY_MS = 100
        fun attach(
            project: Project,
            editor: Editor,
            modeChanged: (MessageLensMode) -> Unit = {},
        ): MessageLensEditorController = MessageLensEditorController(project, editor, modeChanged)
    }
}
