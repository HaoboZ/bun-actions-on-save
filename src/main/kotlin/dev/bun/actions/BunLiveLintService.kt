package dev.bun.actions

import com.intellij.codeInsight.daemon.DaemonCodeAnalyzer
import com.intellij.openapi.project.Project
import com.intellij.openapi.startup.StartupActivity

class BunLiveLintStartupActivity : StartupActivity.DumbAware {
    override fun runActivity(project: Project) {
        project.getService(BunLiveLintService::class.java).configurationChanged()
    }
}

class BunLiveLintService(private val project: Project) {
    fun configurationChanged() {
        if (!project.isDisposed) DaemonCodeAnalyzer.getInstance(project).restart("Bun live lint settings changed")
    }
}
