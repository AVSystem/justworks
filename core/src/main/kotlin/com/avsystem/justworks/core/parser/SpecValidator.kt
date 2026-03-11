package com.avsystem.justworks.core.parser

import arrow.core.NonEmptyList
import arrow.core.raise.ExperimentalRaiseAccumulateApi
import arrow.core.raise.context.Raise
import arrow.core.raise.context.accumulate
import arrow.core.raise.context.ensure
import arrow.core.raise.context.ensureNotNull
import io.swagger.v3.oas.models.OpenAPI

object SpecValidator {
    sealed class ValidationIssue {
        abstract val message: String

        class Error(override val message: String) : ValidationIssue()

        class Warning(override val message: String) : ValidationIssue()
    }

    @OptIn(ExperimentalRaiseAccumulateApi::class)
    context(_: Raise<NonEmptyList<ValidationIssue>>)
    fun validate(openApi: OpenAPI): Unit = accumulate {
        ensureNotNull(openApi.info) {
            ValidationIssue.Error("Spec is missing required 'info' section")
        }

        ensure(!openApi.paths.isNullOrEmpty()) {
            ValidationIssue.Warning("Spec has no paths defined")
        }
        // Detect unsupported constructs for v1
        openApi.paths?.values?.forEach { pathItem ->
            pathItem.readOperationsMap()?.values?.forEach { operation ->
                ensure(operation.callbacks.isNullOrEmpty()) {
                    ValidationIssue.Warning("Callbacks are not supported in v1 and will be ignored")
                }
            }
        }

        openApi.components?.links?.let { links ->
            ensure(links.isEmpty()) {
                ValidationIssue.Warning("Links are not supported in v1 and will be ignored")
            }
        }
    }
}
