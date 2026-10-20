pluginManagement {
    repositories {
        maven("https://maven.fabricmc.net/") { name = "Fabric" }
        mavenCentral()
        gradlePluginPortal()
    }
}

rootProject.name = "cb-blocklab"

// Every mod lives in its own folder with a paper/ and a fabric/ module, e.g. corpes/paper is :corpes-paper.
for (mod in listOf("corpes", "gatekeeper")) {
    for (platform in listOf("paper", "fabric")) {
        include("$mod-$platform")
        project(":$mod-$platform").projectDir = file("$mod/$platform")
    }
}
