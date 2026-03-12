package com.avsystem.justworks.core.gen

import com.avsystem.justworks.core.model.PrimitiveType
import com.avsystem.justworks.core.model.PropertyModel
import com.avsystem.justworks.core.model.SchemaModel
import com.avsystem.justworks.core.model.TypeRef
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals

class InlineSchemaDedupTest {
    @Test
    fun `identical schemas return same name`() {
        val deduplicator = InlineSchemaDeduplicator()

        val props1 = listOf(
            PropertyModel("id", TypeRef.Primitive(PrimitiveType.INT), null, false),
            PropertyModel("name", TypeRef.Primitive(PrimitiveType.STRING), null, false),
        )
        val required = setOf("id", "name")

        val props2 = listOf(
            PropertyModel("id", TypeRef.Primitive(PrimitiveType.INT), null, false),
            PropertyModel("name", TypeRef.Primitive(PrimitiveType.STRING), null, false),
        )

        val name1 = deduplicator.getOrGenerateName(props1, required, "FirstContext")
        val name2 = deduplicator.getOrGenerateName(props2, required, "SecondContext")

        // First occurrence wins
        assertEquals("FirstContext", name1)
        assertEquals("FirstContext", name2)  // Same structure returns same name
    }

    @Test
    fun `different schemas return different names`() {
        val deduplicator = InlineSchemaDeduplicator()

        val props1 = listOf(
            PropertyModel("id", TypeRef.Primitive(PrimitiveType.INT), null, false),
        )

        val props2 = listOf(
            PropertyModel("name", TypeRef.Primitive(PrimitiveType.STRING), null, false),
        )

        val name1 = deduplicator.getOrGenerateName(props1, setOf("id"), "FirstContext")
        val name2 = deduplicator.getOrGenerateName(props2, setOf("name"), "SecondContext")

        assertEquals("FirstContext", name1)
        assertEquals("SecondContext", name2)
        assertNotEquals(name1, name2)
    }

    @Test
    fun `name collision with component schema appends Inline suffix`() {
        val deduplicator = InlineSchemaDeduplicator()

        val componentSchemas = listOf(
            SchemaModel(
                name = "Pet",
                description = null,
                properties = emptyList(),
                requiredProperties = emptySet(),
                isEnum = false,
                allOf = null,
                oneOf = null,
                anyOf = null,
                discriminator = null,
            ),
        )
        deduplicator.registerComponentSchemas(componentSchemas)

        val props = listOf(
            PropertyModel("id", TypeRef.Primitive(PrimitiveType.INT), null, false),
        )

        val name = deduplicator.getOrGenerateName(props, setOf("id"), "Pet")

        assertEquals("PetInline", name)  // Collision with component schema
    }

    @Test
    fun `property order does not affect equality`() {
        val deduplicator = InlineSchemaDeduplicator()

        val props1 = listOf(
            PropertyModel("name", TypeRef.Primitive(PrimitiveType.STRING), null, false),
            PropertyModel("id", TypeRef.Primitive(PrimitiveType.INT), null, false),
        )

        val props2 = listOf(
            PropertyModel("id", TypeRef.Primitive(PrimitiveType.INT), null, false),
            PropertyModel("name", TypeRef.Primitive(PrimitiveType.STRING), null, false),
        )

        val required = setOf("id", "name")

        val name1 = deduplicator.getOrGenerateName(props1, required, "FirstContext")
        val name2 = deduplicator.getOrGenerateName(props2, required, "SecondContext")

        // Same structure despite different order
        assertEquals("FirstContext", name1)
        assertEquals("FirstContext", name2)
    }

    @Test
    fun `different required sets produce different keys`() {
        val deduplicator = InlineSchemaDeduplicator()

        val props = listOf(
            PropertyModel("id", TypeRef.Primitive(PrimitiveType.INT), null, false),
            PropertyModel("name", TypeRef.Primitive(PrimitiveType.STRING), null, true),
        )

        val name1 = deduplicator.getOrGenerateName(props, setOf("id", "name"), "FirstContext")
        val name2 = deduplicator.getOrGenerateName(props, setOf("id"), "SecondContext")

        // Different required sets mean different structures
        assertEquals("FirstContext", name1)
        assertEquals("SecondContext", name2)
        assertNotEquals(name1, name2)
    }

    @Test
    fun `collision with existing inline schema name appends Inline suffix`() {
        val deduplicator = InlineSchemaDeduplicator()

        val props1 = listOf(
            PropertyModel("id", TypeRef.Primitive(PrimitiveType.INT), null, false),
        )
        val props2 = listOf(
            PropertyModel("name", TypeRef.Primitive(PrimitiveType.STRING), null, false),
        )

        // First schema gets the base name
        val name1 = deduplicator.getOrGenerateName(props1, setOf("id"), "Context")
        assertEquals("Context", name1)

        // Second schema (different structure) wants same name, gets Inline suffix
        val name2 = deduplicator.getOrGenerateName(props2, setOf("name"), "Context")
        assertEquals("ContextInline", name2)
    }
}
