package cn.gbk.logbacklens.route

import com.intellij.psi.JavaPsiFacade
import com.intellij.psi.PsiClassObjectAccessExpression
import com.intellij.psi.PsiClass
import com.intellij.psi.PsiExpression
import com.intellij.psi.PsiField
import com.intellij.psi.PsiMethodCallExpression
import com.intellij.psi.PsiReferenceExpression
import com.intellij.psi.PsiVariable
import com.intellij.psi.util.PsiTreeUtil
import com.intellij.psi.util.PsiUtil

object Slf4jLoggerIdentityResolver {
    fun resolve(call: PsiMethodCallExpression): LoggerIdentity {
        val qualifier = call.methodExpression.qualifierExpression
            ?: return LoggerIdentity.Unknown("SLF4J call has no logger receiver.")

        resolveFactoryCall(qualifier)?.let { return it }
        val variable = (qualifier as? PsiReferenceExpression)?.resolve() as? PsiVariable
        resolveFactoryCall(variable?.initializer)?.let { return it }

        if (qualifier.text == "log") {
            val containingClass = PsiTreeUtil.getParentOfType(call, com.intellij.psi.PsiClass::class.java)
            val hasSlf4j = containingClass?.modifierList?.annotations.orEmpty().any { annotation ->
                annotation.qualifiedName == LOMBOK_SLF4J || annotation.nameReferenceElement?.referenceName == "Slf4j"
            }
            if (hasSlf4j) {
                val name = containingClass?.qualifiedName ?: containingClass?.name
                if (name != null) return LoggerIdentity.Known(name)
            }
        }

        return LoggerIdentity.Unknown("Logger name cannot be derived from the receiver initializer.")
    }

    private fun resolveFactoryCall(expression: PsiExpression?): LoggerIdentity? {
        val factoryCall = expression as? PsiMethodCallExpression ?: return null
        if (factoryCall.methodExpression.referenceName != "getLogger") return null
        val resolvedClass = factoryCall.resolveMethod()?.containingClass?.qualifiedName
        val qualifier = factoryCall.methodExpression.qualifierExpression as? PsiReferenceExpression
        val qualifierClass = (qualifier?.resolve() as? PsiClass)?.qualifiedName
        if (resolvedClass != LOGGER_FACTORY && qualifierClass != LOGGER_FACTORY && qualifier?.text != LOGGER_FACTORY) return null
        val argument = factoryCall.argumentList.expressions.singleOrNull()
            ?: return LoggerIdentity.Unknown("LoggerFactory.getLogger must have one statically supported argument.")

        if (argument is PsiClassObjectAccessExpression) {
            val loggerClass = PsiUtil.resolveClassInClassTypeOnly(argument.operand.type)
            val name = loggerClass?.qualifiedName ?: argument.operand.type.canonicalText.takeIf(String::isNotBlank)
            return if (name != null) LoggerIdentity.Known(name) else LoggerIdentity.Unknown("Logger class literal is unresolved.")
        }

        val evaluator = JavaPsiFacade.getInstance(argument.project).constantEvaluationHelper
        val referencedField = (argument as? PsiReferenceExpression)?.resolve() as? PsiField
        val constant = evaluator.computeConstantExpression(argument) as? String
            ?: referencedField?.computeConstantValue() as? String
            ?: referencedField?.initializer?.let(evaluator::computeConstantExpression) as? String
        return if (constant != null) {
            LoggerIdentity.Known(constant)
        } else {
            LoggerIdentity.Unknown("LoggerFactory argument is not a class literal or constant string.")
        }
    }

    private const val LOGGER_FACTORY = "org.slf4j.LoggerFactory"
    private const val LOMBOK_SLF4J = "lombok.extern.slf4j.Slf4j"
}
