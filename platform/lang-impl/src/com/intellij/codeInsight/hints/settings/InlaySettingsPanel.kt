// Copyright 2000-2021 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license that can be found in the LICENSE file.
package com.intellij.codeInsight.hints.settings

import com.intellij.codeInsight.hints.*
import com.intellij.codeInsight.hints.settings.language.NewInlayProviderSettingsModel
import com.intellij.codeInsight.hints.settings.language.ParameterInlayProviderSettingsModel
import com.intellij.codeInsight.hints.settings.language.createEditor
import com.intellij.lang.Language
import com.intellij.openapi.application.ApplicationBundle
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.text.StringUtil
import com.intellij.ui.CheckboxTree
import com.intellij.ui.CheckedTreeNode
import com.intellij.ui.JBSplitter
import com.intellij.ui.ScrollPaneFactory
import com.intellij.util.ui.JBUI
import com.intellij.util.ui.UIUtil
import com.intellij.util.ui.tree.TreeUtil
import net.miginfocom.swing.MigLayout
import org.jetbrains.annotations.Nls
import java.awt.BorderLayout
import javax.swing.JLabel
import javax.swing.JPanel
import javax.swing.JTree
import javax.swing.event.TreeSelectionListener
import javax.swing.tree.DefaultMutableTreeNode
import javax.swing.tree.DefaultTreeModel

class InlaySettingsPanel(val project: Project): JPanel(BorderLayout()) {

  private val tree: CheckboxTree
  private val rightPanel: JPanel = JPanel(MigLayout("wrap, insets 0 10 0 0, gapy 20"))

  init {
    val settings = InlayHintsSettings.instance()
    val root = CheckedTreeNode()
    var nodeToSelect: CheckedTreeNode? = null
    val groups = InlayHintsProviderExtension.inlayProviderName.extensionList.groupBy { it.instance.groupId }.mapValues {
      it.value.map { point ->
        NewInlayProviderSettingsModel(point.instance.withSettings(Language.findLanguageByID(point.language)!!, settings),
          settings) as InlayProviderSettingsModel
      }
    }.toMutableMap()
    val paramLanguages = PARAMETER_NAME_HINTS_EP.extensionList.mapNotNull {
      ParameterInlayProviderSettingsModel(it.instance, Language.findLanguageByID(it.language)!!)
    }
    groups[PARAMETERS_GROUP] = paramLanguages
    val sortedMap = groups.toSortedMap(Comparator.comparing { sortedGroups.indexOf(it) })
    for (group in sortedMap) {
      val groupNode = CheckedTreeNode(ApplicationBundle.message("settings.hints.group." + group.key))
      root.add(groupNode)
      for (lang in group.value.groupBy { it.language }) {
        if (lang.value.size == 1) {
          val node = addModelNode(lang.value.first(), groupNode)
          if (nodeToSelect == null && getProviderId(node) == settings.getLastViewedProviderId()) {
            nodeToSelect = node
          }
        }
        else {
          val langNode = CheckedTreeNode(lang.key)
          groupNode.add(langNode)
          lang.value.forEach {
            val node = addModelNode(it, langNode)
            if (nodeToSelect == null && getProviderId(node) == settings.getLastViewedProviderId()) {
              nodeToSelect = node
            }
          }
        }
      }
    }

    tree = CheckboxTree(object : CheckboxTree.CheckboxTreeCellRenderer() {
      override fun customizeRenderer(tree: JTree?,
                                     value: Any?,
                                     selected: Boolean,
                                     expanded: Boolean,
                                     leaf: Boolean,
                                     row: Int,
                                     hasFocus: Boolean) {
        if (value !is DefaultMutableTreeNode) return

        when (val item = value.userObject) {
          is String -> textRenderer.append(item)
          is Language -> textRenderer.append(item.displayName)
          is InlayProviderSettingsModel -> textRenderer.append(
            if ((value.parent as DefaultMutableTreeNode).userObject is String) item.language.displayName else item.name)
          is ImmediateConfigurable.Case -> textRenderer.append(item.name)
        }
      }
    }, root)
    tree.addTreeSelectionListener(
      TreeSelectionListener { updateRightPanel(it?.newLeadSelectionPath?.lastPathComponent as? CheckedTreeNode) })
    if (nodeToSelect == null) {
      TreeUtil.expand(tree, 1)
    }
    else {
      TreeUtil.selectNode(tree, nodeToSelect)
    }

    val splitter = JBSplitter(false, 0.3f)
    splitter.firstComponent = ScrollPaneFactory.createScrollPane(tree)
    splitter.secondComponent = rightPanel
    add(splitter, BorderLayout.CENTER)
  }

  private fun addModelNode(model: InlayProviderSettingsModel,
                           parent: CheckedTreeNode): CheckedTreeNode {
    model.onChangeListener = object : ChangeListener {
      override fun settingsChanged() {

      }
    }
    val node = CheckedTreeNode(model)
    node.isChecked = model.isEnabled
    parent.add(node)
    model.cases.forEach {
      val caseNode = object: CheckedTreeNode(it) {
        override fun setChecked(checked: Boolean) {
          super.setChecked(checked)
          it.value = checked
        }
      }
      caseNode.isChecked = it.value
      node.add(caseNode)
    }
    return node
  }

  private fun updateRightPanel(treeNode: CheckedTreeNode?) {
    rightPanel.removeAll()
    when (val item = treeNode?.userObject) {
      is InlayProviderSettingsModel -> {
        InlayHintsSettings.instance().saveLastViewedProviderId(getProviderId(treeNode))

        addDescription(item.description)
        item.component.border = JBUI.Borders.empty()
        rightPanel.add(item.component)
        addPreview(item.getCasePreview(null) ?: item.previewText, item.language)
      }
      is ImmediateConfigurable.Case -> {
        val parent = treeNode.parent as CheckedTreeNode
        val model = parent.userObject as InlayProviderSettingsModel
        addDescription(model.getCaseDescription(item))
        val preview = model.getCasePreview(item)
        addPreview(preview, model.language)
      }
    }
    rightPanel.revalidate()
    rightPanel.repaint()
  }

  private fun addPreview(previewText: String?, language: Language) {
    if (previewText != null) {
      val editor = createEditor(language, project) {}
      editor.text = previewText
      rightPanel.add(editor)
    }
  }

  private fun addDescription(@Nls s: String?) {
    val htmlBody = UIUtil.toHtml(StringUtil.notNullize(s))
    rightPanel.add(JLabel(htmlBody), "growy, width 200:300:300")
  }

  private fun getProviderId(treeNode: CheckedTreeNode): String {
    val model = treeNode.userObject as InlayProviderSettingsModel
    return model.language.id + "." + model.id
  }

  fun reset() {
    reset(tree.model.root as CheckedTreeNode)
  }

  private fun reset(node: CheckedTreeNode) {
    when (node.userObject) {
      is InlayProviderSettingsModel -> {
        val model = node.userObject as InlayProviderSettingsModel
        if (model.isEnabled != node.isChecked) {
          node.isChecked = model.isEnabled
          refreshNode(node)
        }
        model.reset()
      }
      is ImmediateConfigurable.Case -> {
        val case = node.userObject as ImmediateConfigurable.Case
        if (case.value != node.isChecked) {
          node.isChecked = case.value
          refreshNode(node)
        }
      }
    }
    node.children().toList().forEach { reset(it as CheckedTreeNode) }
  }

  private fun refreshNode(node: CheckedTreeNode) {
    val treeModel = tree.model as DefaultTreeModel
    treeModel.nodeChanged(node)
    treeModel.nodeChanged(node.parent)
    treeModel.nodeChanged(node.parent.parent)
  }

  fun apply() {
    apply(tree.model.root as CheckedTreeNode)
  }

  private fun apply(node: CheckedTreeNode) {
    when (node.userObject) {
      is InlayProviderSettingsModel -> {
        val model = node.userObject as InlayProviderSettingsModel
        model.isEnabled = node.isChecked
        model.apply()
      }
      is ImmediateConfigurable.Case -> {
        (node.userObject as ImmediateConfigurable.Case).value = node.isChecked
      }
    }
    node.children().toList().forEach { apply(it as CheckedTreeNode) }
  }

  fun isModified(): Boolean {
    return isModified(tree.model.root as CheckedTreeNode)
  }

  private fun isModified(node: CheckedTreeNode): Boolean {
    when (node.userObject) {
      is InlayProviderSettingsModel -> {
        val model = node.userObject as InlayProviderSettingsModel
        if ((node.isChecked != model.isEnabled) || model.isModified()) return true
      }
    }
    return node.children().toList().any { isModified(it as CheckedTreeNode) }
  }
}