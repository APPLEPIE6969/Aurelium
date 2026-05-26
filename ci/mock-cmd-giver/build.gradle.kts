plugins {
 id("java")
}

group = "mock-cmd-giver"
version = "1.0"

java {
 toolchain.languageVersion.set(JavaLanguageVersion.of(25))
}

repositories {
 mavenCentral()
 maven("https://repo.papermc.io/repository/maven-public/")
}

dependencies {
 compileOnly("io.papermc.paper:paper-api:26.1.2.build.65-stable")
}

tasks.withType<JavaCompile>().configureEach {
 options.encoding = Charsets.UTF_8.name()
 options.release = 25
}
