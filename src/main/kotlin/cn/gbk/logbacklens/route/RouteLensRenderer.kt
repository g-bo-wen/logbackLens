package cn.gbk.logbacklens.route

import cn.gbk.logbacklens.logback.LogLevel
import com.intellij.openapi.editor.EditorCustomElementRenderer
import com.intellij.openapi.editor.Inlay
import com.intellij.openapi.editor.colors.EditorFontType
import com.intellij.openapi.editor.markup.TextAttributes
import java.awt.Color
import java.awt.Graphics2D
import java.awt.geom.Rectangle2D

internal data class RouteSegment(
    val text: String,
    val tooltip: String,
    val destination: RouteDestination? = null,
)

internal class RouteLensRenderer(
    internal val segments: List<RouteSegment>,
    colorValue: String,
) : EditorCustomElementRenderer {
    private val color = runCatching { Color.decode(colorValue) }.getOrDefault(Color.GRAY)

    internal val text: String get() = segments.joinToString("") { segment -> segment.text }

    override fun calcWidthInPixels(inlay: Inlay<*>): Int = fontMetrics(inlay).stringWidth(text)

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

    fun tooltipAt(inlay: Inlay<*>, mouseX: Int): String? = segmentAt(inlay, mouseX)?.tooltip

    fun destinationAt(inlay: Inlay<*>, mouseX: Int): RouteDestination? =
        segmentAt(inlay, mouseX)?.destination

    private fun segmentAt(inlay: Inlay<*>, mouseX: Int): RouteSegment? {
        val bounds = inlay.bounds ?: return null
        val relativeX = mouseX - bounds.x
        if (relativeX < 0) return null
        val metrics = fontMetrics(inlay)
        var start = 0
        segments.forEach { segment ->
            val end = start + metrics.stringWidth(segment.text)
            if (relativeX in start until end) return segment
            start = end
        }
        return null
    }

    private fun fontMetrics(inlay: Inlay<*>) = inlay.editor.contentComponent.getFontMetrics(
        inlay.editor.colorsScheme.getFont(EditorFontType.PLAIN),
    )
}

internal object RouteLensPresentationFactory {
    fun create(
        loggerIdentity: LoggerIdentity,
        eventLevel: LogLevel,
        outcome: RouteOutcome,
        color: String,
    ): RouteLensRenderer? {
        val logger = (loggerIdentity as? LoggerIdentity.Known)?.name ?: "unknown logger"
        val segments = when (outcome) {
            is RouteOutcome.Disabled -> listOf(
                RouteSegment(
                    " ⊘ disabled",
                    "${eventLevel.name} is below effective ${outcome.effectiveLevel.name} for $logger.",
                ),
            )
            is RouteOutcome.Destinations -> destinationSegments(logger, outcome.values)
            RouteOutcome.KnownNoFile -> return null
            is RouteOutcome.Unknown -> listOf(
                RouteSegment(
                    " → ?",
                    buildString {
                        append("Route is uncertain for ").append(logger).append(": ")
                        append(outcome.reasons.joinToString(" "))
                        if (outcome.partialDestinations.isNotEmpty()) {
                            append(" Known partial destinations: ")
                            append(outcome.partialDestinations.joinToString { it.value })
                        }
                    },
                ),
            )
        }
        return RouteLensRenderer(segments, color)
    }

    private fun destinationSegments(logger: String, destinations: List<RouteDestination>): List<RouteSegment> {
        val visible = destinations.take(MAX_VISIBLE_DESTINATIONS)
        return buildList {
            add(RouteSegment(" → ", "Static Logback file routes for $logger."))
            visible.forEachIndexed { index, destination ->
                if (index > 0) add(RouteSegment(", ", "Another static file route for $logger."))
                val kind = if (destination.kind == DestinationKind.ROLLING_PATTERN) "rolling pattern" else "active file"
                add(
                    RouteSegment(
                        destination.value,
                        "$logger → ${destination.appenderChain.joinToString(" → ")} → $kind ${destination.value}",
                        destination,
                    ),
                )
            }
            val remaining = destinations.size - visible.size
            if (remaining > 0) {
                add(RouteSegment(" +$remaining", "$remaining more static file destination(s)."))
            }
        }
    }

    private const val MAX_VISIBLE_DESTINATIONS = 2
}
