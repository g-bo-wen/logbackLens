package cn.gbk.logbacklens.spike

import com.intellij.openapi.editor.Document
import com.intellij.openapi.util.TextRange
import com.intellij.psi.JavaTokenType
import com.intellij.psi.PsiExpression
import com.intellij.psi.PsiLiteralExpression
import com.intellij.psi.PsiMethodCallExpression
import com.intellij.psi.PsiFile
import com.intellij.psi.PsiParenthesizedExpression
import com.intellij.psi.PsiPolyadicExpression
import com.intellij.psi.util.PsiTreeUtil

internal data class MessageLensTarget(
    val callRange: TextRange,
    val placeholderRanges: List<TextRange>,
    val tailRange: TextRange,
    val expressionTexts: List<String>,
) {
    val hiddenRanges: List<TextRange>
        get() = (placeholderRanges + tailRange).sortedBy(TextRange::getStartOffset)
}

internal object MessageLensPsiAnalyzer {
    fun analyze(file: PsiFile, document: Document): MessageLensTarget? {
        return analyzeAll(file, document).singleOrNull()
    }

    fun analyzeAll(file: PsiFile, document: Document): List<MessageLensTarget> {
        if (!file.isValid) return emptyList()

        return PsiTreeUtil.findChildrenOfType(file, PsiMethodCallExpression::class.java)
            .asSequence()
            .filter(::isTargetCall)
            .mapNotNull { call -> analyzeCall(call, document) }
            .sortedBy { target -> target.callRange.startOffset }
            .toList()
    }

    private fun analyzeCall(call: PsiMethodCallExpression, document: Document): MessageLensTarget? {
        val expressions = call.argumentList.expressions
        if (expressions.size < 2) return null

        val templateExpression = expressions[0]
        val literals = collectStringLiterals(templateExpression) ?: return null
        val placeholderRanges = literals.flatMap(::placeholderRanges)
        if (placeholderRanges.isEmpty() || placeholderRanges.size != expressions.size - 1) return null

        val firstArgumentStart = expressions[1].textRange.startOffset
        val separatorText = document.getText(TextRange(templateExpression.textRange.endOffset, firstArgumentStart))
        val commaOffset = separatorText.indexOf(',')
        if (commaOffset < 0) return null
        val tailStart = templateExpression.textRange.endOffset + commaOffset
        val tailEnd = call.argumentList.textRange.endOffset - 1
        if (tailStart >= tailEnd) return null

        val target = MessageLensTarget(
            callRange = call.textRange,
            placeholderRanges = placeholderRanges,
            tailRange = TextRange(tailStart, tailEnd),
            expressionTexts = expressions.drop(1).map { it.text },
        )
        return target.takeIf { it.isWithin(document.textLength) }
    }

    private fun collectStringLiterals(expression: PsiExpression): List<PsiLiteralExpression>? {
        return when (expression) {
            is PsiLiteralExpression -> listOf(expression).takeIf { expression.value is String }
            is PsiParenthesizedExpression -> expression.expression?.let(::collectStringLiterals)
            is PsiPolyadicExpression -> {
                if (expression.operationTokenType != JavaTokenType.PLUS) return null
                expression.operands.flatMap(::stringLiteralsWithin).takeIf { literals -> literals.isNotEmpty() }
            }
            else -> null
        }
    }

    private fun stringLiteralsWithin(expression: PsiExpression): List<PsiLiteralExpression> = when (expression) {
        is PsiLiteralExpression -> listOf(expression).takeIf { expression.value is String }.orEmpty()
        is PsiParenthesizedExpression -> expression.expression?.let(::stringLiteralsWithin).orEmpty()
        is PsiPolyadicExpression -> {
            if (expression.operationTokenType == JavaTokenType.PLUS) {
                expression.operands.flatMap(::stringLiteralsWithin)
            } else {
                emptyList()
            }
        }
        else -> emptyList()
    }

    private fun placeholderRanges(literal: PsiLiteralExpression): List<TextRange> {
        val ranges = mutableListOf<TextRange>()
        var localOffset = literal.text.indexOf(PLACEHOLDER)
        while (localOffset >= 0) {
            ranges += TextRange(
                literal.textRange.startOffset + localOffset,
                literal.textRange.startOffset + localOffset + PLACEHOLDER.length,
            )
            localOffset = literal.text.indexOf(PLACEHOLDER, localOffset + PLACEHOLDER.length)
        }
        return ranges
    }

    private fun isTargetCall(call: PsiMethodCallExpression): Boolean {
        val methodExpression = call.methodExpression
        return methodExpression.referenceName == "info" && methodExpression.qualifierExpression?.text == "log"
    }

    private fun MessageLensTarget.isWithin(documentLength: Int): Boolean {
        val ranges = hiddenRanges + callRange
        return ranges.all { range ->
            range.startOffset >= 0 && range.endOffset <= documentLength && range.startOffset < range.endOffset
        }
    }

    private const val PLACEHOLDER = "{}"
}
