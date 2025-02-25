// Copyright 2000-2021 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license that can be found in the LICENSE file.

package org.jetbrains.kotlin.idea.caches.resolve

import com.google.common.collect.ImmutableMap
import com.intellij.openapi.diagnostic.ControlFlowException
import com.intellij.openapi.progress.ProcessCanceledException
import com.intellij.openapi.progress.ProgressIndicatorProvider
import com.intellij.openapi.project.DumbService
import com.intellij.openapi.project.IndexNotReadyException
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.ModificationTracker
import com.intellij.psi.PsiElement
import com.intellij.psi.util.findParentInFile
import com.intellij.psi.util.findTopmostParentInFile
import com.intellij.psi.util.findTopmostParentOfType
import org.jetbrains.kotlin.analyzer.AnalysisResult
import org.jetbrains.kotlin.container.ComponentProvider
import org.jetbrains.kotlin.container.get
import org.jetbrains.kotlin.context.GlobalContext
import org.jetbrains.kotlin.context.withModule
import org.jetbrains.kotlin.context.withProject
import org.jetbrains.kotlin.descriptors.ModuleDescriptor
import org.jetbrains.kotlin.diagnostics.*
import org.jetbrains.kotlin.diagnostics.PositioningStrategies.DECLARATION_WITH_BODY
import org.jetbrains.kotlin.frontend.di.createContainerForLazyBodyResolve
import org.jetbrains.kotlin.idea.caches.project.getModuleInfo
import org.jetbrains.kotlin.idea.caches.trackers.clearInBlockModifications
import org.jetbrains.kotlin.idea.caches.trackers.inBlockModifications
import org.jetbrains.kotlin.idea.compiler.IdeMainFunctionDetectorFactory
import org.jetbrains.kotlin.idea.compiler.IdeSealedClassInheritorsProvider
import org.jetbrains.kotlin.idea.project.IdeaModuleStructureOracle
import org.jetbrains.kotlin.idea.project.findAnalyzerServices
import org.jetbrains.kotlin.idea.project.languageVersionSettings
import org.jetbrains.kotlin.idea.util.application.withPsiAttachment
import org.jetbrains.kotlin.psi.*
import org.jetbrains.kotlin.psi.psiUtil.parentsWithSelf
import org.jetbrains.kotlin.resolve.*
import org.jetbrains.kotlin.resolve.diagnostics.Diagnostics
import org.jetbrains.kotlin.resolve.diagnostics.DiagnosticsElementsCache
import org.jetbrains.kotlin.resolve.lazy.BodyResolveMode
import org.jetbrains.kotlin.resolve.lazy.ResolveSession
import org.jetbrains.kotlin.storage.CancellableSimpleLock
import org.jetbrains.kotlin.storage.guarded
import org.jetbrains.kotlin.types.KotlinType
import org.jetbrains.kotlin.util.slicedMap.ReadOnlySlice
import org.jetbrains.kotlin.util.slicedMap.WritableSlice
import org.jetbrains.kotlin.utils.addToStdlib.safeAs
import org.jetbrains.kotlin.utils.checkWithAttachment
import java.util.*
import java.util.concurrent.locks.ReentrantLock

internal class PerFileAnalysisCache(val file: KtFile, componentProvider: ComponentProvider) {
    private val globalContext = componentProvider.get<GlobalContext>()
    private val moduleDescriptor = componentProvider.get<ModuleDescriptor>()
    private val resolveSession = componentProvider.get<ResolveSession>()
    private val codeFragmentAnalyzer = componentProvider.get<CodeFragmentAnalyzer>()
    private val bodyResolveCache = componentProvider.get<BodyResolveCache>()

    private val cache = HashMap<PsiElement, AnalysisResult>()
    private var fileResult: AnalysisResult? = null
    private val lock = ReentrantLock()
    private val guardLock = CancellableSimpleLock(lock,
                                                  checkCancelled = {
                                                      ProgressIndicatorProvider.checkCanceled()
                                                  },
                                                  interruptedExceptionHandler = { throw ProcessCanceledException(it) })

    private fun check(element: KtElement) {
        checkWithAttachment(element.containingFile == file, {
            "Expected $file, but was ${element.containingFile} for ${if (element.isValid) "valid" else "invalid"} $element "
        }) {
            it.withPsiAttachment("element.kt", element)
            it.withPsiAttachment("file.kt", element.containingFile)
            it.withPsiAttachment("original.kt", file)
        }
    }

    internal val isValid: Boolean get() = moduleDescriptor.isValid

    internal fun fetchAnalysisResults(element: KtElement): AnalysisResult? {
        check(element)

        if (lock.tryLock()) {
            try {
                updateFileResultFromCache()

                return fileResult?.takeIf { file.inBlockModifications.isEmpty() }
            } finally {
                lock.unlock()
            }
        }
        return null
    }

    internal fun getAnalysisResults(element: KtElement, callback: DiagnosticSink.DiagnosticsCallback? = null): AnalysisResult {
        check(element)

        val analyzableParent = KotlinResolveDataProvider.findAnalyzableParent(element) ?: return AnalysisResult.EMPTY

        fun handleResult(result: AnalysisResult, callback: DiagnosticSink.DiagnosticsCallback?): AnalysisResult {
            callback?.let { result.bindingContext.diagnostics.forEach(it::callback) }
            return result
        }

        return guardLock.guarded {
            // step 1: perform incremental analysis IF it is applicable
            getIncrementalAnalysisResult(callback)?.let {
                return@guarded handleResult(it, callback)
            }

            // cache does not contain AnalysisResult per each kt/psi element
            // instead it looks up analysis for its parents - see lookUp(analyzableElement)

            // step 2: return result if it is cached
            lookUp(analyzableParent)?.let {
                return@guarded handleResult(it, callback)
            }

            val localDiagnostics = mutableSetOf<Diagnostic>()
            val localCallback = if (callback != null) { d: Diagnostic ->
                localDiagnostics.add(d)
                callback.callback(d)
            } else null

            // step 3: perform analyze of analyzableParent as nothing has been cached yet
            val result = analyze(analyzableParent, null, localCallback)

            // some diagnostics could be not handled with a callback - send out the rest
            callback?.let { c ->
                result.bindingContext.diagnostics.filterNot { it in localDiagnostics }.forEach(c::callback)
            }
            cache[analyzableParent] = result

            return@guarded result
        }
    }

    private fun getIncrementalAnalysisResult(callback: DiagnosticSink.DiagnosticsCallback?): AnalysisResult? {
        updateFileResultFromCache()

        val inBlockModifications = file.inBlockModifications
        if (inBlockModifications.isNotEmpty()) {
            try {
                // IF there is a cached result for ktFile and there are inBlockModifications
                fileResult = fileResult?.let { result ->
                    var analysisResult = result
                    // Force full analysis when existed is erroneous
                    if (analysisResult.isError()) return@let null
                    for (inBlockModification in inBlockModifications) {
                        val resultCtx = analysisResult.bindingContext

                        val stackedCtx =
                            if (resultCtx is StackedCompositeBindingContextTrace.StackedCompositeBindingContext) resultCtx else null

                        // no incremental analysis IF it is not applicable
                        if (stackedCtx?.isIncrementalAnalysisApplicable() == false) return@let null

                        val trace: StackedCompositeBindingContextTrace =
                            if (stackedCtx != null && stackedCtx.element() == inBlockModification) {
                                val trace = stackedCtx.bindingTrace()
                                trace.clear()
                                trace
                            } else {
                                // to reflect a depth of stacked binding context
                                val depth = (stackedCtx?.depth() ?: 0) + 1

                                StackedCompositeBindingContextTrace(
                                    depth,
                                    element = inBlockModification,
                                    resolveContext = resolveSession.bindingContext,
                                    parentContext = resultCtx
                                )
                            }

                        callback?.let { trace.parentDiagnosticsApartElement.forEach(it::callback) }

                        val newResult = analyze(inBlockModification, trace, callback)
                        analysisResult = wrapResult(result, newResult, trace)
                    }
                    file.clearInBlockModifications()

                    analysisResult
                }
            } catch (e: Throwable) {
                if (e !is ControlFlowException) {
                    file.clearInBlockModifications()
                    fileResult = null
                }
                throw e
            }
        }
        if (fileResult == null) {
            file.clearInBlockModifications()
        }
        return fileResult
    }

    private fun updateFileResultFromCache() {
        // move fileResult from cache if it is stored there
        if (fileResult == null && cache.containsKey(file)) {
            fileResult = cache[file]

            // drop existed results for entire cache:
            // if incremental analysis is applicable it will produce a single value for file
            // otherwise those results are potentially stale
            cache.clear()
        }
    }

    private fun lookUp(analyzableElement: KtElement): AnalysisResult? {
        // Looking for parent elements that are already analyzed
        // Also removing all elements whose parents are already analyzed, to guarantee consistency
        val descendantsOfCurrent = arrayListOf<PsiElement>()
        val toRemove = hashSetOf<PsiElement>()

        var result: AnalysisResult? = null
        for (current in analyzableElement.parentsWithSelf) {
            val cached = cache[current]
            if (cached != null) {
                result = cached
                toRemove.addAll(descendantsOfCurrent)
                descendantsOfCurrent.clear()
            }

            descendantsOfCurrent.add(current)
        }

        cache.keys.removeAll(toRemove)

        return result
    }

    private fun wrapResult(
        oldResult: AnalysisResult,
        newResult: AnalysisResult,
        elementBindingTrace: StackedCompositeBindingContextTrace
    ): AnalysisResult {
        val newBindingCtx = elementBindingTrace.stackedContext
        return when {
            oldResult.isError() -> AnalysisResult.internalError(newBindingCtx, oldResult.error)
            newResult.isError() -> AnalysisResult.internalError(newBindingCtx, newResult.error)
            else -> AnalysisResult.success(
                newBindingCtx,
                oldResult.moduleDescriptor,
                oldResult.shouldGenerateCode
            )
        }
    }

    private fun analyze(
        analyzableElement: KtElement,
        bindingTrace: BindingTrace?,
        callback: DiagnosticSink.DiagnosticsCallback?
    ): AnalysisResult {
        ProgressIndicatorProvider.checkCanceled()

        val project = analyzableElement.project
        if (DumbService.isDumb(project)) {
            return AnalysisResult.EMPTY
        }

        moduleDescriptor.assertValid()
        try {
            return KotlinResolveDataProvider.analyze(
                project,
                globalContext,
                moduleDescriptor,
                resolveSession,
                codeFragmentAnalyzer,
                bodyResolveCache,
                analyzableElement,
                bindingTrace,
                callback
            )
        } catch (e: ProcessCanceledException) {
            throw e
        } catch (e: IndexNotReadyException) {
            throw e
        } catch (e: Throwable) {
            DiagnosticUtils.throwIfRunningOnServer(e)
            LOG.error(e)

            return AnalysisResult.internalError(BindingContext.EMPTY, e)
        }
    }
}

private class MergedDiagnostics(val diagnostics: Collection<Diagnostic>, override val modificationTracker: ModificationTracker) :
    Diagnostics {
    @Suppress("UNCHECKED_CAST")
    private val elementsCache = DiagnosticsElementsCache(this) { true }

    override fun all() = diagnostics

    override fun forElement(psiElement: PsiElement): MutableCollection<Diagnostic> = elementsCache.getDiagnostics(psiElement)

    override fun noSuppression() = this
}

/**
 * Keep in mind: trace fallbacks to [resolveContext] (is used during resolve) that does not have any
 * traces of earlier resolve for this [element]
 *
 * When trace turned into [BindingContext] it fallbacks to [parentContext]:
 * It is expected that all slices specific to [element] (and its descendants) are stored in this binding context
 * and for the rest elements it falls back to [parentContext].
 */
private class StackedCompositeBindingContextTrace(
    val depth: Int, // depth of stack over original ktFile bindingContext
    val element: KtElement,
    val resolveContext: BindingContext,
    val parentContext: BindingContext
) : DelegatingBindingTrace(
    resolveContext,
    "Stacked trace for resolution of $element",
    allowSliceRewrite = true
) {
    /**
     * Effectively StackedCompositeBindingContext holds up-to-date and partially outdated contexts (parentContext)
     *
     * The most up-to-date results for element are stored here (in a DelegatingBindingTrace#map)
     *
     * Note: It does not delete outdated results rather hide it therefore there is some extra memory footprint.
     *
     * Note: stackedContext differs from DelegatingBindingTrace#bindingContext:
     *      if result is not present in this context it goes to parentContext rather to resolveContext
     *      diagnostics are aggregated from this context and parentContext
     */
    val stackedContext = StackedCompositeBindingContext()

    /**
     * All diagnostics from parentContext apart this diagnostics this belongs to the element or its descendants
     */
    val parentDiagnosticsApartElement: Collection<Diagnostic> = run {
        val all = parentContext.diagnostics.all()
        val filtered = all.filter { it.psiElement == element && selfDiagnosticToHold(it) } + all.filter { it.psiElement.parentsWithSelf.none { e -> e == element } }
        filtered
    }

    inner class StackedCompositeBindingContext : BindingContext {
        var cachedDiagnostics: Diagnostics? = null

        fun bindingTrace(): StackedCompositeBindingContextTrace = this@StackedCompositeBindingContextTrace

        fun element(): KtElement = this@StackedCompositeBindingContextTrace.element

        fun depth(): Int = this@StackedCompositeBindingContextTrace.depth

        // to prevent too deep stacked binding context
        fun isIncrementalAnalysisApplicable(): Boolean = this@StackedCompositeBindingContextTrace.depth < 16

        override fun getDiagnostics(): Diagnostics {
            if (cachedDiagnostics == null) {
                val mergedDiagnostics = mutableSetOf<Diagnostic>()
                mergedDiagnostics.addAll(parentDiagnosticsApartElement)
                this@StackedCompositeBindingContextTrace.mutableDiagnostics?.all()?.let {
                    mergedDiagnostics.addAll(it)
                }
                cachedDiagnostics = MergedDiagnostics(mergedDiagnostics, parentContext.diagnostics.modificationTracker)
            }
            return cachedDiagnostics!!
        }

        override fun <K : Any?, V : Any?> get(slice: ReadOnlySlice<K, V>, key: K): V? {
            return selfGet(slice, key) ?: parentContext.get(slice, key)
        }

        override fun getType(expression: KtExpression): KotlinType? {
            val typeInfo = get(BindingContext.EXPRESSION_TYPE_INFO, expression)
            return typeInfo?.type
        }

        override fun <K, V> getKeys(slice: WritableSlice<K, V>): Collection<K> {
            val keys = map.getKeys(slice)
            val fromParent = parentContext.getKeys(slice)
            if (keys.isEmpty()) return fromParent
            if (fromParent.isEmpty()) return keys

            return keys + fromParent
        }

        override fun <K : Any?, V : Any?> getSliceContents(slice: ReadOnlySlice<K, V>): ImmutableMap<K, V> {
            return ImmutableMap.copyOf(parentContext.getSliceContents(slice) + map.getSliceContents(slice))
        }

        override fun addOwnDataTo(trace: BindingTrace, commitDiagnostics: Boolean) = throw UnsupportedOperationException()
    }

    override fun clear() {
        super.clear()
        stackedContext.cachedDiagnostics = null
    }

    companion object {
        private fun selfDiagnosticToHold(d: Diagnostic): Boolean {
            @Suppress("MoveVariableDeclarationIntoWhen")
            val positioningStrategy = d.factory.safeAs<DiagnosticFactoryWithPsiElement<*, *>>()?.positioningStrategy
            return when (positioningStrategy) {
                DECLARATION_WITH_BODY -> false
                else -> true
            }
        }
    }
}

private object KotlinResolveDataProvider {
    fun findAnalyzableParent(element: KtElement): KtElement? {
        if (element is KtFile) return element

        @Suppress("MoveVariableDeclarationIntoWhen")
        val topmostElement = element.findTopmostParentInFile {
            it is KtNamedFunction ||
            it is KtAnonymousInitializer ||
            it is KtProperty ||
            it is KtImportDirective ||
            it is KtPackageDirective ||
            it is KtCodeFragment ||
            // TODO: Non-analyzable so far, add more granular analysis
            it is KtAnnotationEntry ||
            it is KtTypeConstraint ||
            it is KtSuperTypeList ||
            it is KtTypeParameter ||
            it is KtParameter ||
            it is KtTypeAlias
        } as KtElement?

        // parameters and supertype lists are not analyzable by themselves, but if we don't count them as topmost, we'll stop inside, say,
        // object expressions inside arguments of super constructors of classes (note that classes themselves are not topmost elements)
        val analyzableElement = when (topmostElement) {
            is KtAnnotationEntry,
            is KtTypeConstraint,
            is KtSuperTypeList,
            is KtTypeParameter,
            is KtParameter -> topmostElement.findParentInFile { it is KtClassOrObject || it is KtCallableDeclaration } as? KtElement?
            else -> topmostElement
        }
        // Primary constructor should never be returned
        if (analyzableElement is KtPrimaryConstructor) return analyzableElement.getContainingClassOrObject()
        // Class initializer should be replaced by containing class to provide full analysis
        if (analyzableElement is KtClassInitializer) return analyzableElement.containingDeclaration
        return analyzableElement
        // if none of the above worked, take the outermost declaration
            ?: element.findTopmostParentOfType<KtDeclaration>()
            // if even that didn't work, take the whole file
            ?: element.containingFile as? KtFile
    }

    fun analyze(
        project: Project,
        globalContext: GlobalContext,
        moduleDescriptor: ModuleDescriptor,
        resolveSession: ResolveSession,
        codeFragmentAnalyzer: CodeFragmentAnalyzer,
        bodyResolveCache: BodyResolveCache,
        analyzableElement: KtElement,
        bindingTrace: BindingTrace?,
        callback: DiagnosticSink.DiagnosticsCallback?
    ): AnalysisResult {
        try {
            if (analyzableElement is KtCodeFragment) {
                val bodyResolveMode = BodyResolveMode.PARTIAL_FOR_COMPLETION
                val trace: BindingTrace = codeFragmentAnalyzer.analyzeCodeFragment(analyzableElement, bodyResolveMode)
                val bindingContext = trace.bindingContext
                return AnalysisResult.success(bindingContext, moduleDescriptor)
            }

            val trace = bindingTrace ?: DelegatingBindingTrace(
                resolveSession.bindingContext,
                "Trace for resolution of $analyzableElement",
                allowSliceRewrite = true
            )

            val moduleInfo = analyzableElement.containingKtFile.getModuleInfo()

            val targetPlatform = moduleInfo.platform

            try {
                trace.resetCallback()
                callback?.let {
                    trace.setCallback(it)
                }
                /*
                Note that currently we *have* to re-create LazyTopDownAnalyzer with custom trace in order to disallow resolution of
                bodies in top-level trace (trace from DI-container).
                Resolving bodies in top-level trace may lead to memory leaks and incorrect resolution, because top-level
                trace isn't invalidated on in-block modifications (while body resolution surely does)

                Also note that for function bodies, we'll create DelegatingBindingTrace in ResolveElementCache anyways
                (see 'functionAdditionalResolve'). However, this trace is still needed, because we have other
                codepaths for other KtDeclarationWithBodies (like property accessors/secondary constructors/class initializers)
                 */
                val lazyTopDownAnalyzer = createContainerForLazyBodyResolve(
                    //TODO: should get ModuleContext
                    globalContext.withProject(project).withModule(moduleDescriptor),
                    resolveSession,
                    trace,
                    targetPlatform,
                    bodyResolveCache,
                    targetPlatform.findAnalyzerServices(project),
                    analyzableElement.languageVersionSettings,
                    IdeaModuleStructureOracle(),
                    IdeMainFunctionDetectorFactory(),
                    IdeSealedClassInheritorsProvider
            ).get<LazyTopDownAnalyzer>()

                lazyTopDownAnalyzer.analyzeDeclarations(TopDownAnalysisMode.TopLevelDeclarations, listOf(analyzableElement))
            } finally {
                trace.resetCallback()
            }

            return AnalysisResult.success(trace.bindingContext, moduleDescriptor)
        } catch (e: ProcessCanceledException) {
            throw e
        } catch (e: IndexNotReadyException) {
            throw e
        } catch (e: Throwable) {
            DiagnosticUtils.throwIfRunningOnServer(e)
            LOG.error(e)

            return AnalysisResult.internalError(BindingContext.EMPTY, e)
        }
    }
}
