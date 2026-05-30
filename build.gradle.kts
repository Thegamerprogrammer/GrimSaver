plugins {
    alias(libs.plugins.fabric.loom)
    alias(libs.plugins.kotlin.jvm)
}

base {
    archivesName = providers.gradleProperty("archives_base_name").get()
    version = providers.gradleProperty("mod_version").get()
    group = providers.gradleProperty("maven_group").get()
}

repositories {
    mavenCentral()
    mavenLocal()
    maven { name = "CCBlueX Releases"; url = uri("https://maven.ccbluex.net/releases") }
    maven { name = "CCBlueX Snapshots"; url = uri("https://maven.ccbluex.net/snapshots") }
    maven { name = "Fabric"; url = uri("https://maven.fabricmc.net/") }
}

dependencies {
    testImplementation(kotlin("test"))
    add("minecraft", libs.minecraft)
    add("api", libs.fabric.loader)
    add("api", libs.fabric.api)
    add("api", libs.fabric.kotlin)
}

tasks.processResources {
    val props = mapOf(
        "version" to providers.gradleProperty("mod_version").get(),
        "minecraft_version" to providers.gradleProperty("mod_mc_version").get(),
        "fabric_version" to libs.versions.fabric.api.get(),
        "loader_version" to libs.versions.fabric.loader.get(),
        "fabric_kotlin_version" to libs.versions.fabric.kotlin.get()
    )
    inputs.properties(props)
    filesMatching("fabric.mod.json") { expand(props) }
}

java {
    withSourcesJar()
    toolchain { languageVersion.set(JavaLanguageVersion.of(libs.versions.jdk.get().toInt())) }
}

kotlin {
    compilerOptions {
        suppressWarnings = true
        jvmToolchain(libs.versions.jdk.get().toInt())
        freeCompilerArgs.add("-Xexplicit-backing-fields")
        freeCompilerArgs.add("-Xcontext-parameters")
    }
}
