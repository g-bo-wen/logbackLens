package cn.gbk.logbacklens.route

import cn.gbk.logbacklens.logback.LogLevel
import com.intellij.openapi.util.TextRange
import com.intellij.psi.PsiExpressionStatement
import com.intellij.psi.PsiFile
import com.intellij.psi.PsiMethodCallExpression
import com.intellij.psi.util.PsiTreeUtil

internal data class Slf4jRouteCall(
    val callRange: TextRange,
    val presentationOffset: Int,
    val eventLevel: LogLevel,
    val loggerIdentity: LoggerIdentity,
)

internal object Slf4jRouteCallAnalyzer {
    private val methods = mapOf(
        "trace" to LogLevel.TRACE,
        "debug" to LogLevel.DEBUG,
        "info" to LogLevel.INFO,
        "warn" to LogLevel.WARN,
        "error" to LogLevel.ERROR,
    )

    fun analyze(file: PsiFile): List<Slf4jRouteCall> {
        if (!file.isValid) return emptyList()
        return PsiTreeUtil.findChildrenOfType(file, PsiMethodCallExpression::class.java)
            .asSequence()
            .mapNotNull(::analyzeCall)
            .sortedBy(Slf4jRouteCall::presentationOffset)
            .toList()
    }

    private fun analyzeCall(call: PsiMethodCallExpression): Slf4jRouteCall? {
        val methodName = call.methodExpression.referenceName ?: return null
        val level = methods[methodName] ?: return null
        if (!isSlf4jCall(call)) return null
        val statement = call.parent as? PsiExpressionStatement
        return Slf4jRouteCall(
            callRange = call.textRange,
            presentationOffset = statement?.textRange?.endOffset ?: call.textRange.endOffset,
            eventLevel = level,
            loggerIdentity = Slf4jLoggerIdentityResolver.resolve(call),
        )
    }

    private fun isSlf4jCall(call: PsiMethodCallExpression): Boolean {
        if (call.resolveMethod()?.containingClass?.qualifiedName == SLF4J_LOGGER) return true
        if (call.methodExpression.qualifierExpression?.text != "log") return false
        val containingClass = PsiTreeUtil.getParentOfType(call, com.intellij.psi.PsiClass::class.java) ?: return false
        return containingClass.modifierList?.annotations.orEmpty().any { annotation ->
            annotation.qualifiedName == LOMBOK_SLF4J || annotation.nameReferenceElement?.referenceName == "Slf4j"
        }
    }

    private const val SLF4J_LOGGER = "org.slf4j.Logger"
    private const val LOMBOK_SLF4J = "lombok.extern.slf4j.Slf4j"
}
