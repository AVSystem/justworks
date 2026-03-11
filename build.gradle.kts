plugins {
    kotlin("jvm") version "2.3.0" apply false
    id("org.jlleitschuh.gradle.ktlint") version "12.1.2"
}

allprojects {
    group = "com.avsystem"
    version = "0.0.1"

    repositories {
        mavenCentral()
    }

    apply(plugin = "org.jlleitschuh.gradle.ktlint")
}
