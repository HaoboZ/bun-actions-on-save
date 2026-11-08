package dev.bun.actions

import com.intellij.openapi.components.PersistentStateComponent
import com.intellij.openapi.components.State
import com.intellij.openapi.components.Storage
import com.intellij.openapi.project.Project
import com.intellij.util.xmlb.XmlSerializerUtil

@State(name = "BunActionsOnSave", storages = [Storage("bun-actions.xml")])
class BunSettings : PersistentStateComponent<BunSettings> {
    var configurationMode: String = BunConfigurationMode.AUTOMATIC.name
    var formatEnabled: Boolean = false
    var lintEnabled: Boolean = false
    var showLintWarnings: Boolean = false
    var formatFileTypes: String = ""
    var lintFileTypes: String = ""
    var formatFileTypesConfigured: Boolean = false
    var lintFileTypesConfigured: Boolean = false

    // Retained to migrate projects configured by earlier plugin builds.
    var formatExtensions: String = "js,jsx,ts,tsx,mjs,cjs,mts,cts,json,jsonc,css,scss,html,vue,svelte,md,mdx,yaml,yml,toml,graphql,gql"
    var lintExtensions: String = "js,jsx,ts,tsx,mjs,cjs,mts,cts"
    var bunExecutable: String = "bun"

    override fun getState(): BunSettings = this
    override fun loadState(state: BunSettings) = XmlSerializerUtil.copyBean(state, this)

    companion object {
        fun get(project: Project): BunSettings = project.getService(BunSettings::class.java)
    }
}

enum class BunConfigurationMode { DISABLED, AUTOMATIC, MANUAL }
