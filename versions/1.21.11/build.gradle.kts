plugins {
    id("com.github.spotbugs")
    id("jacoco")
}

group = "com.aureleconomy"
version = "1.5.4-1.21.x"

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
    archiveBaseName = "Aurelium-1.5.4-1.21.x"
    archiveVersion = ""
    archiveClassifier = ""

    // No relocate — ASM compatibility issue with Java 21 + shadow 8.1.8

    manifest {
        attributes("Main-Class" to "com.aureleconomy.AurelEconomy")
        attributes("Implementation-Version" to "1.5.4-1.21.x")
    }

    // Post-process: rewrite the JAR to deduplicate all entries.
    // Paper's PluginRemapper rejects JARs with duplicate entries.
    doLast {
        val jarFile = archiveFile.get().asFile
        val tmpJar = File(jarFile.parentFile, jarFile.name + ".tmp")
        val pluginYml = File(project.projectDir, "src/main/resources/plugin.yml")

        val dedupScript = """
            import zipfile, os, sys
            src = sys.argv[1]
            dst = sys.argv[2]
            plugin = sys.argv[3]
            seen = set()
            with zipfile.ZipFile(src, 'r') as zin, zipfile.ZipFile(dst, 'w', zipfile.ZIP_DEFLATED) as zout:
                zout.write(plugin, 'plugin.yml')
                seen.add('plugin.yml')
                for entry in zin.infolist():
                    if entry.filename not in seen:
                        zout.writestr(entry, zin.read(entry.filename))
                    seen.add(entry.filename)
            os.replace(dst, src)
            """.trimIndent()

        val proc = ProcessBuilder("python3", "-c", dedupScript, jarFile.absolutePath, tmpJar.absolutePath, pluginYml.absolutePath)
            .redirectErrorStream(true)
            .start()
        proc.inputStream.bufferedReader().forEachLine { println(it) }
        proc.waitFor()
        if (proc.exitValue() != 0) {
            throw GradleException("JAR dedup script failed with exit code ${proc.exitValue()}")
        }
    }
}
