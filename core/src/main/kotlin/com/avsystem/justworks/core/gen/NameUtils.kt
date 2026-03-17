package com.avsystem.justworks.core.gen

private val DELIMITERS = Regex("[_\\-.]+")
private val CAMEL_BOUNDARY = Regex("(?<=[a-z0-9])(?=[A-Z])")

/**
 * The set of Kotlin hard keywords that are reserved identifiers and require backtick escaping
 * when used as property names in generated code.
 */
val KOTLIN_HARD_KEYWORDS = setOf(
    "as",
    "break",
    "class",
    "continue",
    "do",
    "else",
    "false",
    "for",
    "fun",
    "if",
    "in",
    "interface",
    "is",
    "null",
    "object",
    "package",
    "return",
    "super",
    "this",
    "throw",
    "true",
    "try",
    "typealias",
    "typeof",
    "val",
    "var",
    "when",
    "while",
)

/**
 * Converts a string to a valid Kotlin identifier for use as a property name.
 * Calls [toCamelCase] first, then backtick-escapes the result if it is a Kotlin hard keyword.
 *
 * Examples:
 * - "object" -> "`object`"
 * - "in" -> "`in`"
 * - "normalName" -> "normalName"
 * - "my_object" -> "myObject"
 */
fun String.toKotlinIdentifier(): String {
    val camelCased = toCamelCase()
    return if (camelCased in KOTLIN_HARD_KEYWORDS) "`$camelCased`" else camelCased
}

/**
 * Converts a string to camelCase.
 * Splits on `_`, `-`, `.` delimiters, lowercases first segment,
 * capitalizes subsequent segments, and joins.
 */
fun String.toCamelCase(): String {
    if (isBlank()) return this
    val parts =
        split(DELIMITERS).filter { it.isNotEmpty() }.flatMap { it.split(CAMEL_BOUNDARY) }.filter { it.isNotEmpty() }
    if (parts.isEmpty()) return this
    return buildString {
        append(parts.first().lowercase())
        for (i in 1 until parts.size) {
            append(parts[i].replaceFirstChar { it.uppercaseChar() })
        }
    }
}

/**
 * Converts a string to PascalCase.
 * Like [toCamelCase] but capitalizes the first segment too.
 */
fun String.toPascalCase(): String {
    if (isBlank()) return this
    val parts =
        split(DELIMITERS).filter { it.isNotEmpty() }.flatMap { it.split(CAMEL_BOUNDARY) }.filter { it.isNotEmpty() }
    if (parts.isEmpty()) return this
    return parts.joinToString("") { it.replaceFirstChar { c -> c.uppercaseChar() } }
}

/**
 * Converts any string to UPPER_SNAKE_CASE for use as an enum constant name.
 * Inserts `_` before uppercase letters in camelCase, replaces non-alphanumeric
 * with `_`, uppercases, deduplicates `_`, trims leading/trailing `_`.
 * Prefixes with `VALUE_` if the first character is a digit.
 */
fun String.toEnumConstantName(): String {
    if (isBlank()) return this
    val snaked = replace(CAMEL_BOUNDARY, "_")
    val cleaned = snaked.map { c -> if (c.isLetterOrDigit()) c.uppercaseChar() else '_' }.joinToString("")
    val deduped = cleaned.replace(Regex("_+"), "_").trim('_')
    if (deduped.isEmpty()) return this
    return if (deduped.first().isDigit()) "VALUE_$deduped" else deduped
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
