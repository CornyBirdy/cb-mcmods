plugins {
    id("net.fabricmc.fabric-loom") version "1.18-SNAPSHOT"
}

group = "dev.cornybirdy"
version = (findProperty("releaseVersion") as String?) ?: "1.0.0"

val minecraftVersion = property("minecraft_version") as String
val sqliteVersion = property("estate_sqlite_version") as String

repositories {
    mavenCentral()
}

dependencies {
    minecraft("com.mojang:minecraft:$minecraftVersion")
    implementation("net.fabricmc:fabric-loader:${property("loader_version")}")
    implementation("net.fabricmc.fabric-api:fabric-api:${property("fabric_api_version")}")
    // The database driver goes inside the mod jar, so servers only need Estate and Fabric API.
    implementation("org.xerial:sqlite-jdbc:$sqliteVersion")
    include("org.xerial:sqlite-jdbc:$sqliteVersion")
}

// The platform-free claim rules are compiled straight into the mod.
sourceSets.main {
    java.srcDir("../core/src/main/java")
    resources.srcDir("../core/src/main/resources")
}

fabricApi {
    // A headless server runs src/gametest on every build, so CI catches broken mixins and protection holes.
    configureTests {
        createSourceSet.set(true)
        modId.set("estate-gametest")
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
    options.compilerArgs.addAll(listOf("-Xmaxerrs", "1000"))
}

tasks.processResources {
    val props = mapOf("version" to project.version, "minecraft_version" to minecraftVersion)
    inputs.properties(props)
    filesMatching("fabric.mod.json") {
        expand(props)
    }
}

tasks.jar {
    archiveFileName.set("FabricEstate-${project.version}.jar")
}
