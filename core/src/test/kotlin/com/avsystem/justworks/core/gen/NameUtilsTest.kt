package com.avsystem.justworks.core.gen

import kotlin.test.Test
import kotlin.test.assertEquals

class NameUtilsTest {
    // -- toCamelCase --

    @Test
    fun `toCamelCase converts snake_case`() {
        assertEquals("snakeCase", "snake_case".toCamelCase())
    }

    @Test
    fun `toCamelCase converts kebab-case`() {
        assertEquals("kebabCase", "kebab-case".toCamelCase())
    }

    @Test
    fun `toCamelCase lowercases PascalCase`() {
        assertEquals("pascalCase", "PascalCase".toCamelCase())
    }

    @Test
    fun `toCamelCase preserves single word`() {
        assertEquals("single", "single".toCamelCase())
    }

    @Test
    fun `toCamelCase converts dot-delimited`() {
        assertEquals("withDots", "with.dots".toCamelCase())
    }

    @Test
    fun `toCamelCase converts already_camel style`() {
        assertEquals("alreadyCamel", "already_camel".toCamelCase())
    }

    @Test
    fun `toCamelCase handles consecutive underscores`() {
        assertEquals("fooBar", "foo__bar".toCamelCase())
    }

    @Test
    fun `toCamelCase handles consecutive hyphens`() {
        assertEquals("fooBar", "foo--bar".toCamelCase())
    }

    @Test
    fun `toCamelCase handles mixed consecutive delimiters`() {
        assertEquals("fooBar", "foo-_bar".toCamelCase())
    }

    @Test
    fun `toCamelCase treats all-uppercase as single word`() {
        assertEquals("url", "URL".toCamelCase())
    }

    @Test
    fun `toCamelCase splits acronym followed by word`() {
        assertEquals("urlMapping", "URLMapping".toCamelCase())
    }

    @Test
    fun `toCamelCase splits multiple acronyms`() {
        assertEquals("httpsConfig", "HTTPSConfig".toCamelCase())
    }

    @Test
    fun `toCamelCase handles acronym in middle`() {
        assertEquals("getUrlMapping", "getURLMapping".toCamelCase())
    }

    @Test
    fun `toPascalCase treats all-uppercase as single word`() {
        assertEquals("Url", "URL".toPascalCase())
    }

    @Test
    fun `toPascalCase splits acronym followed by word`() {
        assertEquals("UrlMapping", "URLMapping".toPascalCase())
    }

    @Test
    fun `toPascalCase splits multiple acronyms`() {
        assertEquals("HttpsConfig", "HTTPSConfig".toPascalCase())
    }

    @Test
    fun `toPascalCase handles acronym in middle`() {
        assertEquals("GetUrlMapping", "getURLMapping".toPascalCase())
    }

    // -- toEnumConstantName --

    @Test
    fun `toEnumConstantName uppercases simple word`() {
        assertEquals("AVAILABLE", "available".toEnumConstantName())
    }

    @Test
    fun `toEnumConstantName preserves snake_case underscores`() {
        assertEquals("PENDING_REVIEW", "pending_review".toEnumConstantName())
    }

    @Test
    fun `toEnumConstantName splits camelCase`() {
        assertEquals("CAMEL_CASE", "camelCase".toEnumConstantName())
    }

    @Test
    fun `toEnumConstantName converts hyphens`() {
        assertEquals("WITH_HYPHENS", "with-hyphens".toEnumConstantName())
    }

    @Test
    fun `toEnumConstantName converts spaces`() {
        assertEquals("WITH_SPACES", "with spaces".toEnumConstantName())
    }

    @Test
    fun `toEnumConstantName returns original for all-special-chars input`() {
        assertEquals("!!!", "!!!".toEnumConstantName())
    }

    @Test
    fun `toEnumConstantName returns original for empty string`() {
        assertEquals("", "".toEnumConstantName())
    }

    @Test
    fun `toEnumConstantName handles consecutive underscores`() {
        assertEquals("FOO_BAR", "foo__bar".toEnumConstantName())
    }

    @Test
    fun `toEnumConstantName handles consecutive hyphens`() {
        assertEquals("FOO_BAR", "foo--bar".toEnumConstantName())
    }

    @Test
    fun `toEnumConstantName splits acronym followed by word`() {
        assertEquals("URL_MAPPING", "URLMapping".toEnumConstantName())
    }

    @Test
    fun `toEnumConstantName handles multiple acronyms`() {
        assertEquals("HTTPS_CONFIG", "HTTPSConfig".toEnumConstantName())
    }

    // -- operationNameFromPath --

    @Test
    fun `operationNameFromPath converts simple path`() {
        assertEquals("PostPets", operationNameFromPath("POST", "/pets"))
    }

    @Test
    fun `operationNameFromPath handles path parameter`() {
        assertEquals("GetPetsById", operationNameFromPath("GET", "/pets/{id}"))
    }

    @Test
    fun `operationNameFromPath handles multiple path parameters`() {
        assertEquals(
            "PutUsersByUserIdOrdersByOrderId",
            operationNameFromPath("PUT", "/users/{userId}/orders/{orderId}"),
        )
    }

    @Test
    fun `operationNameFromPath handles hyphens in path`() {
        assertEquals("GetApiTokens", operationNameFromPath("GET", "/api-tokens"))
    }

    @Test
    fun `operationNameFromPath handles underscores in path`() {
        assertEquals("GetApiTokens", operationNameFromPath("GET", "/api_tokens"))
    }

    @Test
    fun `operationNameFromPath handles uppercase method`() {
        assertEquals("DeletePets", operationNameFromPath("DELETE", "/pets"))
    }

    @Test
    fun `operationNameFromPath handles camelCase path parameter`() {
        assertEquals("GetUsersByUserId", operationNameFromPath("GET", "/users/{userId}"))
    }
}
