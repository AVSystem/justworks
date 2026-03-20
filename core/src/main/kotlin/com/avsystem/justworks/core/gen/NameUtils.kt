package com.avsystem.justworks.core.gen

private val DELIMITERS = Regex("[_\\-.]+")
private val CAMEL_BOUNDARY = Regex("(?<=[a-z0-9])(?=[A-Z])|(?<=[A-Z])(?=[A-Z][a-z])")

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
 * Sanitizes a nested inline schema name by replacing dot separators with underscores.
 * E.g. "Pet.Address" becomes "Pet_Address".
 */
fun String.toInlinedName(): String = replace(".", "_")

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
private val KOTLIN_HARD_KEYWORDS = setOf(
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
 * Returns true if [name] is a valid Kotlin identifier (not a hard keyword,
 * starts with letter or underscore, contains only alphanumerics and underscores).
 */
fun isValidKotlinIdentifier(name: String): Boolean =
    name.matches(Regex("^[a-zA-Z_][a-zA-Z0-9_]*$")) && name !in KOTLIN_HARD_KEYWORDS

/**
 * Returns [name] unchanged if it is a valid Kotlin identifier; otherwise
 * prefixes [parentName] and converts [name] to PascalCase.
 *
 * Example: sanitizeSchemaName("true", "DeviceStatus") -> "DeviceStatusTrue"
 */
fun sanitizeSchemaName(name: String, parentName: String): String = if (isValidKotlinIdentifier(name)) {
    name
} else {
    "$parentName${name.toPascalCase()}"
}

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
