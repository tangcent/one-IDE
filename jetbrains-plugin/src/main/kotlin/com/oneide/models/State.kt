package com.oneide.models

import com.fasterxml.jackson.annotation.JsonInclude

@JsonInclude(JsonInclude.Include.NON_DEFAULT)
data class FileState(
    val filePath: String = "",
    val cursor: Int = 0,
    val column: Int = 0,
    val isActive: Boolean = false
)

@JsonInclude(JsonInclude.Include.NON_EMPTY)
data class FolderState(
    val path: String = "",
    val openedFiles: MutableList<FileState> = mutableListOf(),
    var activeFile: String? = null,
    val subFolders: MutableList<FolderState> = mutableListOf()
)

@JsonInclude(JsonInclude.Include.NON_EMPTY)
data class State(
    val timestamp: Long = 0,
    val source: String = "",
    val ide: String = "",
    val root: FolderState = FolderState()
)
