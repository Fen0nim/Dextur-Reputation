plugins { `java-library` }
dependencies {
    api(project(":reputation-application"))
    implementation("com.zaxxer:HikariCP:6.3.0")
    implementation("org.mariadb.jdbc:mariadb-java-client:3.5.6")
    testImplementation("org.testcontainers:mariadb:1.21.3")
    testImplementation("org.testcontainers:mysql:1.21.3")
    testImplementation("org.testcontainers:junit-jupiter:1.21.3")
    testRuntimeOnly("org.slf4j:slf4j-simple:2.0.17")
    testRuntimeOnly("com.mysql:mysql-connector-j:9.5.0")
}
val integrationTest = tasks.register<Test>("integrationTest") {
    description = "Runs mandatory MariaDB integration tests (requires Docker)."
    group = "verification"
    testClassesDirs = sourceSets.test.get().output.classesDirs
    classpath = sourceSets.test.get().runtimeClasspath
    useJUnitPlatform { includeTags("database") }
    shouldRunAfter(tasks.test)
}
tasks.test { useJUnitPlatform { excludeTags("database") } }
tasks.check { dependsOn(integrationTest) }
