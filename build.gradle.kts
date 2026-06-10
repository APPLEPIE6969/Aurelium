plugins {
    id("java")
    id("com.github.jengelman.shadow") version "9.0.0" apply false
    id("com.github.spotbugs") version "6.1.7" apply false
}

group = "com.aureleconomy"
version = "1.5.1"

// Root project doesn't produce a JAR — all builds happen in version subprojects
tasks.compileJava { enabled = false }
tasks.processResources { enabled = false }
tasks.jar { enabled = false }
tasks.compileTestJava { enabled = false }
tasks.test { enabled = false }

// Configure version-specific subprojects (apply java + shadow)
subprojects {
    if (name.startsWith("v")) {
        apply(plugin = "java")
        apply(plugin = "com.github.jengelman.shadow")

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

        tasks.test {
            useJUnitPlatform()
        }

        tasks.withType<ProcessResources>().configureEach {
            filteringCharset = Charsets.UTF_8.name()
        }

        tasks.named("build") {
            dependsOn(tasks.named("shadowJar"))
        }

        tasks.named<Jar>("jar") {
            enabled = false
        }
    }
}

tasks.register("buildAll") {
    group = "build"
    description = "Builds the plugin for all supported Minecraft versions"
    dependsOn(subprojects.filter { it.name.startsWith("v") }.map { it.tasks.named("build") })
}
