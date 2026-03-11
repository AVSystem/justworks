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
    fun `toCamelCase converts UPPER_CASE preserving segment casing`() {
        // Splits on _, gets ["UPPER", "CASE"]. No camel boundaries in all-caps segments.
        // First segment lowercased -> "upper", second replaceFirstChar -> "CASE" (already upper).
        assertEquals("upperCASE", "UPPER_CASE".toCamelCase())
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
    fun `toEnumConstantName prefixes digit-starting values`() {
        assertEquals("VALUE_123", "123".toEnumConstantName())
    }

    @Test
    fun `toEnumConstantName converts hyphens`() {
        assertEquals("WITH_HYPHENS", "with-hyphens".toEnumConstantName())
    }

    @Test
    fun `toEnumConstantName converts spaces`() {
        assertEquals("WITH_SPACES", "with spaces".toEnumConstantName())
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
        assertEquals("PutUsersByUserIdOrdersByOrderId", operationNameFromPath("PUT", "/users/{userId}/orders/{orderId}"))
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
    fun `operationNameFromPath handles mixed case method`() {
        assertEquals("DeletePets", operationNameFromPath("DELETE", "/pets"))
    }

    @Test
    fun `operationNameFromPath handles camelCase path parameter`() {
        assertEquals("GetUsersByUserId", operationNameFromPath("GET", "/users/{userId}"))
    }

    // -- toKotlinIdentifier --

    @Test
    fun `toKotlinIdentifier backtick-escapes object keyword`() {
        assertEquals("`object`", "object".toKotlinIdentifier())
    }

    @Test
    fun `toKotlinIdentifier backtick-escapes in keyword`() {
        assertEquals("`in`", "in".toKotlinIdentifier())
    }

    @Test
    fun `toKotlinIdentifier backtick-escapes class keyword`() {
        assertEquals("`class`", "class".toKotlinIdentifier())
    }

    @Test
    fun `toKotlinIdentifier leaves non-keyword unchanged`() {
        assertEquals("normalName", "normalName".toKotlinIdentifier())
    }

    @Test
    fun `toKotlinIdentifier camelCases non-keyword with underscore`() {
        assertEquals("myObject", "my_object".toKotlinIdentifier())
    }

    @Test
    fun `toKotlinIdentifier backtick-escapes val keyword`() {
        assertEquals("`val`", "val".toKotlinIdentifier())
    }

    @Test
    fun `toKotlinIdentifier backtick-escapes fun keyword`() {
        assertEquals("`fun`", "fun".toKotlinIdentifier())
    }

    @Test
    fun `toKotlinIdentifier backtick-escapes return keyword`() {
        assertEquals("`return`", "return".toKotlinIdentifier())
    }
}
