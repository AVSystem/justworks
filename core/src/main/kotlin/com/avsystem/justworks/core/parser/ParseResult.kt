package com.avsystem.justworks.core.parser

import com.avsystem.justworks.core.model.ApiSpec

sealed interface ParseResult {
    data class Success(
        val spec: ApiSpec,
        val warnings: List<String> = emptyList(),
    ) : ParseResult

    data class Failure(
        val errors: List<String>,
    ) : ParseResult
}
