import com.vanniktech.maven.publish.GradlePlugin
import com.vanniktech.maven.publish.JavadocJar

plugins {
    kotlin("jvm")
    `java-gradle-plugin`
    id("com.vanniktech.maven.publish")
    id("org.jetbrains.kotlinx.kover")
    id("org.jetbrains.dokka") version "2.2.0"
}

kotlin {
    jvmToolchain(21)
}

dokka {
    moduleName.set("justworks-plugin")

    dokkaSourceSets.main {
        sourceLink {
            localDirectory.set(file("src/main/kotlin"))
            remoteUrl("https://github.com/AVSystem/justworks/tree/master/plugin/src/main/kotlin")
            remoteLineSuffix.set("#L")
        }
    }
}

dependencies {
    implementation(project(":core"))
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

mavenPublishing {
    configure(GradlePlugin(javadocJar = JavadocJar.Empty()))

    pom {
        name = "justworks-plugin"
        description = "Gradle plugin for generating Kotlin Ktor client code from OpenAPI 3.0 specifications"
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

kover {
    currentProject {
        sources {
            excludedSourceSets.add("functionalTest")
        }
    }
}

tasks.named("koverXmlReport") {
    dependsOn(functionalTestTask)
}

tasks.test {
    useJUnitPlatform()
}
