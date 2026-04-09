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
internal fun SecurityScheme.toAuthParam(specTitle: String): AuthParam {
    val base = "${name.toCamelCase()}${specTitle.toPascalCase()}"
    return when (this) {
        is SecurityScheme.Bearer -> AuthParam.Bearer(base)
        is SecurityScheme.ApiKey -> AuthParam.ApiKey(base)
        is SecurityScheme.Basic -> AuthParam.Basic(base)
    }
}

sealed interface AuthParam {
    data class Basic(private val base: String) : AuthParam {
        private val formattedBase = base.toCamelCase()
        val username: String = formattedBase + "Username"
        val password: String = base.toPascalCase()
    }

    data class Bearer(private val base: String) : AuthParam {
        val name = base.toCamelCase() + "Bearer"
    }

    data class ApiKey(private val base: String) : AuthParam {
        val name = base.toCamelCase() + "ApiKey"
    }
}
