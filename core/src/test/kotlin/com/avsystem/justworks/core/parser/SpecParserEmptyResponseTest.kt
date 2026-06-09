package com.avsystem.justworks.core.parser

import com.avsystem.justworks.core.model.ApiSpec
import com.avsystem.justworks.core.model.TypeRef
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull

/**
 * A response whose schema carries no content (`{}` / `{ "nullable": true }`) must parse to a
 * `null` response schema, so the client generates a `Unit` return type instead of `JsonElement`.
 * A response with a real `$ref` schema stays typed.
 */
class SpecParserEmptyResponseTest : SpecParserTestBase() {
    private val spec: ApiSpec = parseSpec(loadResource("fixtures/empty-response-schema-spec.json"))

    private fun responseSchema(operationId: String, code: String) = spec.endpoints
        .first { it.operationId == operationId }
        .responses
        .getValue(code)
        .schema

    @Test
    fun `empty content response schema parses to null`() {
        assertNull(responseSchema("createThing", "201"))
    }

    @Test
    fun `response with a ref schema stays typed`() {
        val schema = responseSchema("getThing", "200")
        assertIs<TypeRef.Reference>(schema)
        assertEquals("Thing", schema.schemaName)
    }
}
