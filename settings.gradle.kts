// Сборка Homie: главный экран для приставок Android TV.

pluginManagement {
    repositories {
        google()
        mavenCentral()
    }
}

dependencyResolutionManagement {
    // Репозитории задаются только здесь, модуль своих не добавляет.
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
    }
}

rootProject.name = "Homie"

include(":app")
