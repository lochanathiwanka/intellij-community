// Copyright 2000-2021 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license that can be found in the LICENSE file.

package org.jetbrains.kotlin.idea.core

import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.progress.ProgressManager
import com.intellij.psi.*
import com.intellij.psi.impl.CompositeShortNamesCache
import com.intellij.psi.search.GlobalSearchScope
import com.intellij.psi.search.PsiShortNamesCache
import com.intellij.psi.stubs.StringStubIndexExtension
import org.jetbrains.kotlin.asJava.elements.KtLightElement
import org.jetbrains.kotlin.descriptors.*
import org.jetbrains.kotlin.idea.FrontendInternals
import org.jetbrains.kotlin.idea.caches.IDEKotlinBinaryClassCache
import org.jetbrains.kotlin.idea.caches.KotlinShortNamesCache
import org.jetbrains.kotlin.idea.caches.resolve.resolveImportReference
import org.jetbrains.kotlin.idea.caches.resolve.util.getJavaMemberDescriptor
import org.jetbrains.kotlin.idea.caches.resolve.util.resolveToDescriptor
import org.jetbrains.kotlin.idea.codeInsight.forceEnableSamAdapters
import org.jetbrains.kotlin.idea.core.extension.KotlinIndicesHelperExtension
import org.jetbrains.kotlin.idea.resolve.ResolutionFacade
import org.jetbrains.kotlin.idea.resolve.frontendService
import org.jetbrains.kotlin.idea.search.excludeKotlinSources
import org.jetbrains.kotlin.idea.stubindex.*
import org.jetbrains.kotlin.idea.util.CallType
import org.jetbrains.kotlin.idea.util.CallTypeAndReceiver
import org.jetbrains.kotlin.idea.util.application.withPsiAttachment
import org.jetbrains.kotlin.idea.util.receiverTypes
import org.jetbrains.kotlin.idea.util.substituteExtensionIfCallable
import org.jetbrains.kotlin.incremental.KotlinLookupLocation
import org.jetbrains.kotlin.incremental.components.NoLookupLocation
import org.jetbrains.kotlin.lexer.KtTokens
import org.jetbrains.kotlin.load.java.sam.SamAdapterDescriptor
import org.jetbrains.kotlin.name.FqName
import org.jetbrains.kotlin.psi.*
import org.jetbrains.kotlin.psi.psiUtil.contains
import org.jetbrains.kotlin.resolve.BindingContext
import org.jetbrains.kotlin.resolve.deprecation.DeprecationResolver
import org.jetbrains.kotlin.resolve.descriptorUtil.fqNameSafe
import org.jetbrains.kotlin.resolve.scopes.DescriptorKindFilter
import org.jetbrains.kotlin.resolve.scopes.SyntheticScopes
import org.jetbrains.kotlin.resolve.scopes.collectSyntheticStaticFunctions
import org.jetbrains.kotlin.types.KotlinType
import org.jetbrains.kotlin.utils.KotlinExceptionWithAttachments
import org.jetbrains.kotlin.types.isError
import java.lang.reflect.Field
import java.lang.reflect.Modifier

class KotlinIndicesHelper(
    private val resolutionFacade: ResolutionFacade,
    private val scope: GlobalSearchScope,
    visibilityFilter: (DeclarationDescriptor) -> Boolean,
    private val declarationTranslator: (KtDeclaration) -> KtDeclaration? = { it },
    applyExcludeSettings: Boolean = true,
    private val filterOutPrivate: Boolean = true,
    private val file: KtFile? = null
) {

    private val moduleDescriptor = resolutionFacade.moduleDescriptor
    private val project = resolutionFacade.project
    private val scopeWithoutKotlin = scope.excludeKotlinSources() as GlobalSearchScope

    @OptIn(FrontendInternals::class)
    private val descriptorFilter: (DeclarationDescriptor) -> Boolean = filter@{
        if (resolutionFacade.frontendService<DeprecationResolver>().isHiddenInResolution(it)) return@filter false
        if (!visibilityFilter(it)) return@filter false
        if (applyExcludeSettings && it.isExcludedFromAutoImport(project, file)) return@filter false
        true
    }

    fun getTopLevelCallablesByName(name: String): Collection<CallableDescriptor> {
        val declarations = LinkedHashSet<KtNamedDeclaration>()
        declarations.addTopLevelNonExtensionCallablesByName(KotlinFunctionShortNameIndex.getInstance(), name)
        declarations.addTopLevelNonExtensionCallablesByName(KotlinPropertyShortNameIndex.getInstance(), name)
        return declarations
            .flatMap { it.resolveToDescriptors<CallableDescriptor>() }
            .filter { descriptorFilter(it) }
    }

    private fun MutableSet<KtNamedDeclaration>.addTopLevelNonExtensionCallablesByName(
        index: StringStubIndexExtension<out KtNamedDeclaration>,
        name: String
    ) {
        index.get(name, project, scope)
            .filterTo(this) { it.parent is KtFile && it is KtCallableDeclaration && it.receiverTypeReference == null }
    }

    fun getTopLevelExtensionOperatorsByName(name: String): Collection<FunctionDescriptor> {
        return KotlinFunctionShortNameIndex.getInstance().get(name, project, scope)
            .filter { it.parent is KtFile && it.receiverTypeReference != null && it.hasModifier(KtTokens.OPERATOR_KEYWORD) }
            .flatMap { it.resolveToDescriptors<FunctionDescriptor>() }
            .filter { descriptorFilter(it) && it.extensionReceiverParameter != null }
            .distinct()
    }

    fun getMemberOperatorsByName(name: String): Collection<FunctionDescriptor> {
        return KotlinFunctionShortNameIndex.getInstance().get(name, project, scope)
            .filter { it.parent is KtClassBody && it.receiverTypeReference == null && it.hasModifier(KtTokens.OPERATOR_KEYWORD) }
            .flatMap { it.resolveToDescriptors<FunctionDescriptor>() }
            .filter { descriptorFilter(it) && it.extensionReceiverParameter == null }
            .distinct()
    }

    fun processTopLevelCallables(nameFilter: (String) -> Boolean, processor: (CallableDescriptor) -> Unit) {
        fun processIndex(index: StringStubIndexExtension<out KtCallableDeclaration>) {
            for (key in index.getAllKeys(project)) {
                ProgressManager.checkCanceled()
                if (!nameFilter(key.substringAfterLast('.', key))) continue

                for (declaration in index.get(key, project, scope)) {
                    if (declaration.receiverTypeReference != null) continue
                    if (filterOutPrivate && declaration.hasModifier(KtTokens.PRIVATE_KEYWORD)) continue

                    for (descriptor in declaration.resolveToDescriptors<CallableDescriptor>()) {
                        if (descriptorFilter(descriptor)) {
                            processor(descriptor)
                        }
                    }
                }
            }
        }
        processIndex(KotlinTopLevelFunctionFqnNameIndex.getInstance())
        processIndex(KotlinTopLevelPropertyFqnNameIndex.getInstance())
    }

    fun getCallableTopLevelExtensions(
        callTypeAndReceiver: CallTypeAndReceiver<*, *>,
        position: KtExpression,
        bindingContext: BindingContext,
        receiverTypeFromDiagnostic: KotlinType?,
        nameFilter: (String) -> Boolean
    ): Collection<CallableDescriptor> {
        val receiverTypes = callTypeAndReceiver.receiverTypes(
            bindingContext, position, moduleDescriptor, resolutionFacade, stableSmartCastsOnly = false
        )

        return if (receiverTypes == null || receiverTypes.all { it.isError }) {
            if (receiverTypeFromDiagnostic != null)
                getCallableTopLevelExtensions(callTypeAndReceiver, listOf(receiverTypeFromDiagnostic), nameFilter)
            else
                emptyList()
        } else {
            getCallableTopLevelExtensions(callTypeAndReceiver, receiverTypes, nameFilter)
        }
    }

    fun getCallableTopLevelExtensions(
        callTypeAndReceiver: CallTypeAndReceiver<*, *>,
        receiverTypes: Collection<KotlinType>,
        nameFilter: (String) -> Boolean,
        declarationFilter: (KtDeclaration) -> Boolean = { true }
    ): Collection<CallableDescriptor> {
        if (receiverTypes.isEmpty()) return emptyList()

        val topLevelExtensionsIndex = KotlinTopLevelExtensionsByReceiverTypeIndex.INSTANCE
        val suitableTopLevelExtensions = topLevelExtensionsIndex.getSuitableExtensions(
            receiverTypes,
            nameFilter,
            declarationFilter,
            callTypeAndReceiver
        )

        val additionalDescriptors = ArrayList<CallableDescriptor>(0)

        val lookupLocation = this.file?.let { KotlinLookupLocation(it) } ?: NoLookupLocation.FROM_IDE
        for (extension in @Suppress("DEPRECATION") KotlinIndicesHelperExtension.getInstances(project)) {
            extension.appendExtensionCallables(additionalDescriptors, moduleDescriptor, receiverTypes, nameFilter, lookupLocation)
        }

        return if (additionalDescriptors.isNotEmpty())
            suitableTopLevelExtensions + additionalDescriptors
        else
            suitableTopLevelExtensions
    }

    fun getCallableExtensionsDeclaredInObjects(
        callTypeAndReceiver: CallTypeAndReceiver<*, *>,
        receiverTypes: Collection<KotlinType>,
        nameFilter: (String) -> Boolean,
        declarationFilter: (KtDeclaration) -> Boolean = { true }
    ): Collection<CallableDescriptor> {
        if (receiverTypes.isEmpty()) return emptyList()

        val extensionsInObjectsIndex = KotlinExtensionsInObjectsByReceiverTypeIndex.INSTANCE

        return extensionsInObjectsIndex.getSuitableExtensions(
            receiverTypes,
            nameFilter,
            declarationFilter,
            callTypeAndReceiver
        )
    }

    fun resolveTypeAliasesUsingIndex(type: KotlinType, originalTypeName: String): Set<TypeAliasDescriptor> {
        val typeConstructor = type.constructor

        val index = KotlinTypeAliasByExpansionShortNameIndex.INSTANCE
        val out = LinkedHashMap<FqName, TypeAliasDescriptor>()

        fun searchRecursively(typeName: String) {
            ProgressManager.checkCanceled()
            index[typeName, project, scope].asSequence()
                .filter { it in scope }
                .flatMap { it.resolveToDescriptors<TypeAliasDescriptor>().asSequence() }
                .filter { it.expandedType.constructor == typeConstructor }
                .filter { out.putIfAbsent(it.fqNameSafe, it) == null }
                .map { it.name.asString() }
                .forEach(::searchRecursively)
        }

        searchRecursively(originalTypeName)
        return out.values.toSet()
    }

    private fun KotlinExtensionsByReceiverTypeIndex.getSuitableExtensions(
        receiverTypes: Collection<KotlinType>,
        nameFilter: (String) -> Boolean,
        declarationFilter: (KtDeclaration) -> Boolean,
        callTypeAndReceiver: CallTypeAndReceiver<*, *>
    ): Collection<CallableDescriptor> {
        val receiverTypeNames = collectAllNamesOfTypes(receiverTypes)

        val declarations = getAllKeys(project).asSequence()
            .onEach { ProgressManager.checkCanceled() }
            .filter { receiverTypeNameFromKey(it) in receiverTypeNames && nameFilter(callableNameFromKey(it)) }
            .flatMap { get(it, project, scope) }
            .filter(declarationFilter)

        return findSuitableExtensions(declarations, receiverTypes, callTypeAndReceiver.callType)
    }

    private fun possibleTypeAliasExpansionNames(originalTypeName: String): Set<String> {
        val index = KotlinTypeAliasByExpansionShortNameIndex.INSTANCE
        val out = mutableSetOf<String>()

        fun searchRecursively(typeName: String) {
            ProgressManager.checkCanceled()
            index[typeName, project, scope].asSequence()
                .filter { it in scope }
                .mapNotNull { it.name }
                .filter { out.add(it) }
                .forEach(::searchRecursively)
        }

        searchRecursively(originalTypeName)
        return out
    }

    private fun MutableCollection<String>.addTypeNames(type: KotlinType) {
        val constructor = type.constructor
        constructor.declarationDescriptor?.name?.asString()?.let { typeName ->
            add(typeName)
            addAll(possibleTypeAliasExpansionNames(typeName))
        }
        constructor.supertypes.forEach { addTypeNames(it) }
    }

    private fun collectAllNamesOfTypes(types: Collection<KotlinType>): HashSet<String> {
        val receiverTypeNames = HashSet<String>()
        types.forEach { receiverTypeNames.addTypeNames(it) }
        return receiverTypeNames
    }

    /**
     * Check that function or property with the given qualified name can be resolved in given scope and called on given receiver
     */
    private fun findSuitableExtensions(
        declarations: Sequence<KtCallableDeclaration>,
        receiverTypes: Collection<KotlinType>,
        callType: CallType<*>
    ): Collection<CallableDescriptor> {
        val result = LinkedHashSet<CallableDescriptor>()

        fun processDescriptor(descriptor: CallableDescriptor) {
            if (descriptor.extensionReceiverParameter != null && descriptorFilter(descriptor)) {
                result.addAll(descriptor.substituteExtensionIfCallable(receiverTypes, callType))
            }
        }

        declarations.forEach { it.resolveToDescriptors<CallableDescriptor>().forEach(::processDescriptor) }

        return result
    }

    fun getJvmClassesByName(name: String): Collection<ClassDescriptor> {
        return PsiShortNamesCache.getInstance(project).getClassesByName(name, scope)
            .filter { it in scope && it.containingFile != null }
            .mapNotNull { it.resolveToDescriptor(resolutionFacade) }
            .filter(descriptorFilter)
            .toSet()
    }

    fun getKotlinEnumsByName(name: String): Collection<DeclarationDescriptor> {
        return KotlinClassShortNameIndex.getInstance()[name, project, scope]
            .filter { it is KtEnumEntry && it in scope }
            .flatMap { it.resolveToDescriptors<DeclarationDescriptor>() }
            .filter(descriptorFilter)
            .toSet()
    }

    fun processJvmCallablesByName(
        name: String,
        filter: (PsiMember) -> Boolean,
        processor: (CallableDescriptor) -> Unit
    ) {
        val javaDeclarations = getJavaCallables(name, PsiShortNamesCache.getInstance(project))
        val processed = HashSet<CallableDescriptor>()
        for (javaDeclaration in javaDeclarations) {
            ProgressManager.checkCanceled()
            if (javaDeclaration is KtLightElement<*, *>) continue
            if (!filter(javaDeclaration as PsiMember)) continue
            val descriptor = javaDeclaration.getJavaMemberDescriptor(resolutionFacade) as? CallableDescriptor ?: continue
            if (!processed.add(descriptor)) continue
            if (!descriptorFilter(descriptor)) continue
            processor(descriptor)
        }
    }

    /*
     * This is a dirty work-around to filter out results from BrShortNamesCache.
     * BrShortNamesCache creates a synthetic class (LightBrClass), which traverses all annotated properties
     *     in a module inside "myFieldCache" (and in Kotlin light classes too, of course).
     * It triggers the light classes compilation in the UI thread inside our static field import quick-fix.
     */
    private val filteredShortNamesCaches: List<PsiShortNamesCache>? by lazy {
        val shortNamesCache = PsiShortNamesCache.getInstance(project)
        if (shortNamesCache is CompositeShortNamesCache) {
            try {
                fun getMyCachesField(clazz: Class<out PsiShortNamesCache>): Field = try {
                    clazz.getDeclaredField("myCaches")
                } catch (e: NoSuchFieldException) {
                    // In case the class is proguarded
                    clazz.declaredFields.first {
                        Modifier.isPrivate(it.modifiers) && Modifier.isFinal(it.modifiers) && !Modifier.isStatic(it.modifiers)
                                && it.type.isArray && it.type.componentType == PsiShortNamesCache::class.java
                    }
                }

                val myCachesField = getMyCachesField(shortNamesCache::class.java)
                val previousIsAccessible = myCachesField.isAccessible
                try {
                    myCachesField.isAccessible = true
                    @Suppress("UNCHECKED_CAST")
                    return@lazy (myCachesField.get(shortNamesCache) as Array<PsiShortNamesCache>).filter {
                        it !is KotlinShortNamesCache
                                && it::class.java.name != "com.android.tools.idea.databinding.BrShortNamesCache"
                                && it::class.java.name != "com.android.tools.idea.databinding.DataBindingComponentShortNamesCache"
                                && it::class.java.name != "com.android.tools.idea.databinding.DataBindingShortNamesCache"
                    }
                } finally {
                    myCachesField.isAccessible = previousIsAccessible
                }
            } catch (thr: Throwable) {
                // Our dirty hack isn't working
            }
        }

        return@lazy null
    }

    private fun getJavaCallables(name: String, shortNamesCache: PsiShortNamesCache): Sequence<Any> {
        filteredShortNamesCaches?.let { caches -> return getCallablesByName(name, scopeWithoutKotlin, caches) }
        return shortNamesCache.getFieldsByNameUnfiltered(name, scopeWithoutKotlin).asSequence() +
                shortNamesCache.getMethodsByNameUnfiltered(name, scopeWithoutKotlin).asSequence()
    }

    private fun getCallablesByName(name: String, scope: GlobalSearchScope, caches: List<PsiShortNamesCache>): Sequence<Any> {
        return caches.asSequence().flatMap { cache ->
            cache.getMethodsByNameUnfiltered(name, scope) + cache.getFieldsByNameUnfiltered(name, scope).asSequence()
        }
    }

    // getMethodsByName() removes duplicates from returned set of names, which can be excessively slow
    // if the number of candidates is large (KT-16071) and is unnecessary because Kotlin performs its own
    // duplicate filtering later
    private fun PsiShortNamesCache.getMethodsByNameUnfiltered(name: String, scope: GlobalSearchScope): Sequence<PsiMethod> {
        val result = arrayListOf<PsiMethod>()
        processMethodsWithName(name, scope) { result.add(it) }
        return result.asSequence()
    }

    private fun PsiShortNamesCache.getFieldsByNameUnfiltered(name: String, scope: GlobalSearchScope): Sequence<PsiField> {
        val result = arrayListOf<PsiField>()
        processFieldsWithName(name, { field -> result.add(field); true }, scope, null)
        return result.asSequence()
    }

    fun processKotlinCallablesByName(
        name: String,
        filter: (KtNamedDeclaration) -> Boolean,
        processor: (CallableDescriptor) -> Unit
    ) {
        val functions: Sequence<KtCallableDeclaration> = KotlinFunctionShortNameIndex.getInstance().get(name, project, scope).asSequence()
        val properties: Sequence<KtNamedDeclaration> = KotlinPropertyShortNameIndex.getInstance().get(name, project, scope).asSequence()
        val processed = HashSet<CallableDescriptor>()
        for (declaration in functions + properties) {
            ProgressManager.checkCanceled()
            if (!filter(declaration)) continue

            for (descriptor in declaration.resolveToDescriptors<CallableDescriptor>()) {
                if (!processed.add(descriptor)) continue
                if (!descriptorFilter(descriptor)) continue
                processor(descriptor)
            }
        }
    }

    fun getKotlinClasses(
        nameFilter: (String) -> Boolean,
        psiFilter: (KtDeclaration) -> Boolean = { true },
        kindFilter: (ClassKind) -> Boolean = { true }
    ): Collection<ClassDescriptor> {
        val index = KotlinFullClassNameIndex.getInstance()
        return index.getAllKeys(project).asSequence()
            .filter { fqName ->
                ProgressManager.checkCanceled()
                nameFilter(fqName.substringAfterLast('.'))
            }
            .toList()
            .flatMap { fqName ->
                index[fqName, project, scope].flatMap { classOrObject ->
                    classOrObject.resolveToDescriptorsWithHack(psiFilter).filterIsInstance<ClassDescriptor>()
                }
            }
            .filter { kindFilter(it.kind) && descriptorFilter(it) }
    }

    fun getTopLevelTypeAliases(nameFilter: (String) -> Boolean): Collection<TypeAliasDescriptor> {
        val index = KotlinTopLevelTypeAliasFqNameIndex.getInstance()
        return index.getAllKeys(project).asSequence()
            .filter {
                ProgressManager.checkCanceled()
                nameFilter(it.substringAfterLast('.'))
            }
            .toList()
            .flatMap { fqName ->
                index[fqName, project, scope]
                    .flatMap { it.resolveToDescriptors<TypeAliasDescriptor>() }

            }
            .filter(descriptorFilter)
    }

    fun processObjectMembers(
        descriptorKindFilter: DescriptorKindFilter,
        nameFilter: (String) -> Boolean,
        filter: (KtNamedDeclaration, KtObjectDeclaration) -> Boolean,
        processor: (DeclarationDescriptor) -> Unit
    ) {
        fun processIndex(index: StringStubIndexExtension<out KtNamedDeclaration>) {
            for (name in index.getAllKeys(project)) {
                ProgressManager.checkCanceled()
                if (!nameFilter(name)) continue

                for (declaration in index.get(name, project, scope)) {
                    val objectDeclaration = declaration.parent.parent as? KtObjectDeclaration ?: continue
                    if (objectDeclaration.isObjectLiteral()) continue
                    if (filterOutPrivate && declaration.hasModifier(KtTokens.PRIVATE_KEYWORD)) continue
                    if (!filter(declaration, objectDeclaration)) continue
                    for (descriptor in declaration.resolveToDescriptors<CallableDescriptor>()) {
                        if (descriptorKindFilter.accepts(descriptor) && descriptorFilter(descriptor)) {
                            processor(descriptor)
                        }
                    }
                }
            }
        }

        if (descriptorKindFilter.kindMask.and(DescriptorKindFilter.FUNCTIONS_MASK) != 0) {
            processIndex(KotlinFunctionShortNameIndex.getInstance())
        }
        if (descriptorKindFilter.kindMask.and(DescriptorKindFilter.VARIABLES_MASK) != 0) {
            processIndex(KotlinPropertyShortNameIndex.getInstance())
        }
    }

    fun processJavaStaticMembers(
        descriptorKindFilter: DescriptorKindFilter,
        nameFilter: (String) -> Boolean,
        processor: (DeclarationDescriptor) -> Unit
    ) {
        val shortNamesCache = PsiShortNamesCache.getInstance(project)

        val allMethodNames = hashSetOf<String>()
        shortNamesCache.processAllMethodNames(
            { name -> if (nameFilter(name)) allMethodNames.add(name); true },
            scopeWithoutKotlin,
            null
        )
        for (name in allMethodNames) {
            ProgressManager.checkCanceled()

            for (method in shortNamesCache.getMethodsByName(name, scopeWithoutKotlin).filterNot { it is KtLightElement<*, *> }) {
                if (!method.hasModifierProperty(PsiModifier.STATIC)) continue
                if (filterOutPrivate && method.hasModifierProperty(PsiModifier.PRIVATE)) continue
                if (method.containingClass?.parent !is PsiFile) continue // only top-level classes
                val descriptor = method.getJavaMemberDescriptor(resolutionFacade) ?: continue
                val container = descriptor.containingDeclaration as? ClassDescriptor ?: continue
                if (descriptorKindFilter.accepts(descriptor) && descriptorFilter(descriptor)) {
                    processor(descriptor)

                    // SAM-adapter
                    @OptIn(FrontendInternals::class)
                    val syntheticScopes = resolutionFacade.getFrontendService(SyntheticScopes::class.java).forceEnableSamAdapters()
                    val contributedFunctions = container.staticScope.getContributedFunctions(descriptor.name, NoLookupLocation.FROM_IDE)

                    syntheticScopes.collectSyntheticStaticFunctions(contributedFunctions, NoLookupLocation.FROM_IDE)
                        .filterIsInstance<SamAdapterDescriptor<*>>()
                        .firstOrNull { it.baseDescriptorForSynthetic.original == descriptor.original }
                        ?.let { processor(it) }
                }
            }
        }

        val allFieldNames = hashSetOf<String>()
        shortNamesCache.processAllFieldNames({ name -> if (nameFilter(name)) allFieldNames.add(name); true }, scopeWithoutKotlin, null)
        for (name in allFieldNames) {
            ProgressManager.checkCanceled()

            for (field in shortNamesCache.getFieldsByName(name, scopeWithoutKotlin).filterNot { it is KtLightElement<*, *> }) {
                if (!field.hasModifierProperty(PsiModifier.STATIC)) continue
                if (filterOutPrivate && field.hasModifierProperty(PsiModifier.PRIVATE)) continue
                val descriptor = field.getJavaMemberDescriptor(resolutionFacade) ?: continue
                if (descriptorKindFilter.accepts(descriptor) && descriptorFilter(descriptor)) {
                    processor(descriptor)
                }
            }
        }
    }

    private inline fun <reified TDescriptor : Any> KtNamedDeclaration.resolveToDescriptors(): Collection<TDescriptor> {
        return resolveToDescriptorsWithHack { true }.filterIsInstance<TDescriptor>()
    }

    private fun KtNamedDeclaration.resolveToDescriptorsWithHack(
        psiFilter: (KtDeclaration) -> Boolean
    ): Collection<DeclarationDescriptor> {
        val ktFile = containingFile
        if (ktFile !is KtFile) {
            // https://ea.jetbrains.com/browser/ea_problems/219256
            LOG.error(
                KotlinExceptionWithAttachments("KtElement not inside KtFile ($ktFile, is valid: ${ktFile.isValid})")
                    .withAttachment("file", ktFile)
                    .withAttachment("virtualFile", containingFile.virtualFile)
                    .withAttachment("compiledFile", IDEKotlinBinaryClassCache.getInstance().isKotlinJvmCompiledFile(containingFile.virtualFile))
                    .withAttachment("element", this)
                    .withAttachment("type", javaClass)
                    .withPsiAttachment("file.kt", ktFile)
            )

            return emptyList()
        }

        if (ktFile.isCompiled) { //TODO: it's temporary while resolveToDescriptor does not work for compiled declarations
            val fqName = fqName ?: return emptyList()
            return resolutionFacade.resolveImportReference(moduleDescriptor, fqName)
        } else {
            val translatedDeclaration = declarationTranslator(this) ?: return emptyList()
            if (!psiFilter(translatedDeclaration)) return emptyList()

            return listOfNotNull(resolutionFacade.resolveToDescriptor(translatedDeclaration))
        }
    }

    companion object {
        private val LOG = Logger.getInstance(KotlinIndicesHelper::class.java)
    }
}

