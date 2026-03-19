plugins {
    kotlin("jvm")
    `java-gradle-plugin`
    `maven-publish`
}

kotlin {
    jvmToolchain(21)
}

// todo: remove when https://github.com/JLLeitschuh/ktlint-gradle/issues/912 resolved
ktlint {
    version.set("1.8.0")
}

dependencies {
    implementation(project(":core"))
    implementation("com.squareup:kotlinpoet:2.2.0")
    testImplementation(kotlin("test"))
}

gradlePlugin {
    plugins {
        create("justworks") {
            id = "com.avsystem.justworks"
            implementationClass = "com.avsystem.justworks.gradle.JustworksPlugin"
        }
    }
}

// Functional test source set
val functionalTest by sourceSets.creating {
    compileClasspath += sourceSets["main"].output
    runtimeClasspath += sourceSets["main"].output
}

val functionalTestImplementation by configurations.getting {
    extendsFrom(configurations["testImplementation"])
}

val functionalTestRuntimeOnly by configurations.getting {
    extendsFrom(configurations["testRuntimeOnly"])
}

dependencies {
    functionalTestImplementation(gradleTestKit())
}

val functionalTestTask =
    tasks.register<Test>("functionalTest") {
        testClassesDirs = functionalTest.output.classesDirs
        classpath = functionalTest.runtimeClasspath
        useJUnitPlatform()
    }

tasks.check {
    dependsOn(functionalTestTask)
}

tasks.test {
    useJUnitPlatform()
}
