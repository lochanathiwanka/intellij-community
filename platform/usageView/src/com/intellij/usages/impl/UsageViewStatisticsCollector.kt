// Copyright 2000-2020 JetBrains s.r.o. Use of this source code is governed by the Apache 2.0 license that can be found in the LICENSE file.
package com.intellij.usages.impl

import com.intellij.ide.util.scopeChooser.ScopeIdMapper
import com.intellij.internal.statistic.eventLog.EventLogGroup
import com.intellij.internal.statistic.eventLog.events.EventFields
import com.intellij.internal.statistic.eventLog.validator.ValidationResultType
import com.intellij.internal.statistic.eventLog.validator.rules.EventContext
import com.intellij.internal.statistic.eventLog.validator.rules.impl.CustomValidationRule
import com.intellij.internal.statistic.service.fus.collectors.CounterUsagesCollector
import com.intellij.lang.Language
import com.intellij.openapi.project.Project
import com.intellij.psi.PsiElement
import com.intellij.psi.search.SearchScope
import com.intellij.usageView.UsageInfo
import com.intellij.usages.Usage
import com.intellij.usages.rules.PsiElementUsage
import org.jetbrains.annotations.Nls

enum class CodeNavigateSource {
  ShowUsagesPopup,
  FindToolWindow
}

enum class TooManyUsagesUserAction {
  Shown,
  Aborted,
  Continued
}

class UsageViewStatisticsCollector : CounterUsagesCollector() {
  override fun getGroup() = GROUP

  companion object {
    val GROUP = EventLogGroup("usage.view", 2)

    private val REFERENCE_CLASS = EventFields.Class("reference_class")
    private val USAGE_SHOWN = GROUP.registerEvent("usage.shown", REFERENCE_CLASS, EventFields.Language)
    private val USAGE_NAVIGATE = GROUP.registerEvent("usage.navigate", REFERENCE_CLASS, EventFields.Language)
    private val UI_LOCATION = EventFields.Enum("ui_location", CodeNavigateSource::class.java)

    private val itemChosen = GROUP.registerEvent("item.chosen", UI_LOCATION, EventFields.Language)

    const val SCOPE_RULE_ID = "scopeRule"

    private val SYMBOL_CLASS = EventFields.Class("symbol")
    private val SEARCH_SCOPE = EventFields.StringValidatedByCustomRule("scope", SCOPE_RULE_ID)
    private val RESULTS_TOTAL = EventFields.Int("results_total")
    private val FIRST_RESULT_TS = EventFields.Long("duration_first_results_ms")
    private val TOO_MANY_RESULTS = EventFields.Boolean("too_many_result_warning")

    private val searchFinished = GROUP.registerVarargEvent("finished",
      SYMBOL_CLASS,
      SEARCH_SCOPE,
      EventFields.Language,
      RESULTS_TOTAL,
      FIRST_RESULT_TS,
      EventFields.DurationMs,
      TOO_MANY_RESULTS,
      UI_LOCATION)

    private val tabSwitched = GROUP.registerEvent("switch.tab")

    private val PREVIOUS_SCOPE = EventFields.StringValidatedByCustomRule("previous", SCOPE_RULE_ID)
    private val NEW_SCOPE = EventFields.StringValidatedByCustomRule("new", SCOPE_RULE_ID)

    private val scopeChanged = GROUP.registerEvent("scope.changed", PREVIOUS_SCOPE, NEW_SCOPE, SYMBOL_CLASS)

    private val USER_ACTION = EventFields.Enum("userAction", TooManyUsagesUserAction::class.java)
    private val tooManyUsagesDialog = GROUP.registerVarargEvent("tooManyResultsDialog",
      USER_ACTION,
      SYMBOL_CLASS,
      SEARCH_SCOPE,
      EventFields.Language
    )

    @JvmStatic
    fun logUsageShown(project: Project?, referenceClass: Class<out Any>?, language: Language?) {
      USAGE_SHOWN.log(project, referenceClass, language)
    }

    @JvmStatic
    fun logUsageNavigate(project: Project?, usage: Usage) {
      USAGE_NAVIGATE.log(project, (usage as? PsiElementUsage)?.referenceClass, (usage as? PsiElementUsage)?.element?.language)
    }

    @JvmStatic
    fun logUsageNavigate(project: Project?, usage: UsageInfo) {
      USAGE_NAVIGATE.log(project, usage.referenceClass, usage.element?.language)
    }

    @JvmStatic
    fun logItemChosen(project: Project?, source: CodeNavigateSource, language: Language) = itemChosen.log(project, source, language)

    @JvmStatic
    fun logSearchFinished(project: Project?,
                          targetClass: Class<*>?,
                          scope: SearchScope?,
                          language: Language?,
                          results: Int,
                          durationFirstResults: Long,
                          duration: Long,
                          tooManyResult: Boolean,
                          source: CodeNavigateSource) =
      searchFinished.log(project,
        SYMBOL_CLASS.with(targetClass),
        SEARCH_SCOPE.with(scope?.let{ ScopeIdMapper.instance.getScopeSerializationId(it.displayName) }),
        EventFields.Language.with(language),
        RESULTS_TOTAL.with(results),
        FIRST_RESULT_TS.with(durationFirstResults),
        EventFields.DurationMs.with(duration),
        TOO_MANY_RESULTS.with(tooManyResult),
        UI_LOCATION.with(source))

    @JvmStatic
    fun logTabSwitched(project: Project?) = tabSwitched.log(project)

    @JvmStatic
    fun logScopeChanged(project: Project?,
                        previousScope: SearchScope?,
                        newScope: SearchScope?,
                        symbolClass: Class<*>?) {
      val scopeIdMapper = ScopeIdMapper.instance
      scopeChanged.log(project,
        previousScope?.let{ scopeIdMapper.getScopeSerializationId(it.displayName) },
        newScope?.let{ scopeIdMapper.getScopeSerializationId(it.displayName) },
        symbolClass)
    }

    @JvmStatic
    fun logTooManyDialog(project: Project?,
                         action: TooManyUsagesUserAction,
                         targetClass: Class<out PsiElement>?,
                         @Nls scope: String,
                         language: Language?) =
      tooManyUsagesDialog.log(project,
        USER_ACTION.with(action),
        SYMBOL_CLASS.with(targetClass),
        SEARCH_SCOPE.with(ScopeIdMapper.instance.getScopeSerializationId(scope)),
        EventFields.Language.with(language))
  }
}

class ScopeRuleValidator : CustomValidationRule() {
  @Suppress("HardCodedStringLiteral")
  override fun doValidate(data: String, context: EventContext): ValidationResultType =
    if (ScopeIdMapper.standardNames.contains(data)) ValidationResultType.ACCEPTED else ValidationResultType.REJECTED

  override fun acceptRuleId(ruleId: String?): Boolean = ruleId == UsageViewStatisticsCollector.SCOPE_RULE_ID
}