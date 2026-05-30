plugins {
 id("java")
 id("com.github.spotbugs") version "6.1.7"
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
 testImplementation("org.mockito:mockito-core:5.23.0")
 testImplementation("net.bytebuddy:byte-buddy-agent:1.18.8")
 testImplementation("org.mockito:mockito-junit-jupiter:5.23.0")
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
 enabled = false
}

tasks.spotbugsTest {
 enabled = false
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
 options.release = 25
 options.compilerArgs.add("--enable-preview")
}

tasks.withType<ProcessResources>().configureEach {
 filteringCharset = Charsets.UTF_8.name()
}

tasks.test {
 useJUnitPlatform()
 jvmArgs(
  "--enable-preview",
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
