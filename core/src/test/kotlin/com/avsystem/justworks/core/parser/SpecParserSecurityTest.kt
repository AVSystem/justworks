package com.avsystem.justworks.core.parser

import com.avsystem.justworks.core.model.ApiKeyLocation
import com.avsystem.justworks.core.model.ApiSpec
import com.avsystem.justworks.core.model.SecurityScheme
import org.junit.jupiter.api.TestInstance
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class SpecParserSecurityTest : SpecParserTestBase() {
    private lateinit var apiSpec: ApiSpec

    @BeforeTest
    fun setUp() {
        if (!::apiSpec.isInitialized) {
            apiSpec = parseSpec(loadResource("security-schemes-spec.yaml"))
        }
    }

    @Test
    fun `parses exactly 4 security schemes from fixture`() {
        assertEquals(4, apiSpec.securitySchemes.size)
    }

    @Test
    fun `parses Bearer security scheme`() {
        val bearer = apiSpec.securitySchemes.filterIsInstance<SecurityScheme.Bearer>()
        assertEquals(1, bearer.size)
        assertEquals("BearerAuth", bearer.single().name)
    }

    @Test
    fun `parses ApiKey header security scheme`() {
        val apiKeys = apiSpec.securitySchemes.filterIsInstance<SecurityScheme.ApiKey>()
        val header = apiKeys.single { it.location == ApiKeyLocation.HEADER }
        assertEquals("ApiKeyHeader", header.name)
        assertEquals("X-API-Key", header.parameterName)
    }

    @Test
    fun `parses ApiKey query security scheme`() {
        val apiKeys = apiSpec.securitySchemes.filterIsInstance<SecurityScheme.ApiKey>()
        val query = apiKeys.single { it.location == ApiKeyLocation.QUERY }
        assertEquals("ApiKeyQuery", query.name)
        assertEquals("api_key", query.parameterName)
    }

    @Test
    fun `parses Basic security scheme`() {
        val basic = apiSpec.securitySchemes.filterIsInstance<SecurityScheme.Basic>()
        assertEquals(1, basic.size)
        assertEquals("BasicAuth", basic.single().name)
    }

    @Test
    fun `excludes unreferenced OAuth2 scheme`() {
        val names = apiSpec.securitySchemes.map { it.name }
        assertTrue("UnusedOAuth" !in names, "UnusedOAuth should not be in parsed schemes")
    }

    @Test
    fun `excludes unsupported cookie API key scheme`() {
        val names = apiSpec.securitySchemes.map { it.name }
        assertTrue("ApiKeyCookie" !in names, "ApiKeyCookie should not be in parsed schemes")
    }

    @Test
    fun `spec without security field produces empty securitySchemes`() {
        val petstore = parseSpec(loadResource("petstore.yaml"))
        assertTrue(petstore.securitySchemes.isEmpty(), "petstore should have no security schemes")
    }
}
