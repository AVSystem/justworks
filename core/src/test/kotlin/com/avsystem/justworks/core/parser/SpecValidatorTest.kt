package com.avsystem.justworks.core.parser

import arrow.core.raise.iorNel
import com.avsystem.justworks.core.Issue
import io.swagger.v3.oas.models.OpenAPI
import io.swagger.v3.oas.models.PathItem
import io.swagger.v3.oas.models.Paths
import io.swagger.v3.oas.models.info.Info
import kotlin.test.Test
import kotlin.test.assertTrue

class SpecValidatorTest {
    private fun validateAndCollectWarnings(openApi: OpenAPI): List<Issue.Warning> =
        iorNel { SpecValidator.validate(openApi) }.fold(
            { warnings -> warnings },
            { emptyList() },
            { warnings, _ -> warnings },
        )

    // -- VALID-01: Valid spec --

    @Test
    fun `valid OpenAPI object produces no issues`() {
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

        val warnings = validateAndCollectWarnings(openApi)
        assertTrue(warnings.isEmpty(), "Valid spec should produce no warnings, got: $warnings")
    }

    // -- VALID-02: Missing required fields --

    @Test
    fun `OpenAPI with null info produces warning`() {
        val openApi =
            OpenAPI().apply {
                info = null
                paths =
                    Paths().apply {
                        addPathItem("/test", PathItem())
                    }
            }

        val warnings = validateAndCollectWarnings(openApi)
        assertTrue(warnings.isNotEmpty(), "Missing info should produce warnings")
        assertTrue(
            warnings.any { it.message.contains("info", ignoreCase = true) },
            "Warning should mention 'info': $warnings",
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

        val warnings = validateAndCollectWarnings(openApi)
        assertTrue(warnings.isNotEmpty(), "Spec with no paths should produce warnings")
        assertTrue(
            warnings.any { it.message.contains("paths", ignoreCase = true) },
            "Warning should mention 'paths': $warnings",
        )
    }
}
