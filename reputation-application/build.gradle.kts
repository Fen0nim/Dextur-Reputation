plugins { `java-library` }
dependencies {
    api(project(":reputation-domain"))
    api("net.kyori:adventure-text-minimessage:5.2.0")
    implementation("org.yaml:snakeyaml:2.2")
    implementation("net.kyori:adventure-text-serializer-plain:5.2.0")
}
sourceSets.test { resources.srcDir("../reputation-paper/src/main/resources") }
