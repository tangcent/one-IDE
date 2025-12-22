plugins {
    id("org.jetbrains.intellij") version "1.17.2"
    kotlin("jvm") version "1.9.22"
}

group = "com.itangcent.oneide"
version = "1.0.3"

repositories {
    mavenCentral()
}

intellij {
    version.set("2023.3")
    downloadSources.set(true)
}

dependencies {
    implementation("com.google.code.gson:gson:2.10.1")
    implementation("com.fasterxml.jackson.module:jackson-module-kotlin:2.15.3")
    testImplementation("junit:junit:4.13.2")
}

tasks {
    patchPluginXml {
        sinceBuild.set("233")
        untilBuild.set(provider { null })
        pluginDescription.set(file("parts/pluginDescription.html").readText())
        changeNotes.set(file("parts/pluginChanges.html").readText())
    }

    buildPlugin {
        archiveBaseName.set("one-ide")
    }
}
