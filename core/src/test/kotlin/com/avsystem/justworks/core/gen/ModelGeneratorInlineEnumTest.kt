package com.avsystem.justworks.core.gen

import com.avsystem.justworks.core.gen.model.ModelGenerator
import com.avsystem.justworks.core.model.ApiSpec
import com.avsystem.justworks.core.model.EnumBackingType
import com.avsystem.justworks.core.model.PropertyModel
import com.avsystem.justworks.core.model.SchemaModel
import com.avsystem.justworks.core.model.TypeRef
import com.squareup.kotlinpoet.TypeSpec
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Inline enums are lifted by [ApiSpec.transform] and generated as enum classes nested inside the
 * type that owns them (mirroring how inline objects are nested), named after their position.
 */
class ModelGeneratorInlineEnumTest {
    private val modelPackage = "com.example.model"

    private fun generate(spec: ApiSpec) = spec.transform().let { transformed ->
        context(
            Hierarchy(ModelPackage(modelPackage)).apply { addSchemas(transformed.schemas.map { it.schema }) },
            NameRegistry(),
        ) {
            ModelGenerator.generate(transformed)
        }
    }

    private fun allTypes(files: List<com.squareup.kotlinpoet.FileSpec>): List<TypeSpec> {
        fun flatten(types: List<TypeSpec>): List<TypeSpec> = types.flatMap { listOf(it) + flatten(it.typeSpecs) }
        return flatten(files.flatMap { it.members.filterIsInstance<TypeSpec>() })
    }

    private fun schema(name: String, vararg properties: PropertyModel) = SchemaModel(
        name = name,
        description = null,
        properties = properties.toList(),
        requiredProperties = properties.filterNot { it.nullable }.map { it.name }.toSet(),
        allOf = null,
        oneOf = null,
        anyOf = null,
        discriminator = null,
    )

    private fun spec(schema: SchemaModel) =
        ApiSpec("Test", "1.0", emptyList(), listOf(schema), emptyList(), emptyList())

    @Test
    fun `inline enum in array items generates a typed enum nested in the owner`() {
        val schema = schema(
            "ExecuteSpeedTestRequest",
            PropertyModel(
                "measurements",
                TypeRef.Array(TypeRef.InlineEnum(listOf("DownloadSpeed", "UploadSpeed"), EnumBackingType.STRING)),
                null,
                true,
            ),
        )

        val types = allTypes(generate(spec(schema)))
        val enumType = assertNotNull(types.find { it.name == "MeasurementsItem" }, "Expected nested enum")
        assertEquals(setOf("DOWNLOAD_SPEED", "UPLOAD_SPEED"), enumType.enumConstants.keys)

        val measurements = types
            .first { it.name == "ExecuteSpeedTestRequest" }
            .propertySpecs
            .first { it.name == "measurements" }
        assertTrue(
            measurements.type.toString().contains("ExecuteSpeedTestRequest.MeasurementsItem"),
            "Expected property to reference the nested enum, got: ${measurements.type}",
        )
    }

    @Test
    fun `integer-backed inline enum generates a typed enum`() {
        val schema = schema(
            "Request",
            PropertyModel(
                "codes",
                TypeRef.Array(TypeRef.InlineEnum(listOf("100", "200"), EnumBackingType.INTEGER)),
                null,
                true,
            ),
        )

        val enumType = allTypes(generate(spec(schema))).find { it.name == "CodesItem" }
        assertNotNull(enumType, "Expected nested enum 'CodesItem'")
        assertEquals(setOf("100", "200"), enumType.enumConstants.keys)
    }

    @Test
    fun `inline enum directly on a property generates a typed enum`() {
        val schema = schema(
            "Request",
            PropertyModel(
                "status",
                TypeRef.InlineEnum(listOf("Active", "Inactive"), EnumBackingType.STRING),
                null,
                false,
            ),
        )

        val types = allTypes(generate(spec(schema)))
        val enumType = assertNotNull(types.find { it.name == "Status" }, "Expected nested enum 'Status'")
        assertEquals(setOf("ACTIVE", "INACTIVE"), enumType.enumConstants.keys)

        val status = types.first { it.name == "Request" }.propertySpecs.first { it.name == "status" }
        assertTrue(
            status.type.toString().contains("Request.Status"),
            "Expected property to reference the nested enum, got: ${status.type}",
        )
    }

    @Test
    fun `inline enum as a map value generates a typed enum`() {
        val schema = schema(
            "Request",
            PropertyModel(
                "byRegion",
                TypeRef.Map(TypeRef.InlineEnum(listOf("Eu", "Us"), EnumBackingType.STRING)),
                null,
                true,
            ),
        )

        val types = allTypes(generate(spec(schema)))
        val enumType = assertNotNull(types.find { it.name == "ByRegionValue" }, "Expected nested enum 'ByRegionValue'")
        assertEquals(setOf("EU", "US"), enumType.enumConstants.keys)

        val byRegion = types.first { it.name == "Request" }.propertySpecs.first { it.name == "byRegion" }
        assertTrue(
            byRegion.type.toString().contains("Request.ByRegionValue"),
            "Expected map value to reference the nested enum, got: ${byRegion.type}",
        )
    }
}
