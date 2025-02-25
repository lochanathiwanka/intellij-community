package com.jetbrains.packagesearch.intellij.plugin.ui.toolwindow.models

import com.intellij.openapi.module.Module

internal data class PackagesToUpgrade(
    val upgradesByModule: Map<Module, Set<PackageUpgradeInfo>>
) {

    val allUpdates by lazy { upgradesByModule.values.flatten() }

    fun getUpdatesForModule(moduleModel: ModuleModel) =
        upgradesByModule[moduleModel.projectModule.nativeModule]?.toList() ?: emptyList()

    data class PackageUpgradeInfo(
        val packageModel: PackageModel.Installed,
        val usageInfo: DependencyUsageInfo,
        val targetVersion: PackageVersion.Named
    )

    companion object {

        val EMPTY = PackagesToUpgrade(emptyMap())
    }
}
