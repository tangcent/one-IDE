package com.oneide

import com.intellij.openapi.util.IconLoader

object Icons {
    val Logo = IconLoader.getIcon("/icons/one-ide.svg", Icons::class.java)
    val LogoSmall = IconLoader.getIcon("/icons/one-ide.svg", Icons::class.java) // SVG scales, so we can reuse
}
