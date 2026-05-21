plugins {
 id("java")
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
}

tasks.withType<JavaCompile>().configureEach {
 options.encoding = Charsets.UTF_8.name()
 options.release = 25
}

tasks.withType<ProcessResources>().configureEach {
 filteringCharset = Charsets.UTF_8.name()
}
