pluginManagement {
    repositories {
        maven("https://maven.fabricmc.net/") { name = "Fabric" }
        mavenCentral()
        gradlePluginPortal()
    }
}

rootProject.name = "cornybirdy-mods"

// Every mod lives in its own folder with one module per platform, e.g. corpes/paper is :corpes-paper.
// Estate keeps its claim rules in a plain Java core/ module (no Minecraft code) so a Paper port can share them.
val mods = mapOf(
    "corpes" to listOf("paper", "fabric"),
    "gatekeeper" to listOf("paper", "fabric"),
    "estate" to listOf("core", "fabric"),
)
for ((mod, platforms) in mods) {
    for (platform in platforms) {
        include("$mod-$platform")
        project(":$mod-$platform").projectDir = file("$mod/$platform")
    }
}
