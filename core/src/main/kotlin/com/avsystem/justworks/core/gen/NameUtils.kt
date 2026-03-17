package com.avsystem.justworks.core.gen

private val DELIMITERS = Regex("[_\\-.]+")
private val CAMEL_BOUNDARY = Regex("(?<=[a-z0-9])(?=[A-Z])")

/**
 * Converts a string to camelCase.
 * Splits on `_`, `-`, `.` delimiters, lowercases first segment,
 * capitalizes subsequent segments, and joins.
 */
fun String.toCamelCase(): String = toPascalCase().replaceFirstChar { it.lowercaseChar() }

/**
 * Converts a string to PascalCase.
 * Like [toCamelCase] but capitalizes the first segment too.
 */
fun String.toPascalCase(): String = split(DELIMITERS)
    .filter { it.isNotEmpty() }
    .flatMap { it.split(CAMEL_BOUNDARY) }
    .joinToString("") { it.lowercase().replaceFirstChar { c -> c.uppercaseChar() } }

/**
 * Converts any string to UPPER_SNAKE_CASE for use as an enum constant name.
 * Inserts `_` before uppercase letters in camelCase, replaces non-alphanumeric
 * with `_`, uppercases, deduplicates `_`, trims leading/trailing `_`.
 */
fun String.toEnumConstantName(): String {
    val converted = replace(CAMEL_BOUNDARY, "_")
        .replace(Regex("[^a-zA-Z0-9]+"), "_")
        .trim('_')
        .uppercase()

    return when {
        converted.isEmpty() -> this
        else -> converted
    }
}

/**
 * Generates a PascalCase operation name from HTTP method and path.
 * Path parameters like {id} become "ById", {userId} becomes "ByUserId".
 * Handles hyphens, underscores, and dots in path segments.
 *
 * Examples:
 * - ("POST", "/pets") -> "PostPets"
 * - ("GET", "/pets/{id}") -> "GetPetsById"
 * - ("PUT", "/users/{userId}/orders/{orderId}") -> "PutUsersByUserIdOrdersByOrderId"
 * - ("GET", "/api-tokens") -> "GetApiTokens"
 */
fun operationNameFromPath(method: String, path: String): String {
    val methodPart = method.lowercase().replaceFirstChar { it.uppercase() }

    val pathPart = path
        .split("/")
        .filter { it.isNotEmpty() }
        .joinToString("") { segment ->
            if (segment.startsWith("{") && segment.endsWith("}")) {
                // Path parameter: {id} -> ById, {userId} -> ByUserId
                val paramName = segment.removeSurrounding("{", "}")
                "By" + paramName.toPascalCase()
            } else {
                segment.toPascalCase()
            }
        }

    return methodPart + pathPart
}
