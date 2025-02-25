// Copyright 2000-2021 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license that can be found in the LICENSE file.
package org.intellij.plugins.markdown.ui.preview.jcef

import com.intellij.openapi.util.Disposer
import com.intellij.openapi.util.registry.Registry
import com.intellij.ui.jcef.JCEFHtmlPanel
import org.intellij.markdown.html.HtmlGenerator
import org.intellij.plugins.markdown.extensions.MarkdownConfigurableExtension
import org.intellij.plugins.markdown.extensions.jcef.MarkdownJCEFPreviewExtension
import org.intellij.plugins.markdown.ui.preview.MarkdownHtmlPanel
import org.intellij.plugins.markdown.ui.preview.PreviewStaticServer
import org.intellij.plugins.markdown.ui.preview.ResourceProvider
import java.nio.file.Path
import kotlin.random.Random

class MarkdownJCEFHtmlPanel : JCEFHtmlPanel(isOffScreenRendering(), null, getClassUrl()), MarkdownHtmlPanel {
  private val resourceProvider = MyResourceProvider()
  private val browserPipe = BrowserPipe(this)

  private val scrollListeners = ArrayList<MarkdownHtmlPanel.ScrollListener>()

  private val scriptingLines get() =
    scripts.joinToString("\n") {
      "<script src=\"${PreviewStaticServer.getStaticUrl(resourceProvider, it)}\"></script>"
    }

  private val stylesLines get() =
    styles.joinToString("\n") {
      "<link rel=\"stylesheet\" href=\"${PreviewStaticServer.getStaticUrl(resourceProvider, it)}\"/>"
    }

  private val contentSecurityPolicy get() =
    PreviewStaticServer.createCSP(
      scripts.map { PreviewStaticServer.getStaticUrl(resourceProvider, it) },
      styles.map { PreviewStaticServer.getStaticUrl(resourceProvider, it) }
    )

  private val indexContent get() =
    // language=HTML
    """
      <!DOCTYPE html>
      <html>
        <head>
          <title>IntelliJ Markdown Preview</title>
          <meta http-equiv="Content-Security-Policy" content="$contentSecurityPolicy"/>
          <meta name="markdown-position-attribute-name" content="${HtmlGenerator.SRC_ATTRIBUTE_NAME}"/>
          $scriptingLines
          $stylesLines
        </head>
      </html>
    """

  @Volatile
  private var delayedContent: String? = null
  private var firstUpdate = true
  private var previousRenderClosure: String = ""

  init {
    Disposer.register(this, browserPipe)
    val resourceProviderRegistration = PreviewStaticServer.instance.registerResourceProvider(resourceProvider)
    Disposer.register(this, resourceProviderRegistration)
    browserPipe.addBrowserEvents(SET_SCROLL_EVENT)
    browserPipe.subscribe(BrowserPipe.WINDOW_READY_EVENT) {
      delayedContent?.let {
        cefBrowser.executeJavaScript(it, null, 0)
        delayedContent = null
      }
    }
    browserPipe.subscribe(SET_SCROLL_EVENT) { data ->
      data.toIntOrNull()?.let { offset ->
        scrollListeners.forEach { it.onScroll(offset) }
      }
    }
    extensions.flatMap { it.events.entries }.forEach { (event, handler) ->
      browserPipe.addBrowserEvents(event)
      browserPipe.subscribe(event, handler)
    }
    super<JCEFHtmlPanel>.setHtml(indexContent)
  }

  private fun updateDom(renderClosure: String, initialScrollOffset: Int) {
    previousRenderClosure = renderClosure
    val scrollCode = if (firstUpdate) {
      "window.scrollController.scrollTo($initialScrollOffset, true);"
    }
    else ""
    val code =
      // language=JavaScript
      """
        (function() {
          const action = () => {
            console.time("incremental-dom-patch");
            const render = $previousRenderClosure;
            IncrementalDOM.patch(document.body, () => render());
            $scrollCode
            if (IncrementalDOM.notifications.afterPatchListeners) {
              IncrementalDOM.notifications.afterPatchListeners.forEach(listener => listener());
            }
            console.timeEnd("incremental-dom-patch");
          };
          if (document.readyState === "loading" || document.readyState === "uninitialized") {
            document.addEventListener("DOMContentLoaded", () => action(), { once: true });
          }
          else {
            action();
          }
        })();
      """
    delayedContent = code
    cefBrowser.executeJavaScript(code, null, 0)
  }

  override fun setHtml(html: String, initialScrollOffset: Int, documentPath: Path?) {
    val basePath = documentPath?.parent
    val builder = IncrementalDOMBuilder(html, basePath)
    updateDom(builder.generateRenderClosure(), initialScrollOffset)
    firstUpdate = false
  }

  override fun reloadWithOffset(offset: Int) {
    delayedContent = null
    firstUpdate = true
    super<JCEFHtmlPanel>.setHtml(indexContent)
    updateDom(previousRenderClosure, offset)
  }

  override fun addScrollListener(listener: MarkdownHtmlPanel.ScrollListener) {
    scrollListeners.add(listener)
  }

  override fun removeScrollListener(listener: MarkdownHtmlPanel.ScrollListener?) {
    scrollListeners.remove(listener)
  }

  override fun scrollToMarkdownSrcOffset(offset: Int, smooth: Boolean) {
    cefBrowser.executeJavaScript(
      // language=JavaScript
      "if (window.scrollController) { window.scrollController.scrollTo($offset, $smooth); }",
      null,
      0
    )
  }

  override fun dispose() {
    super.dispose()
  }

  private inner class MyResourceProvider : ResourceProvider {
    private val internalResources = baseScripts + baseStyles

    override fun canProvide(resourceName: String): Boolean =
      resourceName in internalResources || extensions.any {
        it.resourceProvider.canProvide(resourceName)
      }

    override fun loadResource(resourceName: String): ResourceProvider.Resource? {
      return when (resourceName) {
        EVENTS_SCRIPT_FILENAME -> ResourceProvider.Resource(browserPipe.inject().toByteArray())
        in internalResources -> ResourceProvider.loadInternalResource<MarkdownJCEFHtmlPanel>(resourceName)
        else ->
          extensions.map { it.resourceProvider }.firstOrNull {
            it.canProvide(resourceName)
          }?.loadResource(resourceName)
      }
    }
  }

  companion object {
    private const val SET_SCROLL_EVENT = "setScroll"

    private val extensions
      get() = MarkdownJCEFPreviewExtension.allSorted.filter {
        if (it is MarkdownConfigurableExtension) {
          it.isEnabled
        }
        else true
      }

    private const val EVENTS_SCRIPT_FILENAME = "events.js"

    private val baseScripts = listOf(
      "incremental-dom.min.js",
      "incremental-dom-additions.js",
      "BrowserPipe.js",
      EVENTS_SCRIPT_FILENAME,
      "ScrollSync.js"
    )

    private val baseStyles = emptyList<String>()

    val scripts get() = baseScripts + extensions.flatMap { it.scripts }

    val styles get() = extensions.flatMap { it.styles }

    private fun getClassUrl(): String {
      val url = try {
        val cls = MarkdownJCEFHtmlPanel::class.java
        cls.getResource("${cls.simpleName}.class").toExternalForm() ?: error("Failed to get class URL!")
      }
      catch (ignored: Exception) {
        "about:blank"
      }
      return "$url@${Random.nextInt(Integer.MAX_VALUE)}"
    }

    private fun isOffScreenRendering(): Boolean = Registry.`is`("ide.browser.jcef.markdownView.osr.enabled")
  }
}
