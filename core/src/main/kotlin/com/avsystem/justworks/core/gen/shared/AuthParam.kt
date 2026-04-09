package com.avsystem.justworks.core.gen.shared

import com.avsystem.justworks.core.gen.toCamelCase
import com.avsystem.justworks.core.gen.toPascalCase
import com.avsystem.justworks.core.model.SecurityScheme

/**
 * Derives constructor parameter names for a security scheme.
 *
 * Bearer and ApiKey produce a single parameter name; Basic produces two
 * (username + password). The names are scoped by [specTitle] to avoid
 * collisions when multiple specs define schemes with the same name.
 */
internal fun SecurityScheme.paramNames(specTitle: String): List<String> {
    val base = "${name.toCamelCase()}${specTitle.toPascalCase()}"
    return when (this) {
        is SecurityScheme.Bearer -> listOf("${base}Token")
        is SecurityScheme.ApiKey -> listOf(base)
        is SecurityScheme.Basic -> listOf("${base}Username", "${base}Password")
    }
}
