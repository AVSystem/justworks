package com.avsystem.justworks.core.gen

import com.avsystem.justworks.core.model.PrimitiveType
import com.avsystem.justworks.core.model.PropertyModel
import com.avsystem.justworks.core.model.TypeRef
import com.squareup.kotlinpoet.TypeName
import kotlin.test.Test
import kotlin.test.assertEquals

class TypeMappingTest {
    private val pkg = ModelPackage("com.example.model")

    private fun map(typeRef: TypeRef): TypeName = context(pkg) {
        TypeMapping.toTypeName(typeRef)
    }

    // -- Primitive types --

    @Test
    fun `maps STRING to kotlin String`() {
        val result = map(TypeRef.Primitive(PrimitiveType.STRING))
        assertEquals("kotlin.String", result.toString())
    }

    @Test
    fun `maps INT to kotlin Int`() {
        val result = map(TypeRef.Primitive(PrimitiveType.INT))
        assertEquals("kotlin.Int", result.toString())
    }

    @Test
    fun `maps LONG to kotlin Long`() {
        val result = map(TypeRef.Primitive(PrimitiveType.LONG))
        assertEquals("kotlin.Long", result.toString())
    }

    @Test
    fun `maps DOUBLE to kotlin Double`() {
        val result = map(TypeRef.Primitive(PrimitiveType.DOUBLE))
        assertEquals("kotlin.Double", result.toString())
    }

    @Test
    fun `maps FLOAT to kotlin Float`() {
        val result = map(TypeRef.Primitive(PrimitiveType.FLOAT))
        assertEquals("kotlin.Float", result.toString())
    }

    @Test
    fun `maps BOOLEAN to kotlin Boolean`() {
        val result = map(TypeRef.Primitive(PrimitiveType.BOOLEAN))
        assertEquals("kotlin.Boolean", result.toString())
    }

    @Test
    fun `maps BYTE_ARRAY to kotlin ByteArray`() {
        val result = map(TypeRef.Primitive(PrimitiveType.BYTE_ARRAY))
        assertEquals("kotlin.ByteArray", result.toString())
    }

    @Test
    fun `maps DATE_TIME to kotlin time Instant`() {
        val result = map(TypeRef.Primitive(PrimitiveType.DATE_TIME))
        assertEquals("kotlin.time.Instant", result.toString())
    }

    @Test
    fun `maps DATE to kotlinx datetime LocalDate`() {
        val result = map(TypeRef.Primitive(PrimitiveType.DATE))
        assertEquals("kotlinx.datetime.LocalDate", result.toString())
    }

    @Test
    fun `maps UUID to kotlin uuid Uuid`() {
        val result = map(TypeRef.Primitive(PrimitiveType.UUID))
        assertEquals("kotlin.uuid.Uuid", result.toString())
    }

    // -- Array --

    @Test
    fun `maps Array of String to List of String`() {
        val ref = TypeRef.Array(TypeRef.Primitive(PrimitiveType.STRING))
        val result = map(ref)
        assertEquals("kotlin.collections.List<kotlin.String>", result.toString())
    }

    // -- Map --

    @Test
    fun `maps Map of String to Map with String key and String value`() {
        val ref = TypeRef.Map(TypeRef.Primitive(PrimitiveType.STRING))
        val result = map(ref)
        assertEquals("kotlin.collections.Map<kotlin.String, kotlin.String>", result.toString())
    }

    // -- Reference --

    @Test
    fun `maps Reference to ClassName in model package`() {
        val ref = TypeRef.Reference("Pet")
        val result = map(ref)
        assertEquals("com.example.model.Pet", result.toString())
    }

    // -- Nested generics --

    @Test
    fun `maps Array of Reference to List of model class`() {
        val ref = TypeRef.Array(TypeRef.Reference("Pet"))
        val result = map(ref)
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
        val result = map(ref)
        assertEquals("com.example.model.Pet_Address", result.toString())
    }

    // -- SCHM-07: Instant mapping verification --

    @Test
    fun `Instant maps to kotlin-time package`() {
        assertEquals("kotlin.time", INSTANT.packageName)
        assertEquals("Instant", INSTANT.simpleName)
    }

    // -- Unknown --

    @Test
    fun `maps Unknown to kotlinx serialization json JsonElement`() {
        val result = map(TypeRef.Unknown)
        assertEquals("kotlinx.serialization.json.JsonElement", result.toString())
    }

    @Test
    fun `maps Array of Unknown to List of JsonElement`() {
        val result = map(TypeRef.Array(TypeRef.Unknown))
        assertEquals("kotlin.collections.List<kotlinx.serialization.json.JsonElement>", result.toString())
    }

    @Test
    fun `maps Map of Unknown to Map with JsonElement value`() {
        val result = map(TypeRef.Map(TypeRef.Unknown))
        assertEquals("kotlin.collections.Map<kotlin.String, kotlinx.serialization.json.JsonElement>", result.toString())
    }
}
