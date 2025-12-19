package com.oneide.models

import kotlin.random.Random

object IdeMetaData {
    val id: String = generateRandomId()
    const val ide: String = "jetbrains"

    @Volatile
    var lastKnownTimestamp: Long = 0

    private fun generateRandomId(): String {
        val chars = "0123456789abcdefghijklmnopqrstuvwxyz"
        return (1..6)
            .map { chars[Random.nextInt(chars.length)] }
            .joinToString("")
    }
}
