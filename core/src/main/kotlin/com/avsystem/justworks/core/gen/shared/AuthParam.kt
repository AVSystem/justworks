package com.avsystem.justworks.core.gen.shared

import com.avsystem.justworks.core.gen.toCamelCase
import com.avsystem.justworks.core.gen.toPascalCase
import com.avsystem.justworks.core.model.SecurityScheme

internal fun SecurityScheme.Bearer.toAuthParam(specTitle: String) = AuthParam.Bearer(name, specTitle)

internal fun SecurityScheme.ApiKey.toAuthParam(specTitle: String) = AuthParam.ApiKey(name, specTitle)

internal fun SecurityScheme.Basic.toAuthParam(specTitle: String) = AuthParam.Basic(name, specTitle)

sealed interface AuthParam {
    @ConsistentCopyVisibility
    data class Basic private constructor(val username: String, val password: String) : AuthParam {
        companion object {
            operator fun invoke(base: String, specTitle: String): Basic {
                val formattedBase = formatBase(base, specTitle)
                return Basic(formattedBase + "Username", formattedBase + "Password")
            }
        }
    }

    @ConsistentCopyVisibility
    data class Bearer private constructor(val name: String) : AuthParam {
        constructor(base: String, specTitle: String) : this("${formatBase(base, specTitle)}Token")
    }

    @ConsistentCopyVisibility
    data class ApiKey private constructor(val name: String) : AuthParam {
        constructor(base: String, specTitle: String) : this(formatBase(base, specTitle))
    }
}

private fun formatBase(base: String, specTitle: String) = base.toCamelCase() + specTitle.toPascalCase()
