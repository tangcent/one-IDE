package com.oneide

import java.nio.file.Path
import java.nio.file.Paths

/**
 * Global config variables
 */
object OneIde {
    var oneIdeDir: Path = Paths.get(System.getProperty("user.home"), ".one-ide")
}
