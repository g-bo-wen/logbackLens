package cn.gbk

internal object RenderingPrimitiveOffsets {
    const val FIXTURE = "log.info(\"order {} {}\", orderId, accountId);"

    private const val PLACEHOLDER = "{}"
    private const val TAIL = ", orderId, accountId"

    val firstPlaceholderStart = FIXTURE.indexOf(PLACEHOLDER)
    val firstPlaceholderEnd = firstPlaceholderStart + PLACEHOLDER.length
    val secondPlaceholderStart = FIXTURE.indexOf(PLACEHOLDER, firstPlaceholderEnd)
    val secondPlaceholderEnd = secondPlaceholderStart + PLACEHOLDER.length
    val tailStart = FIXTURE.indexOf(TAIL)
    val tailEnd = tailStart + TAIL.length

    val foldRanges = listOf(
        firstPlaceholderStart until firstPlaceholderEnd,
        secondPlaceholderStart until secondPlaceholderEnd,
        tailStart until tailEnd,
    )
}
