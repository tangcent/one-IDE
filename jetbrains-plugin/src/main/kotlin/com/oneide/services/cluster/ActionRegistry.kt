package com.oneide.services.cluster

import com.oneide.models.Role
import com.oneide.utils.Logger

/**
 * Registry for role-based actions.
 * 
 * Business services register actions with specific roles and action names.
 * Roles fire actions through this registry, and only matching listeners are invoked.
 */
class ActionRegistry {
    
    /**
     * Action listeners map: actionName -> list of (role filter, action callback)
     * When role is null, the action is triggered for all roles
     */
    private val actionListeners = mutableMapOf<String, MutableList<ActionEntry>>()
    
    private data class ActionEntry(
        val role: Role?,
        val action: () -> Unit
    )
    
    /**
     * Register an action listener for a specific role and action name.
     * 
     * @param role The role to listen for, or null to listen for all roles
     * @param actionName The name of the action to listen for
     * @param action The callback to invoke when the action occurs
     * @return An unsubscribe function to remove the listener
     */
    fun addAction(role: Role?, actionName: String, action: () -> Unit): () -> Unit {
        val listeners = actionListeners.getOrPut(actionName) { mutableListOf() }
        val entry = ActionEntry(role, action)
        listeners.add(entry)
        return {
            listeners.remove(entry)
        }
    }
    
    /**
     * Fire an action, notifying all registered listeners that match the current role.
     * Called by Role implementations.
     * 
     * @param currentRole The current role firing the action
     * @param actionName The name of the action being fired
     */
    fun fireAction(currentRole: Role, actionName: String) {
        val listeners = actionListeners[actionName] ?: return
        for (entry in listeners.toList()) {
            // Invoke if role is null (all roles) or matches current role
            if (entry.role == null || entry.role == currentRole) {
                try {
                    entry.action()
                } catch (e: Exception) {
                    Logger.error("Action listener error for '$actionName' in role $currentRole", e)
                }
            }
        }
    }
    
    /**
     * Clear all registered action listeners.
     */
    fun clear() {
        actionListeners.clear()
    }
    
    companion object {
        // Standard action names
        const val ACTION_USER_ACTIVITY = "userActivity"
        const val ACTION_HEARTBEAT = "heartbeat"
        const val ACTION_INIT = "init"
    }
}
