// Copyright 2000-2021 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license that can be found in the LICENSE file.
package com.intellij.codeInsight.documentation.actions

import com.intellij.codeInsight.documentation.DocumentationManager
import com.intellij.codeInsight.hint.HintManagerImpl.ActionToIgnore
import com.intellij.codeInsight.lookup.LookupManager
import com.intellij.featureStatistics.FeatureUsageTracker
import com.intellij.lang.documentation.ide.actions.DOCUMENTATION_TARGETS_KEY
import com.intellij.lang.documentation.ide.impl.DocumentationManager.Companion.instance
import com.intellij.openapi.actionSystem.*
import com.intellij.openapi.editor.EditorGutter
import com.intellij.openapi.project.DumbAware
import com.intellij.openapi.util.registry.Registry
import com.intellij.psi.PsiDocumentManager
import com.intellij.psi.util.PsiUtilBase

open class ShowQuickDocInfoAction : AnAction(),
                                    ActionToIgnore,
                                    DumbAware,
                                    PopupAction,
                                    UpdateInBackground,
                                    PerformWithDocumentsCommitted {

  init {
    isEnabledInModalContext = true
    @Suppress("LeakingThis")
    setInjectedContext(true)
  }

  override fun update(e: AnActionEvent) {
    if (Registry.`is`("documentation.v2")) {
      e.presentation.isEnabled = !e.dataContext.getData(DOCUMENTATION_TARGETS_KEY).isNullOrEmpty()
      return
    }
    val presentation = e.presentation
    val dataContext = e.dataContext
    presentation.isEnabled = false
    val project = CommonDataKeys.PROJECT.getData(dataContext) ?: return
    val editor = CommonDataKeys.EDITOR.getData(dataContext)
    val element = CommonDataKeys.PSI_ELEMENT.getData(dataContext)
    if (editor == null && element == null) return
    if (LookupManager.getInstance(project).activeLookup != null) {
      presentation.isEnabled = true
    }
    else {
      if (editor != null) {
        if (e.getData(EditorGutter.KEY) != null) return
        val file = PsiDocumentManager.getInstance(project).getPsiFile(editor.document)
        if (file == null && element == null) return
      }
      presentation.isEnabled = true
    }
  }

  override fun actionPerformed(e: AnActionEvent) {
    if (Registry.`is`("documentation.v2")) {
      actionPerformedV2(e.dataContext)
      return
    }
    val dataContext = e.dataContext
    val project = CommonDataKeys.PROJECT.getData(dataContext) ?: return
    val editor = CommonDataKeys.EDITOR.getData(dataContext)
    if (editor != null) {
      FeatureUsageTracker.getInstance().triggerFeatureUsed(CODEASSISTS_QUICKJAVADOC_FEATURE)
      val activeLookup = LookupManager.getActiveLookup(editor)
      if (activeLookup != null) {
        FeatureUsageTracker.getInstance().triggerFeatureUsed(CODEASSISTS_QUICKJAVADOC_LOOKUP_FEATURE)
      }
      val psiFile = PsiUtilBase.getPsiFileInEditor(editor, project) ?: return
      val documentationManager = DocumentationManager.getInstance(project)
      val hint = documentationManager.docInfoHint
      documentationManager.showJavaDocInfo(editor, psiFile, hint != null || activeLookup == null)
      return
    }
    val element = CommonDataKeys.PSI_ELEMENT.getData(dataContext)
    if (element != null) {
      FeatureUsageTracker.getInstance().triggerFeatureUsed(CODEASSISTS_QUICKJAVADOC_CTRLN_FEATURE)
      val documentationManager = DocumentationManager.getInstance(project)
      val hint = documentationManager.docInfoHint
      documentationManager.showJavaDocInfo(element, null, hint != null, null)
    }
  }

  private fun actionPerformedV2(dataContext: DataContext) {
    val project = dataContext.getData(CommonDataKeys.PROJECT) ?: return
    instance(project).actionPerformed(dataContext)
  }

  @Suppress("SpellCheckingInspection")
  companion object {
    const val CODEASSISTS_QUICKJAVADOC_FEATURE = "codeassists.quickjavadoc"
    const val CODEASSISTS_QUICKJAVADOC_LOOKUP_FEATURE = "codeassists.quickjavadoc.lookup"
    const val CODEASSISTS_QUICKJAVADOC_CTRLN_FEATURE = "codeassists.quickjavadoc.ctrln"
  }
}
