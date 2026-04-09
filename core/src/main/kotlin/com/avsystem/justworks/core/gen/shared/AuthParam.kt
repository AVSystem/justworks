package com.avsystem.justworks.core.gen.shared

import com.avsystem.justworks.core.gen.TOKEN
import com.avsystem.justworks.core.gen.toCamelCase
import com.avsystem.justworks.core.model.SecurityScheme

/**
 * Builds the list of auth-related constructor parameter names based on security schemes.
 */
internal fun buildAuthConstructorParams(securitySchemes: List<SecurityScheme>): List<String> =
    if (isSingleBearer(securitySchemes)) {
        listOf(TOKEN)
    } else {
        securitySchemes.flatMap {
            when (it) {
                is SecurityScheme.Bearer -> {
                    listOf(it.toAuthParam().name)
                }

                is SecurityScheme.ApiKey -> {
                    listOf(it.toAuthParam().name)
                }

                is SecurityScheme.Basic -> {
                    val authParam = it.toAuthParam()
                    listOf(authParam.username, it.toAuthParam().password)
                }
            }
        }
    }

sealed interface AuthParam {
    data class Bearer(private val _name: String) : AuthParam {
        val name = "${_name.toCamelCase()}Token"
    }

    data class Basic(private val _name: String) : AuthParam {
        val name = _name.toCamelCase()
        val username = "${name}Username"
        val password = "${name}Password"
    }

    data class ApiKey(private val _name: String) : AuthParam {
        val name = _name.toCamelCase()
    }
}

internal fun SecurityScheme.Basic.toAuthParam(): AuthParam.Basic = AuthParam.Basic(name)

internal fun SecurityScheme.ApiKey.toAuthParam(): AuthParam.ApiKey = AuthParam.ApiKey(name)

internal fun SecurityScheme.Bearer.toAuthParam(): AuthParam.Bearer = AuthParam.Bearer(name)

internal fun isSingleBearer(securitySchemes: List<SecurityScheme>): Boolean =
    securitySchemes.size == 1 && securitySchemes.first() is SecurityScheme.Bearer
