package com.avsystem.justworks.core.gen.shared

import com.avsystem.justworks.core.gen.toCamelCase
import com.avsystem.justworks.core.gen.toPascalCase
import com.avsystem.justworks.core.model.SecurityScheme

/**
 * Derives constructor parameter names for a security scheme.
 *
 * Bearer and ApiKey produce a single parameter name; Basic produces two
 * (username + password). The names are scoped by both [SecurityScheme.name]
 * and [SecurityScheme.specTitle] to avoid collisions across specs.
 */
internal val SecurityScheme.paramNames: List<String>
    get() {
        val base = "${name.toCamelCase()}${specTitle.toPascalCase()}"
        return when (this) {
            is SecurityScheme.Bearer -> listOf("${base}Token")
            is SecurityScheme.ApiKey -> listOf(base)
            is SecurityScheme.Basic -> listOf("${base}Username", "${base}Password")
        }
    }
