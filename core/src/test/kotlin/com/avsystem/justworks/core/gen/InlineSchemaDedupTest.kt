package com.avsystem.justworks.core.gen

import com.avsystem.justworks.core.model.PrimitiveType
import com.avsystem.justworks.core.model.PropertyModel
import com.avsystem.justworks.core.model.TypeRef
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals

class InlineSchemaDedupTest {
    @Test
    fun `identical schemas return same name via InlineSchemaKey`() {
        val props1 = listOf(
            PropertyModel("id", TypeRef.Primitive(PrimitiveType.INT), null, false),
            PropertyModel("name", TypeRef.Primitive(PrimitiveType.STRING), null, false),
        )
        val required = setOf("id", "name")

        val props2 = listOf(
            PropertyModel("id", TypeRef.Primitive(PrimitiveType.INT), null, false),
            PropertyModel("name", TypeRef.Primitive(PrimitiveType.STRING), null, false),
        )

        val key1 = InlineSchemaKey.from(props1, required)
        val key2 = InlineSchemaKey.from(props2, required)

        assertEquals(key1, key2)
    }

    @Test
    fun `different schemas produce different keys`() {
        val props1 = listOf(
            PropertyModel("id", TypeRef.Primitive(PrimitiveType.INT), null, false),
        )

        val props2 = listOf(
            PropertyModel("name", TypeRef.Primitive(PrimitiveType.STRING), null, false),
        )

        val key1 = InlineSchemaKey.from(props1, setOf("id"))
        val key2 = InlineSchemaKey.from(props2, setOf("name"))

        assertNotEquals(key1, key2)
    }

    @Test
    fun `name collision with component schema uses numeric suffix`() {
        val registry = NameRegistry().apply {
            reserve("Pet")
        }

        val name = registry.register("Pet")

        assertEquals("Pet2", name)
    }

    @Test
    fun `property order does not affect equality`() {
        val props1 = listOf(
            PropertyModel("name", TypeRef.Primitive(PrimitiveType.STRING), null, false),
            PropertyModel("id", TypeRef.Primitive(PrimitiveType.INT), null, false),
        )

        val props2 = listOf(
            PropertyModel("id", TypeRef.Primitive(PrimitiveType.INT), null, false),
            PropertyModel("name", TypeRef.Primitive(PrimitiveType.STRING), null, false),
        )

        val required = setOf("id", "name")

        val key1 = InlineSchemaKey.from(props1, required)
        val key2 = InlineSchemaKey.from(props2, required)

        assertEquals(key1, key2)
    }

    @Test
    fun `different required sets produce different keys`() {
        val props = listOf(
            PropertyModel("id", TypeRef.Primitive(PrimitiveType.INT), null, false),
            PropertyModel("name", TypeRef.Primitive(PrimitiveType.STRING), null, true),
        )

        val key1 = InlineSchemaKey.from(props, setOf("id", "name"))
        val key2 = InlineSchemaKey.from(props, setOf("id"))

        assertNotEquals(key1, key2)
    }

    @Test
    fun `collision with existing inline schema name uses numeric suffix`() {
        val registry = NameRegistry()

        val name1 = registry.register("Context")
        assertEquals("Context", name1)

        val name2 = registry.register("Context")
        assertEquals("Context2", name2)
    }
}
