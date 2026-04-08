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
    }
}

rootProject.name = "OCR_manga"
include(":app")
include(":opencv")
project(":opencv").projectDir = File("C:\\Users\\admin\\AndroidStudioProjects\\ocrmanga\\app\\src\\main\\assets\\opencv-4.12.0-android-sdk\\OpenCV-android-sdk\\sdk")
