package com.avsystem.justworks.core.gen

import com.avsystem.justworks.core.parser.ParseResult
import com.avsystem.justworks.core.parser.SpecParser
import java.io.File
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * Integration test that validates the full generation pipeline against a real-world
 * OpenAPI spec. This ensures that enum generation, ApiClientBase, and client code
 * work correctly with production-grade specs.
 *
 * The spec fixture is NOT committed to version control (see .gitignore).
 * To run this test locally, place a large OpenAPI 3.0 JSON spec
 * as core/src/test/resources/fixtures/real-world-spec.json.
 */
class IntegrationTest {
    private val modelPackage = "com.example.model"
    private val apiPackage = "com.example.api"

    private fun parseRealWorldSpec(): ParseResult.Success {
        val specUrl = javaClass.getResource("/fixtures/real-world-spec.json")
        if (specUrl == null) {
            println("SKIPPED: real-world spec fixture not found in test resources")
            return ParseResult.Success(
                apiSpec = com.avsystem.justworks.core.model.ApiSpec(
                    title = "skip",
                    version = "0",
                    endpoints = emptyList(),
                    schemas = emptyList(),
                    enums = emptyList(),
                ),
            )
        }
        val specFile = File(specUrl.toURI())
        return when (val result = SpecParser.parse(specFile)) {
            is ParseResult.Success -> result
            is ParseResult.Failure -> fail("Failed to parse real-world spec: ${result.errors}")
        }
    }

    private fun isFixtureAvailable(): Boolean = javaClass.getResource("/fixtures/real-world-spec.json") != null

    @Test
    fun `real-world spec generates compilable enum code without class body conflicts`() {
        if (!isFixtureAvailable()) {
            println("SKIPPED: real-world spec fixture not available")
            return
        }

        val parsed = parseRealWorldSpec()
        val spec = parsed.apiSpec
        assertTrue(spec.enums.isNotEmpty(), "Spec should contain enum definitions")

        val generator = ModelGenerator(modelPackage)
        val files = generator.generate(spec)
        assertTrue(files.isNotEmpty(), "ModelGenerator should produce output files")

        val enumSources = files
            .map { it.toString() }
            .filter { it.contains("enum class") }

        assertTrue(enumSources.isNotEmpty(), "Should generate at least one enum class")

        for (source in enumSources) {
            assertFalse(
                source.contains(Regex("""[A-Z_]+\(\) \{""")),
                "Enum constants should not have anonymous class body () {}: found in:\n${source.take(500)}",
            )
            assertFalse(
                source.contains(Regex("""\b[A-Z][A-Z_0-9]+ \{""")),
                "Enum constants should not have class body { }: found in:\n${source.take(500)}",
            )

            assertTrue(
                source.contains("@SerialName"),
                "Enum source should contain @SerialName annotations:\n${source.take(500)}",
            )

            assertTrue(
                source.contains("@Serializable"),
                "Enum source should contain @Serializable annotation:\n${source.take(500)}",
            )
        }
    }

    @Test
    fun `real-world spec generates ApiClientBase when endpoints exist`() {
        if (!isFixtureAvailable()) {
            println("SKIPPED: real-world spec fixture not available")
            return
        }

        val parsed = parseRealWorldSpec()
        val spec = parsed.apiSpec
        assertTrue(spec.endpoints.isNotEmpty(), "Spec should contain endpoints")

        val apiClientBaseFile = ApiClientBaseGenerator.generate()
        assertNotNull(apiClientBaseFile, "ApiClientBaseGenerator should produce output")

        val source = apiClientBaseFile.toString()
        assertTrue(
            source.contains("ApiClientBase"),
            "Generated output should contain ApiClientBase class",
        )
        assertTrue(
            source.contains("abstract class ApiClientBase"),
            "ApiClientBase should be an abstract class",
        )
    }

    @Test
    fun `real-world spec full pipeline generates client code without exceptions`() {
        if (!isFixtureAvailable()) {
            println("SKIPPED: real-world spec fixture not available")
            return
        }

        val parsed = parseRealWorldSpec()
        val spec = parsed.apiSpec

        val modelGenerator = ModelGenerator(modelPackage)
        val modelFiles = modelGenerator.generate(spec)
        assertTrue(modelFiles.isNotEmpty(), "ModelGenerator should produce files")

        val clientGenerator = ClientGenerator(apiPackage, modelPackage)
        val clientFiles = clientGenerator.generate(spec)
        assertTrue(clientFiles.isNotEmpty(), "ClientGenerator should produce files for a spec with endpoints")

        val apiClientBaseFile = ApiClientBaseGenerator.generate()
        assertNotNull(apiClientBaseFile, "ApiClientBaseGenerator should produce output")

        println("Integration test passed: generated ${modelFiles.size} model files, ${clientFiles.size} client files")
    }
}
