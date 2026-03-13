package com.avsystem.justworks.core.parser

import arrow.core.raise.ExperimentalRaiseAccumulateApi
import arrow.core.raise.context.accumulate
import arrow.core.raise.context.ensureNotNullOrAccumulate
import arrow.core.raise.context.ensureOrAccumulate
import arrow.core.raise.fold
import io.swagger.v3.oas.models.OpenAPI

object SpecValidator {
    sealed class ValidationIssue {
        abstract val message: String

        class Error(override val message: String) : ValidationIssue()

        class Warning(override val message: String) : ValidationIssue()
    }

    /**
     * Validates a parsed OpenAPI model for required fields and unsupported constructs.
     *
     * Collects all issues without short-circuiting (using Arrow [accumulate]) so that
     * callers receive the full list of problems in a single call.
     *
     * Returned issues are either [ValidationIssue.Error] (spec is unusable) or
     * [ValidationIssue.Warning] (spec can be processed but some features will be ignored).
     *
     * @param openApi the parsed OpenAPI model from Swagger Parser
     * @return list of [ValidationIssue]; empty when the spec is fully valid
     */
    @OptIn(ExperimentalRaiseAccumulateApi::class)
    fun validate(openApi: OpenAPI): List<ValidationIssue> = fold(
        {
            accumulate {
                ensureNotNullOrAccumulate(openApi.info) {
                    ValidationIssue.Error("Spec is missing required 'info' section")
                }

                ensureOrAccumulate(!openApi.paths.isNullOrEmpty()) {
                    ValidationIssue.Warning("Spec has no paths defined")
                }
                // Detect unsupported constructs for v1
                openApi.paths?.values?.forEach { pathItem ->
                    pathItem.readOperationsMap()?.values?.forEach { operation ->
                        ensureOrAccumulate(operation.callbacks.isNullOrEmpty()) {
                            ValidationIssue.Warning("Callbacks are not supported in v1 and will be ignored")
                        }
                    }
                }

                openApi.components?.links?.let { links ->
                    ensureOrAccumulate(links.isEmpty()) {
                        ValidationIssue.Warning("Links are not supported in v1 and will be ignored")
                    }
                }
            }
        },
        { it },
        { emptyList() },
    )
}
