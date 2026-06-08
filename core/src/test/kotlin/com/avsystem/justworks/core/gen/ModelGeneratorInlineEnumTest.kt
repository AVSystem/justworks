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
}
