// Copyright 2000-2019 JetBrains s.r.o. Use of this source code is governed by the Apache 2.0 license that can be found in the LICENSE file.
package com.intellij.codeInsight.intention.impl.preview

import com.intellij.codeInsight.daemon.impl.ShowIntentionsPass
import com.intellij.codeInsight.intention.IntentionAction
import com.intellij.codeInsight.intention.IntentionActionDelegate
import com.intellij.codeInsight.intention.impl.CachedIntentions
import com.intellij.codeInsight.intention.impl.IntentionActionWithTextCaching
import com.intellij.codeInsight.intention.impl.ShowIntentionActionsHandler
import com.intellij.codeInsight.intention.impl.config.IntentionManagerSettings
import com.intellij.diff.comparison.ComparisonManager
import com.intellij.diff.comparison.ComparisonPolicy
import com.intellij.diff.fragments.LineFragment
import com.intellij.lang.injection.InjectedLanguageManager
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.progress.DumbProgressIndicator
import com.intellij.openapi.progress.ProcessCanceledException
import com.intellij.openapi.progress.ProgressManager
import com.intellij.openapi.project.Project
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiFile
import com.intellij.psi.PsiFileFactory
import com.intellij.psi.PsiRecursiveElementWalkingVisitor
import com.intellij.psi.impl.source.tree.injected.InjectedLanguageUtil
import java.io.IOException
import java.util.concurrent.Callable

internal class IntentionPreviewComputable(private val project: Project,
                                          private val action: IntentionAction,
                                          private val originalFile: PsiFile,
                                          private val originalEditor: Editor) : Callable<IntentionPreviewContent> {
  override fun call(): IntentionPreviewContent {
    val diffContent = tryCreateDiffContent()
    if (diffContent != null) {
      return diffContent
    }
    val descriptionContent = tryCreateDescriptionContent()
    if (descriptionContent != null) {
      return descriptionContent
    }
    return IntentionPreviewEmptyResult
  }

  private fun tryCreateDescriptionContent(): IntentionPreviewHtmlResult? {
    val originalAction = IntentionActionDelegate.unwrap(action)
    val actionMetaData = IntentionManagerSettings.getInstance().metaData.singleOrNull {
      md -> IntentionActionDelegate.unwrap(md.action) === originalAction
    } ?: return null
    return try {
      IntentionPreviewHtmlResult(actionMetaData.description.text.replace(Regex("<!--.+-->"), ""))
    } catch(ex: IOException) {
      null
    }
  }

  private fun tryCreateDiffContent(): IntentionPreviewDiffResult? {
    try {
      return generatePreview()
    }
    catch (e: IntentionPreviewUnsupportedOperationException) {
      return null
    }
    catch (e: ProcessCanceledException) {
      throw e
    }
    catch (e: Exception) {
      LOG.debug("There are exceptions on invocation the intention: '${action.text}' on a copy of the file.", e)
      return null
    }
  }

  fun generatePreview(): IntentionPreviewDiffResult? {
    val origPair = ShowIntentionActionsHandler.chooseFileForAction(originalFile, originalEditor, action) ?: return null
    val origFile: PsiFile
    val caretOffset: Int
    if (origPair.first != originalFile) {
      val manager = InjectedLanguageManager.getInstance(project)
      origFile = PsiFileFactory.getInstance(project).createFileFromText(
        origPair.first.name, origPair.first.fileType, manager.getUnescapedText(origPair.first))
      caretOffset = mapInjectedOffsetToUnescaped(origPair.first, origPair.second.caretModel.offset) 
    } else {
      origFile = originalFile
      caretOffset = originalEditor.caretModel.offset
    }
    val psiFileCopy = origFile.copy() as PsiFile
    ProgressManager.checkCanceled()
    val editorCopy = IntentionPreviewEditor(psiFileCopy, caretOffset)

    val writable = originalEditor.document.isWritable
    try {
      originalEditor.document.setReadOnly(true)
      ProgressManager.checkCanceled()
      if (!action.invokeForPreview(project, editorCopy, psiFileCopy)) {
        if (!action.startInWriteAction() || action.getElementToMakeWritable(originalFile)?.containingFile !== originalFile) {
          return null
        }
        val action = findCopyIntention(project, editorCopy, psiFileCopy, action) ?: return null
        LOG.error("Intention preview fallback is used for action " + action::class.java + "|" + action.familyName)
        action.invoke(project, editorCopy, psiFileCopy)
      }
      ProgressManager.checkCanceled()
    }
    finally {
      originalEditor.document.setReadOnly(!writable)
    }

    return IntentionPreviewDiffResult(
      psiFileCopy,
      origFile,
      ComparisonManager.getInstance().compareLines(origFile.text, editorCopy.document.text, ComparisonPolicy.TRIM_WHITESPACES,
                                                   DumbProgressIndicator.INSTANCE)
    )
  }

  private fun mapInjectedOffsetToUnescaped(injectedFile: PsiFile, injectedOffset: Int): Int {
    var unescapedOffset = 0
    var escapedOffset = 0
    injectedFile.accept(object : PsiRecursiveElementWalkingVisitor() {
      override fun visitElement(element: PsiElement) {
        val leafText = InjectedLanguageUtil.getUnescapedLeafText(element, false)
        if (leafText != null) {
          unescapedOffset += leafText.length
          escapedOffset += element.textLength
          if (escapedOffset >= injectedOffset) {
            unescapedOffset -= escapedOffset - injectedOffset
            stopWalking()
          }
        }
        super.visitElement(element)
      }
    })
    return unescapedOffset
  }

  companion object {
    private val LOG = Logger.getInstance(IntentionPreviewComputable::class.java)

    private fun getFixes(cachedIntentions: CachedIntentions): Sequence<IntentionActionWithTextCaching> =
      sequenceOf<IntentionActionWithTextCaching>()
        .plus(cachedIntentions.intentions)
        .plus(cachedIntentions.inspectionFixes)
        .plus(cachedIntentions.errorFixes)

    private fun findCopyIntention(project: Project,
                                  editorCopy: Editor,
                                  psiFileCopy: PsiFile,
                                  originalAction: IntentionAction): IntentionAction? {
      val actionsToShow = ShowIntentionsPass.getActionsToShow(editorCopy, psiFileCopy, false)
      val cachedIntentions = CachedIntentions.createAndUpdateActions(project, psiFileCopy, editorCopy, actionsToShow)

      return getFixes(cachedIntentions).find { it.text == originalAction.text }?.action
    }
  }
}

internal sealed interface IntentionPreviewContent

internal data class IntentionPreviewDiffResult(val psiFile: PsiFile, val origFile: PsiFile, val lineFragments: List<LineFragment>): IntentionPreviewContent
internal data class IntentionPreviewHtmlResult(val html: String): IntentionPreviewContent
internal object IntentionPreviewEmptyResult : IntentionPreviewContent