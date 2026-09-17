package cn.gbk

import cn.gbk.logbacklens.spike.InteractionFixture
import cn.gbk.logbacklens.spike.MessageLensEditorController
import cn.gbk.logbacklens.spike.MessageLensMode
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.fileEditor.TextEditor
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.Disposer
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.openapi.wm.ToolWindow
import com.intellij.openapi.wm.ToolWindowFactory
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBPanel
import com.intellij.ui.content.ContentFactory
import java.awt.BorderLayout
import java.awt.FlowLayout
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import javax.swing.JButton
import javax.swing.JPanel

class MyToolWindowFactory : ToolWindowFactory {
    override fun shouldBeAvailable(project: Project) = true

    override fun createToolWindowContent(project: Project, toolWindow: ToolWindow) {
        val spike = RenderingPrimitiveToolWindow(project)
        val content = ContentFactory.getInstance().createContent(spike.component, null, false)
        toolWindow.contentManager.addContent(content)
    }
}

private class RenderingPrimitiveToolWindow(private val project: Project) {
    private val controller = RenderingPrimitiveController()
    private val status = JBLabel("Open the fixed fixture, then inspect Gate 1 through Gate 4.")
    private var fixtureEditor: Editor? = null
    private var fixturePath: java.nio.file.Path? = null
    private var openingFixture = false
    private val openFixtureButton = button("Open fixture") { openFixture() }
    private var interactionFixturePath: java.nio.file.Path? = null
    private var interactionController: MessageLensEditorController? = null
    private var openingInteractionFixture = false
    private val openInteractionFixtureButton = button("Open PRE-I-002 fixture") { openInteractionFixture() }

    val component = JBPanel<JBPanel<*>>(BorderLayout()).apply {
        add(JPanel(FlowLayout(FlowLayout.LEFT)).apply {
            add(openInteractionFixtureButton)
            add(openFixtureButton)
            add(button("Gate 1: folds") { applyGate(RenderingGate.FOLDS_ONLY) })
            add(button("Gate 2: first inlay") { applyGate(RenderingGate.FIRST_INLAY) })
            add(button("Gate 3: both inlays") { applyGate(RenderingGate.BOTH_INLAYS) })
            add(button("Gate 4: integrity") { verifyIntegrity() })
            add(button("Raw") { clearRendering() })
        }, BorderLayout.NORTH)
        add(status, BorderLayout.CENTER)
    }

    private fun button(text: String, action: () -> Unit) = JButton(text).apply {
        addActionListener { action() }
    }

    private fun openFixture() {
        if (openingFixture) {
            status.text = "Fixture is already opening."
            return
        }

        fixtureEditor?.takeUnless(Editor::isDisposed)?.let(controller::clear)
        fixtureEditor = null
        openingFixture = true
        openFixtureButton.isEnabled = false
        status.text = "Creating the fixed fixture outside the UI thread…"

        ApplicationManager.getApplication().executeOnPooledThread {
            val fixtureResult = runCatching {
                val path = fixturePath ?: Files.createTempFile("log-lens-rendering-primitive-", ".java").also {
                    Files.writeString(it, RenderingPrimitiveOffsets.FIXTURE, StandardCharsets.UTF_8)
                    it.toFile().deleteOnExit()
                    fixturePath = it
                }
                FixtureFile(LocalFileSystem.getInstance().refreshAndFindFileByNioFile(path))
            }

            ApplicationManager.getApplication().invokeLater {
                openingFixture = false
                openFixtureButton.isEnabled = true
                if (project.isDisposed) return@invokeLater

                fixtureResult.fold(
                    onSuccess = ::openFixtureEditor,
                    onFailure = { error ->
                        status.text = "BLOCKED: fixture creation failed (${error.javaClass.simpleName})."
                    },
                )
            }
        }
    }

    private fun openFixtureEditor(fixtureFile: FixtureFile) {
        val virtualFile = fixtureFile.virtualFile
        if (virtualFile == null) {
            status.text = "BLOCKED: IntelliJ could not load the temporary Java fixture."
            return
        }

        val editor = FileEditorManager.getInstance(project)
            .openFile(virtualFile, true)
            .filterIsInstance<TextEditor>()
            .firstOrNull()
            ?.editor
        fixtureEditor = editor
        if (editor != null) controller.clear(editor)
        status.text = if (editor?.document?.text == RenderingPrimitiveOffsets.FIXTURE) {
            "Fixture ready in Raw mode. Document contains the exact fixed tracer."
        } else {
            "BLOCKED: the selected editor is not the fixed fixture."
        }
    }

    private fun openInteractionFixture() {
        if (openingInteractionFixture) {
            status.text = "PRE-I-002 fixture is already opening."
            return
        }

        interactionController?.let { controller ->
            if (!controller.isDisposed()) Disposer.dispose(controller)
        }
        interactionController = null
        openingInteractionFixture = true
        openInteractionFixtureButton.isEnabled = false
        status.text = "Creating the valid Java fixture outside the UI thread…"

        ApplicationManager.getApplication().executeOnPooledThread {
            val fixtureResult = runCatching {
                val path = interactionFixturePath
                    ?: Files.createTempFile("log-lens-interaction-feasibility-", ".java").also {
                        Files.writeString(it, InteractionFixture.MULTI_TARGET_SOURCE, StandardCharsets.UTF_8)
                        it.toFile().deleteOnExit()
                        interactionFixturePath = it
                    }
                FixtureFile(LocalFileSystem.getInstance().refreshAndFindFileByNioFile(path))
            }

            ApplicationManager.getApplication().invokeLater {
                openingInteractionFixture = false
                openInteractionFixtureButton.isEnabled = true
                if (project.isDisposed) return@invokeLater

                fixtureResult.fold(
                    onSuccess = ::openInteractionEditor,
                    onFailure = { error ->
                        status.text = "PRE-I-002 BLOCKED: fixture creation failed (${error.javaClass.simpleName})."
                    },
                )
            }
        }
    }

    private fun openInteractionEditor(fixtureFile: FixtureFile) {
        val virtualFile = fixtureFile.virtualFile
        if (virtualFile == null) {
            status.text = "PRE-I-002 BLOCKED: IntelliJ could not load the valid Java fixture."
            return
        }

        val editor = FileEditorManager.getInstance(project)
            .openFile(virtualFile, true)
            .filterIsInstance<TextEditor>()
            .firstOrNull()
            ?.editor
        if (editor == null) {
            status.text = "PRE-I-002 BLOCKED: no text editor was created."
            return
        }

        interactionController = MessageLensEditorController.attach(project, editor) { mode ->
            status.text = when (mode) {
                MessageLensMode.LENS -> "PRE-I-002 Lens: supported calls outside carets are rendered."
                MessageLensMode.RAW -> "PRE-I-002 Raw: all supported calls intersect carets, or PSI is rebuilding."
                MessageLensMode.UNAVAILABLE -> "PRE-I-002 Raw: no supported target PSI is currently available."
                MessageLensMode.DISPOSED -> "PRE-I-002 controller disposed with its editor/project."
            }
        }
        status.text = "PRE-I-002 attached; automatic PSI analysis is pending."
    }

    private fun applyGate(gate: RenderingGate) {
        val editor = currentFixtureEditor() ?: return
        val result = controller.apply(editor, gate)
        status.text = result.message
    }

    private fun verifyIntegrity() {
        val editor = currentFixtureEditor() ?: return
        FileDocumentManager.getInstance().saveDocument(editor.document)
        val path = fixturePath
        status.text = "Gate 4: checking the saved file outside the UI thread…"

        ApplicationManager.getApplication().executeOnPooledThread {
            val diskTextResult = runCatching {
                path?.let { Files.readString(it, StandardCharsets.UTF_8) }
            }
            ApplicationManager.getApplication().invokeLater {
                if (project.isDisposed || editor.isDisposed) return@invokeLater

                diskTextResult.fold(
                    onSuccess = { diskText ->
                        val documentMatches = editor.document.text == RenderingPrimitiveOffsets.FIXTURE
                        val diskMatches = diskText == RenderingPrimitiveOffsets.FIXTURE
                        status.text = if (documentMatches && diskMatches) {
                            "Gate 4 PASS: Document.text and saved UTF-8 file both remain unchanged."
                        } else {
                            "Gate 4 FAIL: documentMatches=$documentMatches, diskMatches=$diskMatches"
                        }
                    },
                    onFailure = { error ->
                        status.text = "Gate 4 BLOCKED: file read failed (${error.javaClass.simpleName})."
                    },
                )
            }
        }
    }

    private fun clearRendering() {
        val editor = currentFixtureEditor() ?: return
        controller.clear(editor)
        status.text = "Raw mode: all spike folds and inlays removed."
    }

    private fun currentFixtureEditor(): Editor? {
        val editor = fixtureEditor
        if (editor == null || editor.isDisposed) {
            status.text = "Open the fixture first."
            return null
        }
        if (editor.document.text != RenderingPrimitiveOffsets.FIXTURE) {
            status.text = "STOP: fixture Document changed; rendering was not applied."
            return null
        }
        return editor
    }
}

private data class FixtureFile(
    val virtualFile: VirtualFile?,
)
