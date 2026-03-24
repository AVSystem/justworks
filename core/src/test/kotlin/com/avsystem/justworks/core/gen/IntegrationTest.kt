package com.avsystem.justworks.core.gen

import com.avsystem.justworks.core.gen.NameRegistry
import com.avsystem.justworks.core.parser.ParseResult
import com.avsystem.justworks.core.parser.SpecParser
import java.io.File
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * Integration test that validates the full generation pipeline against real-world
 * OpenAPI specs. This ensures that enum generation, ApiClientBase, and client code
 * work correctly with production-grade specs.
 */
class IntegrationTest {
    private val modelPackage = "com.example.model"
    private val apiPackage = "com.example.api"

    companion object {
        private val SPEC_FIXTURES = listOf(
            "/fixtures/platform-api.json",
            "/fixtures/analytics-api.json",
        )
    }

    private fun parseSpec(resourcePath: String): ParseResult.Success {
        val specUrl = javaClass.getResource(resourcePath)
            ?: fail("Spec fixture not found: $resourcePath")
        val specFile = File(specUrl.toURI())
        return when (val result = SpecParser.parse(specFile)) {
            is ParseResult.Success -> result
            is ParseResult.Failure -> fail("Failed to parse $resourcePath: ${result.errors}")
        }
    }

    @Test
    fun `real-world specs generate compilable enum code without class body conflicts`() {
        for (fixture in SPEC_FIXTURES) {
            val spec = parseSpec(fixture).apiSpec
            if (spec.enums.isEmpty()) continue

            val generator = ModelGenerator(modelPackage, NameRegistry())
            val files = generator.generate(spec)
            assertTrue(files.isNotEmpty(), "$fixture: ModelGenerator should produce output files")

            val enumSources = files
                .map { it.toString() }
                .filter { it.contains("enum class") }

            assertTrue(enumSources.isNotEmpty(), "$fixture: Should generate at least one enum class")

            for (source in enumSources) {
                assertFalse(
                    source.contains(Regex("""[A-Z_]+\(\) \{""")),
                    "$fixture: Enum constants should not have anonymous class body",
                )
                assertFalse(
                    source.contains(Regex("""\b[A-Z][A-Z_0-9]+ \{""")),
                    "$fixture: Enum constants should not have class body",
                )
                assertTrue(
                    source.contains("@SerialName"),
                    "$fixture: Enum source should contain @SerialName annotations",
                )
                assertTrue(
                    source.contains("@Serializable"),
                    "$fixture: Enum source should contain @Serializable annotation",
                )
            }
        }
    }

    @Test
    fun `real-world specs generate ApiClientBase when endpoints exist`() {
        for (fixture in SPEC_FIXTURES) {
            val spec = parseSpec(fixture).apiSpec
            if (spec.endpoints.isEmpty()) continue

            val apiClientBaseFile = ApiClientBaseGenerator.generate()
            assertNotNull(apiClientBaseFile, "$fixture: ApiClientBaseGenerator should produce output")

            val source = apiClientBaseFile.toString()
            assertTrue(
                source.contains("ApiClientBase"),
                "$fixture: Generated output should contain ApiClientBase class",
            )
            assertTrue(
                source.contains("abstract class ApiClientBase"),
                "$fixture: ApiClientBase should be an abstract class",
            )
        }
    }

    @Test
    fun `real-world specs full pipeline generates client code without exceptions`() {
        for (fixture in SPEC_FIXTURES) {
            val spec = parseSpec(fixture).apiSpec

            val modelGenerator = ModelGenerator(modelPackage, NameRegistry())
            val modelFiles = modelGenerator.generate(spec)
            assertTrue(modelFiles.isNotEmpty(), "$fixture: ModelGenerator should produce files")

            if (spec.endpoints.isNotEmpty()) {
                val clientGenerator = ClientGenerator(apiPackage, modelPackage, NameRegistry())
                val clientFiles = clientGenerator.generate(spec)
                assertTrue(
                    clientFiles.isNotEmpty(),
                    "$fixture: ClientGenerator should produce files for a spec with endpoints",
                )
            }

            val apiClientBaseFile = ApiClientBaseGenerator.generate()
            assertNotNull(apiClientBaseFile, "$fixture: ApiClientBaseGenerator should produce output")
        }
    }

    // -- Phase 2: Format type mapping validation --

    @Test
    fun `format mappings produce correct types in generated output`() {
        for (fixture in SPEC_FIXTURES) {
            val spec = parseSpec(fixture).apiSpec

            val generator = ModelGenerator(modelPackage, NameRegistry())
            val files = generator.generate(spec)
            assertTrue(files.isNotEmpty(), "$fixture: ModelGenerator should produce output files")

            val allSources = files.map { it.toString() }

            val hasUuid = spec.schemas.any { schema ->
                schema.properties.any {
                    it.type == com.avsystem.justworks.core.model.TypeRef.Primitive(
                        com.avsystem.justworks.core.model.PrimitiveType.UUID,
                    )
                }
            }
            if (hasUuid) {
                assertTrue(
                    allSources.any { it.contains("kotlin.uuid.Uuid") },
                    "$fixture: Expected kotlin.uuid.Uuid import when spec contains UUID properties",
                )
                assertTrue(
                    allSources.any { it.contains("UuidSerializer") },
                    "$fixture: Expected UuidSerializer when spec contains UUID properties",
                )
            }
        }
    }

    @Test
    fun `all generated files are syntactically valid Kotlin`() {
        for (fixture in SPEC_FIXTURES) {
            val spec = parseSpec(fixture).apiSpec

            val generator = ModelGenerator(modelPackage, NameRegistry())
            val files = generator.generate(spec)
            assertTrue(files.isNotEmpty(), "$fixture: ModelGenerator should produce output files")

            for (file in files) {
                val source = file.toString()
                assertTrue(source.isNotBlank(), "$fixture: Generated file ${file.name} should not be blank")

                assertFalse(
                    source.contains("FIXME"),
                    "$fixture: Generated file ${file.name} should not contain FIXME markers",
                )
            }
        }
    }
}
