package com.avsystem.justworks.core.parser

import com.avsystem.justworks.core.Warnings
import com.avsystem.justworks.core.warn
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
        if (openApi.info == null) {
            warn("Spec is missing required 'info' section")
        }

        if (openApi.paths.isNullOrEmpty()) {
            warn("Spec has no paths defined")
        }

        openApi.paths?.values?.forEach { pathItem ->
            pathItem.readOperationsMap()?.values?.forEach { operation ->
                if (!operation.callbacks.isNullOrEmpty()) {
                    warn("Callbacks are not supported in v1 and will be ignored")
                }
            }
        }

        openApi.components?.links?.let { links ->
            if (links.isNotEmpty()) {
                warn("Links are not supported in v1 and will be ignored")
            }
        }
    }
}
