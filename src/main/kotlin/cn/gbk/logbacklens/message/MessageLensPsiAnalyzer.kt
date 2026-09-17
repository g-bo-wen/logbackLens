package cn.gbk.logbacklens.message

import com.intellij.openapi.editor.Document
import com.intellij.openapi.util.TextRange
import com.intellij.psi.JavaTokenType
import com.intellij.psi.CommonClassNames
import com.intellij.psi.PsiExpression
import com.intellij.psi.PsiFile
import com.intellij.psi.PsiLiteralExpression
import com.intellij.psi.PsiMethodCallExpression
import com.intellij.psi.PsiParenthesizedExpression
import com.intellij.psi.PsiPolyadicExpression
import com.intellij.psi.util.PsiTreeUtil
import com.intellij.psi.util.InheritanceUtil

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
    private val classicMethods = setOf("trace", "debug", "info", "warn", "error")

    fun analyze(file: PsiFile, document: Document): MessageLensTarget? =
        analyzeAll(file, document).singleOrNull()

    fun analyzeAll(file: PsiFile, document: Document): List<MessageLensTarget> {
        if (!file.isValid) return emptyList()

        return PsiTreeUtil.findChildrenOfType(file, PsiMethodCallExpression::class.java)
            .asSequence()
            .filter(::isSupportedSlf4jCall)
            .mapNotNull { call -> analyzeCall(call, document) }
            .sortedBy { target -> target.callRange.startOffset }
            .toList()
    }

    private fun analyzeCall(call: PsiMethodCallExpression, document: Document): MessageLensTarget? {
        val expressions = call.argumentList.expressions
        if (expressions.size < 2 || expressions.last().isThrowable()) return null

        val templateExpression = expressions[0]
        val literals = collectStringLiterals(templateExpression) ?: return null
        val placeholderRanges = literals.flatMap { literal -> placeholderRanges(literal) ?: return null }
        if (placeholderRanges.isEmpty() || placeholderRanges.size != expressions.size - 1) return null

        val firstArgumentStart = expressions[1].textRange.startOffset
        val separatorText = document.getText(TextRange(templateExpression.textRange.endOffset, firstArgumentStart))
        val commaOffset = separatorText.indexOf(',')
        if (commaOffset < 0) return null
        val tailStart = templateExpression.textRange.endOffset + commaOffset
        val tailEnd = call.argumentList.textRange.endOffset - 1
        if (tailStart >= tailEnd) return null

        return MessageLensTarget(
            callRange = call.textRange,
            placeholderRanges = placeholderRanges,
            tailRange = TextRange(tailStart, tailEnd),
            expressionTexts = expressions.drop(1).map(PsiExpression::getText),
        ).takeIf { target -> target.isWithin(document.textLength) }
    }

    private fun collectStringLiterals(expression: PsiExpression): List<PsiLiteralExpression>? = when (expression) {
        is PsiLiteralExpression -> listOf(expression).takeIf { expression.value is String }
        is PsiParenthesizedExpression -> expression.expression?.let(::collectStringLiterals)
        is PsiPolyadicExpression -> {
            if (expression.operationTokenType != JavaTokenType.PLUS) return null
            expression.operands.flatMap(::stringLiteralsWithin).takeIf(List<PsiLiteralExpression>::isNotEmpty)
        }
        else -> null
    }

    private fun stringLiteralsWithin(expression: PsiExpression): List<PsiLiteralExpression> = when (expression) {
        is PsiLiteralExpression -> listOf(expression).takeIf { expression.value is String }.orEmpty()
        is PsiParenthesizedExpression -> expression.expression?.let(::stringLiteralsWithin).orEmpty()
        is PsiPolyadicExpression -> if (expression.operationTokenType == JavaTokenType.PLUS) {
            expression.operands.flatMap(::stringLiteralsWithin)
        } else {
            emptyList()
        }
        else -> emptyList()
    }

    private fun placeholderRanges(literal: PsiLiteralExpression): List<TextRange>? {
        val ranges = mutableListOf<TextRange>()
        var localOffset = literal.text.indexOf(PLACEHOLDER)
        while (localOffset >= 0) {
            if (localOffset > 0 && literal.text[localOffset - 1] == '\\') return null
            ranges += TextRange(
                literal.textRange.startOffset + localOffset,
                literal.textRange.startOffset + localOffset + PLACEHOLDER.length,
            )
            localOffset = literal.text.indexOf(PLACEHOLDER, localOffset + PLACEHOLDER.length)
        }
        return ranges
    }

    private fun isSupportedSlf4jCall(call: PsiMethodCallExpression): Boolean {
        val methodExpression = call.methodExpression
        if (methodExpression.referenceName !in classicMethods || methodExpression.qualifierExpression == null) return false
        return call.resolveMethod()?.containingClass?.qualifiedName == SLF4J_LOGGER
    }

    private fun PsiExpression.isThrowable(): Boolean {
        val expressionType = type ?: return false
        if (InheritanceUtil.isInheritor(expressionType, CommonClassNames.JAVA_LANG_THROWABLE)) return true

        // In incomplete PSI the JDK class may not resolve. Remaining Raw for conventional
        // Throwable names is safer than rendering a possible SLF4J trailing exception.
        val simpleName = expressionType.canonicalText.substringAfterLast('.')
        return simpleName.endsWith("Throwable") || simpleName.endsWith("Exception") || simpleName.endsWith("Error")
    }

    private fun MessageLensTarget.isWithin(documentLength: Int): Boolean =
        (hiddenRanges + callRange).all { range ->
            range.startOffset >= 0 && range.endOffset <= documentLength && range.startOffset < range.endOffset
        }

    private const val PLACEHOLDER = "{}"
    private const val SLF4J_LOGGER = "org.slf4j.Logger"
}
