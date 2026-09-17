package cn.gbk

internal object RenderingPrimitiveOffsets {
    const val FIXTURE = "log.info(\"order {} {}\", orderId, accountId);"

    val firstPlaceholderStart = FIXTURE.indexOf("{}")
    val secondPlaceholderStart = FIXTURE.indexOf("{}", firstPlaceholderStart + 2)
    val argumentsTailStart = FIXTURE.indexOf(", orderId")
    val argumentsTailEnd = FIXTURE.lastIndexOf(')')

    val foldRanges = listOf(
        firstPlaceholderStart until firstPlaceholderStart + 2,
        secondPlaceholderStart until secondPlaceholderStart + 2,
        argumentsTailStart until argumentsTailEnd,
    )
    val firstPlaceholderEnd = firstPlaceholderStart + 2
    val secondPlaceholderEnd = secondPlaceholderStart + 2
}
