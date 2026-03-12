package com.avsystem.justworks.core.parser

import io.swagger.v3.oas.models.OpenAPI

object SpecValidator {
    fun validate(openApi: OpenAPI): List<String> {
        val errors = mutableListOf<String>()

        // Missing info is an error -- required by OpenAPI spec
        if (openApi.info == null) {
            errors.add("[JUSTWORKS] Spec is missing required 'info' section")
        }

        // Missing paths is a warning — accumulated but filtered out (not returned to caller)
        if (openApi.paths.isNullOrEmpty()) {
            errors.add("[JUSTWORKS] Warning: Spec has no paths defined")
        }

        // Detect unsupported constructs for v1
        openApi.paths?.values?.forEach { pathItem ->
            pathItem.readOperationsMap()?.values?.forEach { operation ->
                if (!operation.callbacks.isNullOrEmpty()) {
                    errors.add("[JUSTWORKS] Warning: Callbacks are not supported in v1 and will be ignored")
                    return@forEach
                }
            }
        }

        openApi.components?.links?.let { links ->
            if (links.isNotEmpty()) {
                errors.add("[JUSTWORKS] Warning: Links are not supported in v1 and will be ignored")
            }
        }

        // Only return actual errors (non-warnings) as blockers
        return errors.filter { !it.contains("Warning:") }
    }
}
