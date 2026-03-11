plugins {
    kotlin("jvm")
    `maven-publish`
}

kotlin {
    jvmToolchain(21)
}

dependencies {
    implementation("io.swagger.parser.v3:swagger-parser:2.1.39")
    implementation("com.squareup:kotlinpoet:2.2.0")
    implementation("org.jetbrains.kotlinx:kotlinx-datetime:0.7.1")
    testImplementation(kotlin("test"))
}

tasks.test {
    useJUnitPlatform()
}

publishing {
    publications {
        create<MavenPublication>("maven") {
            from(components["java"])
        }
    }
}
