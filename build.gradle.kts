import com.vanniktech.maven.publish.MavenPublishBaseExtension
import com.vanniktech.maven.publish.SonatypeHost

plugins {
    kotlin("jvm") version "2.3.0" apply false
    id("org.jlleitschuh.gradle.ktlint") version "12.1.2" apply false
    id("org.jetbrains.kotlinx.kover") version "0.9.1" apply false
    id("com.vanniktech.maven.publish") version "0.30.0" apply false
}

allprojects {
    group = "com.avsystem.justworks"
    version = System
        .getenv("RELEASE_VERSION")
        ?.let { Regex("""^v(\d+\.\d+\.\d+.*)$""").matchEntire(it)?.groupValues?.get(1) }
        ?: "0.0.1-SNAPSHOT"

    repositories {
        mavenCentral()
    }

    apply(plugin = "org.jlleitschuh.gradle.ktlint")
}

subprojects {
    pluginManager.withPlugin("com.vanniktech.maven.publish") {
        extensions.configure<MavenPublishBaseExtension> {
            publishToMavenCentral(SonatypeHost.CENTRAL_PORTAL)
            signAllPublications()

            pom {
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
    }
}
