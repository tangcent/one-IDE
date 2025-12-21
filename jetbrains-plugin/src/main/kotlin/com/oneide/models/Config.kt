package com.oneide.models
import com.fasterxml.jackson.annotation.JsonIgnoreProperties

import com.fasterxml.jackson.annotation.JsonIgnore

@JsonIgnoreProperties(ignoreUnknown = true)
data class Config(
    var excludeFiles: List<String> = emptyList(),
    var excludeGitIgnore: Boolean = false,
    
    @JsonIgnore
    var syncRules: Boolean = true,
    @JsonIgnore
    var currentTool: String = "Auto"
)
