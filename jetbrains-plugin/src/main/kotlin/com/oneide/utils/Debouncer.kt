package com.oneide.utils

import com.intellij.util.concurrency.AppExecutorUtil
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit

/**
 * Utility class for debouncing actions.
 * Ensures that an action is only executed after a specified delay has passed without any new calls.
 */
open class Debouncer(private val defaultDelay: Long = 300) {
    private var scheduledFuture: ScheduledFuture<*>? = null

    /**
     * Schedules the action to be executed after the delay.
     * If a previous action was scheduled, it is cancelled.
     *
     * @param delay The delay in milliseconds
     * @param action The action to execute
     */
    @Synchronized
    open fun debounce(delay: Long = defaultDelay, action: () -> Unit) {
        scheduledFuture?.cancel(false)
        scheduledFuture = AppExecutorUtil.getAppScheduledExecutorService().schedule({
            try {
                action()
            } catch (e: Exception) {
                Logger.error("Error in debounced task", e)
            }
        }, delay, TimeUnit.MILLISECONDS)
    }

    /**
     * Cancels the currently scheduled action, if any.
     */
    @Synchronized
    fun cancel() {
        scheduledFuture?.cancel(false)
        scheduledFuture = null
    }
}
