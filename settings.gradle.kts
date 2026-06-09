rootProject.name = "Aurelium"

// Version-specific modules
include("v26_1_2")
include("v1_21_11")

project(":v26_1_2").projectDir = file("versions/26.1.2")
project(":v1_21_11").projectDir = file("versions/1.21.11")
