# Coding Conventions

**Analysis Date:** 2026-03-19

## Naming Patterns

**Files:**
- `*Generator.kt`: Code generation classes (e.g., `ClientGenerator.kt`, `ModelGenerator.kt`)
- `*Test.kt`: Test classes paired with source files (e.g., `NameUtilsTest.kt`, `ClientGeneratorTest.kt`)
- Extension functions: Named by what they extend (e.g., `NameUtils.kt` for `String.toCamelCase()`, `String.toPascalCase()`)
- Model classes: `ApiSpec.kt`, `TypeRef.kt`, `Parameter.kt` (each model file contains related sealed interfaces and enums)

**Functions:**
- Extension functions use descriptive names: `toCamelCase()`, `toPascalCase()`, `toEnumConstantName()`, `toInlinedName()`, `operationNameFromPath()`
- Private utility functions: snake_case with clear action verbs (e.g., `buildHierarchyInfo()`, `collectAllInlineSchemas()`)
- Generator functions: `generate()` as main entry point, `generateClientFile()`, `generateSchemaFiles()` for internal steps

**Variables:**
- camelCase for local variables: `endpoints`, `schemas`, `typeSpec`, `generator`
- Constants: UPPER_SNAKE_CASE stored in companion objects or at file level (e.g., `DEFAULT_TAG`, `API_SUFFIX`, `BASE_URL`, `TOKEN`)
- Private regex patterns: lowercase with descriptive names (e.g., `DELIMITERS`, `CAMEL_BOUNDARY`)

**Types:**
- PascalCase for classes: `ClientGenerator`, `ApiSpec`, `Endpoint`, `Parameter`
- PascalCase for sealed interfaces: `TypeRef`, `ParseResult`, `ValidationIssue`
- PascalCase for enums: `HttpMethod`, `ParameterLocation`, `PrimitiveType`, `EnumBackingType`
- Companion object constants in ALL_CAPS_SNAKE_CASE

## Code Style

**Formatting:**
- Ktlint (via Gradle plugin `org.jlleitschuh.gradle.ktlint`) with official Kotlin code style
- Max line length: 120 characters (configured in `.editorconfig`, file: `/Users/bkozak/IdeaProjects/ApiKt/.editorconfig` lines 34-35)
- Indentation: 4 spaces (official Kotlin convention, enforced in `.editorconfig`)

**Linting:**
- Tool: Ktlint 1.8.0 (pinned in `core/build.gradle.kts` line 15)
- Code style: `ktlint_official` (configured in `.editorconfig` line 32)
- Trailing commas: Enabled for call sites to support cleaner Git diffs (`.editorconfig` lines 44-45)
- Trailing commas disabled on declaration sites (e.g., enum variants) (`.editorconfig` line 48)
- Multiline expression wrapping: Disabled to allow flexible formatting (`.editorconfig` lines 50-53)
- Build scripts excluded: `.gradle.kts` files skip Ktlint checks entirely (`.editorconfig` lines 66-68)

**Gradle Configuration:**
- Code style enforcement: `kotlin.code.style=official` in `gradle.properties`
- Compilation context parameters: `-Xcontext-parameters` flag in `core/build.gradle.kts` (line 40)

## Import Organization

**Order:**
1. Kotlin standard library imports (e.g., `kotlin.test.Test`, `kotlin.test.assertEquals`)
2. Third-party library imports sorted alphabetically (e.g., `arrow.core.*`, `com.squareup.kotlinpoet.*`, `io.swagger.*`, `io.ktor.*`)
3. Project imports last (e.g., `com.avsystem.justworks.core.model.*`, `com.avsystem.justworks.core.parser.*`)

Example from `ClientGeneratorTest.kt` (lines 1-19):
```kotlin
import com.avsystem.justworks.core.model.ApiSpec
import com.avsystem.justworks.core.model.Endpoint
import com.avsystem.justworks.core.model.HttpMethod
// ... more com.avsystem imports ...
import com.squareup.kotlinpoet.ExperimentalKotlinPoetApi
import com.squareup.kotlinpoet.KModifier
// ... more kotlinpoet imports ...
import kotlin.test.Test
import kotlin.test.assertEquals
```

**Path Aliases:**
- No path aliases detected; fully qualified imports used throughout

## Error Handling

**Patterns:**
- Sealed interface `ParseResult` with two subtypes: `Success(apiSpec, warnings)` and `Failure(errors, warnings)` for explicit result types (pattern-matching style)
- Arrow `Either` used internally in parsers with `.merge()` to collapse to `ParseResult` (example: `SpecParser.kt` lines 68-92)
- Arrow `Raise` context used for error accumulation in validation: `SpecValidator.fold()` with `accumulate{}` to collect all errors without short-circuiting (example: `SpecValidator.kt` lines 31-59)
- Kotlin `check()` and `fail()` for immediate validation failures in tests and critical paths (example: `SpecParserTestBase.kt` lines 10-11)
- No try-catch blocks in observable code; uses Arrow `catch` when needed internally

## Logging

**Framework:** Not detected in core logic; uses `println()` or Ktor built-in logging only when needed

**Patterns:**
- No explicit logging calls found in main code generation or parsing
- Error messages embedded in exception messages and validation issues
- Warnings captured in `ParseResult.Success.warnings` list instead of logs

## Comments

**When to Comment:**
- Documentation above public functions using KDoc (/** ... */) (example: `NameUtils.kt` lines 6-10)
- Section separators using `// ============================================================================` for logical grouping in constant files (example: `Names.kt` lines 6-8, 28-30)
- TODO comments marked as `// todo:` (example: `core/build.gradle.kts` line 13: `// todo: remove when https://github.com/JLLeitschuh/ktlint-gradle/issues/912 resolved`)
- No comments inside methods; code is self-documenting via clear naming

**JSDoc/KDoc:**
- Used extensively on public classes and functions with examples
- Example from `NameUtils.kt`: Multi-line KDoc with purpose, behavior details, and examples
- Example from `SpecParser.kt`: Comprehensive KDoc with `@param` and `@return` documentation and usage pattern in backticks
- Pattern: Document WHAT (purpose), HOW (behavior), and WHEN (use cases)

## Function Design

**Size:** Functions are typically 10-50 lines; longer functions (100+) are private builder methods in generators (example: `ModelGenerator.kt` lines 58-80)

**Parameters:**
- Private helper functions use default parameters for flexibility (example: `ClientGeneratorTest.kt` lines 34-54 with `endpoint()` factory)
- Named parameters with descriptive names; rarely more than 4 parameters
- When 3+ parameters: use multiline formatting (enforced by ktlint rule `ktlint_function_signature_rule_force_multiline_when_parameter_count_greater_or_equal_than = 3`)

**Return Values:**
- Return `List<T>` for collections; use `emptyList()` explicitly for no results (example: `ClientGeneratorTest.kt` line 30)
- Sealed interface results for operations that can fail (`ParseResult.Success` vs `ParseResult.Failure`)
- Extension functions return transformed copies (e.g., `String.toCamelCase()` returns new string, immutable style)

## Module Design

**Exports:**
- `object SpecParser` singleton with public `parse()` function as main entry point (example: `SpecParser.kt` lines 53-92)
- Multiple generator classes (`ClientGenerator`, `ModelGenerator`, `SerializersModuleGenerator`) each with public `generate()` function
- Utility functions exported as extension functions (e.g., `String.toCamelCase()` in `NameUtils.kt`)

**Barrel Files:**
- No barrel files (index/export files) detected; each file imports what it needs directly
- Model file `ApiSpec.kt` contains multiple related data classes but no wildcard exports

## Sealed Interfaces and Pattern Matching

**TypeRef sealed interface** (`core/src/main/kotlin/com/avsystem/justworks/core/model/TypeRef.kt`):
- Used throughout generators for type-safe handling of different schema reference types
- Variants: `Primitive`, `Array`, `Reference`, `Map`, `Inline`, `Unknown`
- Matched with `when (typeRef)` in `TypeMapping.kt` and generators for exhaustive handling

**ParseResult sealed interface** (`core/src/main/kotlin/com/avsystem/justworks/core/parser/SpecParser.kt`):
- Variants: `Success(apiSpec, warnings)`, `Failure(errors, warnings)`
- Required pattern: `when (result) { is ParseResult.Success -> ..., is ParseResult.Failure -> ... }`

## Data Classes and Records

- Preferred for immutable models (example: `Endpoint`, `Parameter`, `SchemaModel`, `PropertyModel`)
- All use named parameters in constructors
- No mutable properties; state passed through function returns

---

*Convention analysis: 2026-03-19*
