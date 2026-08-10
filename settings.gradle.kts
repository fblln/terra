// Isolated Projects: no project may configure another. That rules out the usual
// `subprojects { }` block in the root build, so shared decisions move here —
// plugin versions via pluginManagement, repositories via dependencyResolutionManagement
// — and each project then configures only itself.

pluginManagement {
    repositories {
        gradlePluginPortal()
        mavenCentral()
    }
    plugins {
        kotlin("jvm") version "2.4.10"
    }
}

dependencyResolutionManagement {
    // Declaring repositories in a project build file would be a cross-project
    // assumption; fail loudly rather than let one drift in.
    repositoriesMode = RepositoriesMode.FAIL_ON_PROJECT_REPOS
    repositories { mavenCentral() }
}

rootProject.name = "terra"

include("junit", "cli", "demo")
