package com.avsystem.justworks.core.parser

import com.avsystem.justworks.core.Issue
import com.avsystem.justworks.core.Warnings
import com.avsystem.justworks.core.ensureNotNullOrAccumulate
import com.avsystem.justworks.core.ensureOrAccumulate
import io.swagger.v3.oas.models.OpenAPI

object SpecValidator {
    /**
     * Validates a parsed OpenAPI model for required fields and unsupported constructs.
     *
     * Accumulates all warnings without short-circuiting so that callers receive the
     * full list of problems in a single call.
     *
     * @param openApi the parsed OpenAPI model from Swagger Parser
     */
    context(_: Warnings)
    fun validate(openApi: OpenAPI) {
        ensureNotNullOrAccumulate(openApi.info) {
            Issue.Warning("Spec is missing required 'info' section")
        }

        ensureOrAccumulate(!openApi.paths.isNullOrEmpty()) {
            Issue.Warning("Spec has no paths defined")
        }

        openApi.paths?.values?.forEach { pathItem ->
            pathItem.readOperationsMap()?.values?.forEach { operation ->
                ensureOrAccumulate(operation.callbacks.isNullOrEmpty()) {
                    Issue.Warning("Callbacks are not supported in v1 and will be ignored")
                }
            }
        }

        openApi.components?.links?.let { links ->
            ensureOrAccumulate(links.isEmpty()) {
                Issue.Warning("Links are not supported in v1 and will be ignored")
            }
        }
    }
}
