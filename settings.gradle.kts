import java.util.*

pluginManagement {
    repositories {
        // Rolia - mavenLocal() was FIRST here, which means any artifact sitting in the developer's or
        // runner's ~/.m2 silently shadows the real plugin of the same coordinates. That is a genuine
        // supply-chain hole and it buys nothing for a project nobody builds plugins for locally.
        gradlePluginPortal()
        maven {
            name = "canvasmc"
            url = uri("https://maven.canvasmc.io/public")
        }
    }
}

plugins {
    id("org.gradle.toolchains.foojay-resolver-convention") version "1.0.0"
}

if (!file(".git").exists()) {
    val errorText = """
        
        =====================[ ERROR ]=====================
         The Rolia project directory is not a properly cloned Git repository.
         
         In order to build Rolia from source you must clone
         the Rolia repository using Git, not download a code
         zip from GitHub.
         
         Built Rolia jars are available for download at
         https://github.com/Relozan2006/Rolia/releases
         
         See https://github.com/Relozan2006/Rolia/blob/HEAD/policies/CONTRIBUTING.md
         for further information on building and modifying Rolia.
        ===================================================
    """.trimIndent()
    error(errorText)
}

enableFeaturePreview("TYPESAFE_PROJECT_ACCESSORS")

rootProject.name = "rolia"
for (name in listOf("canvas-api", "canvas-server")) {
    val projName = name.lowercase(Locale.ENGLISH)
    include(projName)
    findProject(":$projName")!!.projectDir = file(name)
}

rootDir.listFiles()
    ?.filter { it.isDirectory && (it.name.endsWith("-debug", ignoreCase = true) || it.name.endsWith("-plugin", ignoreCase = true)) }
    ?.forEach { dir ->
        val projName = dir.name.lowercase(Locale.ENGLISH)
        include(projName)
        findProject(":$projName")!!.projectDir = dir
    }

gradle.lifecycle.beforeProject {
    val mcVersion = providers.gradleProperty("mcVersion").get().trim()
    val canvasChannel = providers.gradleProperty("channel").get().trim()
    val canvasBuildNumber = providers.environmentVariable("BUILD_NUMBER").orNull?.trim()?.toInt()
    val versionString = if (canvasBuildNumber == null) {
        "$mcVersion.local-SNAPSHOT"
    } else {
        "$mcVersion.build.$canvasBuildNumber-${canvasChannel.lowercase()}"
    }
    version = versionString
}
