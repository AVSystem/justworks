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

class ModelGeneratorInlineEnumTest {
    private val modelPackage = "com.example.model"

    private fun generate(spec: ApiSpec) = context(
        Hierarchy(ModelPackage(modelPackage)).apply { addSchemas(spec.schemas) },
        NameRegistry(),
    ) {
        ModelGenerator.generate(spec)
    }

    private val inlineEnumSchema = SchemaModel(
        name = "ExecuteSpeedTestRequest",
        description = null,
        properties = listOf(
            PropertyModel(
                name = "measurements",
                type = TypeRef.Array(
                    TypeRef.InlineEnum(
                        values = listOf("DownloadSpeed", "UploadSpeed"),
                        backingType = EnumBackingType.STRING,
                        contextHint = "ExecuteSpeedTestRequest.MeasurementsItem",
                    ),
                ),
                description = null,
                nullable = true,
            ),
        ),
        requiredProperties = emptySet(),
        allOf = null,
        oneOf = null,
        anyOf = null,
        discriminator = null,
    )

    @Test
    fun `inline enum in array items generates a typed enum class`() {
        val files = generate(
            ApiSpec("Test", "1.0", emptyList(), listOf(inlineEnumSchema), emptyList(), emptyList()),
        )

        val enumName = "ExecuteSpeedTestRequest_MeasurementsItem"
        val enumType = files
            .flatMap { it.members.filterIsInstance<TypeSpec>() }
            .find { it.name == enumName }
        assertNotNull(enumType, "Expected generated enum '$enumName'")
        assertEquals(
            setOf("DOWNLOAD_SPEED", "UPLOAD_SPEED"),
            enumType.enumConstants.keys,
        )
    }

    @Test
    fun `property references the generated enum instead of String`() {
        val files = generate(
            ApiSpec("Test", "1.0", emptyList(), listOf(inlineEnumSchema), emptyList(), emptyList()),
        )

        val dataClass = files
            .flatMap { it.members.filterIsInstance<TypeSpec>() }
            .find { it.name == "ExecuteSpeedTestRequest" }
        assertNotNull(dataClass, "Expected data class")
        val measurements = dataClass.propertySpecs.first { it.name == "measurements" }
        val typeString = measurements.type.toString()
        assertTrue(
            typeString.contains("ExecuteSpeedTestRequest_MeasurementsItem"),
            "Expected property to reference generated enum, got: $typeString",
        )
    }

    @Test
    fun `two properties with identical inline enums dedup to a single generated enum`() {
        val values = listOf("DownloadSpeed", "UploadSpeed")
        val schema = SchemaModel(
            name = "Request",
            description = null,
            properties = listOf(
                PropertyModel(
                    name = "primary",
                    type = TypeRef.Array(
                        TypeRef.InlineEnum(values, EnumBackingType.STRING, "Request.PrimaryItem"),
                    ),
                    description = null,
                    nullable = true,
                ),
                PropertyModel(
                    name = "secondary",
                    type = TypeRef.Array(
                        TypeRef.InlineEnum(values, EnumBackingType.STRING, "Request.SecondaryItem"),
                    ),
                    description = null,
                    nullable = true,
                ),
            ),
            requiredProperties = emptySet(),
            allOf = null,
            oneOf = null,
            anyOf = null,
            discriminator = null,
        )

        val files = generate(
            ApiSpec("Test", "1.0", emptyList(), listOf(schema), emptyList(), emptyList()),
        )

        val enums = files
            .flatMap { it.members.filterIsInstance<TypeSpec>() }
            .filter { it.enumConstants.isNotEmpty() }
        assertEquals(1, enums.size, "Expected exactly one generated enum, got: ${enums.map { it.name }}")

        // Both properties reference the same generated enum.
        val dataClass = files
            .flatMap { it.members.filterIsInstance<TypeSpec>() }
            .first { it.name == "Request" }
        val enumName = enums.single().name
        assertNotNull(enumName)
        dataClass.propertySpecs.forEach { prop ->
            assertTrue(
                prop.type.toString().contains(enumName),
                "Expected '${prop.name}' to reference '$enumName', got: ${prop.type}",
            )
        }
    }

    @Test
    fun `inline schemas differing only in a nested enum contextHint dedup to one`() {
        fun wrapper(enumHint: String, propHint: String) = TypeRef.Inline(
            properties = listOf(
                PropertyModel(
                    name = "mode",
                    type = TypeRef.InlineEnum(
                        values = listOf("Fast", "Slow"),
                        backingType = EnumBackingType.STRING,
                        contextHint = enumHint,
                    ),
                    description = null,
                    nullable = false,
                ),
            ),
            requiredProperties = setOf("mode"),
            contextHint = propHint,
        )

        val schema = SchemaModel(
            name = "Request",
            description = null,
            properties = listOf(
                PropertyModel("a", wrapper("Request.A.Mode", "Request.A"), null, false),
                PropertyModel("b", wrapper("Request.B.Mode", "Request.B"), null, false),
            ),
            requiredProperties = emptySet(),
            allOf = null,
            oneOf = null,
            anyOf = null,
            discriminator = null,
        )

        val files = generate(
            ApiSpec("Test", "1.0", emptyList(), listOf(schema), emptyList(), emptyList()),
        )

        val types = files.flatMap { it.members.filterIsInstance<TypeSpec>() }
        // One wrapper data class (the nested inline schema) + one enum, despite two properties.
        val wrappers = types.filter { it.name != "Request" && it.enumConstants.isEmpty() }
        val enums = types.filter { it.enumConstants.isNotEmpty() }
        assertEquals(1, wrappers.size, "Expected one deduped inline schema, got: ${wrappers.map { it.name }}")
        assertEquals(1, enums.size, "Expected one deduped inline enum, got: ${enums.map { it.name }}")
    }

    @Test
    fun `integer-backed inline enum generates a typed enum class`() {
        val schema = SchemaModel(
            name = "Request",
            description = null,
            properties = listOf(
                PropertyModel(
                    name = "codes",
                    type = TypeRef.Array(
                        TypeRef.InlineEnum(
                            values = listOf("100", "200"),
                            backingType = EnumBackingType.INTEGER,
                            contextHint = "Request.CodesItem",
                        ),
                    ),
                    description = null,
                    nullable = true,
                ),
            ),
            requiredProperties = emptySet(),
            allOf = null,
            oneOf = null,
            anyOf = null,
            discriminator = null,
        )

        val files = generate(
            ApiSpec("Test", "1.0", emptyList(), listOf(schema), emptyList(), emptyList()),
        )

        val enumType = files
            .flatMap { it.members.filterIsInstance<TypeSpec>() }
            .find { it.name == "Request_CodesItem" }
        assertNotNull(enumType, "Expected generated enum 'Request_CodesItem'")
        assertEquals(setOf("100", "200"), enumType.enumConstants.keys)
    }

    @Test
    fun `inline enum directly on a property generates a typed enum class`() {
        val schema = SchemaModel(
            name = "Request",
            description = null,
            properties = listOf(
                PropertyModel(
                    name = "status",
                    type = TypeRef.InlineEnum(
                        values = listOf("Active", "Inactive"),
                        backingType = EnumBackingType.STRING,
                        contextHint = "Request.Status",
                    ),
                    description = null,
                    nullable = false,
                ),
            ),
            requiredProperties = setOf("status"),
            allOf = null,
            oneOf = null,
            anyOf = null,
            discriminator = null,
        )

        val files = generate(
            ApiSpec("Test", "1.0", emptyList(), listOf(schema), emptyList(), emptyList()),
        )

        val types = files.flatMap { it.members.filterIsInstance<TypeSpec>() }
        val enumType = types.find { it.name == "Request_Status" }
        assertNotNull(enumType, "Expected generated enum 'Request_Status'")
        assertEquals(setOf("ACTIVE", "INACTIVE"), enumType.enumConstants.keys)

        val status = types.first { it.name == "Request" }.propertySpecs.first { it.name == "status" }
        assertTrue(
            status.type.toString().contains("Request_Status"),
            "Expected property to reference generated enum, got: ${status.type}",
        )
    }

    @Test
    fun `inline enum as a map value generates a typed enum class`() {
        val schema = SchemaModel(
            name = "Request",
            description = null,
            properties = listOf(
                PropertyModel(
                    name = "byRegion",
                    type = TypeRef.Map(
                        TypeRef.InlineEnum(
                            values = listOf("Eu", "Us"),
                            backingType = EnumBackingType.STRING,
                            contextHint = "Request.ByRegionValue",
                        ),
                    ),
                    description = null,
                    nullable = true,
                ),
            ),
            requiredProperties = emptySet(),
            allOf = null,
            oneOf = null,
            anyOf = null,
            discriminator = null,
        )

        val files = generate(
            ApiSpec("Test", "1.0", emptyList(), listOf(schema), emptyList(), emptyList()),
        )

        val types = files.flatMap { it.members.filterIsInstance<TypeSpec>() }
        val enumType = types.find { it.name == "Request_ByRegionValue" }
        assertNotNull(enumType, "Expected generated enum 'Request_ByRegionValue'")
        assertEquals(setOf("EU", "US"), enumType.enumConstants.keys)

        val byRegion = types.first { it.name == "Request" }.propertySpecs.first { it.name == "byRegion" }
        assertTrue(
            byRegion.type.toString().contains("Request_ByRegionValue"),
            "Expected map value to reference generated enum, got: ${byRegion.type}",
        )
    }
}
