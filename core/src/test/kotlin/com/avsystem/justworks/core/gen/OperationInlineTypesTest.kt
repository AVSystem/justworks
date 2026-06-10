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
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

class OperationInlineTypesTest {
    private fun inline(vararg props: String) = TypeRef.Inline(
        properties = props.map { PropertyModel(it, TypeRef.Primitive(PrimitiveType.STRING), null, false) },
        requiredProperties = emptySet(),
        contextHint = "x",
    )

    private fun endpoint(
        operationId: String,
        requestBody: RequestBody? = null,
        responses: Map<String, Response> = emptyMap(),
    ) = Endpoint(
        path = "/x",
        method = HttpMethod.POST,
        operationId = operationId,
        summary = null,
        description = null,
        tags = listOf("domains"),
        parameters = emptyList(),
        requestBody = requestBody,
        responses = responses,
    )

    private fun spec(vararg endpoints: Endpoint) = ApiSpec(
        title = "T",
        version = "1",
        endpoints = endpoints.toList(),
        schemas = emptyList(),
        enums = emptyList(),
        securitySchemes = emptyList(),
    )

    @Test
    fun `lifts inline request and response into planned types named after the operation`() {
        val ep = endpoint(
            operationId = "domains_update",
            requestBody = RequestBody(true, ContentType.JSON_CONTENT_TYPE, inline("name")),
            responses = mapOf("200" to Response("200", "ok", inline("id"))),
        )
        val (rewritten, byOp) = planOperationInlineTypes(spec(ep))

        val planned = byOp.getValue("domains_update")
        assertEquals(
            listOf("DomainsUpdateRequest", "DomainsUpdateResponse"),
            planned.map { it.simpleName },
        )

        // Spec rewritten: inline schemas replaced by references to the planned ids.
        val newEp = rewritten.endpoints.single()
        val reqRef = assertIs<TypeRef.Reference>(newEp.requestBody!!.schema)
        assertEquals("domains_update#request", reqRef.schemaName)
        val respRef = assertIs<TypeRef.Reference>(newEp.responses.getValue("200").schema)
        assertEquals("domains_update#response#200", respRef.schemaName)
    }

    @Test
    fun `non-2xx and default responses get distinct names`() {
        val ep = endpoint(
            operationId = "getThing",
            responses = mapOf(
                "200" to Response("200", "ok", inline("ok")),
                "404" to Response("404", "nf", inline("err")),
                "default" to Response("default", "def", inline("def")),
            ),
        )
        val (_, byOp) = planOperationInlineTypes(spec(ep))
        assertEquals(
            setOf("GetThingResponse", "GetThingResponse404", "GetThingDefaultResponse"),
            byOp.getValue("getThing").map { it.simpleName }.toSet(),
        )
    }

    @Test
    fun `referenced (non-inline) bodies are left untouched`() {
        val ep = endpoint(
            operationId = "createPet",
            requestBody = RequestBody(true, ContentType.JSON_CONTENT_TYPE, TypeRef.Reference("NewPet")),
            responses = mapOf("200" to Response("200", "ok", TypeRef.Reference("Pet"))),
        )
        val (rewritten, byOp) = planOperationInlineTypes(spec(ep))
        assertNull(byOp["createPet"])
        assertTrue(
            rewritten.endpoints
                .single()
                .requestBody!!
                .schema is TypeRef.Reference,
        )
    }
}
