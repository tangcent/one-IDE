package com.oneide.models

import com.intellij.openapi.application.ApplicationNamesInfo
import com.intellij.openapi.components.Service
import com.intellij.openapi.project.Project
import kotlin.random.Random

@Service(Service.Level.PROJECT)
class IdeMetaData(private val project: Project) {
    val id: String = generateRandomId()
    val ide: String = ApplicationNamesInfo.getInstance().fullProductName

    @Volatile
    var lastCheckPoint: Long = 0

    private fun generateRandomId(): String {
        val chars = "0123456789abcdefghijklmnopqrstuvwxyz"
        return (1..6)
            .map { chars[Random.nextInt(chars.length)] }
            .joinToString("")
    }

    companion object {
        fun getInstance(project: Project): IdeMetaData = project.getService(IdeMetaData::class.java)
    }
}
