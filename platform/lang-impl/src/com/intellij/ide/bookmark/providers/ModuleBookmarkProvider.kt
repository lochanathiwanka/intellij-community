// Copyright 2000-2021 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license that can be found in the LICENSE file.
package com.intellij.ide.bookmark.providers

import com.intellij.ide.bookmark.Bookmark
import com.intellij.ide.bookmark.BookmarkProvider
import com.intellij.ide.projectView.impl.ModuleGroupUrl
import com.intellij.ide.projectView.impl.ModuleUrl
import com.intellij.ide.util.treeView.AbstractTreeNode
import com.intellij.ide.util.treeView.NodeDescriptor
import com.intellij.openapi.module.ModuleManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.roots.ui.configuration.ProjectSettingsService
import com.intellij.openapi.util.text.StringUtil

class ModuleBookmarkProvider(private val project: Project) : BookmarkProvider {
  override fun getWeight() = 10
  override fun getProject() = project

  internal val moduleManager
    get() = if (project.isDisposed) null else ModuleManager.getInstance(project)

  internal val projectSettingsService
    get() = if (project.isDisposed) null else ProjectSettingsService.getInstance(project)

  override fun compare(bookmark1: Bookmark, bookmark2: Bookmark): Int {
    val moduleBookmark1 = bookmark1 as? ModuleBookmark
    val moduleBookmark2 = bookmark2 as? ModuleBookmark
    if (moduleBookmark1 == null && moduleBookmark2 == null) return 0
    if (moduleBookmark1 == null) return -1
    if (moduleBookmark2 == null) return 1
    if (moduleBookmark1.isGroup && !moduleBookmark2.isGroup) return -1
    if (!moduleBookmark1.isGroup && moduleBookmark2.isGroup) return 1
    return StringUtil.naturalCompare(moduleBookmark1.name, moduleBookmark2.name)
  }

  override fun createBookmark(map: MutableMap<String, String>) =
    map["group"]?.let { ModuleBookmark(this, it, true) } ?: map["module"]?.let { ModuleBookmark(this, it, false) }

  private fun createGroupBookmark(name: String?) = name?.let { ModuleBookmark(this, it, true) }
  private fun createModuleBookmark(name: String?) = name?.let { ModuleBookmark(this, it, false) }
  override fun createBookmark(context: Any?): ModuleBookmark? = when (context) {
    is AbstractTreeNode<*> -> createBookmark(context.value)
    is NodeDescriptor<*> -> createBookmark(context.element)
    is ModuleGroupUrl -> createGroupBookmark(context.url)
    is ModuleUrl -> createModuleBookmark(context.url)
    is Module -> createModuleBookmark(context.name)
    else -> null
  }
}
