plugins {
 id("java")
 id("com.github.spotbugs") version "6.0.7"
 id("jacoco")
}

group = "com.aureleconomy"
version = "1.4.5"

java {
 toolchain.languageVersion.set(JavaLanguageVersion.of(25))
}

repositories {
 mavenCentral()
 maven("https://repo.papermc.io/repository/maven-public/")
 maven("https://jitpack.io")
}

dependencies {
 compileOnly("io.papermc.paper:paper-api:26.1.2.build.64-stable")
 compileOnly("com.github.MilkBowl:VaultAPI:1.7") {
 exclude(group = "org.bukkit", module = "bukkit")
 }

 // Test dependencies
 testImplementation("org.junit.jupiter:junit-jupiter:5.10.2")
 testImplementation("org.mockito:mockito-inline:5.11.0")
 testImplementation("org.mockito:mockito-junit-jupiter:5.14.2")
 testImplementation("io.papermc.paper:paper-api:26.1.2.build.64-stable")
 testImplementation("net.kyori:adventure-api:4.17.0")
 testRuntimeOnly("org.junit.platform:junit-platform-launcher")
 testRuntimeOnly("org.xerial:sqlite-jdbc:3.45.1.0")
}

spotbugs {
 effort.set(com.github.spotbugs.snom.Effort.MAX)
 reportLevel.set(com.github.spotbugs.snom.Confidence.HIGH)
}

tasks.spotbugsMain {
 reports.create("html") {
 required.set(true)
 }
}

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
 minimum = "0.50".toBigDecimal()
 }
 }
 }
}

tasks.check {
 dependsOn(tasks.jacocoTestCoverageVerification)
}

tasks.withType<JavaCompile>().configureEach {
 options.encoding = Charsets.UTF_8.name()
 options.release = 25
}

tasks.withType<ProcessResources>().configureEach {
 filteringCharset = Charsets.UTF_8.name()
}

tasks.test {
 useJUnitPlatform()
 // Load Mockito as a Java agent so ByteBuddy can mock final classes on JDK 25+
 jvmArgs("--add-opens", "java.base/java.lang=ALL-UNNAMED",
  "--add-opens", "java.base/java.lang.reflect=ALL-UNNAMED",
  "--add-opens", "java.base/sun.reflect=ALL-UNNAMED",
  "--add-opens", "java.base/java.util=ALL-UNNAMED")
}
