package cn.gbk.logbacklens.message

import cn.gbk.logbacklens.settings.LogLensApplicationState
import cn.gbk.logbacklens.settings.LogLensApplicationSettings
import com.intellij.openapi.application.ReadAction
import com.intellij.openapi.command.WriteCommandAction
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.actionSystem.IdeActions
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.Disposer
import com.intellij.psi.PsiDocumentManager
import com.intellij.testFramework.fixtures.BasePlatformTestCase

class MessageLensInteractionTest : BasePlatformTestCase() {
    fun testProjectServiceAttachesExistingEditorAndReactsToApplicationSetting() {
        val editor = configureFixture()
        editor.caretModel.moveToOffset(0)
        val service = MessageLensProjectService.getInstance(project)
        val settings = LogLensApplicationSettings.getInstance()
        val original = settings.snapshot()

        try {
            service.start()
            val controller = requireNotNull(service.controllerForTest(editor))
            controller.refreshNow()
            assertEquals(MessageLensMode.LENS, controller.currentMode())

            settings.update(original.copy(messageLensEnabled = false))
            controller.refreshNow()
            assertEquals(MessageLensMode.UNAVAILABLE, controller.currentMode())
            assertEquals(0, controller.ownedFoldCount())
        } finally {
            settings.update(original)
        }
    }

    fun testSupportedSlf4jClassicMethodsAndRawBoundaryMatrix() {
        val source = """class DemoService {
            private org.slf4j.Logger audit;
            private FakeLogger fake;
            private static final String TEMPLATE = "constant {}";

            void write(Object value, RuntimeException failure) {
                audit.trace("trace {}", value);
                audit.debug("debug {}", value);
                audit.info("info {}", value);
                audit.warn("warn {}", value);
                audit.error("error {}", value);
                fake.info("wrong receiver {}", value);
                audit.info("no placeholder", value);
                audit.info("too few {} {}", value);
                audit.info("too many {}", value, value);
                audit.info(TEMPLATE, value);
                audit.info("cross " + "{" + "}", value);
                audit.info("escaped \\{}", value);
                audit.error("throwable {} {}", value, failure);
                audit.atInfo();
            }

            interface FakeLogger {
                void info(String template, Object... arguments);
            }
        }""".trimIndent()
        val editor = configureText(source)

        val targets = analyzeAll(editor)

        assertEquals(targets.joinToString { target -> text(editor, target.callRange) }, 5, targets.size)
        assertEquals(
            listOf("trace", "debug", "info", "warn", "error"),
            targets.map { target -> text(editor, target.callRange).substringBefore('(').substringAfterLast('.') },
        )
    }

    fun testMessageSettingDisablesAndRestoresPresentationWithoutChangingDocument() {
        val editor = configureFixture()
        val original = editor.document.text
        var settings = LogLensApplicationState()
        val controller = MessageLensEditorController.attach(
            project,
            editor,
            settingsProvider = { settings.copy() },
        )
        editor.caretModel.moveToOffset(0)
        controller.refreshNow()
        assertEquals(MessageLensMode.LENS, controller.currentMode())

        settings = settings.copy(messageLensEnabled = false)
        controller.invalidate()
        controller.refreshNow()
        assertEquals(MessageLensMode.UNAVAILABLE, controller.currentMode())
        assertEquals(0, controller.ownedFoldCount())
        assertEquals(0, controller.ownedInlayCount())
        assertEquals(original, editor.document.text)

        settings = settings.copy(messageLensEnabled = true, messageExpressionColor = "#123456")
        controller.invalidate()
        controller.refreshNow()
        assertEquals(MessageLensMode.LENS, controller.currentMode())
        assertEquals(original, editor.document.text)
    }

    fun testJavaPsiDerivesAllRangesFromTheCurrentDocument() {
        val editor = configureFixture()
        val original = editor.document.text
        val target = requireNotNull(analyze(editor))
        assertEquals("log.info(\"order {} {}\", orderId, accountId)", text(editor, target.callRange))
        assertEquals(listOf("{}", "{}"), target.placeholderRanges.map { text(editor, it) })
        assertEquals(", orderId, accountId", text(editor, target.tailRange))
        assertEquals(listOf("orderId", "accountId"), target.expressionTexts)
        assertEquals(InteractionFixture.SOURCE, original)

        write(project, "shift fixture offsets") {
            editor.document.insertString(0, "// shifted dynamically\n\n")
        }
        commit(editor)
        val shiftedTarget = requireNotNull(analyze(editor))
        assertTrue(shiftedTarget.callRange.startOffset > target.callRange.startOffset)
        assertEquals("log.info(\"order {} {}\", orderId, accountId)", text(editor, shiftedTarget.callRange))
        assertEquals(listOf("{}", "{}"), shiftedTarget.placeholderRanges.map { text(editor, it) })
    }

    fun testCaretAndMultipleCaretsSwitchBetweenLensAndRaw() {
        val editor = configureFixture()
        editor.caretModel.moveToOffset(0)
        val controller = attach(editor)
        controller.refreshNow()

        assertEquals(MessageLensMode.LENS, controller.currentMode())
        assertEquals(3, controller.ownedFoldCount())
        assertEquals(2, controller.ownedInlayCount())
        val callRange = requireNotNull(controller.targetForTest()).callRange

        editor.caretModel.moveToOffset(callRange.startOffset + 4)
        assertEquals(MessageLensMode.RAW, controller.currentMode())
        assertEquals(0, controller.ownedFoldCount())
        assertEquals(0, controller.ownedInlayCount())

        editor.caretModel.moveToOffset(0)
        assertEquals(MessageLensMode.LENS, controller.currentMode())
        val secondary = editor.caretModel.addCaret(editor.offsetToVisualPosition(callRange.startOffset + 8))
        assertNotNull(secondary)
        controller.refreshNow()
        assertEquals(MessageLensMode.RAW, controller.currentMode())

        editor.caretModel.removeCaret(requireNotNull(secondary))
        controller.refreshNow()
        assertEquals(MessageLensMode.LENS, controller.currentMode())

        repeat(30) {
            editor.caretModel.moveToOffset(callRange.startOffset + 1)
            assertEquals(MessageLensMode.RAW, controller.currentMode())
            editor.caretModel.moveToOffset(0)
            assertEquals(MessageLensMode.LENS, controller.currentMode())
        }
        assertEquals(3, controller.ownedFoldCount())
        assertEquals(2, controller.ownedInlayCount())
    }

    fun testEditUndoRedoAndTemporarySyntaxErrorRecoverFromCurrentPsi() {
        val editor = configureFixture()
        val original = editor.document.text
        val controller = attach(editor)
        editor.caretModel.moveToOffset(0)
        controller.refreshNow()

        val argumentOffset = editor.document.text.lastIndexOf("orderId")
        write(project, "rename fixture argument") {
            editor.document.replaceString(argumentOffset, argumentOffset + "orderId".length, "customerId")
        }
        commit(editor)
        controller.refreshNow()
        assertEquals(MessageLensMode.LENS, controller.currentMode())
        assertEquals(listOf("customerId", "accountId"), controller.targetForTest()?.expressionTexts)

        myFixture.performEditorAction(IdeActions.ACTION_UNDO)
        commit(editor)
        controller.refreshNow()
        assertEquals(original, editor.document.text)
        assertEquals(MessageLensMode.LENS, controller.currentMode())

        myFixture.performEditorAction(IdeActions.ACTION_REDO)
        commit(editor)
        controller.refreshNow()
        assertTrue(editor.document.text.contains("customerId, accountId"))
        assertEquals(MessageLensMode.LENS, controller.currentMode())

        val validText = editor.document.text
        val closingQuote = editor.document.text.indexOf("\", customerId")
        write(project, "break fixture syntax") {
            editor.document.deleteString(closingQuote, closingQuote + 1)
        }
        commit(editor)
        controller.refreshNow()
        assertEquals(MessageLensMode.UNAVAILABLE, controller.currentMode())
        assertEquals(0, controller.ownedFoldCount())

        write(project, "repair fixture syntax") {
            editor.document.setText(validText)
        }
        commit(editor)
        controller.refreshNow()
        assertEquals(MessageLensMode.LENS, controller.currentMode())
        assertEquals(3, controller.ownedFoldCount())
    }

    fun testSelectionUsesRawAndDisposalReleasesOwnedPresentation() {
        val editor = configureFixture()
        editor.caretModel.moveToOffset(0)
        val controller = attach(editor)
        controller.refreshNow()
        val callRange = requireNotNull(controller.targetForTest()).callRange

        editor.caretModel.primaryCaret.setSelection(callRange.startOffset - 2, callRange.endOffset + 1)
        controller.refreshNow()
        assertEquals(MessageLensMode.RAW, controller.currentMode())
        assertEquals(
            editor.document.getText(com.intellij.openapi.util.TextRange(callRange.startOffset - 2, callRange.endOffset + 1)),
            editor.selectionModel.selectedText,
        )

        editor.caretModel.primaryCaret.removeSelection()
        editor.caretModel.moveToOffset(0)
        controller.refreshNow()
        assertEquals(MessageLensMode.LENS, controller.currentMode())

        Disposer.dispose(controller)
        assertTrue(controller.isDisposed())
        assertEquals(MessageLensMode.DISPOSED, controller.currentMode())
        assertEquals(0, controller.ownedFoldCount())
        assertEquals(0, controller.ownedInlayCount())
        assertEquals(InteractionFixture.SOURCE, editor.document.text)
    }

    fun testCopyPasteFromLensDoesNotLeaveClonedFoldRegions() {
        val editor = configureFixture()
        var ordinaryFold: com.intellij.openapi.editor.FoldRegion? = null
        editor.foldingModel.runBatchFoldingOperation {
            ordinaryFold = editor.foldingModel.addFoldRegion(0, "class".length, "user-fold")
        }
        assertNotNull(ordinaryFold)
        val controller = attach(editor)
        editor.caretModel.moveToOffset(0)
        controller.refreshNow()
        val callRange = requireNotNull(controller.targetForTest()).callRange
        val lineEnd = editor.document.getLineEndOffset(editor.document.getLineNumber(callRange.endOffset))
        editor.caretModel.moveToOffset(lineEnd)
        controller.refreshNow()
        assertEquals(MessageLensMode.LENS, controller.currentMode())

        val lineStart = editor.document.getLineStartOffset(editor.document.getLineNumber(callRange.startOffset))
        editor.selectionModel.setSelection(lineStart, lineEnd)
        myFixture.performEditorAction(IdeActions.ACTION_COPY)
        editor.selectionModel.removeSelection()
        editor.caretModel.moveToOffset(lineEnd)
        myFixture.performEditorAction(IdeActions.ACTION_EDITOR_ENTER)
        myFixture.performEditorAction(IdeActions.ACTION_PASTE)
        commit(editor)
        controller.refreshNow()

        assertEquals(2, Regex("log\\.info").findAll(editor.document.text).count())
        assertEquals(2, controller.targetsForTest().size)
        assertEquals(MessageLensMode.LENS, controller.currentMode())
        val remainingFolds = editor.foldingModel.allFoldRegions.filter { it.isValid }
        assertEquals(
            "Copy/Paste left unowned folds: ${remainingFolds.joinToString { "${it.startOffset}..${it.endOffset}:${it.placeholderText}" }}",
            controller.ownedFoldCount(),
            remainingFolds.count { fold -> fold.placeholderText.isEmpty() },
        )
        assertTrue(requireNotNull(ordinaryFold).isValid)
        assertTrue(editor.foldingModel.allFoldRegions.any { fold -> fold === ordinaryFold })
    }

    fun testMultipleCallsRenderIndependentlyAndUnsupportedCallDoesNotBlockOthers() {
        val firstCall = "log.info(\"first {} {}\", orderId, accountId);"
        val secondCall = "log.info(\"second {} {}\", orderId, accountId);"
        val source = InteractionFixture.SOURCE.replace(
            "log.info(\"order {} {}\", orderId, accountId);",
            "$firstCall\n        $secondCall",
        )
        val editor = configureText(source)
        val controller = attach(editor)
        editor.caretModel.moveToOffset(0)
        controller.refreshNow()

        assertEquals(2, controller.targetsForTest().size)
        assertEquals(2, controller.installedTargetsForTest().size)
        assertEquals(6, controller.ownedFoldCount())
        val firstRange = controller.targetsForTest().first().callRange
        editor.caretModel.moveToOffset(firstRange.startOffset + 4)
        controller.refreshNow()
        assertEquals(2, controller.targetsForTest().size)
        assertEquals(1, controller.installedTargetsForTest().size)
        assertEquals(3, controller.ownedFoldCount())

        val firstOffset = editor.document.text.indexOf(firstCall)
        write(project, "make first call unsupported") {
            editor.document.replaceString(
                firstOffset,
                firstOffset + firstCall.length,
                "log.info(prefix + \" {}\", orderId, accountId);",
            )
        }
        commit(editor)
        editor.caretModel.moveToOffset(0)
        controller.refreshNow()
        assertEquals(1, controller.targetsForTest().size)
        assertEquals("log.info(\"second {} {}\", orderId, accountId)", text(editor, controller.targetsForTest().single().callRange))
        assertEquals(3, controller.ownedFoldCount())
    }

    fun testThreeCallsAndMultipleCaretsKeepOnlyIntersectedCallsRaw() {
        val calls = (1..3).joinToString("\n        ") { index ->
            "log.info(\"call$index {} {}\", orderId, accountId);"
        }
        val source = InteractionFixture.SOURCE.replace(
            "log.info(\"order {} {}\", orderId, accountId);",
            calls,
        )
        val editor = configureText(source)
        val controller = attach(editor)
        editor.caretModel.moveToOffset(0)
        controller.refreshNow()
        val targets = controller.targetsForTest()
        assertEquals(3, targets.size)
        assertEquals(9, controller.ownedFoldCount())

        val firstCaret = editor.caretModel.addCaret(editor.offsetToVisualPosition(targets.first().callRange.startOffset + 4))
        val thirdCaret = editor.caretModel.addCaret(editor.offsetToVisualPosition(targets.last().callRange.startOffset + 4))
        assertNotNull(firstCaret)
        assertNotNull(thirdCaret)
        controller.refreshNow()
        assertEquals(listOf(targets[1].callRange), controller.installedTargetsForTest().map(MessageLensTarget::callRange))
        assertEquals(3, controller.ownedFoldCount())

        editor.caretModel.removeCaret(requireNotNull(firstCaret))
        editor.caretModel.removeCaret(requireNotNull(thirdCaret))
        controller.refreshNow()
        assertEquals(3, controller.installedTargetsForTest().size)
        assertEquals(9, controller.ownedFoldCount())
    }

    fun testLiteralConcatenationPreservesOperatorsAndOnlyReplacesPlaceholders() {
        val concatenatedCall = "log.info(\"orde \" + \"r   {} {}\", customerId, accountId);"
        val source = InteractionFixture.SOURCE.replace(
            "log.info(\"order {} {}\", orderId, accountId);",
            concatenatedCall,
        )
        val editor = configureText(source)
        val target = requireNotNull(analyze(editor))
        assertEquals(listOf("{}", "{}"), target.placeholderRanges.map { range -> text(editor, range) })
        assertEquals(listOf("customerId", "accountId"), target.expressionTexts)

        val controller = attach(editor)
        editor.caretModel.moveToOffset(0)
        controller.refreshNow()
        assertEquals(MessageLensMode.LENS, controller.currentMode())
        assertEquals(3, controller.ownedFoldCount())
        assertEquals(2, controller.ownedInlayCount())

        val mismatchSource = source.replace("\"orde \" + \"r   {} {}\"", "prefix + \" {}\"")
        write(project, "use mismatched runtime concatenation") { editor.document.setText(mismatchSource) }
        commit(editor)
        controller.refreshNow()
        assertEquals(MessageLensMode.UNAVAILABLE, controller.currentMode())
        assertEquals(0, controller.ownedFoldCount())
    }

    fun testThreeLiteralSegmentsCanPlacePlaceholdersInDifferentSegments() {
        val concatenatedCall = "log.info(\"order \" + \"{} \" + \"{}\", customerId, accountId);"
        val source = InteractionFixture.SOURCE.replace(
            "log.info(\"order {} {}\", orderId, accountId);",
            concatenatedCall,
        )
        val editor = configureText(source)
        val target = requireNotNull(analyze(editor))
        assertEquals(listOf("{}", "{}"), target.placeholderRanges.map { range -> text(editor, range) })

        val controller = attach(editor)
        editor.caretModel.moveToOffset(0)
        controller.refreshNow()
        assertEquals(3, controller.ownedFoldCount())
        assertEquals(2, controller.ownedInlayCount())
    }

    fun testRuntimeConcatenationKeepsExpressionsVisibleAndMapsAllPlaceholders() {
        val twoPlaceholderCall = "log.info(\"orde \" + customerId + \"r   {} {}\", customerId, accountId);"
        val twoPlaceholderSource = InteractionFixture.SOURCE.replace(
            "log.info(\"order {} {}\", orderId, accountId);",
            twoPlaceholderCall,
        )
        val editor = configureText(twoPlaceholderSource)
        var target = requireNotNull(analyze(editor))
        assertEquals(listOf("{}", "{}"), target.placeholderRanges.map { range -> text(editor, range) })
        assertEquals(listOf("customerId", "accountId"), target.expressionTexts)
        assertTrue(text(editor, target.callRange).contains("\" + customerId + \""))

        val threePlaceholderCall =
            "log.info(\"orde {} \" + customerId + \"r   {} {}\", customerId, customerId, accountId);"
        val threePlaceholderSource = twoPlaceholderSource.replace(twoPlaceholderCall, threePlaceholderCall)
        write(project, "use three runtime placeholders") { editor.document.setText(threePlaceholderSource) }
        commit(editor)
        target = requireNotNull(analyze(editor))
        assertEquals(3, target.placeholderRanges.size)
        assertEquals(listOf("customerId", "customerId", "accountId"), target.expressionTexts)

        val controller = attach(editor)
        editor.caretModel.moveToOffset(0)
        controller.refreshNow()
        assertEquals(MessageLensMode.LENS, controller.currentMode())
        assertEquals(4, controller.ownedFoldCount())
        assertEquals(3, controller.ownedInlayCount())
    }

    fun testMultilineTemplateConcatenationPreservesLayoutAndSwitchesAsOneCall() {
        val multilineCall = """log.info("orde {} "
                + customerId + "r   {} {}", customerId, customerId, accountId);"""
        val source = InteractionFixture.SOURCE.replace(
            "log.info(\"order {} {}\", orderId, accountId);",
            multilineCall,
        )
        val editor = configureText(source)
        val target = requireNotNull(analyze(editor))
        assertTrue(text(editor, target.callRange).contains("\n                + customerId"))
        assertEquals(3, target.placeholderRanges.size)
        assertEquals(listOf("customerId", "customerId", "accountId"), target.expressionTexts)

        val controller = attach(editor)
        editor.caretModel.moveToOffset(0)
        controller.refreshNow()
        assertEquals(MessageLensMode.LENS, controller.currentMode())
        assertEquals(4, controller.ownedFoldCount())
        assertEquals(3, controller.ownedInlayCount())

        val secondLineExpression = editor.document.text.indexOf("+ customerId", target.callRange.startOffset)
        editor.caretModel.moveToOffset(secondLineExpression + 2)
        controller.refreshNow()
        assertEquals(MessageLensMode.RAW, controller.currentMode())
        assertEquals(0, controller.ownedFoldCount())

        editor.caretModel.moveToOffset(0)
        controller.refreshNow()
        assertEquals(MessageLensMode.LENS, controller.currentMode())
        assertEquals(4, controller.ownedFoldCount())
    }

    fun testManualMultiTargetFixtureInstallsAllPresentations() {
        val editor = configureText(InteractionFixture.MULTI_TARGET_SOURCE)
        val controller = attach(editor)
        editor.caretModel.moveToOffset(0)
        controller.refreshNow()

        assertEquals(4, controller.targetsForTest().size)
        assertEquals(4, controller.installedTargetsForTest().size)
        assertEquals(13, controller.ownedFoldCount())
        assertEquals(9, controller.ownedInlayCount())
    }

    private fun configureFixture(): Editor {
        return configureText(InteractionFixture.SOURCE)
    }

    private fun configureText(source: String): Editor {
        myFixture.addFileToProject(
            "org/slf4j/Logger.java",
            """package org.slf4j;
                public interface Logger {
                    void trace(String template, Object... arguments);
                    void debug(String template, Object... arguments);
                    void info(String template, Object... arguments);
                    void warn(String template, Object... arguments);
                    void error(String template, Object... arguments);
                }
            """.trimIndent(),
        )
        myFixture.configureByText("DemoService.java", source)
        commit(myFixture.editor)
        return myFixture.editor
    }

    private fun analyze(editor: Editor): MessageLensTarget? = ReadAction.compute<MessageLensTarget?, RuntimeException> {
        val file = PsiDocumentManager.getInstance(project).getPsiFile(editor.document) ?: return@compute null
        MessageLensPsiAnalyzer.analyze(file, editor.document)
    }

    private fun analyzeAll(editor: Editor): List<MessageLensTarget> =
        ReadAction.compute<List<MessageLensTarget>, RuntimeException> {
            val file = PsiDocumentManager.getInstance(project).getPsiFile(editor.document) ?: return@compute emptyList()
            MessageLensPsiAnalyzer.analyzeAll(file, editor.document)
        }

    private fun attach(editor: Editor): MessageLensEditorController =
        MessageLensEditorController.attach(project, editor, settingsProvider = { LogLensApplicationState() })

    private fun commit(editor: Editor) {
        PsiDocumentManager.getInstance(project).commitDocument(editor.document)
    }

    private fun write(project: Project, name: String, action: () -> Unit) {
        WriteCommandAction.runWriteCommandAction(project, name, null, Runnable(action))
    }

    private fun text(editor: Editor, range: com.intellij.openapi.util.TextRange): String =
        editor.document.getText(range)
}
