// Copyright 2000-2021 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license that can be found in the LICENSE file.
package com.intellij.codeInsight.hints

import com.intellij.codeHighlighting.*
import com.intellij.codeInsight.daemon.DaemonCodeAnalyzer
import com.intellij.codeInsight.daemon.impl.TextEditorHighlightingPassRegistrarImpl
import com.intellij.diff.util.DiffUtil
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.editor.EditorFactory
import com.intellij.openapi.project.Project
import com.intellij.openapi.project.ProjectManager
import com.intellij.openapi.util.Key
import com.intellij.psi.PsiFile
import org.jetbrains.annotations.ApiStatus

class InlayHintsPassFactory : TextEditorHighlightingPassFactory, TextEditorHighlightingPassFactoryRegistrar {
  override fun registerHighlightingPassFactory(registrar: TextEditorHighlightingPassRegistrar, project: Project) {
    val ghl = intArrayOf(Pass.UPDATE_ALL).takeIf { (registrar as TextEditorHighlightingPassRegistrarImpl).isSerializeCodeInsightPasses }
    registrar.registerTextEditorHighlightingPass(this, ghl, null, false, -1)
  }

  override fun createHighlightingPass(file: PsiFile, editor: Editor): TextEditorHighlightingPass? {
    if (editor.isOneLineMode) return null
    val savedStamp = editor.getUserData(PSI_MODIFICATION_STAMP)
    if (DiffUtil.isDiffEditor(editor)) return null
    val currentStamp = getCurrentModificationStamp(file)
    if (savedStamp != null && savedStamp == currentStamp) return null

    val settings = InlayHintsSettings.instance()
    val language = file.language
    val collectors = if (isHintsEnabledForEditor(editor)) {
      HintUtils.getHintProvidersForLanguage(language, file.project)
        .mapNotNull { it.getCollectorWrapperFor(file, editor, language) }
        .filter { settings.hintsShouldBeShown(it.key, language) || isProviderAlwaysEnabledForEditor(editor, it.key) }
    }
    else {
      emptyList()
    }
    return InlayHintsPass(file, collectors, editor)
  }

  companion object {
    fun forceHintsUpdateOnNextPass() {
      for (editor in EditorFactory.getInstance().allEditors) {
        clearModificationStamp(editor)
      }
      ProjectManager.getInstance().openProjects.forEach { project ->
        DaemonCodeAnalyzer.getInstance(project).restart()
      }
    }

    @JvmStatic
    private val PSI_MODIFICATION_STAMP: Key<Long> = Key.create("inlay.psi.modification.stamp")

    @JvmStatic
    private val HINTS_DISABLED_FOR_EDITOR: Key<Boolean> = Key.create("inlay.hints.enabled.for.editor")

    @JvmStatic
    private val ALWAYS_ENABLED_HINTS_PROVIDERS: Key<Set<SettingsKey<*>>> = Key.create("inlay.hints.always.enabled.providers")

    fun putCurrentModificationStamp(editor: Editor, file: PsiFile) {
      editor.putUserData(PSI_MODIFICATION_STAMP, getCurrentModificationStamp(file))
    }

    fun clearModificationStamp(editor: Editor) = editor.putUserData(PSI_MODIFICATION_STAMP, null)

    private fun getCurrentModificationStamp(file: PsiFile): Long {
      return file.manager.modificationTracker.modificationCount
    }

    private fun isHintsEnabledForEditor(editor: Editor): Boolean {
      return editor.getUserData(HINTS_DISABLED_FOR_EDITOR) != true
    }

    /**
     * Enables/disables hints for a given editor
     */
    @ApiStatus.Experimental
    @JvmStatic
    fun setHintsEnabled(editor: Editor, value: Boolean) {
      if (value) {
        editor.putUserData(HINTS_DISABLED_FOR_EDITOR, null)
      }
      else {
        editor.putUserData(HINTS_DISABLED_FOR_EDITOR, true)
      }
      forceHintsUpdateOnNextPass()
    }

    private fun isProviderAlwaysEnabledForEditor(editor: Editor, providerKey: SettingsKey<*>): Boolean {
      val alwaysEnabledProviderKeys = editor.getUserData(ALWAYS_ENABLED_HINTS_PROVIDERS)
      if (alwaysEnabledProviderKeys == null) return false
      return providerKey in alwaysEnabledProviderKeys
    }

    /**
     * For a given editor enables hints providers even if they explicitly disabled in settings or even if hints itself are disabled
     * @param keys list of keys of provider that must be enabled or null if default behavior is required
     */
    @ApiStatus.Experimental
    @JvmStatic
    fun setAlwaysEnabledHintsProviders(editor: Editor, keys: Iterable<SettingsKey<*>>?) {
      if (keys == null) {
        editor.putUserData(ALWAYS_ENABLED_HINTS_PROVIDERS, null)
        return
      }
      val keySet = keys.toSet()
      editor.putUserData(ALWAYS_ENABLED_HINTS_PROVIDERS, keySet)
      forceHintsUpdateOnNextPass()
    }
  }
}