import com.vanniktech.maven.publish.GradlePlugin
import com.vanniktech.maven.publish.JavadocJar
import com.vanniktech.maven.publish.SonatypeHost.Companion.CENTRAL_PORTAL

plugins {
    kotlin("jvm")
    `java-gradle-plugin`
    id("com.vanniktech.maven.publish")
    id("org.jetbrains.kotlinx.kover") version "0.9.1"
}

kotlin {
    jvmToolchain(21)
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
    publishToMavenCentral(CENTRAL_PORTAL)
    signAllPublications()

    pom {
        name = "justworks-plugin"
        description = "Gradle plugin for generating Kotlin Ktor client code from OpenAPI 3.0 specifications"
        url = "https://github.com/AVSystem/justworks"

        licenses {
            license {
                name = "The Apache License, Version 2.0"
                url = "https://www.apache.org/licenses/LICENSE-2.0.txt"
            }
        }

        developers {
            developer {
                id = "halotukozak"
                name = "Bartłomiej Kozak"
                email = "b.kozak@avsystem.com"
                organization = "AVSystem"
                organizationUrl = "https://www.avsystem.com"
            }
            developer {
                id = "mzielu"
                name = "Marcin Zieliński"
                email = "m.zielinski@avsystem.com"
                organization = "AVSystem"
                organizationUrl = "https://www.avsystem.com"
            }
        }

        scm {
            connection = "scm:git:git://github.com/AVSystem/justworks.git"
            developerConnection = "scm:git:ssh://github.com/AVSystem/justworks.git"
            url = "https://github.com/AVSystem/justworks"
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
