package cn.gbk

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class RenderingPrimitiveOffsetsTest {
    @Test
    fun `fixed offsets select exactly the two placeholders and argument tail`() {
        val fixture = RenderingPrimitiveOffsets.FIXTURE

        assertEquals("{}", fixture.substring(RenderingPrimitiveOffsets.foldRanges[0]))
        assertEquals("{}", fixture.substring(RenderingPrimitiveOffsets.foldRanges[1]))
        assertEquals(", orderId, accountId", fixture.substring(RenderingPrimitiveOffsets.foldRanges[2]))
    }

    @Test
    fun `end offsets are outside their collapsed half-open ranges`() {
        assertTrue(RenderingPrimitiveOffsets.firstPlaceholderEnd !in RenderingPrimitiveOffsets.foldRanges[0])
        assertTrue(RenderingPrimitiveOffsets.secondPlaceholderEnd !in RenderingPrimitiveOffsets.foldRanges[1])
    }

    private fun String.substring(range: IntRange): String = substring(range.first, range.last + 1)
}
