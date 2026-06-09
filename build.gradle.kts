plugins {
    id("java")
    id("io.github.goooler.shadow") version "8.1.8" apply false
    id("com.github.spotbugs") version "6.1.7" apply false
    id("jacoco") apply false
}

group = "com.aureleconomy"
version = "1.5.1"

// Root project doesn't produce a JAR — all builds happen in version subprojects
tasks.compileJava { enabled = false }
tasks.processResources { enabled = false }
tasks.jar { enabled = false }
tasks.compileTestJava { enabled = false }
tasks.test { enabled = false }

// Configure version-specific subprojects (apply shadow, spotbugs, jacoco)
subprojects {
    if (name.startsWith("v")) {
        apply(plugin = "java")
        apply(plugin = "io.github.goooler.shadow")
        apply(plugin = "com.github.spotbugs")
        apply(plugin = "jacoco")

        group = rootProject.group
        version = rootProject.version

        repositories {
            mavenCentral()
            maven("https://repo.papermc.io/repository/maven-public/")
            maven("https://jitpack.io")
        }

        dependencies {
            compileOnly("com.github.MilkBowl:VaultAPI:1.7") {
                exclude(group = "org.bukkit", module = "bukkit")
            }

            // Shaded dependencies (bundled into the plugin JAR)
            implementation("com.zaxxer:HikariCP:5.1.0")
            implementation("com.mysql:mysql-connector-j:8.3.0")
            implementation("com.google.code.gson:gson:2.10.1")

            // Test dependencies
            testImplementation("org.junit.jupiter:junit-jupiter:5.10.2")
            testImplementation("org.mockito:mockito-core:5.23.0")
            testImplementation("org.mockito:mockito-junit-jupiter:5.23.0")
            testRuntimeOnly("org.junit.platform:junit-platform-launcher")
            testRuntimeOnly("org.xerial:sqlite-jdbc:3.45.3.0")
            testRuntimeOnly("net.bytebuddy:byte-buddy-agent:1.17.7")
        }

        // Spotbugs config
        extensions.configure<com.github.spotbugs.snom.SpotBugsExtension> {
            effort.set(com.github.spotbugs.snom.Effort.MAX)
            reportLevel.set(com.github.spotbugs.snom.Confidence.HIGH)
        }
        tasks.named("spotbugsMain") { enabled = false }
        tasks.named("spotbugsTest") { enabled = false }

        // Jacoco config
        tasks.named<org.gradle.testing.jacoco.tasks.JacocoReport>("jacocoTestReport") {
            dependsOn(tasks.test)
            reports {
                xml.required = true
                html.required = true
            }
        }
        tasks.named<org.gradle.testing.jacoco.tasks.JacocoCoverageVerification>("jacocoTestCoverageVerification") {
            violationRules {
                rule {
                    limit {
                        minimum = "0.0".toBigDecimal()
                    }
                }
            }
        }
        tasks.check {
            dependsOn(tasks.named("jacocoTestCoverageVerification"))
        }

        tasks.test {
            useJUnitPlatform()
        }

        tasks.withType<ProcessResources>().configureEach {
            filteringCharset = Charsets.UTF_8.name()
        }

        tasks.build {
            dependsOn(tasks.shadowJar)
        }

        shadowJar {
            relocate("com.zaxxer.hikari", "com.aureleconomy.lib.hikari")
            relocate("com.mysql", "com.aureleconomy.lib.mysql")
            relocate("com.google.gson", "com.aureleconomy.lib.gson")
            archiveClassifier.set("")
        }

        tasks.jar {
            enabled = false
        }
    }
}

tasks.register("buildAll") {
    group = "build"
    description = "Builds the plugin for all supported Minecraft versions"
    dependsOn(subprojects.filter { it.name.startsWith("v") }.map { it.tasks.named("build") })
}
