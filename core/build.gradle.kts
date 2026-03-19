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
    version.set("1.8.0")
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
    publishToMavenCentral(com.vanniktech.maven.publish.SonatypeHost.CENTRAL_PORTAL)
    signAllPublications()

    pom {
        name.set("justworks-core")
        description.set("Kotlin OpenAPI 3.0 client code generator with Ktor and kotlinx.serialization")
        url.set("https://github.com/AVSystem/justworks")

        licenses {
            license {
                name.set("The Apache License, Version 2.0")
                url.set("https://www.apache.org/licenses/LICENSE-2.0.txt")
            }
        }

        developers {
            developer {
                id.set("avsystem")
                name.set("AVSystem")
                organization.set("AVSystem")
                organizationUrl.set("https://www.avsystem.com")
            }
        }

        scm {
            connection.set("scm:git:git://github.com/AVSystem/justworks.git")
            developerConnection.set("scm:git:ssh://github.com/AVSystem/justworks.git")
            url.set("https://github.com/AVSystem/justworks")
        }
    }
}

val compileKotlin: KotlinCompile by tasks
compileKotlin.compilerOptions {
    freeCompilerArgs.set(listOf("-Xcontext-parameters"))
}
