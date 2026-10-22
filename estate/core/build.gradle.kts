plugins {
    `java-library`
}

group = "dev.cornybirdy"
version = (findProperty("releaseVersion") as String?) ?: "1.0.0"

repositories {
    mavenCentral()
}

val sqliteVersion = property("estate_sqlite_version") as String

dependencies {
    // Minecraft and Paper both ship Gson and SLF4J, so the core only compiles against them.
    compileOnly("com.google.code.gson:gson:2.13.1")
    compileOnly("org.slf4j:slf4j-api:2.0.17")

    testImplementation(platform("org.junit:junit-bom:5.12.2"))
    testImplementation("org.junit.jupiter:junit-jupiter")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
    testImplementation("com.google.code.gson:gson:2.13.1")
    testImplementation("org.slf4j:slf4j-api:2.0.17")
    testImplementation("org.xerial:sqlite-jdbc:$sqliteVersion")
}

java {
    toolchain.languageVersion.set(JavaLanguageVersion.of(25))
}

tasks.withType<JavaCompile>().configureEach {
    options.encoding = "UTF-8"
    options.release.set(25)
}

tasks.test {
    useJUnitPlatform()
    testLogging {
        events("failed")
        exceptionFormat = org.gradle.api.tasks.testing.logging.TestExceptionFormat.FULL
    }
}
