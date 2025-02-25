// Copyright 2000-2021 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license that can be found in the LICENSE file.
package org.jetbrains.plugins.groovy.annotator

import com.intellij.codeInspection.ProblemHighlightType
import com.intellij.lang.annotation.AnnotationHolder
import com.intellij.lang.annotation.HighlightSeverity
import com.intellij.psi.PsiElement
import org.jetbrains.annotations.Nls
import org.jetbrains.plugins.groovy.GroovyBundle
import org.jetbrains.plugins.groovy.codeInspection.bugs.GrRemoveModifierFix
import org.jetbrains.plugins.groovy.lang.psi.GroovyElementVisitor
import org.jetbrains.plugins.groovy.lang.psi.api.auxiliary.modifiers.GrModifier
import org.jetbrains.plugins.groovy.lang.psi.api.auxiliary.modifiers.GrModifierList
import org.jetbrains.plugins.groovy.lang.psi.api.statements.branch.GrYieldStatement
import org.jetbrains.plugins.groovy.lang.psi.api.statements.clauses.GrCaseSection
import org.jetbrains.plugins.groovy.lang.psi.api.statements.expressions.GrSwitchExpression
import org.jetbrains.plugins.groovy.lang.psi.api.statements.typedef.GrPermitsClause

class GroovyAnnotatorPre40(private val holder: AnnotationHolder) : GroovyElementVisitor() {
  companion object {
    fun AnnotationHolder.registerModifierProblem(modifier : PsiElement, inspectionMessage : @Nls String, fixMessage : @Nls String) {
      val builder = newAnnotation(HighlightSeverity.ERROR,
                                  inspectionMessage).range(modifier)
      registerLocalFix(builder, GrRemoveModifierFix(modifier.text, fixMessage), modifier, inspectionMessage, ProblemHighlightType.ERROR,
                       modifier.textRange)
      builder.create()
    }
  }

  override fun visitModifierList(modifierList: GrModifierList) {
    val sealed = modifierList.getModifier(GrModifier.SEALED)
    if (sealed != null) {
      holder.registerModifierProblem(sealed, GroovyBundle.message("inspection.message.modifier.sealed.available.with.groovy.or.later"), GroovyBundle.message("illegal.sealed.modifier.fix"))
    }
    val nonSealed = modifierList.getModifier(GrModifier.NON_SEALED)
    if (nonSealed != null) {
      holder.registerModifierProblem(nonSealed, GroovyBundle.message("inspection.message.modifier.nonsealed.available.with.groovy.or.later"), GroovyBundle.message("illegal.nonsealed.modifier.fix"))
    }
  }

  override fun visitPermitsClause(permitsClause: GrPermitsClause) {
    permitsClause.keyword?.let {holder.newAnnotation(HighlightSeverity.ERROR,
                                                     GroovyBundle.message("inspection.message.permits.available.with.groovy.4.or.later")).range(it).create() }
  }

  override fun visitSwitchExpression(switchExpression: GrSwitchExpression) {
    switchExpression.firstChild?.let { holder.newAnnotation(HighlightSeverity.ERROR,
      GroovyBundle.message("inspection.message.switch.expressions.are.available.with.groovy.4.or.later")).range(it).create() }
    super.visitSwitchExpression(switchExpression)
  }

  override fun visitCaseSection(caseSection: GrCaseSection) : Unit = with(caseSection) {
    arrow?.let { holder.newAnnotation(HighlightSeverity.ERROR,
      GroovyBundle.message("inspection.message.arrows.in.case.expressions.are.available.with.groovy.4.or.later")).range(it).create() }
    expressions?.takeIf { it.size > 1 }?.let { holder.newAnnotation(HighlightSeverity.ERROR,
      GroovyBundle.message("inspection.message.multiple.expressions.in.case.section.are.available.with.groovy.4.or.later")).range(it.first()?.parent ?: this).create() }
    super.visitCaseSection(caseSection)
  }

  override fun visitYieldStatement(yieldStatement: GrYieldStatement) {
    yieldStatement.yieldKeyword.let { holder.newAnnotation(HighlightSeverity.ERROR,
      GroovyBundle.message("inspection.message.keyword.yield.available.with.groovy.4.or.later")).range(it).create() }
    super.visitYieldStatement(yieldStatement)
  }
}
