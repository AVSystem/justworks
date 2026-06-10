package com.avsystem.justworks.core.gen

import com.avsystem.justworks.core.gen.model.ModelGenerator
import com.avsystem.justworks.core.model.ApiSpec
import com.avsystem.justworks.core.model.PrimitiveType
import com.avsystem.justworks.core.model.PropertyModel
import com.avsystem.justworks.core.model.SchemaModel
import com.avsystem.justworks.core.model.TypeRef
import kotlin.test.Test

class ModelGeneratorRegressionTest {
    private val modelPackage = "com.example.model"

    private fun generate(spec: ApiSpec): List<com.squareup.kotlinpoet.FileSpec> {
        val transformed = spec.transform()
        val hierarchy = Hierarchy(ModelPackage(modelPackage)).apply {
            addSchemas(transformed.schemas.map { it.schema })
        }
        return context(hierarchy, NameRegistry()) { ModelGenerator.generate(transformed) }
    }

    private fun spec(schemas: List<SchemaModel> = emptyList()) = ApiSpec(
        title = "Test",
        version = "1.0",
        endpoints = emptyList(),
        schemas = schemas,
        enums = emptyList(),
        securitySchemes = emptyList(),
    )

    private fun schema(
        name: String,
        properties: List<PropertyModel> = emptyList(),
        requiredProperties: Set<String> = emptySet(),
        oneOf: List<TypeRef>? = null,
    ) = SchemaModel(
        name = name,
        description = null,
        properties = properties,
        requiredProperties = requiredProperties,
        allOf = null,
        oneOf = oneOf,
        anyOf = null,
        discriminator = null,
    )

    @Test
    fun `reproduce issue where variant with inline property causes crash`() {
        val shapeSchema = schema(
            name = "Shape",
            oneOf = listOf(TypeRef.Reference("Circle")),
        )
        val circleSchema = schema(
            name = "Circle",
            properties = listOf(
                PropertyModel(
                    name = "config",
                    type = TypeRef.Inline(
                        properties = listOf(
                            PropertyModel("radius", TypeRef.Primitive(PrimitiveType.DOUBLE), null, false),
                        ),
                        requiredProperties = setOf("radius"),
                    ),
                    description = null,
                    nullable = false,
                ),
            ),
            requiredProperties = setOf("config"),
        )

        // This should not crash
        generate(spec(schemas = listOf(shapeSchema, circleSchema)))
    }
}
