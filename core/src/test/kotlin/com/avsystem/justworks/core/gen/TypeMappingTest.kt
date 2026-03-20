package com.avsystem.justworks.core.gen

import com.avsystem.justworks.core.model.PrimitiveType
import com.avsystem.justworks.core.model.PropertyModel
import com.avsystem.justworks.core.model.TypeRef
import kotlin.test.Test
import kotlin.test.assertEquals

class TypeMappingTest {
    private val pkg = "com.example.model"

    // -- Primitive types --

    @Test
    fun `maps STRING to kotlin String`() {
        val result = TypeMapping.toTypeName(TypeRef.Primitive(PrimitiveType.STRING), pkg)
        assertEquals("kotlin.String", result.toString())
    }

    @Test
    fun `maps INT to kotlin Int`() {
        val result = TypeMapping.toTypeName(TypeRef.Primitive(PrimitiveType.INT), pkg)
        assertEquals("kotlin.Int", result.toString())
    }

    @Test
    fun `maps LONG to kotlin Long`() {
        val result = TypeMapping.toTypeName(TypeRef.Primitive(PrimitiveType.LONG), pkg)
        assertEquals("kotlin.Long", result.toString())
    }

    @Test
    fun `maps DOUBLE to kotlin Double`() {
        val result = TypeMapping.toTypeName(TypeRef.Primitive(PrimitiveType.DOUBLE), pkg)
        assertEquals("kotlin.Double", result.toString())
    }

    @Test
    fun `maps FLOAT to kotlin Float`() {
        val result = TypeMapping.toTypeName(TypeRef.Primitive(PrimitiveType.FLOAT), pkg)
        assertEquals("kotlin.Float", result.toString())
    }

    @Test
    fun `maps BOOLEAN to kotlin Boolean`() {
        val result = TypeMapping.toTypeName(TypeRef.Primitive(PrimitiveType.BOOLEAN), pkg)
        assertEquals("kotlin.Boolean", result.toString())
    }

    @Test
    fun `maps BYTE_ARRAY to kotlin ByteArray`() {
        val result = TypeMapping.toTypeName(TypeRef.Primitive(PrimitiveType.BYTE_ARRAY), pkg)
        assertEquals("kotlin.ByteArray", result.toString())
    }

    @Test
    fun `maps DATE_TIME to kotlin time Instant`() {
        val result = TypeMapping.toTypeName(TypeRef.Primitive(PrimitiveType.DATE_TIME), pkg)
        assertEquals("kotlin.time.Instant", result.toString())
    }

    @Test
    fun `maps DATE to kotlinx datetime LocalDate`() {
        val result = TypeMapping.toTypeName(TypeRef.Primitive(PrimitiveType.DATE), pkg)
        assertEquals("kotlinx.datetime.LocalDate", result.toString())
    }

    @Test
    fun `maps UUID to kotlin uuid Uuid`() {
        val result = TypeMapping.toTypeName(TypeRef.Primitive(PrimitiveType.UUID), pkg)
        assertEquals("kotlin.uuid.Uuid", result.toString())
    }

    // -- Array --

    @Test
    fun `maps Array of String to List of String`() {
        val ref = TypeRef.Array(TypeRef.Primitive(PrimitiveType.STRING))
        val result = TypeMapping.toTypeName(ref, pkg)
        assertEquals("kotlin.collections.List<kotlin.String>", result.toString())
    }

    // -- Map --

    @Test
    fun `maps Map of String to Map with String key and String value`() {
        val ref = TypeRef.Map(TypeRef.Primitive(PrimitiveType.STRING))
        val result = TypeMapping.toTypeName(ref, pkg)
        assertEquals("kotlin.collections.Map<kotlin.String, kotlin.String>", result.toString())
    }

    // -- Reference --

    @Test
    fun `maps Reference to ClassName in model package`() {
        val ref = TypeRef.Reference("Pet")
        val result = TypeMapping.toTypeName(ref, pkg)
        assertEquals("com.example.model.Pet", result.toString())
    }

    // -- Nested generics --

    @Test
    fun `maps Array of Reference to List of model class`() {
        val ref = TypeRef.Array(TypeRef.Reference("Pet"))
        val result = TypeMapping.toTypeName(ref, pkg)
        assertEquals("kotlin.collections.List<com.example.model.Pet>", result.toString())
    }

    // -- Inline --

    @Test
    fun `maps Inline to ClassName using contextHint`() {
        val ref = TypeRef.Inline(
            properties = listOf(PropertyModel("name", TypeRef.Primitive(PrimitiveType.STRING), null, false)),
            requiredProperties = setOf("name"),
            contextHint = "Pet.Address",
        )
        val result = TypeMapping.toTypeName(ref, pkg)
        assertEquals("com.example.model.Pet_Address", result.toString())
    }

    // -- Unknown --

    @Test
    fun `maps Unknown to kotlin Any`() {
        val result = TypeMapping.toTypeName(TypeRef.Unknown, pkg)
        assertEquals("kotlin.Any", result.toString())
    }
}
