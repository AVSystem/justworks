package com.avsystem.justworks.core.gen

import com.avsystem.justworks.core.model.ApiSpec
import com.avsystem.justworks.core.model.ContentType
import com.avsystem.justworks.core.model.Endpoint
import com.avsystem.justworks.core.model.HttpMethod
import com.avsystem.justworks.core.model.PrimitiveType
import com.avsystem.justworks.core.model.PropertyModel
import com.avsystem.justworks.core.model.RequestBody
import com.avsystem.justworks.core.model.Response
import com.avsystem.justworks.core.model.TypeRef
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class InlineTypeResolverTest {
    private fun emptySpec() = ApiSpec(
        title = "test",
        version = "1.0",
        schemas = emptyList(),
        enums = emptyList(),
        endpoints = emptyList(),
    )

    private fun inlineType(vararg propNames: String, contextHint: String = "Test") = TypeRef.Inline(
        properties = propNames.map { PropertyModel(it, TypeRef.Primitive(PrimitiveType.STRING), null, false) },
        requiredProperties = propNames.toSet(),
        contextHint = contextHint,
    )

    private fun nameMapFor(vararg types: TypeRef.Inline): Map<InlineSchemaKey, String> =
        types.associate { InlineSchemaKey.from(it.properties, it.requiredProperties) to "${it.contextHint}Resolved" }

    @Test
    fun `resolveTypeRef replaces Inline with Reference`() {
        val spec = emptySpec()
        val inline = inlineType("id")
        val nameMap = nameMapFor(inline)

        val resolved = spec.resolveTypeRef(inline, nameMap)

        assertEquals(TypeRef.Reference("TestResolved"), resolved)
    }

    @Test
    fun `resolveTypeRef passes through Primitive unchanged`() {
        val spec = emptySpec()
        val primitive = TypeRef.Primitive(PrimitiveType.INT)

        val resolved = spec.resolveTypeRef(primitive, emptyMap())

        assertEquals(primitive, resolved)
    }

    @Test
    fun `resolveTypeRef passes through Reference unchanged`() {
        val spec = emptySpec()
        val ref = TypeRef.Reference("Foo")

        val resolved = spec.resolveTypeRef(ref, emptyMap())

        assertEquals(ref, resolved)
    }

    @Test
    fun `resolveTypeRef resolves Inline inside Array`() {
        val spec = emptySpec()
        val inline = inlineType("name")
        val nameMap = nameMapFor(inline)

        val resolved = spec.resolveTypeRef(TypeRef.Array(inline), nameMap)

        assertEquals(TypeRef.Array(TypeRef.Reference("TestResolved")), resolved)
    }

    @Test
    fun `resolveTypeRef resolves Inline inside Map`() {
        val spec = emptySpec()
        val inline = inlineType("name")
        val nameMap = nameMapFor(inline)

        val resolved = spec.resolveTypeRef(TypeRef.Map(inline), nameMap)

        assertEquals(TypeRef.Map(TypeRef.Reference("TestResolved")), resolved)
    }

    @Test
    fun `resolveTypeRef fails fast on missing mapping`() {
        val spec = emptySpec()
        val inline = inlineType("unknown")

        val error = assertFailsWith<IllegalStateException> {
            spec.resolveTypeRef(inline, emptyMap())
        }
        assertEquals(true, error.message?.contains("Missing inline schema mapping"))
    }

    @Test
    fun `resolveInlineTypes returns spec unchanged when nameMap is empty`() {
        val spec = emptySpec()

        val resolved = spec.resolveInlineTypes(emptyMap())

        assertEquals(spec, resolved)
    }

    @Test
    fun `resolveInlineTypes resolves inline types in endpoint responses`() {
        val inline = inlineType("status")
        val nameMap = nameMapFor(inline)

        val spec = ApiSpec(
            title = "test",
            version = "1.0",
            schemas = emptyList(),
            enums = emptyList(),
            endpoints = listOf(
                Endpoint(
                    path = "/test",
                    method = HttpMethod.GET,
                    operationId = "getTest",
                    summary = null,
                    tags = emptyList(),
                    parameters = emptyList(),
                    requestBody = null,
                    responses = mapOf("200" to Response("200", null, inline)),
                ),
            ),
        )

        val resolved = spec.resolveInlineTypes(nameMap)

        val resolvedResponseType = resolved.endpoints
            .first()
            .responses["200"]
            ?.schema
        assertEquals(TypeRef.Reference("TestResolved"), resolvedResponseType)
    }

    @Test
    fun `resolveInlineTypes resolves inline types in request body`() {
        val inline = inlineType("payload")
        val nameMap = nameMapFor(inline)

        val spec = ApiSpec(
            title = "test",
            version = "1.0",
            schemas = emptyList(),
            enums = emptyList(),
            endpoints = listOf(
                Endpoint(
                    path = "/test",
                    method = HttpMethod.POST,
                    operationId = "postTest",
                    summary = null,
                    tags = emptyList(),
                    parameters = emptyList(),
                    requestBody = RequestBody(true, ContentType.JSON_CONTENT_TYPE, inline),
                    responses = emptyMap(),
                ),
            ),
        )

        val resolved = spec.resolveInlineTypes(nameMap)

        val resolvedRequestType = resolved.endpoints
            .first()
            .requestBody
            ?.schema
        assertEquals(TypeRef.Reference("TestResolved"), resolvedRequestType)
    }
}
