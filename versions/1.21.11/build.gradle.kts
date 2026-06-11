plugins {
    id("com.github.spotbugs")
    id("jacoco")
}

group = "com.aureleconomy"
version = "1.5.1"

java {
    toolchain.languageVersion.set(JavaLanguageVersion.of(21))
}

repositories {
    mavenCentral()
    maven("https://repo.papermc.io/repository/maven-public/")
    maven("https://jitpack.io")
}

dependencies {
    compileOnly("io.papermc.paper:paper-api:1.21.11-R0.1-SNAPSHOT")

    implementation("com.zaxxer:HikariCP:5.1.0")
    implementation("com.mysql:mysql-connector-j:8.3.0")
    implementation("com.google.code.gson:gson:2.10.1")

    testImplementation("org.junit.jupiter:junit-jupiter:5.10.2")
    testImplementation("org.mockito:mockito-core:5.23.0")
    testImplementation("org.mockito:mockito-junit-jupiter:5.23.0")
    testImplementation("io.papermc.paper:paper-api:1.21.11-R0.1-SNAPSHOT")
    testImplementation("net.kyori:adventure-api:4.17.0")
    testImplementation("com.github.MilkBowl:VaultAPI:1.7") {
        exclude(group = "org.bukkit", module = "bukkit")
    }
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
    testRuntimeOnly("org.xerial:sqlite-jdbc:3.45.3.0")
    testRuntimeOnly("net.bytebuddy:byte-buddy-agent:1.17.7")
}

sourceSets {
    main {
        java {
            srcDirs("../../src/main/java", "src/main/java")
        }
        resources {
            // Version-specific dir first (plugin.yml with api-version 1.21)
            // then shared resources (config.yml, messages.yml, web/)
            setSrcDirs(listOf("src/main/resources", "../../src/main/resources"))
        }
    }
    test {
        java {
            srcDirs("../../src/test/java", "src/test/java")
        }
        resources {
            srcDirs("../../src/test/resources")
        }
    }
}

spotbugs {
    effort.set(com.github.spotbugs.snom.Effort.MAX)
    reportLevel.set(com.github.spotbugs.snom.Confidence.HIGH)
}

tasks.spotbugsMain { enabled = false }
tasks.spotbugsTest { enabled = false }

tasks.jacocoTestReport {
    dependsOn(tasks.test)
    reports {
        xml.required = true
        html.required = true
    }
}

tasks.jacocoTestCoverageVerification {
    violationRules {
        rule {
            limit {
                minimum = "0.0".toBigDecimal()
            }
        }
    }
}

tasks.check {
    dependsOn(tasks.jacocoTestCoverageVerification)
}

tasks.withType<JavaCompile>().configureEach {
    options.encoding = Charsets.UTF_8.name()
    options.release = 21
}

tasks.withType<ProcessResources>().configureEach {
    filteringCharset = Charsets.UTF_8.name()
    duplicatesStrategy = DuplicatesStrategy.EXCLUDE
}

tasks.test {
    useJUnitPlatform()
    jvmArgs(
        "-Dnet.bytebuddy.experimental=true",
        "--add-opens", "java.base/java.lang=ALL-UNNAMED",
        "--add-opens", "java.base/java.lang.reflect=ALL-UNNAMED",
        "--add-opens", "java.base/jdk.internal.reflect=ALL-UNNAMED",
        "--add-opens", "java.base/java.util=ALL-UNNAMED"
    )
    doFirst {
        val agent = configurations.testRuntimeClasspath.get().find { it.name.contains("byte-buddy-agent") }
        if (agent != null) {
            jvmArgs("-javaagent:${agent.absolutePath}")
        }
    }
}

tasks.named<com.github.jengelman.gradle.plugins.shadow.tasks.ShadowJar>("shadowJar") {
    archiveBaseName = "Aurelium-1.21.11"
    archiveVersion = "1.5.1"

    // No relocate — ASM compatibility issue with Java 21 + shadow 8.1.8

    manifest {
        attributes("Main-Class" to "com.aureleconomy.AurelEconomy")
        attributes("Implementation-Version" to "1.5.1")
    }

    // After shadow builds the fat JAR, remove any duplicate plugin.yml
    // that may have come from the root resources dir.
    // Paper's PluginRemapper rejects JARs with duplicate entries.
    doLast {
        val jarFile = archiveFile.get().asFile
        val tmpJar = File(jarFile.parentFile, jarFile.name + ".tmp")
        val pluginYml = File("src/main/resources/plugin.yml")

        java.util.zip.ZipFile(jarFile).use { zip ->
            java.util.zip.ZipOutputStream(tmpJar.outputStream()).use { out ->
                val seen = mutableSetOf<String>()
                // Add plugin.yml first (version-specific)
                out.putNextEntry(java.util.zip.ZipEntry("plugin.yml"))
                pluginYml.inputStream().use { it.copyTo(out) }
                out.closeEntry()
                seen.add("plugin.yml")
                // Copy all other entries, skipping duplicates
                zip.entries().asSequence().filter { it.name != "plugin.yml" }.forEach { entry ->
                    if (seen.add(entry.name)) {
                        out.putNextEntry(java.util.zip.ZipEntry(entry.name))
                        if (!entry.isDirectory) {
                            zip.getInputStream(entry).use { it.copyTo(out) }
                        }
                        out.closeEntry()
                    }
                }
            }
        }
        jarFile.delete()
        tmpJar.renameTo(jarFile)
    }
}