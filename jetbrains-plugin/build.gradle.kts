plugins {
    id("org.jetbrains.intellij") version "1.17.2"
    kotlin("jvm") version "1.9.22"
}

group = "com.itangcent.oneide"
version = "1.1.6"

repositories {
    mavenCentral()
}

intellij {
    version.set("2023.3")
    downloadSources.set(true)
}

// Disable self-update check that fails build due to GitHub network issues
tasks {
    initializeIntelliJPlugin {
        enabled = false
    }
    // buildSearchableOptions requires extra memory and often OOM on Windows, skip it
    buildSearchableOptions {
        enabled = false
    }
}

dependencies {
    implementation("com.google.code.gson:gson:2.10.1")
    implementation("com.fasterxml.jackson.module:jackson-module-kotlin:2.15.3")
    testImplementation("junit:junit:4.13.2")
}

// IntelliJ Platform 2023.3 (since-build 233) requires Java 17. Compile to the same
// bytecode target even when the build runs on a newer JDK so the test runner (Java 17)
// can load the produced classes.
java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}

kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
    }
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
