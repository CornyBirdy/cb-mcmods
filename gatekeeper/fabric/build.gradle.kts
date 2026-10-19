plugins {
    id("net.fabricmc.fabric-loom") version "1.18-SNAPSHOT"
}

group = "dev.cornybirdy"
version = (findProperty("releaseVersion") as String?) ?: "1.0.0"

val minecraftVersion = property("minecraft_version") as String

dependencies {
    minecraft("com.mojang:minecraft:$minecraftVersion")
    implementation("net.fabricmc:fabric-loader:${property("loader_version")}")
    implementation("net.fabricmc.fabric-api:fabric-api:${property("fabric_api_version")}")
}

fabricApi {
    // A headless server runs src/gametest on every build, so CI catches broken mixins and toggles.
    configureTests {
        createSourceSet.set(true)
        modId.set("gatekeeper-gametest")
        enableClientGameTests.set(false)
        eula.set(true)
    }
}

java {
    toolchain.languageVersion.set(JavaLanguageVersion.of(25))
}

tasks.withType<JavaCompile>().configureEach {
    options.encoding = "UTF-8"
    options.release.set(25)
}

tasks.processResources {
    val props = mapOf("version" to project.version, "minecraft_version" to minecraftVersion)
    inputs.properties(props)
    filesMatching("fabric.mod.json") {
        expand(props)
    }
}

tasks.jar {
    archiveFileName.set("FabricGatekeeper-${project.version}.jar")
}
