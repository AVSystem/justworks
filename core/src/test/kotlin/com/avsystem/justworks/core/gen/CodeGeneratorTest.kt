package com.avsystem.justworks.core.gen

import com.avsystem.justworks.core.parser.ParseResult
import com.avsystem.justworks.core.parser.SpecParser
import java.io.File
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertTrue
import kotlin.test.fail

class CodeGeneratorTest {
    companion object {
        private val SPEC_FIXTURES = listOf(
            "/fixtures/platform-api.json",
            "/fixtures/analytics-api.json",
        )
    }

    @Test
    fun `generate produces model and client files for real-world specs`() {
        for (fixture in SPEC_FIXTURES) {
            val specUrl = javaClass.getResource(fixture)
                ?: fail("Spec fixture not found: $fixture")
            val specFile = File(specUrl.toURI())
            val spec = when (val result = SpecParser.parse(specFile)) {
                is ParseResult.Success -> result.value
                is ParseResult.Failure -> fail("Failed to parse $fixture: ${result.error}")
            }

            val outputDir = Files.createTempDirectory("codegen-test").toFile()
            try {
                val result = CodeGenerator.generate(
                    spec = spec,
                    modelPackage = "com.example.model",
                    apiPackage = "com.example.api",
                    outputDir = outputDir,
                )

                assertTrue(result.modelFiles > 0, "$fixture: should produce model files")
                if (spec.endpoints.isNotEmpty()) {
                    assertTrue(result.clientFiles > 0, "$fixture: should produce client files")
                }

                val generatedFiles = outputDir.walkTopDown().filter { it.isFile }.toList()
                assertTrue(generatedFiles.isNotEmpty(), "$fixture: output directory should contain files")

                val fileNames = generatedFiles.map { it.nameWithoutExtension }
                val duplicates = fileNames.groupingBy { it }.eachCount().filter { it.value > 1 }
                assertTrue(duplicates.isEmpty(), "$fixture: duplicate file names found: ${duplicates.keys}")
            } finally {
                outputDir.deleteRecursively()
            }
        }
    }

    @Test
    fun `inline operation bodies are nested inside the client class`() {
        val yaml = """
            openapi: "3.0.0"
            info: { title: T, version: "1" }
            paths:
              /domains/{id}:
                put:
                  operationId: domains_update
                  tags: [domains]
                  parameters:
                    - { name: id, in: path, required: true, schema: { type: string } }
                  requestBody:
                    required: true
                    content:
                      application/json:
                        schema:
                          type: object
                          required: [name]
                          properties:
                            name: { type: string }
                  responses:
                    '200':
                      description: ok
                      content:
                        application/json:
                          schema:
                            type: object
                            properties:
                              id: { type: string }
        """.trimIndent()
        val specFile = File.createTempFile("nested-inline", ".yaml").apply {
            writeText(yaml)
            deleteOnExit()
        }
        val spec = when (val r = SpecParser.parse(specFile)) {
            is ParseResult.Success -> r.value
            is ParseResult.Failure -> fail("parse failed: ${r.error}")
        }

        val outputDir = Files.createTempDirectory("codegen-nested").toFile()
        try {
            CodeGenerator.generate(spec, "com.example.model", "com.example.api", outputDir)

            val apiFile = outputDir.resolve("com/example/api/DomainsApi.kt")
            assertTrue(apiFile.exists(), "DomainsApi.kt should exist")
            val api = apiFile.readText()

            // Request/response bodies are nested types, referenced by the function.
            assertTrue(api.contains("data class DomainsUpdateRequest"), "request body should be nested in client")
            assertTrue(api.contains("data class DomainsUpdateResponse"), "response body should be nested in client")
            assertTrue(
                api.contains("body: DomainsUpdateRequest"),
                "function should reference the nested request type",
            )

            // No top-level model files were emitted for the inline bodies.
            val modelDir = outputDir.resolve("com/example/model")
            val modelFiles = modelDir.listFiles()?.map { it.name }.orEmpty()
            assertTrue(
                modelFiles.none { it.contains("DomainsUpdate") },
                "inline bodies should not be top-level model files, got: $modelFiles",
            )
        } finally {
            outputDir.deleteRecursively()
        }
    }

    @Test
    fun `inline object properties are nested inside the parent model type`() {
        val yaml = """
            openapi: "3.0.0"
            info: { title: T, version: "1" }
            paths: {}
            components:
              schemas:
                DataSetList:
                  type: object
                  properties:
                    total: { type: integer, format: int32 }
                    address:
                      type: object
                      properties:
                        street: { type: string }
        """.trimIndent()
        val specFile = File.createTempFile("nested-prop", ".yaml").apply {
            writeText(yaml)
            deleteOnExit()
        }
        val spec = when (val r = SpecParser.parse(specFile)) {
            is ParseResult.Success -> r.value
            is ParseResult.Failure -> fail("parse failed: ${r.error}")
        }

        val outputDir = Files.createTempDirectory("codegen-prop").toFile()
        try {
            CodeGenerator.generate(spec, "com.example.model", "com.example.api", outputDir)

            val modelDir = outputDir.resolve("com/example/model")
            val files = modelDir.listFiles()?.map { it.name }.orEmpty()
            assertTrue(files.contains("DataSetList.kt"), "got: $files")
            // The inline `address` object is nested in DataSetList, not a separate file.
            assertTrue(files.none { it.contains("Address") }, "inline property should not be top-level, got: $files")

            val src = modelDir.resolve("DataSetList.kt").readText()
            assertTrue(src.contains("data class Address"), "address should be nested in DataSetList")
            assertTrue(src.contains("address: Address"), "property should reference the nested type")
        } finally {
            outputDir.deleteRecursively()
        }
    }

    @Test
    fun `lowercase component schema names become PascalCase types`() {
        val yaml = """
            openapi: "3.0.0"
            info: { title: T, version: "1" }
            paths: {}
            components:
              schemas:
                dataSetList:
                  type: object
                  properties:
                    owner: { ${'$'}ref: '#/components/schemas/data_owner' }
                data_owner:
                  type: object
                  properties:
                    name: { type: string }
        """.trimIndent()
        val specFile = File.createTempFile("pascal", ".yaml").apply {
            writeText(yaml)
            deleteOnExit()
        }
        val spec = when (val r = SpecParser.parse(specFile)) {
            is ParseResult.Success -> r.value
            is ParseResult.Failure -> fail("parse failed: ${r.error}")
        }

        val outputDir = Files.createTempDirectory("codegen-pascal").toFile()
        try {
            CodeGenerator.generate(spec, "com.example.model", "com.example.api", outputDir)
            val modelDir = outputDir.resolve("com/example/model")
            val files = modelDir.listFiles()?.map { it.name }.orEmpty()
            assertTrue(files.contains("DataSetList.kt"), "got: $files")
            assertTrue(files.contains("DataOwner.kt"), "got: $files")

            val src = modelDir.resolve("DataSetList.kt").readText()
            assertTrue(src.contains("data class DataSetList"), "type name should be PascalCase")
            // $ref to data_owner resolves to the PascalCase DataOwner type.
            assertTrue(src.contains("owner: DataOwner"), "ref should resolve to PascalCase type; src: $src")
        } finally {
            outputDir.deleteRecursively()
        }
    }
}
