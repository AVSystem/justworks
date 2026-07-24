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
                  description: A pet in the store
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
    fun `custom apiClassPrefix and apiClassSuffix change generated client class name`() {
        writeBuildFile(
            """
            apiClassPrefix = "My"
            apiClassSuffix = "Client"
            """.trimIndent(),
        )

        runner("justworksGenerateMain").build()

        val apiDir = projectDir.resolve("build/generated/justworks/main/com/example/api")
        val clientFile = apiDir.resolve("MyPetsClient.kt")
        assertTrue(clientFile.exists(), "Expected MyPetsClient.kt; files: ${apiDir.listFiles()?.map { it.name }}")
        assertTrue(clientFile.readText().contains("class MyPetsClient"), "Expected class MyPetsClient")
    }

    @Test
    fun `generateKdoc true by default emits KDoc in generated code`() {
        writeBuildFile()

        runner("justworksGenerateMain").build()

        val petFile = projectDir.resolve("build/generated/justworks/main/com/example/model/Pet.kt")
        val clientFile = projectDir.resolve("build/generated/justworks/main/com/example/api/PetsApi.kt")
        assertTrue(petFile.readText().contains("/**"), "Model should contain KDoc by default")
        assertTrue(clientFile.readText().contains("/**"), "Client should contain KDoc by default")
    }

    @Test
    fun `generateKdoc false suppresses all KDoc in generated code`() {
        writeBuildFile("generateKdoc = false")

        runner("justworksGenerateMain").build()

        val petFile = projectDir.resolve("build/generated/justworks/main/com/example/model/Pet.kt")
        val clientFile = projectDir.resolve("build/generated/justworks/main/com/example/api/PetsApi.kt")
        assertFalse(petFile.readText().contains("/**"), "Model should contain no KDoc when generateKdoc=false")
        assertFalse(clientFile.readText().contains("/**"), "Client should contain no KDoc when generateKdoc=false")
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
    fun `externally-tagged wrapper oneOf compiles and round-trips the wrapper wire shape`() {
        // Recursive file-tree union from issue #104: each variant is a single-key wrapper object.
        writeFile(
            "api/petstore.yaml",
            """
            openapi: '3.0.0'
            info:
              title: Wrapper Union Test
              version: '1.0'
            paths:
              /tree:
                get:
                  operationId: getTree
                  summary: Get the file tree
                  tags:
                    - tree
                  responses:
                    '200':
                      description: The root node
                      content:
                        application/json:
                          schema:
                            ${'$'}ref: '#/components/schemas/Node'
            components:
              schemas:
                Node:
                  oneOf:
                    - type: object
                      required: [File]
                      properties:
                        File:
                          type: object
                          required: [name, sizeBytes]
                          properties:
                            name: { type: string }
                            sizeBytes: { type: integer }
                    - type: object
                      required: [Directory]
                      properties:
                        Directory:
                          type: object
                          required: [name, children]
                          properties:
                            name: { type: string }
                            children:
                              type: array
                              items:
                                ${'$'}ref: '#/components/schemas/Node'
            """.trimIndent(),
        )

        // Build file with a test source set that exercises the generated serializer at runtime.
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
                testImplementation(kotlin("test-junit"))
            }

            justworks {
                specs {
                    register("main") {
                        specFile = file("api/petstore.yaml")
                        packageName = "com.example"
                    }
                }
            }
            """.trimIndent(),
        )

        writeFile(
            "src/test/kotlin/WrapperUnionRoundTripTest.kt",
            """
            import com.example.model.Node
            import kotlinx.serialization.SerializationException
            import kotlinx.serialization.json.Json
            import kotlin.test.Test
            import kotlin.test.assertEquals
            import kotlin.test.assertFailsWith

            class WrapperUnionRoundTripTest {
                private val json = Json

                @Test
                fun `decodes the externally-tagged wire payload`() {
                    val wire = ""${'"'}{"File":{"name":"a.txt","sizeBytes":12}}""${'"'}
                    val node = json.decodeFromString<Node>(wire)
                    val file = node as Node.File
                    assertEquals("a.txt", file.name)
                    assertEquals(12, file.sizeBytes)
                }

                @Test
                fun `encodes back to the wrapper shape, not an internal type field`() {
                    val node: Node = Node.File(name = "a.txt", sizeBytes = 12)
                    val encoded = json.encodeToString(node)
                    assertEquals(
                        json.parseToJsonElement(""${'"'}{"File":{"name":"a.txt","sizeBytes":12}}""${'"'}),
                        json.parseToJsonElement(encoded),
                    )
                }

                @Test
                fun `round-trips a recursive directory tree`() {
                    val wire =
                        ""${'"'}{"Directory":{"name":"root","children":[{"File":{"name":"a.txt","sizeBytes":1}},{"Directory":{"name":"sub","children":[]}}]}}""${'"'}
                    val node = json.decodeFromString<Node>(wire)
                    // Encode with the static Node type (an `as` cast below would smart-cast `node`
                    // to Node.Directory and pick the wrong serializer).
                    val encoded = json.encodeToString<Node>(node)
                    assertEquals(
                        json.parseToJsonElement(wire),
                        json.parseToJsonElement(encoded),
                    )
                    val dir = node as Node.Directory
                    assertEquals("root", dir.name)
                    assertEquals(2, dir.children.size)
                    val nested = dir.children[1] as Node.Directory
                    assertEquals("sub", nested.name)
                    assertEquals(0, nested.children.size)
                }

                @Test
                fun `rejects an unknown wrapper key`() {
                    // Hits the `else ->` branch in deserialize.
                    assertFailsWith<SerializationException> {
                        json.decodeFromString<Node>(""${'"'}{"Bogus":{}}""${'"'})
                    }
                }

                @Test
                fun `rejects an empty wrapper object`() {
                    // Hits the `singleOrNull() ?: throw` branch (zero keys).
                    assertFailsWith<SerializationException> {
                        json.decodeFromString<Node>("{}")
                    }
                }

                @Test
                fun `rejects a multi-key wrapper object`() {
                    // Hits the `singleOrNull() ?: throw` branch (two keys).
                    assertFailsWith<SerializationException> {
                        json.decodeFromString<Node>(""${'"'}{"File":{"name":"a.txt","sizeBytes":1},"Directory":{"name":"x","children":[]}}""${'"'})
                    }
                }
            }
            """.trimIndent(),
        )

        val result = runner("test").build()

        assertEquals(
            TaskOutcome.SUCCESS,
            result.task(":justworksGenerateMain")?.outcome,
            "justworksGenerateMain should succeed",
        )
        assertEquals(
            TaskOutcome.SUCCESS,
            result.task(":test")?.outcome,
            "runtime round-trip test against the generated wrapper serializer should pass",
        )

        // The generated union must not fall back to internal tagging.
        val nodeFile = projectDir.resolve("build/generated/justworks/main/com/example/model/Node.kt")
        assertTrue(nodeFile.exists(), "Node.kt should exist")
        val nodeContent = nodeFile.readText()
        assertFalse(
            nodeContent.contains("JsonClassDiscriminator"),
            "Wrapper union must NOT emit @JsonClassDiscriminator",
        )
        assertTrue(
            nodeContent.contains("with = NodeSerializer::class"),
            "Node should bind the bespoke NodeSerializer",
        )
        assertTrue(
            projectDir.resolve("build/generated/justworks/main/com/example/model/NodeSerializer.kt").exists(),
            "NodeSerializer.kt should be generated",
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

        // Sealed class file exists with nested subtypes
        val shapeFile = outputDir.resolve("Shape.kt")
        assertTrue(shapeFile.exists(), "Shape.kt should exist")
        val shapeContent = shapeFile.readText()
        assertTrue(shapeContent.contains("sealed interface"), "Shape.kt should contain sealed interface")
        assertTrue(shapeContent.contains("JsonClassDiscriminator"), "Shape.kt should contain @JsonClassDiscriminator")

        // Variant subtypes are nested inside Shape.kt, no separate files
        val circleFile = outputDir.resolve("Circle.kt")
        assertFalse(circleFile.exists(), "Circle.kt should NOT exist as a separate file")
        assertTrue(shapeContent.contains("data class Circle"), "Shape.kt should contain nested Circle")
        assertTrue(shapeContent.contains("data class Square"), "Shape.kt should contain nested Square")

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

        // Auth params and applyAuth are generated in the per-spec client, not in ApiClientBase
        val clientFile = projectDir
            .resolve("build/generated/justworks/secured/com/example/secured/api/DataApi.kt")
        assertTrue(clientFile.exists(), "DataApi.kt should exist")

        val content = clientFile.readText()
        assertTrue(content.contains("apiKeyAuthSecuredApi"), "Should contain apiKeyAuthSecuredApi param")
        assertTrue(content.contains("basicAuthSecuredApiUsername"), "Should contain basicAuthSecuredApiUsername param")
        assertTrue(content.contains("basicAuthSecuredApiPassword"), "Should contain basicAuthSecuredApiPassword param")
        assertTrue(content.contains("X-API-Key"), "Should contain X-API-Key header name")
        assertTrue(content.contains("applyAuth"), "Should contain applyAuth override")
        assertTrue(content.contains("Authorization"), "Should contain Authorization header for Basic auth")

        // ApiClientBase should NOT contain token — auth is per-client now
        val apiClientBase = projectDir
            .resolve("build/generated/justworks/shared/kotlin/com/avsystem/justworks/ApiClientBase.kt")
        val baseContent = apiClientBase.readText()
        assertFalse(baseContent.contains("token"), "ApiClientBase should not contain token param")
        assertFalse(baseContent.contains("Bearer"), "ApiClientBase should not contain Bearer auth")
    }

    @Test
    fun `spec without security schemes generates ApiClientBase without auth`() {
        writeBuildFile()

        runner("justworksGenerateMain").build()

        val apiClientBase = projectDir
            .resolve("build/generated/justworks/shared/kotlin/com/avsystem/justworks/ApiClientBase.kt")
        assertTrue(apiClientBase.exists(), "ApiClientBase.kt should exist")

        val content = apiClientBase.readText()
        assertFalse(content.contains("token"), "Should not contain token param")
        assertFalse(content.contains("Bearer"), "Should not contain Bearer auth")
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

    @Test
    fun `multiple specs with conflicting security schemes generate unique params in ApiClientBase`() {
        writeFile(
            "api/spec1.yaml",
            """
            openapi: '3.0.0'
            info:
              title: API 1
              version: '1.0'
            paths:
              /health:
                get:
                  operationId: checkHealth
                  responses:
                    '200':
                      description: OK
            components:
              securitySchemes:
                CommonAuth:
                  type: apiKey
                  in: header
                  name: X-API-Key-1
            security:
              - CommonAuth: []
            """.trimIndent(),
        )

        writeFile(
            "api/spec2.yaml",
            """
            openapi: '3.0.0'
            info:
              title: API 2
              version: '1.0'
            paths:
              /health:
                get:
                  operationId: checkHealth
                  responses:
                    '200':
                      description: OK
            components:
              securitySchemes:
                CommonAuth:
                  type: apiKey
                  in: header
                  name: X-API-Key-2
            security:
              - CommonAuth: []
            """.trimIndent(),
        )

        writeFile(
            "build.gradle.kts",
            """
            plugins {
                id("com.avsystem.justworks")
            }

            justworks {
                specs {
                    register("spec1") {
                        specFile = file("api/spec1.yaml")
                        packageName = "com.example.spec1"
                    }
                    register("spec2") {
                        specFile = file("api/spec2.yaml")
                        packageName = "com.example.spec2"
                    }
                }
            }
            """.trimIndent(),
        )

        runner("justworksGenerateAll").build()

        // Each client has its own auth param scoped by spec title — no cross-spec forwarding
        val api1Client = projectDir
            .resolve("build/generated/justworks/spec1/com/example/spec1/api/DefaultApi.kt")
        assertTrue(api1Client.exists(), "DefaultApi for spec1 should exist")
        val api1Content = api1Client.readText()
        assertTrue(api1Content.contains("commonAuthApi1"), "Spec1 client should take commonAuthApi1")
        assertTrue(api1Content.contains("X-API-Key-1"), "Spec1 client should reference X-API-Key-1")
        assertTrue(
            api1Content.contains("ApiClientBase(baseUrl)"),
            "Spec1 client should pass only baseUrl to super",
        )

        val api2Client = projectDir
            .resolve("build/generated/justworks/spec2/com/example/spec2/api/DefaultApi.kt")
        assertTrue(api2Client.exists(), "DefaultApi for spec2 should exist")
        val api2Content = api2Client.readText()
        assertTrue(api2Content.contains("commonAuthApi2"), "Spec2 client should take commonAuthApi2")
        assertTrue(api2Content.contains("X-API-Key-2"), "Spec2 client should reference X-API-Key-2")
        assertTrue(
            api2Content.contains("ApiClientBase(baseUrl)"),
            "Spec2 client should pass only baseUrl to super",
        )
    }

    @Test
    fun `path parameter values with spaces and slashes are percent-encoded, not left raw`() {
        // Regression test for issue #108: path params were previously interpolated via encodeParam,
        // which strips the JSON quotes but does NOT URL-encode. A "/" would split the URL into an
        // extra path segment, and a raw space would produce an invalid URL. Both must now go through
        // the generated encodePathParam(), which additionally applies Ktor's encodeURLPathPart().
        writeBuildFile()

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
                testImplementation(kotlin("test-junit"))
            }

            justworks {
                specs {
                    register("main") {
                        specFile = file("api/petstore.yaml")
                        packageName = "com.example"
                    }
                }
            }
            """.trimIndent(),
        )

        writeFile(
            "src/test/kotlin/PathParamEncodingTest.kt",
            """
            import com.avsystem.justworks.encodePathParam
            import kotlin.test.Test
            import kotlin.test.assertEquals
            import kotlin.test.assertFalse

            class PathParamEncodingTest {
                @Test
                fun `space in a path param value is percent-encoded rather than left raw`() {
                    val encoded = encodePathParam("free form")
                    assertFalse(' ' in encoded, "Raw space in a URL path segment: ${'$'}encoded")
                    assertEquals("free%20form", encoded)
                }

                @Test
                fun `slash in a path param value is percent-encoded rather than splitting the path`() {
                    val encoded = encodePathParam("a/b")
                    assertFalse('/' in encoded, "A literal slash would introduce an extra path segment: ${'$'}encoded")
                    assertEquals("a%2Fb", encoded)
                }

                @Test
                fun `value with both a space and a slash is fully percent-encoded`() {
                    val encoded = encodePathParam("a b/c")
                    assertEquals("a%20b%2Fc", encoded)
                }
            }
            """.trimIndent(),
        )

        val result = runner("test").build()

        assertEquals(
            TaskOutcome.SUCCESS,
            result.task(":justworksGenerateMain")?.outcome,
            "justworksGenerateMain should succeed",
        )
        assertEquals(
            TaskOutcome.SUCCESS,
            result.task(":test")?.outcome,
            "path param encoding test against the generated encodePathParam() should pass",
        )
    }

    @Test
    fun `multiple specs with identical security schemes pass the build`() {
        writeFile(
            "api/spec1.yaml",
            """
            openapi: '3.0.0'
            info:
              title: API 1
              version: '1.0'
            components:
              securitySchemes:
                CommonAuth:
                  type: apiKey
                  in: header
                  name: X-API-Key
            security:
              - CommonAuth: []
            """.trimIndent(),
        )

        writeFile(
            "api/spec2.yaml",
            """
            openapi: '3.0.0'
            info:
              title: API 2
              version: '1.0'
            components:
              securitySchemes:
                CommonAuth:
                  type: apiKey
                  in: header
                  name: X-API-Key
            security:
              - CommonAuth: []
            """.trimIndent(),
        )

        writeFile(
            "build.gradle.kts",
            """
            plugins {
                id("com.avsystem.justworks")
            }

            justworks {
                specs {
                    register("spec1") {
                        specFile = file("api/spec1.yaml")
                        packageName = "com.example"
                    }
                    register("spec2") {
                        specFile = file("api/spec2.yaml")
                        packageName = "com.example"
                    }
                }
            }
            """.trimIndent(),
        )

        runner("justworksSharedTypes").build()
    }
}
