// Copyright 2000-2021 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license that can be found in the LICENSE file.
package com.intellij.openapi.editor.richcopy;

import com.intellij.ide.highlighter.HighlighterFactory;
import com.intellij.lang.Language;
import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.editor.colors.EditorColorsManager;
import com.intellij.openapi.editor.colors.EditorColorsScheme;
import com.intellij.openapi.editor.colors.TextAttributesKey;
import com.intellij.openapi.editor.highlighter.EditorHighlighter;
import com.intellij.openapi.editor.markup.EffectType;
import com.intellij.openapi.editor.markup.TextAttributes;
import com.intellij.openapi.editor.richcopy.model.SyntaxInfo;
import com.intellij.openapi.editor.richcopy.view.HtmlSyntaxInfoReader;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.util.text.HtmlChunk;
import com.intellij.openapi.util.text.StringUtil;
import com.intellij.psi.PsiFile;
import com.intellij.psi.PsiFileFactory;
import com.intellij.ui.ColorUtil;
import kotlin.text.StringsKt;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.awt.*;
import java.io.IOException;
import java.util.Objects;


public final class HtmlSyntaxInfoUtil {

  private HtmlSyntaxInfoUtil() { }

  public static @NotNull String getStyledSpan(@Nullable String value, String @NotNull ... properties) {
    return appendStyledSpan(new StringBuilder(), value, properties).toString();
  }

  public static @NotNull String getStyledSpan(@NotNull TextAttributesKey attributesKey, @Nullable String value) {
    return appendStyledSpan(new StringBuilder(), attributesKey, value).toString();
  }

  public static @NotNull String getStyledSpan(@NotNull TextAttributes attributes, @Nullable String value) {
    return appendStyledSpan(new StringBuilder(), attributes, value).toString();
  }

  public static @NotNull String getHighlightedByLexerAndEncodedAsHtmlCodeSnippet(
    @NotNull Project project,
    @NotNull Language language,
    @Nullable String codeSnippet
  ) {
    return appendHighlightedByLexerAndEncodedAsHtmlCodeSnippet(new StringBuilder(), project, language, codeSnippet).toString();
  }

  public static @NotNull StringBuilder appendStyledSpan(
    @NotNull StringBuilder buffer,
    @Nullable String value,
    String @NotNull ... properties
  ) {
    HtmlChunk.span().style(StringUtil.join(properties, ";"))
      .addRaw(StringUtil.notNullize(value)) //NON-NLS
      .appendTo(buffer);
    return buffer;
  }

  public static @NotNull StringBuilder appendStyledSpan(
    @NotNull StringBuilder buffer,
    @NotNull TextAttributesKey attributesKey,
    @Nullable String value
  ) {
    appendStyledSpan(
      buffer,
      Objects.requireNonNull(EditorColorsManager.getInstance().getGlobalScheme().getAttributes(attributesKey)),
      value);
    return buffer;
  }

  public static @NotNull StringBuilder appendStyledSpan(
    @NotNull StringBuilder buffer,
    @NotNull TextAttributes attributes,
    @Nullable String value
  ) {
    createHtmlSpanBlockStyledAsTextAttributes(attributes)
      .addRaw(StringUtil.notNullize(value)) //NON-NLS
      .appendTo(buffer);
    return buffer;
  }

  public static @NotNull StringBuilder appendHighlightedByLexerAndEncodedAsHtmlCodeSnippet(
    @NotNull StringBuilder buffer,
    @NotNull Project project,
    @NotNull Language language,
    @Nullable String codeSnippet
  ) {
    return appendHighlightedByLexerAndEncodedAsHtmlCodeSnippet(buffer, project, language, codeSnippet, true);
  }

  public static @NotNull StringBuilder appendHighlightedByLexerAndEncodedAsHtmlCodeSnippet(
    @NotNull StringBuilder buffer,
    @NotNull Project project,
    @NotNull Language language,
    @Nullable String codeSnippet,
    boolean doTrimIndent
  ) {
    codeSnippet = StringUtil.notNullize(codeSnippet);
    String trimmed = doTrimIndent ? StringsKt.trimIndent(codeSnippet) : codeSnippet;
    String zeroIndentCode = trimmed.replace("\t", "    ");
    PsiFile fakePsiFile = PsiFileFactory.getInstance(project).createFileFromText(language, codeSnippet);
    EditorColorsScheme scheme = EditorColorsManager.getInstance().getGlobalScheme();
    if (!zeroIndentCode.isEmpty()) {
      buffer.append(getHtmlContent(fakePsiFile, zeroIndentCode, null, scheme, 0, zeroIndentCode.length()));
    }
    return buffer;
  }

  public static @Nullable CharSequence getHtmlContent(
    @NotNull PsiFile file,
    @NotNull CharSequence text,
    @Nullable SyntaxInfoBuilder.RangeIterator ownRangeIterator,
    @NotNull EditorColorsScheme schemeToUse,
    int startOffset,
    int endOffset
  ) {
    EditorHighlighter highlighter =
      HighlighterFactory.createHighlighter(file.getViewProvider().getVirtualFile(), schemeToUse, file.getProject());
    highlighter.setText(text);

    SyntaxInfoBuilder.HighlighterRangeIterator highlighterRangeIterator =
      new SyntaxInfoBuilder.HighlighterRangeIterator(highlighter, startOffset, endOffset);
    ownRangeIterator = ownRangeIterator == null
                       ? highlighterRangeIterator
                       : new SyntaxInfoBuilder.CompositeRangeIterator(schemeToUse, highlighterRangeIterator, ownRangeIterator);

    return getHtmlContent(text, ownRangeIterator, schemeToUse, endOffset);
  }

  public static @Nullable CharSequence getHtmlContent(
    @NotNull CharSequence text,
    @NotNull SyntaxInfoBuilder.RangeIterator ownRangeIterator,
    @NotNull EditorColorsScheme schemeToUse,
    int stopOffset
  ) {
    SyntaxInfoBuilder.Context context = new SyntaxInfoBuilder.Context(text, schemeToUse, 0);
    SyntaxInfoBuilder.MyMarkupIterator iterator = new SyntaxInfoBuilder.MyMarkupIterator(text, ownRangeIterator, schemeToUse);

    try {
      context.iterate(iterator, stopOffset);
    }
    finally {
      iterator.dispose();
    }
    SyntaxInfo info = context.finish();
    try (HtmlSyntaxInfoReader data = new SimpleHtmlSyntaxInfoReader(info)) {
      data.setRawText(text.toString());
      return data.getBuffer();
    }
    catch (IOException e) {
      Logger.getInstance(HtmlSyntaxInfoUtil.class).error(e);
    }
    return null;
  }

  private static @NotNull HtmlChunk.Element createHtmlSpanBlockStyledAsTextAttributes(@NotNull TextAttributes attributes) {
    StringBuilder style = new StringBuilder();

    Color foregroundColor = attributes.getForegroundColor();
    Color backgroundColor = attributes.getBackgroundColor();

    if (foregroundColor != null) appendProperty(style, "color", ColorUtil.toHtmlColor(foregroundColor));
    if (backgroundColor != null) appendProperty(style, "background-color", ColorUtil.toHtmlColor(backgroundColor));

    switch (attributes.getFontType()) {
      case Font.BOLD:
        appendProperty(style, "font-weight", "bold");
        break;
      case Font.ITALIC:
        appendProperty(style, "font-style", "italic");
        break;
    }

    EffectType effectType = attributes.getEffectType();
    if (attributes.hasEffects() && effectType != null) {
      switch (effectType) {
        case LINE_UNDERSCORE:
          appendProperty(style, "text-decoration-line", "underline");
          break;
        case WAVE_UNDERSCORE:
          appendProperty(style, "text-decoration-line", "underline");
          appendProperty(style, "text-decoration-style", "wavy");
          break;
        case BOLD_LINE_UNDERSCORE:
          appendProperty(style, "text-decoration-line", "underline");
          appendProperty(style, "text-decoration-thickness", "2px");
          break;
        case BOLD_DOTTED_LINE:
          appendProperty(style, "text-decoration-line", "underline");
          appendProperty(style, "text-decoration-thickness", "2px");
          appendProperty(style, "text-decoration-style", "dotted");
          break;
        case STRIKEOUT:
          appendProperty(style, "text-decoration-line", "line-through");
          break;
        case BOXED:
        case SLIGHTLY_WIDER_BOX:
        case SEARCH_MATCH:
          appendProperty(style, "border", "1px solid");
          break;
        case ROUNDED_BOX:
          appendProperty(style, "border", "1px solid");
          appendProperty(style, "border-radius", "2px");
          break;
      }
    }

    Color effectColor = attributes.getEffectColor();
    if (attributes.hasEffects() && effectType != null && effectColor != null) {
      switch (effectType) {
        case LINE_UNDERSCORE:
        case WAVE_UNDERSCORE:
        case BOLD_LINE_UNDERSCORE:
        case BOLD_DOTTED_LINE:
        case STRIKEOUT:
          appendProperty(style, "text-decoration-color", ColorUtil.toHtmlColor(effectColor));
          break;
        case BOXED:
        case ROUNDED_BOX:
        case SEARCH_MATCH:
        case SLIGHTLY_WIDER_BOX:
          appendProperty(style, "border-color", ColorUtil.toHtmlColor(effectColor));
          break;
      }
    }

    return HtmlChunk.span().style(style.toString());
  }

  private static void appendProperty(@NotNull StringBuilder builder, @NotNull String name, @NotNull String value) {
    builder.append(name);
    builder.append(":");
    builder.append(value);
    builder.append(";");
  }


  private final static class SimpleHtmlSyntaxInfoReader extends HtmlSyntaxInfoReader {

    private SimpleHtmlSyntaxInfoReader(SyntaxInfo info) {
      super(info, 2);
    }

    @Override
    protected void appendCloseTags() {

    }

    @Override
    protected void appendStartTags() {

    }

    @Override
    protected void defineBackground(int id, @NotNull StringBuilder styleBuffer) {

    }

    @Override
    protected void appendFontFamilyRule(@NotNull StringBuilder styleBuffer, int fontFamilyId) {

    }
  }
}
