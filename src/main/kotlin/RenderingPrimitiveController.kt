package cn.gbk

import com.intellij.openapi.editor.Editor
import com.intellij.openapi.editor.EditorCustomElementRenderer
import com.intellij.openapi.editor.FoldRegion
import com.intellij.openapi.editor.Inlay
import com.intellij.openapi.editor.colors.EditorFontType
import com.intellij.openapi.editor.markup.TextAttributes
import java.awt.Color
import java.awt.Graphics2D
import java.awt.geom.Rectangle2D

internal enum class RenderingGate {
    FOLDS_ONLY,
    FIRST_INLAY,
    BOTH_INLAYS,
}

internal data class RenderingResult(val success: Boolean, val message: String)

internal class RenderingPrimitiveController {
    private val folds = mutableMapOf<Editor, List<FoldRegion>>()
    private val inlays = mutableMapOf<Editor, List<Inlay<*>>>()

    fun apply(editor: Editor, gate: RenderingGate): RenderingResult {
        clear(editor)
        if (editor.document.text != RenderingPrimitiveOffsets.FIXTURE) {
            return RenderingResult(false, "STOP: fixture Document does not match the fixed tracer.")
        }

        val createdFolds = mutableListOf<FoldRegion>()
        editor.foldingModel.runBatchFoldingOperation {
            RenderingPrimitiveOffsets.foldRanges.forEach { range ->
                editor.foldingModel.addFoldRegion(range.first, range.last + 1, "")?.also { fold ->
                    fold.isExpanded = false
                    createdFolds += fold
                }
            }
        }
        folds[editor] = createdFolds

        if (createdFolds.size != RenderingPrimitiveOffsets.foldRanges.size) {
            clear(editor)
            return RenderingResult(false, "Gate 1 FAIL: empty FoldRegion creation was rejected by the editor.")
        }

        val createdInlays = mutableListOf<Inlay<*>>()
        if (gate >= RenderingGate.FIRST_INLAY) {
            editor.inlayModel.addInlineElement(
                RenderingPrimitiveOffsets.firstPlaceholderEnd,
                true,
                BlueExpressionRenderer("‹orderId›"),
            )?.also(createdInlays::add)
        }
        if (gate >= RenderingGate.BOTH_INLAYS) {
            editor.inlayModel.addInlineElement(
                RenderingPrimitiveOffsets.secondPlaceholderEnd,
                true,
                BlueExpressionRenderer("‹accountId›"),
            )?.also(createdInlays::add)
        }
        inlays[editor] = createdInlays

        val expectedInlayCount = when (gate) {
            RenderingGate.FOLDS_ONLY -> 0
            RenderingGate.FIRST_INLAY -> 1
            RenderingGate.BOTH_INLAYS -> 2
        }
        if (createdInlays.size != expectedInlayCount) {
            clear(editor)
            return RenderingResult(false, "${gate.label} FAIL: the editor rejected an endOffset inline inlay.")
        }

        return RenderingResult(
            true,
            "${gate.label} installed with public FoldingModel/InlayModel APIs; inspect the editor visually.",
        )
    }

    fun clear(editor: Editor) {
        inlays.remove(editor).orEmpty().forEach { inlay ->
            if (inlay.isValid) inlay.dispose()
        }
        val ownedFolds = folds.remove(editor).orEmpty()
        if (ownedFolds.isNotEmpty() && !editor.isDisposed) {
            editor.foldingModel.runBatchFoldingOperation {
                ownedFolds.forEach { fold ->
                    if (fold.isValid) editor.foldingModel.removeFoldRegion(fold)
                }
            }
        }
    }

    private val RenderingGate.label: String
        get() = when (this) {
            RenderingGate.FOLDS_ONLY -> "Gate 1"
            RenderingGate.FIRST_INLAY -> "Gate 2"
            RenderingGate.BOTH_INLAYS -> "Gate 3"
        }
}

internal class BlueExpressionRenderer(private val text: String) : EditorCustomElementRenderer {
    override fun calcWidthInPixels(inlay: Inlay<*>): Int {
        val font = inlay.editor.colorsScheme.getFont(EditorFontType.PLAIN)
        return inlay.editor.contentComponent.getFontMetrics(font).stringWidth(text)
    }

    override fun paint(
        inlay: Inlay<*>,
        g: Graphics2D,
        targetRegion: Rectangle2D,
        textAttributes: TextAttributes,
    ) {
        g.font = inlay.editor.colorsScheme.getFont(EditorFontType.PLAIN)
        g.color = TEST_BLUE
        g.drawString(text, targetRegion.x.toFloat(), (targetRegion.y + inlay.editor.ascent).toFloat())
    }

    private companion object {
        val TEST_BLUE = Color(0x2F, 0x7D, 0xFF)
    }
}
