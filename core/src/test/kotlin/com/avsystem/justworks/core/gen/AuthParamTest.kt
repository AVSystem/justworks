package com.avsystem.justworks.core.gen

import com.avsystem.justworks.core.gen.shared.AuthParam
import com.avsystem.justworks.core.gen.shared.toAuthParam
import com.avsystem.justworks.core.model.ApiKeyLocation
import com.avsystem.justworks.core.model.SecurityScheme
import kotlin.test.Test
import kotlin.test.assertEquals

class AuthParamTest {
    @Test
    fun `Bearer param name includes scheme name and spec title`() {
        val param = SecurityScheme.Bearer("BearerAuth").toAuthParam("Petstore")
        assertEquals("bearerAuthPetstoreToken", param.name)
    }

    @Test
    fun `Basic param generates username and password`() {
        val param = SecurityScheme.Basic("BasicAuth").toAuthParam("Petstore")
        assertEquals("basicAuthPetstoreUsername", param.username)
        assertEquals("basicAuthPetstorePassword", param.password)
    }

    @Test
    fun `ApiKey param name includes scheme name and spec title`() {
        val param = SecurityScheme.ApiKey("ApiKeyHeader", "X-API-Key", ApiKeyLocation.HEADER).toAuthParam("Petstore")
        assertEquals("apiKeyHeaderPetstore", param.name)
    }

    @Test
    fun `multi-word scheme name is camelCased`() {
        val param = SecurityScheme.Bearer("my-bearer-auth").toAuthParam("My API")
        assertEquals("myBearerAuthMyApiToken", param.name)
    }

    @Test
    fun `multi-word spec title is PascalCased`() {
        val param = SecurityScheme.ApiKey("key", "X-Key", ApiKeyLocation.QUERY).toAuthParam("my cool api")
        assertEquals("keyMyCoolApi", param.name)
    }

    @Test
    fun `Basic with multi-word names formats correctly`() {
        val param = SecurityScheme.Basic("http-basic").toAuthParam("Admin Service")
        assertEquals("httpBasicAdminServiceUsername", param.username)
        assertEquals("httpBasicAdminServicePassword", param.password)
    }
}
