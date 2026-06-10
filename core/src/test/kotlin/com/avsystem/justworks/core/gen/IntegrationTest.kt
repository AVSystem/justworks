package com.avsystem.justworks.core.gen

import com.avsystem.justworks.core.gen.client.ClientGenerator
import com.avsystem.justworks.core.gen.model.ModelGenerator
import com.avsystem.justworks.core.gen.shared.ApiClientBaseGenerator
import com.avsystem.justworks.core.model.ApiSpec
import com.avsystem.justworks.core.parser.ParseResult
import com.avsystem.justworks.core.parser.SpecParser
import com.squareup.kotlinpoet.FileSpec
import java.io.File
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertFalse
import kotlin.test.assertIs
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

        /** Vendored popular public OpenAPI specs (see fixtures/public/SOURCES.md). */
        private val PUBLIC_SPECS = listOf(
            "/fixtures/public/swagger-petstore.json",
            "/fixtures/public/petstore-expanded.yaml",
            "/fixtures/public/uspto.yaml",
        )
    }

    private fun parseSpec(resourcePath: String): ParseResult.Success<ApiSpec> {
        val specUrl = javaClass.getResource(resourcePath)
            ?: fail("Spec fixture not found: $resourcePath")
        val specFile = File(specUrl.toURI())
        return when (val result = SpecParser.parse(specFile)) {
            is ParseResult.Success -> result
            is ParseResult.Failure -> fail("Failed to parse $resourcePath: ${result.error}")
        }
    }

    private fun generateModel(spec: ApiSpec): List<FileSpec> =
        context(Hierarchy(ModelPackage(modelPackage)).apply { addSchemas(spec.schemas) }, NameRegistry()) {
            ModelGenerator.generate(spec)
        }

    private fun generateModelWithResolvedSpec(spec: ApiSpec): ModelGenerator.GenerateResult =
        context(Hierarchy(ModelPackage(modelPackage)).apply { addSchemas(spec.schemas) }, NameRegistry()) {
            ModelGenerator.generateWithResolvedSpec(spec)
        }

    private fun generateClient(spec: ApiSpec, hasPolymorphicTypes: Boolean = false): List<FileSpec> = context(
        Hierarchy(ModelPackage(modelPackage)).apply {
            addSchemas(spec.schemas)
        },
        ApiPackage(apiPackage),
        NameRegistry(),
    ) {
        ClientGenerator.generate(spec, hasPolymorphicTypes)
    }

    @Test
    fun `real-world specs generate compilable enum code without class body conflicts`() {
        for (fixture in SPEC_FIXTURES) {
            val spec = parseSpec(fixture).value
            if (spec.enums.isEmpty()) continue

            val files = generateModel(spec)
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
            val spec = parseSpec(fixture).value
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
            val spec = parseSpec(fixture).value

            val (modelFiles, resolvedSpec) = generateModelWithResolvedSpec(spec)
            assertTrue(modelFiles.isNotEmpty(), "$fixture: ModelGenerator should produce files")

            if (spec.endpoints.isNotEmpty()) {
                val clientFiles = generateClient(resolvedSpec)
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
            val spec = parseSpec(fixture).value

            val files = generateModel(spec)
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
    fun `generated client code does not reference Arrow`() {
        for (fixture in SPEC_FIXTURES) {
            val spec = parseSpec(fixture).value
            if (spec.endpoints.isEmpty()) continue

            val (_, resolvedSpec) = generateModelWithResolvedSpec(spec)
            val clientFiles = generateClient(resolvedSpec)
            val apiClientBaseFile = ApiClientBaseGenerator.generate()

            val allSources = (clientFiles + apiClientBaseFile).map { it.toString() }
            for (source in allSources) {
                assertFalse(
                    source.contains("arrow.core"),
                    "$fixture: Generated code should not contain Arrow imports",
                )
            }
        }
    }

    @Test
    fun `all generated files are syntactically valid Kotlin`() {
        for (fixture in SPEC_FIXTURES) {
            val spec = parseSpec(fixture).value

            val files = generateModel(spec)
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

    // -- Popular public OpenAPI specs (issue #47) --

    /** All generated sources for a fixture, indexed by KotlinPoet file name. */
    private class Generated(val modelByName: Map<String, String>, val clientByName: Map<String, String>,) {
        fun model(name: String): String =
            modelByName[name] ?: fail("Expected model file '$name', available: ${modelByName.keys}")

        fun client(name: String): String =
            clientByName[name] ?: fail("Expected client file '$name', available: ${clientByName.keys}")
    }

    private fun generateAll(fixture: String): Generated {
        val spec = parseSpec(fixture).value
        val (modelFiles, resolvedSpec) = generateModelWithResolvedSpec(spec)
        val clientFiles = if (spec.endpoints.isNotEmpty()) generateClient(resolvedSpec) else emptyList()
        return Generated(
            modelFiles.associate { it.name to it.toString() },
            clientFiles.associate { it.name to it.toString() },
        )
    }

    @Test
    fun `public specs parse without errors`() {
        for (fixture in PUBLIC_SPECS) {
            val specUrl = javaClass.getResource(fixture) ?: fail("Public spec fixture not found: $fixture")
            val result = SpecParser.parse(File(specUrl.toURI()))

            val failureError = (result as? ParseResult.Failure)?.error
            assertIs<ParseResult.Success<*>>(
                result,
                "$fixture should parse successfully, but failed: $failureError",
            )

            // Known limitations surface as warnings, never silent failures or hard errors.
            if (result.warnings.isNotEmpty()) {
                println("$fixture parsed with ${result.warnings.size} warning(s):")
                result.warnings.forEach { println("  - ${it.message}") }
            }
        }
    }

    @Test
    fun `swagger-petstore generates expected model and client code`() {
        val gen = generateAll("/fixtures/public/swagger-petstore.json")

        // Pet model: required props non-null, optional props nullable with defaults,
        // object refs become typed references, @SerialName preserves wire names.
        val pet = gen.model("Pet")
        assertContains(pet, "public data class Pet(")
        assertContains(pet, "public val name: String,") // required -> non-null, no default
        assertContains(pet, "public val photoUrls: List<String>,") // required array -> non-null List
        assertContains(pet, "public val id: Long? = null") // optional -> nullable with default
        assertContains(pet, "public val category: Category? = null") // $ref -> typed reference
        assertContains(pet, "public val tags: List<Tag>? = null") // array of $ref
        assertContains(pet, """@SerialName("photoUrls")""")

        // StoreApi.getInventory: object with additionalProperties: integer -> Map<String, Int>.
        val storeApi = gen.client("StoreApi")
        assertContains(
            storeApi,
            "public suspend fun getInventory(): HttpResult<JsonElement, Map<String, Int>>",
        )

        // PetApi.getPetById: int64 path param -> Long, path templated via encodeParam.
        val petApi = gen.client("PetApi")
        assertContains(
            petApi,
            "public suspend fun getPetById(petId: Long): HttpResult<JsonElement, Pet>",
        )
        assertContains(petApi, "/pet/\${encodeParam(petId)}")
    }

    @Test
    fun `petstore-expanded generates expected model and client code`() {
        val gen = generateAll("/fixtures/public/petstore-expanded.yaml")

        // Pet uses allOf(NewPet, {id}) -> flattened: name (from NewPet, required),
        // tag (from NewPet, optional), id (required in the inline part).
        val pet = gen.model("Pet")
        assertContains(pet, "public data class Pet(")
        assertContains(pet, "public val name: String,")
        assertContains(pet, "public val id: Long,") // required -> non-null
        assertContains(pet, "public val tag: String? = null")

        val error = gen.model("Error")
        assertContains(error, "public val code: Int,")
        assertContains(error, "public val message: String,")

        val api = gen.client("DefaultApi")
        // Optional query params -> nullable with default, appended only when non-null.
        assertContains(
            api,
            "public suspend fun findPets(tags: List<String>? = null, limit: Int? = null): " +
                "HttpResult<JsonElement, List<Pet>>",
        )
        // requestBody $ref -> typed body param + JSON content type + setBody.
        assertContains(api, "public suspend fun addPet(body: NewPet): HttpResult<JsonElement, Pet>")
        assertContains(api, "contentType(ContentType.Application.Json)")
        assertContains(api, "setBody(body)")
        // 204 No Content response -> Unit result via toEmptyResult().
        assertContains(api, "public suspend fun deletePet(id: Long): HttpResult<JsonElement, Unit>")
        assertContains(api, ".toEmptyResult()")
    }

    @Test
    fun `uspto generates expected model and degrades freeform response`() {
        val gen = generateAll("/fixtures/public/uspto.yaml")

        // Nested inline object array item gets a synthesized name.
        val dataSetList = gen.model("dataSetList")
        assertContains(dataSetList, "public val total: Int? = null")
        assertContains(dataSetList, "public val apis: List<dataSetList_ApisItem>? = null")

        val metadataApi = gen.client("MetadataApi")
        assertContains(
            metadataApi,
            "public suspend fun listDataSets(): HttpResult<JsonElement, dataSetList>",
        )

        // Freeform search response (no schema) degrades to JsonElement, never a hard failure.
        val searchApi = gen.client("SearchApi")
        assertContains(searchApi, "import kotlinx.serialization.json.JsonElement")
        assertContains(searchApi, "public suspend fun performSearch(")
    }
}
