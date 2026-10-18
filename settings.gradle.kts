pluginManagement {
    repositories {
        // Local pre-seeded repository (populated via curl; works around sandbox egress limits).
        maven { url = uri("file:///home/hatch/workspace/android/local-repo") }
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}
dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        maven { url = uri("file:///home/hatch/workspace/android/local-repo") }
        google()
        mavenCentral()
    }
}
rootProject.name = "Lumora"
include(":app")
