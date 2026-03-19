import com.vanniktech.maven.publish.SonatypeHost.Companion.CENTRAL_PORTAL
import org.jetbrains.kotlin.gradle.tasks.KotlinCompile

plugins {
    kotlin("jvm")
    id("com.vanniktech.maven.publish")
    id("org.jetbrains.kotlinx.kover") version "0.9.1"
}

kotlin {
    jvmToolchain(21)
}

// todo: remove when https://github.com/JLLeitschuh/ktlint-gradle/issues/912 resolved
ktlint {
    version = ("1.8.0")
}

dependencies {
    implementation("io.swagger.parser.v3:swagger-parser:2.1.39")
    implementation("com.squareup:kotlinpoet:2.2.0")
    implementation("org.jetbrains.kotlinx:kotlinx-datetime:0.7.1")
    implementation("io.arrow-kt:arrow-core:2.2.1.1")
    testImplementation(kotlin("test"))
}

tasks.test {
    useJUnitPlatform()
}

mavenPublishing {
    publishToMavenCentral(CENTRAL_PORTAL)
    signAllPublications()

    pom {
        name = "justworks-core"
        description = "Kotlin OpenAPI 3.0 client code generator with Ktor and kotlinx.serialization"
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
                email = "bartlomiejkozak@proton.me"
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
tasks.withType<KotlinCompile>().configureEach {
    compilerOptions {
        freeCompilerArgs.add("-Xcontext-parameters")
    }
}
