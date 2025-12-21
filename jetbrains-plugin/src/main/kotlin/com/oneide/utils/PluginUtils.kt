package com.oneide.utils

import com.intellij.ide.plugins.PluginManagerCore
import com.intellij.openapi.extensions.PluginId

object PluginUtils {
    /**
     * Checks if a plugin with the given ID is installed and enabled.
     */
    fun isPluginInstalled(id: String): Boolean {
        val pluginId = PluginId.getId(id)
        val plugin = PluginManagerCore.getPlugin(pluginId)
        return plugin != null && !PluginManagerCore.isDisabled(pluginId)
    }
}
