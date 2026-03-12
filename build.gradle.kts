plugins {
    kotlin("jvm") version "2.3.0" apply false
    id("org.jlleitschuh.gradle.ktlint") version "12.1.2" apply false
    id("com.vanniktech.maven.publish") version "0.30.0" apply false
}

allprojects {
    group = "com.avsystem.justworks"
    version = System.getenv("RELEASE_VERSION")?.removePrefix("v") ?: "0.0.1-SNAPSHOT"

    repositories {
        mavenCentral()
    }

    apply(plugin = "org.jlleitschuh.gradle.ktlint")
}
