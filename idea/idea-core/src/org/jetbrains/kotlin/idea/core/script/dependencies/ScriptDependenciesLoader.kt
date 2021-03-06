/*
 * Copyright 2010-2018 JetBrains s.r.o. Use of this source code is governed by the Apache 2.0 license
 * that can be found in the license/LICENSE.txt file.
 */

package org.jetbrains.kotlin.idea.core.script.dependencies

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.TransactionGuard
import com.intellij.openapi.components.ServiceManager
import com.intellij.openapi.progress.ProgressIndicator
import com.intellij.openapi.progress.Task
import com.intellij.openapi.project.Project
import com.intellij.openapi.roots.ex.ProjectRootManagerEx
import com.intellij.openapi.util.EmptyRunnable
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.util.ui.UIUtil
import org.jetbrains.kotlin.idea.core.script.*
import org.jetbrains.kotlin.idea.util.application.runWriteAction
import org.jetbrains.kotlin.script.*
import java.util.concurrent.ConcurrentHashMap
import kotlin.script.experimental.dependencies.AsyncDependenciesResolver
import kotlin.script.experimental.dependencies.DependenciesResolver

abstract class ScriptDependenciesLoader(
    protected val file: VirtualFile,
    protected val scriptDef: KotlinScriptDefinition,
    protected val project: Project
) {
    companion object {
        private val loaders = ConcurrentHashMap<VirtualFile, ScriptDependenciesLoader>()

        fun updateDependencies(
            file: VirtualFile,
            scriptDef: KotlinScriptDefinition,
            project: Project
        ) {
            val existingLoader = loaders[file]
            if (existingLoader != null) return existingLoader.updateDependencies()

            val newLoader = when (scriptDef.dependencyResolver) {
                is AsyncDependenciesResolver,
                is LegacyResolverWrapper -> AsyncScriptDependenciesLoader(file, scriptDef, project)
                else -> SyncScriptDependenciesLoader(file, scriptDef, project)
            }
            loaders.put(file, newLoader)
            newLoader.updateDependencies()
        }
    }

    fun updateDependencies() {
        if (shouldUseBackgroundThread()) {
            object : Task.Backgroundable(project, "Kotlin: Loading dependencies for ${file.name} ...", true) {
                override fun run(indicator: ProgressIndicator) {
                    loadDependencies()
                }
            }.queue()
        } else {
            loadDependencies()
        }
    }

    protected abstract fun loadDependencies()
    protected abstract fun shouldUseBackgroundThread(): Boolean
    protected abstract fun shouldShowNotification(): Boolean

    protected val contentLoader = ScriptContentLoader(project)
    protected val cache: ScriptDependenciesCache = ServiceManager.getService(project, ScriptDependenciesCache::class.java)

    private val reporter: ScriptReportSink = ServiceManager.getService(project, ScriptReportSink::class.java)

    protected fun processResult(result: DependenciesResolver.ResolveResult) {
        loaders.remove(file)

        if (cache[file] == null) {
            saveDependencies(result)
            attachReportsIfChanged(result)
            return
        }

        val newDependencies = result.dependencies?.adjustByDefinition(scriptDef)
        if (cache[file] != newDependencies) {
            if (shouldShowNotification() && !ApplicationManager.getApplication().isUnitTestMode) {
                file.addScriptDependenciesNotificationPanel(result, project) {
                    saveDependencies(it)
                    attachReportsIfChanged(it)
                }
            } else {
                saveDependencies(result)
                attachReportsIfChanged(result)
            }
        } else {
            attachReportsIfChanged(result)

            if (shouldShowNotification()) {
                file.removeScriptDependenciesNotificationPanel(project)
            }
        }
    }

    private fun attachReportsIfChanged(result: DependenciesResolver.ResolveResult) {
        if (file.getUserData(IdeScriptReportSink.Reports) != result.reports.takeIf { it.isNotEmpty() }) {
            reporter.attachReports(file, result.reports)
        }
    }

    private fun saveDependencies(result: DependenciesResolver.ResolveResult) {
        if (shouldShowNotification()) {
            file.removeScriptDependenciesNotificationPanel(project)
        }

        val dependencies = result.dependencies?.adjustByDefinition(scriptDef) ?: return
        val rootsChanged = cache.hasNotCachedRoots(dependencies)
        if (cache.save(file, dependencies)) {
            file.scriptDependencies = dependencies
        }

        if (rootsChanged) {
            notifyRootsChanged()
        }
    }

    @Suppress("EXPERIMENTAL_FEATURE_WARNING")
    protected fun notifyRootsChanged() {
        val doNotifyRootsChanged = Runnable {
            runWriteAction {
                if (project.isDisposed) return@runWriteAction

                ProjectRootManagerEx.getInstanceEx(project)?.makeRootsChange(EmptyRunnable.getInstance(), false, true)
                ScriptDependenciesModificationTracker.getInstance(project).incModificationCount()
            }
        }

        if (ApplicationManager.getApplication().isUnitTestMode) {
            UIUtil.invokeLaterIfNeeded(doNotifyRootsChanged)
        } else {
            TransactionGuard.getInstance().submitTransactionLater(project, doNotifyRootsChanged)
        }
    }
}
