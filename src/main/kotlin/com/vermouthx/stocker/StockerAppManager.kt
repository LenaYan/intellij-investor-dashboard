package com.vermouthx.stocker

import com.intellij.openapi.project.Project
import com.intellij.openapi.project.ProjectManagerListener
import java.util.concurrent.ConcurrentHashMap

/**
 * Owns the single application-wide [StockerApp]. Quotes, settings and the message bus
 * are all application-level, so per-project fetchers only multiplied identical HTTP
 * traffic and cross-drove every open project's tables. Projects register when their
 * tool window is created; the shared app shuts down when the last project closes.
 * Visibility is refcounted so refresh pauses only when no tool window is showing.
 */
object StockerAppManager {

    private val sharedApp: StockerApp by lazy { StockerApp() }
    private val projects: MutableSet<Project> = ConcurrentHashMap.newKeySet()
    private val visibleProjects: MutableSet<Project> = ConcurrentHashMap.newKeySet()

    fun myApplication(project: Project?): StockerApp? {
        if (project == null || project !in projects) return null
        return sharedApp
    }

    fun getAllApplications(): Collection<StockerApp> =
        if (projects.isEmpty()) emptyList() else listOf(sharedApp)

    /** Register a project's tool window and start (or join) the shared refresh loop. */
    fun register(project: Project) {
        projects.add(project)
        visibleProjects.add(project)
        sharedApp.resume()
        sharedApp.schedule()
    }

    /** Per-project tool-window visibility; the shared app pauses only when none is visible. */
    fun setToolWindowVisible(project: Project, visible: Boolean) {
        if (visible) visibleProjects.add(project) else visibleProjects.remove(project)
        if (visibleProjects.isEmpty()) sharedApp.pause() else sharedApp.resume()
    }

    fun unregister(project: Project) {
        visibleProjects.remove(project)
        if (projects.remove(project) && projects.isEmpty()) {
            // Last project gone — stop executors and clear tables.
            sharedApp.shutdownThenClear()
        } else if (visibleProjects.isEmpty()) {
            sharedApp.pause()
        }
    }

    class StockerProjectManagerListener : ProjectManagerListener {
        override fun projectClosing(project: Project) {
            unregister(project)
        }
    }
}
