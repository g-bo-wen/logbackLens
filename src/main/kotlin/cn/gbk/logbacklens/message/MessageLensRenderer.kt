package cn.gbk.logbacklens.message

import com.intellij.openapi.editor.EditorCustomElementRenderer
import com.intellij.openapi.editor.Inlay
import com.intellij.openapi.editor.colors.EditorFontType
import com.intellij.openapi.editor.markup.TextAttributes
import java.awt.Color
import java.awt.Graphics2D
import java.awt.geom.Rectangle2D

internal class MessageLensRenderer(
    private val text: String,
    colorValue: String,
) : EditorCustomElementRenderer {
    private val color = runCatching { Color.decode(colorValue) }.getOrDefault(Color.GRAY)

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
        g.color = color
        g.drawString(text, targetRegion.x.toFloat(), (targetRegion.y + inlay.editor.ascent).toFloat())
    }
}
