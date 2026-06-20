rootProject.name = "Aurelium"

// Common module (shared code — no JAR produced)
include("common")

// Version-specific modules
include("v26_2")
include("v26_1_2")
include("v1_21_11")

project(":common").projectDir = file("versions/common")
project(":v26_2").projectDir = file("versions/26.2")
project(":v26_1_2").projectDir = file("versions/26.1.2")
project(":v1_21_11").projectDir = file("versions/1.21.11")
