pluginManagement {
    repositories {
        gradlePluginPortal()
        mavenCentral()
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        mavenCentral()
    }
}

rootProject.name = "klimiter"

include("klimiter-core")
include("klimiter-redis")
include("klimiter-service")
include(":klimiter-architecture-tests")
