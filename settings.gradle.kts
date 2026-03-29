pluginManagement {
    repositories {
        google {
            content {
                includeGroupByRegex("com\\.android.*")
                includeGroupByRegex("com\\.google.*")
                includeGroupByRegex("androidx.*")
            }
        }
        mavenCentral()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
        maven {
            name = "GithubPackages"
            url = uri("https://maven.pkg.github.com/mapconductor/android-sdk")
            credentials {
                username = System.getenv("GPR_USER") ?: ""
                password = System.getenv("GPR_TOKEN") ?: ""
            }
        }
        flatDir {
            dirs(rootDir.resolve("libs"))
        }
    }
}

rootProject.name = "mapconductor-for-here"
include(":sample-app")
