package com.avsystem.justworks.core.parser

import io.swagger.v3.oas.models.OpenAPI
import io.swagger.v3.oas.models.PathItem
import io.swagger.v3.oas.models.Paths
import io.swagger.v3.oas.models.info.Info
import kotlin.test.Test
import kotlin.test.assertIs
import kotlin.test.assertTrue

class SpecValidatorTest {
    // -- VALID-01: Valid spec --

    @Test
    fun `valid OpenAPI object produces no errors`() {
        val openApi =
            OpenAPI().apply {
                info =
                    Info().apply {
                        title = "Test API"
                        version = "1.0.0"
                    }
                paths =
                    Paths().apply {
                        addPathItem("/test", PathItem())
                    }
            }

        val errors = SpecValidator.validate(openApi)
        assertTrue(errors.isEmpty(), "Valid spec should produce no errors, got: $errors")
    }

    // -- VALID-02: Missing required fields --

    @Test
    fun `OpenAPI with null info produces errors`() {
        val openApi =
            OpenAPI().apply {
                info = null
                paths =
                    Paths().apply {
                        addPathItem("/test", PathItem())
                    }
            }

        val issues = SpecValidator.validate(openApi)
        assertTrue(issues.isNotEmpty(), "Missing info should produce issues")
        assertTrue(
            issues.any { it.message.contains("info", ignoreCase = true) },
            "Error should mention 'info': $issues",
        )
    }

    // -- VALID-03: No paths warning --

    @Test
    fun `OpenAPI with no paths produces warning`() {
        val openApi =
            OpenAPI().apply {
                info =
                    Info().apply {
                        title = "Empty API"
                        version = "1.0.0"
                    }
                paths = null
            }

        val issues = SpecValidator.validate(openApi)
        assertTrue(issues.isNotEmpty(), "Spec with no paths should produce issues")
        val warning = issues.firstOrNull { it is SpecValidator.ValidationIssue.Warning }
        assertIs<SpecValidator.ValidationIssue.Warning>(warning, "Expected a Warning for no paths, got: $issues")
        assertTrue(
            warning.message.contains("paths", ignoreCase = true),
            "Warning should mention 'paths': ${warning.message}",
        )
    }
}
