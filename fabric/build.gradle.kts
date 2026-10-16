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
    archiveFileName.set("FabricCorpes-${project.version}.jar")
}
