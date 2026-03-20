import org.jetbrains.kotlin.gradle.tasks.KotlinCompile

plugins {
    kotlin("jvm")
    id("org.jetbrains.kotlinx.kover")
    id("com.vanniktech.maven.publish")
}

kotlin {
    jvmToolchain(21)
}

// todo: remove when https://github.com/JLLeitschuh/ktlint-gradle/issues/912 resolved
ktlint {
    version = "1.8.0"
}

mavenPublishing {
    pom {
        name = "justworks-core"
        description = "OpenAPI 3.0 parser and Kotlin Ktor client code generator"
    }
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


tasks.withType<KotlinCompile>().configureEach {
    compilerOptions {
        freeCompilerArgs.add("-Xcontext-parameters")
    }
}
