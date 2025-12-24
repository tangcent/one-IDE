package com.oneide.models

import com.fasterxml.jackson.annotation.JsonIgnoreProperties
import com.fasterxml.jackson.annotation.JsonInclude

@JsonInclude(JsonInclude.Include.NON_DEFAULT)
@JsonIgnoreProperties(ignoreUnknown = true)
data class FileState(
    val filePath: String = "",
    val cursor: Int = 0,
    val column: Int = 0,
    val isActive: Boolean = false
)

@JsonInclude(JsonInclude.Include.NON_EMPTY)
@JsonIgnoreProperties(ignoreUnknown = true)
data class FolderState(
    val path: String = "",
    val openedFiles: MutableList<FileState> = mutableListOf()
)

@JsonInclude(JsonInclude.Include.NON_EMPTY)
@JsonIgnoreProperties(ignoreUnknown = true)
data class State(
    val timestamp: Long = 0,
    val source: String = "",
    val ide: String = "",
    val root: FolderState = FolderState()
)

enum class Role {
    LEADER, FOLLOWER, CANDIDATE
}

@JsonInclude(JsonInclude.Include.NON_DEFAULT)
@JsonIgnoreProperties(ignoreUnknown = true)
data class NodeInfo(
    val id: String = "",
    val timestamp: Long = 0,
    val lastHeartbeat: Long = 0
)

@JsonInclude(JsonInclude.Include.NON_DEFAULT)
@JsonIgnoreProperties(ignoreUnknown = true)
data class ClusterState(
    val timestamp: Long = 0,
    val leaderId: String = "",
    val state: State = State()
)

@JsonInclude(JsonInclude.Include.NON_DEFAULT)
@JsonIgnoreProperties(ignoreUnknown = true)
data class CandidatesData(
    val candidates: MutableList<NodeInfo> = mutableListOf()
)
