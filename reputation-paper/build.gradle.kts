plugins {
    `java-library`
    id("com.gradleup.shadow") version "9.2.2"
}
dependencies {
    implementation(project(":reputation-storage"))
    compileOnly("io.papermc.paper:paper-api:26.2.build.134-stable")
    compileOnly("me.clip:placeholderapi:2.12.3") { isTransitive = false }
    compileOnly("net.luckperms:api:5.4") { isTransitive = false }
    testImplementation("net.luckperms:api:5.4") { isTransitive = false }
    testImplementation("io.papermc.paper:paper-api:26.2.build.134-stable")
    testImplementation("org.mockito:mockito-core:5.20.0")
    testImplementation("me.clip:placeholderapi:2.12.3") { isTransitive = false }
}
tasks.processResources {
    val properties = mapOf("version" to project.version.toString())
    inputs.properties(properties)
    filesMatching("plugin.yml") { expand(properties) }
}
tasks.shadowJar {
    archiveFileName.set("DexturReputation.jar")
    dependencies { exclude(dependency("net.kyori:.*:.*")); exclude(dependency("org.slf4j:.*:.*")) }
    relocate("com.zaxxer.hikari", "org.dextur.reputation.internal.hikari")
    relocate("org.yaml.snakeyaml", "org.dextur.reputation.internal.yaml")
    mergeServiceFiles()
}
tasks.build { dependsOn(tasks.shadowJar) }

// A separate QA plugin, never packaged in DexturReputation.jar.
val runtimeProbe = sourceSets.create("runtimeProbe")
configurations[runtimeProbe.compileClasspathConfigurationName].extendsFrom(configurations.compileClasspath.get())
tasks.register<Jar>("runtimeProbeJar") {
    from(runtimeProbe.output)
    archiveFileName.set("DexturReputationProbe.jar")
}
tasks.register<com.github.jengelman.gradle.plugins.shadow.tasks.ShadowJar>("storageAdminJar") {
    from(sourceSets.main.get().output)
    configurations = listOf(project.configurations.runtimeClasspath.get())
    archiveFileName.set("DexturReputation-StorageAdmin.jar")
    manifest { attributes["Main-Class"] = "org.dextur.reputation.storage.StorageAdmin" }
    relocate("com.zaxxer.hikari", "org.dextur.reputation.internal.hikari")
    relocate("org.yaml.snakeyaml", "org.dextur.reputation.internal.yaml")
    mergeServiceFiles()
}
tasks.build { dependsOn("storageAdminJar") }
