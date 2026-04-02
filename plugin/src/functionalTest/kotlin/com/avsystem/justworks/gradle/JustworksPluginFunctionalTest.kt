package com.avsystem.justworks.gradle

import org.gradle.testkit.runner.GradleRunner
import org.gradle.testkit.runner.TaskOutcome
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.io.TempDir
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Functional tests for the Justworks Gradle plugin using Gradle TestKit.
 *
 * Each test creates a temporary Gradle project, applies the plugin,
 * and verifies the full lifecycle from a consumer's perspective.
 */
class JustworksPluginFunctionalTest {
    @TempDir
    lateinit var projectDir: File

    private fun writeFile(path: String, content: String) {
        val file = projectDir.resolve(path)
        file.parentFile.mkdirs()
        file.writeText(content)
    }

    @BeforeEach
    fun setup() {
        writeFile(
            "settings.gradle.kts",
            """rootProject.name = "test-project"""",
        )

        writeFile(
            "api/petstore.yaml",
            """
            openapi: '3.0.0'
            info:
              title: Petstore
              version: '1.0'
            paths:
              /pets:
                get:
                  operationId: listPets
                  summary: List pets
                  tags:
                    - pets
                  responses:
                    '200':
                      description: A list of pets
                      content:
                        application/json:
                          schema:
                            type: array
                            items:
                              ${'$'}ref: '#/components/schemas/Pet'
            components:
              schemas:
                Pet:
                  type: object
                  required:
                    - id
                    - name
                  properties:
                    id:
                      type: integer
                      format: int64
                    name:
                      type: string
                    tag:
                      type: string
                Status:
                  type: string
                  enum:
                    - available
                    - pending
                    - sold
            """.trimIndent(),
        )
    }

    private fun writeBuildFile(extraDsl: String = "") {
        writeFile(
            "build.gradle.kts",
            """
            plugins {
                kotlin("jvm") version "2.3.0"
                kotlin("plugin.serialization") version "2.3.0"
                id("com.avsystem.justworks")
            }

            repositories {
                mavenCentral()
            }

            dependencies {
                implementation("org.jetbrains.kotlinx:kotlinx-serialization-core:1.8.1")
                implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.8.1")
                implementation("io.ktor:ktor-client-core:3.1.1")
                implementation("io.ktor:ktor-client-content-negotiation:3.1.1")
                implementation("io.ktor:ktor-serialization-kotlinx-json:3.1.1")
                implementation("io.arrow-kt:arrow-core:2.2.1.1")
            }

            kotlin {
                compilerOptions {
                    freeCompilerArgs.add("-Xcontext-parameters")
                }
            }

            justworks {
                specs {
                    register("main") {
                        specFile = file("api/petstore.yaml")
                        packageName = "com.example"
                        $extraDsl
                    }
                }
            }
            """.trimIndent(),
        )
    }

    private fun runner(vararg args: String): GradleRunner = GradleRunner
        .create()
        .withProjectDir(projectDir)
        .withPluginClasspath()
        .withArguments(*args, "--stacktrace")
        .forwardOutput()

    @Test
    fun `plugin applies and justworksGenerate runs successfully`() {
        writeBuildFile()

        val result = runner("justworksGenerateMain").build()

        assertEquals(
            TaskOutcome.SUCCESS,
            result.task(":justworksGenerateMain")?.outcome,
        )
    }

    @Test
    fun `generated output directory contains kt file under package directory`() {
        writeBuildFile()

        runner("justworksGenerateMain").build()

        val outputDir = projectDir.resolve("build/generated/justworks/main")
        assertTrue(outputDir.exists(), "Output directory should exist")
        assertTrue(outputDir.isDirectory, "Output should be a directory")

        val modelDir = outputDir.resolve("com/example/model")
        assertTrue(modelDir.exists(), "Model package directory com/example/model should exist")

        val ktFiles = modelDir.listFiles()?.filter { it.extension == "kt" } ?: emptyList()
        assertTrue(ktFiles.isNotEmpty(), "Should contain at least one .kt file")
        assertTrue(ktFiles.any { it.name == "Pet.kt" }, "Should contain Pet.kt")

        val apiDir = outputDir.resolve("com/example/api")
        assertTrue(apiDir.exists(), "API package directory com/example/api should exist")

        val apiFiles = apiDir.listFiles()?.filter { it.extension == "kt" } ?: emptyList()
        assertTrue(apiFiles.any { it.name == "PetsApi.kt" }, "Should contain PetsApi.kt")

        // Shared types generated to separate directory with fixed package
        val sharedDir = projectDir.resolve("build/generated/justworks/shared/kotlin/com/avsystem/justworks")
        assertTrue(sharedDir.exists(), "Shared types directory should exist")
        val sharedFiles = sharedDir.listFiles()?.filter { it.extension == "kt" } ?: emptyList()
        assertTrue(sharedFiles.any { it.name == "HttpError.kt" }, "Should contain HttpError.kt")
        assertTrue(sharedFiles.any { it.name == "HttpSuccess.kt" }, "Should contain HttpSuccess.kt")
        assertTrue(sharedFiles.any { it.name == "ApiClientBase.kt" }, "Should contain ApiClientBase.kt")
    }

    @Test
    fun `generated client file contains suspend functions`() {
        writeBuildFile()

        runner("justworksGenerateMain").build()

        val clientFile =
            projectDir.resolve(
                "build/generated/justworks/main/com/example/api/PetsApi.kt",
            )
        assertTrue(clientFile.exists(), "PetsApi.kt should exist")

        val content = clientFile.readText()
        assertTrue(content.contains("suspend fun"), "PetsApi should contain suspend functions")
        assertTrue(content.contains("class PetsApi"), "PetsApi should define PetsApi class")
    }

    @Test
    fun `second run is UP-TO-DATE when inputs unchanged`() {
        writeBuildFile()

        runner("justworksGenerateMain").build()
        val secondResult = runner("justworksGenerateMain").build()

        assertEquals(
            TaskOutcome.UP_TO_DATE,
            secondResult.task(":justworksGenerateMain")?.outcome,
        )
    }

    @Test
    fun `compileKotlin triggers justworksGenerate and compiles successfully`() {
        writeBuildFile()

        val result = runner("compileKotlin").build()

        assertEquals(
            TaskOutcome.SUCCESS,
            result.task(":justworksGenerateMain")?.outcome,
            "justworksGenerateMain should run when compileKotlin is executed",
        )
        assertEquals(
            TaskOutcome.SUCCESS,
            result.task(":compileKotlin")?.outcome,
            "compileKotlin should succeed with generated sources",
        )
    }

    @Test
    fun `generates polymorphic types from spec with oneOf and allOf`() {
        // Write a polymorphic OpenAPI spec
        writeFile(
            "api/petstore.yaml",
            """
            openapi: '3.0.0'
            info:
              title: Polymorphic Test
              version: '1.0'
            paths:
              /shapes:
                get:
                  operationId: listShapes
                  summary: List shapes
                  tags:
                    - shapes
                  responses:
                    '200':
                      description: A list of shapes
                      content:
                        application/json:
                          schema:
                            type: array
                            items:
                              ${'$'}ref: '#/components/schemas/Shape'
            components:
              schemas:
                Shape:
                  oneOf:
                    - ${'$'}ref: '#/components/schemas/Circle'
                    - ${'$'}ref: '#/components/schemas/Square'
                  discriminator:
                    propertyName: shapeType
                    mapping:
                      circle: '#/components/schemas/Circle'
                      square: '#/components/schemas/Square'
                Circle:
                  type: object
                  required:
                    - radius
                    - shapeType
                  properties:
                    radius:
                      type: number
                    shapeType:
                      type: string
                Square:
                  type: object
                  required:
                    - sideLength
                    - shapeType
                  properties:
                    sideLength:
                      type: number
                    shapeType:
                      type: string
            """.trimIndent(),
        )

        writeBuildFile()

        val result = runner("compileKotlin").build()

        assertEquals(
            TaskOutcome.SUCCESS,
            result.task(":justworksGenerateMain")?.outcome,
            "justworksGenerateMain should succeed",
        )
        assertEquals(
            TaskOutcome.SUCCESS,
            result.task(":compileKotlin")?.outcome,
            "compileKotlin should succeed with polymorphic generated sources",
        )

        val outputDir = projectDir.resolve("build/generated/justworks/main/com/example/model")

        // Sealed interface file exists
        val shapeFile = outputDir.resolve("Shape.kt")
        assertTrue(shapeFile.exists(), "Shape.kt should exist")
        val shapeContent = shapeFile.readText()
        assertTrue(shapeContent.contains("sealed interface"), "Shape.kt should contain sealed interface")
        assertTrue(shapeContent.contains("JsonClassDiscriminator"), "Shape.kt should contain @JsonClassDiscriminator")

        // Variant data class file exists and implements sealed interface
        val circleFile = outputDir.resolve("Circle.kt")
        assertTrue(circleFile.exists(), "Circle.kt should exist")
        val circleContent = circleFile.readText()
        assertTrue(circleContent.contains(": Shape"), "Circle.kt should implement Shape")

        // SerializersModule file exists
        val moduleFile = outputDir.resolve("SerializersModule.kt")
        assertTrue(moduleFile.exists(), "SerializersModule.kt should exist")
        val moduleContent = moduleFile.readText()
        assertTrue(
            moduleContent.contains("generatedSerializersModule"),
            "SerializersModule.kt should contain module property",
        )
    }

    @Test
    fun `DSL accepts optional apiPackage and modelPackage overrides`() {
        writeBuildFile(
            """
            apiPackage = "com.example.custom.api"
            modelPackage = "com.example.custom.model"
            """.trimIndent(),
        )

        val result = runner("justworksGenerateMain").build()

        assertEquals(
            TaskOutcome.SUCCESS,
            result.task(":justworksGenerateMain")?.outcome,
        )
    }

    // Multi-spec tests

    private fun writeMultiSpecProject() {
        writeFile(
            "api/petstore.yaml",
            """
            openapi: 3.0.0
            info:
              title: Petstore
              version: 1.0.0
            paths: {}
            components:
              schemas:
                Pet:
                  type: object
                  properties:
                    name:
                      type: string
            """.trimIndent(),
        )

        writeFile(
            "api/payments.yaml",
            """
            openapi: 3.0.0
            info:
              title: Payments
              version: 1.0.0
            paths: {}
            components:
              schemas:
                Payment:
                  type: object
                  properties:
                    amount:
                      type: number
            """.trimIndent(),
        )

        writeFile(
            "build.gradle.kts",
            """
            plugins {
                kotlin("jvm") version "2.3.0"
                kotlin("plugin.serialization") version "2.3.0"
                id("com.avsystem.justworks")
            }
            repositories { mavenCentral() }
            dependencies {
                implementation("org.jetbrains.kotlinx:kotlinx-serialization-core:1.8.1")
                implementation("io.ktor:ktor-client-core:3.1.1")
                implementation("io.ktor:ktor-client-content-negotiation:3.1.1")
                implementation("io.ktor:ktor-serialization-kotlinx-json:3.1.1")
            }
            justworks {
                specs {
                    register("petstore") {
                        specFile = file("api/petstore.yaml")
                        packageName = "com.example.petstore"
                    }
                    register("payments") {
                        specFile = file("api/payments.yaml")
                        packageName = "com.example.payments"
                    }
                }
            }
            """.trimIndent(),
        )
    }

    @Test
    fun `multiple specs generate independently`() {
        writeMultiSpecProject()

        val result = runner("justworksGenerateAll").build()

        assertEquals(TaskOutcome.SUCCESS, result.task(":justworksGeneratePetstore")?.outcome)
        assertEquals(TaskOutcome.SUCCESS, result.task(":justworksGeneratePayments")?.outcome)

        // Verify isolated output directories exist
        assertTrue(projectDir.resolve("build/generated/justworks/petstore").exists())
        assertTrue(projectDir.resolve("build/generated/justworks/payments").exists())

        // Verify generated files in correct packages
        assertTrue(projectDir.resolve("build/generated/justworks/petstore/com/example/petstore/model/Pet.kt").exists())
        assertTrue(
            projectDir.resolve("build/generated/justworks/payments/com/example/payments/model/Payment.kt").exists(),
        )
    }

    @Test
    fun `single spec task generates only that spec`() {
        writeMultiSpecProject()

        val result = runner("justworksGeneratePetstore").build()

        assertEquals(TaskOutcome.SUCCESS, result.task(":justworksGeneratePetstore")?.outcome)
        // Payments task should not have run
        assertNull(result.task(":justworksGeneratePayments"))

        assertTrue(projectDir.resolve("build/generated/justworks/petstore").exists())
        // Payments directory shouldn't exist yet
        assertFalse(projectDir.resolve("build/generated/justworks/payments").exists())
    }

    @Test
    fun `missing required property fails with clear error`() {
        writeFile(
            "build.gradle.kts",
            """
            plugins {
                kotlin("jvm") version "2.3.0"
                id("com.avsystem.justworks")
            }
            justworks {
                specs {
                    register("petstore") {
                        specFile = file("api/petstore.yaml")
                        // packageName missing
                    }
                }
            }
            """.trimIndent(),
        )

        val result = runner("justworksGenerateAll").buildAndFail()

        assertTrue(result.output.contains("Spec 'petstore': packageName is required but not set"))
    }

    @Test
    fun `invalid spec name fails with clear error`() {
        writeFile(
            "build.gradle.kts",
            """
            plugins {
                kotlin("jvm") version "2.3.0"
                id("com.avsystem.justworks")
            }
            justworks {
                specs {
                    register("pet-store") {
                        specFile = file("api/petstore.yaml")
                        packageName = "com.example"
                    }
                }
            }
            """.trimIndent(),
        )

        val result = runner("justworksGenerateAll").buildAndFail()

        assertTrue(result.output.contains("Invalid spec name 'pet-store'"))
    }

    @Test
    fun `spec with security schemes generates ApiClientBase with applyAuth body`() {
        writeFile(
            "api/secured.yaml",
            """
            openapi: '3.0.0'
            info:
              title: Secured API
              version: '1.0'
            paths:
              /data:
                get:
                  operationId: getData
                  summary: Get data
                  tags:
                    - data
                  responses:
                    '200':
                      description: OK
                      content:
                        application/json:
                          schema:
                            type: object
                            properties:
                              value:
                                type: string
            components:
              securitySchemes:
                ApiKeyAuth:
                  type: apiKey
                  in: header
                  name: X-API-Key
                BasicAuth:
                  type: http
                  scheme: basic
            security:
              - ApiKeyAuth: []
              - BasicAuth: []
            """.trimIndent(),
        )

        writeFile(
            "build.gradle.kts",
            """
            plugins {
                kotlin("jvm") version "2.3.0"
                kotlin("plugin.serialization") version "2.3.0"
                id("com.avsystem.justworks")
            }

            repositories {
                mavenCentral()
            }

            dependencies {
                implementation("org.jetbrains.kotlinx:kotlinx-serialization-core:1.8.1")
                implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.8.1")
                implementation("io.ktor:ktor-client-core:3.1.1")
                implementation("io.ktor:ktor-client-content-negotiation:3.1.1")
                implementation("io.ktor:ktor-serialization-kotlinx-json:3.1.1")
                implementation("io.arrow-kt:arrow-core:2.2.1.1")
            }

            kotlin {
                compilerOptions {
                    freeCompilerArgs.add("-Xcontext-parameters")
                }
            }

            justworks {
                specs {
                    register("secured") {
                        specFile = file("api/secured.yaml")
                        packageName = "com.example.secured"
                    }
                }
            }
            """.trimIndent(),
        )

        val result = runner("justworksGenerateSecured").build()

        assertEquals(
            TaskOutcome.SUCCESS,
            result.task(":justworksGenerateSecured")?.outcome,
        )

        val apiClientBase = projectDir
            .resolve("build/generated/justworks/shared/kotlin/com/avsystem/justworks/ApiClientBase.kt")
        assertTrue(apiClientBase.exists(), "ApiClientBase.kt should exist")

        val content = apiClientBase.readText()
        assertTrue(content.contains("apiKeyAuthKey"), "Should contain apiKeyAuthKey param")
        assertTrue(content.contains("basicAuthUsername"), "Should contain basicAuthUsername param")
        assertTrue(content.contains("basicAuthPassword"), "Should contain basicAuthPassword param")
        assertTrue(content.contains("X-API-Key"), "Should contain X-API-Key header name")
        assertTrue(content.contains("applyAuth"), "Should contain applyAuth method")
        assertTrue(content.contains("Authorization"), "Should contain Authorization header for Basic auth")
        assertFalse(
            content.contains("token: () -> String"),
            "Should NOT contain backward-compat token param when explicit security schemes present",
        )
    }

    @Test
    fun `spec without security schemes generates ApiClientBase with no auth params`() {
        writeBuildFile()

        runner("justworksGenerateMain").build()

        val apiClientBase = projectDir
            .resolve("build/generated/justworks/shared/kotlin/com/avsystem/justworks/ApiClientBase.kt")
        assertTrue(apiClientBase.exists(), "ApiClientBase.kt should exist")

        val content = apiClientBase.readText()
        assertTrue(!content.contains("token"), "Should NOT contain token param when no security schemes")
        assertTrue(!content.contains("Bearer"), "Should NOT contain Bearer when no security schemes")
    }

    @Test
    fun `empty specs container logs warning`() {
        writeFile(
            "build.gradle.kts",
            """
            plugins {
                kotlin("jvm") version "2.3.0"
                id("com.avsystem.justworks")
            }
            justworks {
                specs {
                    // empty
                }
            }
            """.trimIndent(),
        )

        val result = runner("justworksGenerateAll").build()

        assertEquals(TaskOutcome.UP_TO_DATE, result.task(":justworksGenerateAll")?.outcome)
        assertTrue(result.output.contains("justworks: no specs configured"))
    }
}
