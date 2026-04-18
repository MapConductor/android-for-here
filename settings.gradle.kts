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
        maven {
            name = "GithubPackages-core"
            url = uri("https://maven.pkg.github.com/MapConductor/android-sdk-core")
            credentials {
                username = System.getenv("GPR_USER") ?: System.getenv("GITHUB_ACTOR") ?: ""
                password = System.getenv("GPR_TOKEN") ?: System.getenv("GITHUB_TOKEN") ?: ""
            }
        }
    }
}

rootProject.name = "android-for-here"
include(":sample-app")
