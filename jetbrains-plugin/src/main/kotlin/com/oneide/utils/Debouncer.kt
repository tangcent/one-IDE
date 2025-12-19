package com.oneide.utils

import com.intellij.util.concurrency.AppExecutorUtil
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit

class Debouncer(private val defaultDelay: Long = 300) {
    private var scheduledFuture: ScheduledFuture<*>? = null

    @Synchronized
    fun debounce(delay: Long = defaultDelay, action: () -> Unit) {
        scheduledFuture?.cancel(false)
        scheduledFuture = AppExecutorUtil.getAppScheduledExecutorService().schedule({
            try {
                action()
            } catch (e: Exception) {
                Logger.error("Error in debounced task", e)
            }
        }, delay, TimeUnit.MILLISECONDS)
    }

    @Synchronized
    fun cancel() {
        scheduledFuture?.cancel(false)
        scheduledFuture = null
    }
}
