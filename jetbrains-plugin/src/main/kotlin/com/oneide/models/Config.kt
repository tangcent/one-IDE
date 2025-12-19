package com.oneide.models
import com.fasterxml.jackson.annotation.JsonIgnoreProperties

@JsonIgnoreProperties(ignoreUnknown = true)
data class Config(
    var excludeFiles: List<String> = emptyList(),
    var excludeGitIgnore: Boolean = false
)
