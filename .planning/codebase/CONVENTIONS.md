# Coding Conventions

**Analysis Date:** 2026-03-11

## Naming Patterns

**Files:**
- `*.kt` for Kotlin source files
- Class names: PascalCase with semantic suffixes
  - Test files: `{Tested}Test.kt`, e.g., `TypeMappingTest.kt`
  - Generator classes: `{Domain}Generator.kt`, e.g., `ModelGenerator.kt`, `ClientGenerator.kt`
  - Task classes: `{Feature}Task.kt`, e.g., `JustworksGenerateTask.kt`
  - Plugin classes: `{Name}Plugin.kt`
  - Utility/Helper: Descriptive `{Purpose}Utils.kt` or semantic object names
  - Model/Data: Simple descriptive names, e.g., `ApiSpec.kt`, `TypeRef.kt`

**Functions/Methods:**
- `camelCase` for all function names
- Extension functions follow Kotlin conventions: `fun String.toCamelCase()`, `fun String.toPascalCase()`
- Test functions use backtick-quoted descriptive names: `` `maps STRING to kotlin String`() ``
  - Format: `` `[action] [input] [expected output]`() ``
  - Example: `` `maps INT to kotlin Int`() ``, `` `generates data class with DATA modifier`() ``
- Private functions prefixed with `private` keyword: `private fun primitiveTypeName(type: PrimitiveType): TypeName`
- Top-level utility functions placed as extension functions on relevant types

**Variables:**
- `camelCase` for all local variables and properties
- `val` preferred over `var` (immutability-first)
- Private properties marked with `KModifier.PRIVATE`: `PropertySpec.builder("baseUrl", STRING).addModifiers(KModifier.PRIVATE)`
- Constants in objects or companion objects use `UPPER_SNAKE_CASE` (Kotlin convention)
  - Example: `KOTLIN_HARD_KEYWORDS`, `HTTP_CLIENT`, `SERIALIZABLE`

**Types:**
- `PascalCase` for all type names (classes, interfaces, sealed interfaces)
- Sealed interfaces/classes for sum types: `sealed interface TypeRef`, `sealed interface ParseResult`
- Enum classes: `enum class HttpMethod { GET, POST, PUT, DELETE, PATCH }`
- Data classes for value objects: `data class ApiSpec(...)`
- Nested types use dot notation in references but no special naming

## Code Style

**Formatting:**
- Tool: Ktlint 1.8.0 (managed via gradle plugin `org.jlleitschuh.gradle.ktlint` v12.1.2)
- Indentation: 4 spaces (enforced in `.editorconfig`)
- Max line length: 120 characters
- UTF-8 encoding for all files
- Trailing newline required at end of file
- Trailing whitespace trimmed

**Linting:**
- Code style: `ktlint_official` (official Kotlin conventions)
- Ktlint disabled for `**/*.gradle.kts` (Gradle DSL scripts)
- Ktlint disabled for generated files in `**/build/**/*.kt` and `**/generated/**/*.kt`
- Multiline function signatures forced when parameter count >= 3
  - Example (3+ params → split to multiple lines): `fun ClientGenerator(apiPackage: String, modelPackage: String,)`
- Multiline class signatures forced when parameter count >= 3
- Trailing commas enabled on call sites: `ij_kotlin_allow_trailing_comma_on_call_site = true`
- Trailing commas disabled on declaration sites (enums, etc.): `ktlint_standard_trailing-comma-on-declaration-site = disabled`
- Multiline expression wrapping disabled: allows first line of expression to start on same line as assignment

## Import Organization

**Order:**
1. Kotlin standard library imports: `import kotlin.*`
2. Third-party library imports: `import io.swagger.*`, `import com.squareup.*`, etc.
3. Project imports: `import com.avsystem.justworks.*`

**Path Aliases:**
- No path aliases used in current codebase
- Imports are fully qualified

**Pattern:**
- Group imports by domain/package origin, not alphabetically
- Example from `ClientGenerator.kt`:
  ```kotlin
  import com.avsystem.justworks.core.model.ApiSpec
  import com.avsystem.justworks.core.model.Endpoint
  // ... more model imports
  import com.squareup.kotlinpoet.ClassName
  import com.squareup.kotlinpoet.CodeBlock
  // ... more kotlinpoet imports
  import java.io.File
  ```

## Error Handling

**Patterns:**
- `ParseResult` sealed interface for success/failure handling:
  - `ParseResult.Success(val spec: ApiSpec, val warnings: List<String> = emptyList())`
  - `ParseResult.Failure(val errors: List<String>)`
- `GradleException` thrown for configuration validation errors in Gradle plugins
- `IllegalArgumentException` for schema validation failures (caught and converted to `ParseResult.Failure`)
- Validation returns `List<String>` for error messages: `SpecValidator.validate(openApi): List<String>`
- Early returns for validation failures: `return ParseResult.Failure(errors)`

## Logging

**Framework:** Gradle `project.logger` (project context) for Gradle tasks; no logging elsewhere

**Patterns:**
- Gradle tasks use `logger.lifecycle(...)` for user-facing messages: `logger.lifecycle("Generated $modelCount model files, $clientCount client files from ${spec.name}")`
- Gradle task validation uses `logger.warn(...)` for non-blocking issues: `project.logger.warn("justworks: no specs configured in justworks.specs { } block")`
- No console logging in core generation logic (`core` module)

## Comments

**When to Comment:**
- Document public API intentions: `/** Generates one KotlinPoet [FileSpec] per API tag, ... */`
- Explain non-obvious logic: e.g., identity map comments in `SpecParser.transformToModel()`
- TODO/FIXME for known limitations: `// todo: remove when https://github.com/JLLeitschuh/ktlint-gradle/issues/912 resolved`

**JSDoc/KDoc:**
- Public classes: Include brief description and usage context
- Public functions: Describe parameters and return value when non-obvious
- Private members: Minimal or none (code should be self-documenting)
- Example from `JustworksExtension.kt`:
  ```kotlin
  /**
   * Container of named OpenAPI spec configurations.
   *
   * Each spec is independently configured and generates to its own
   * build/generated/justworks/{specName}/ directory.
   */
  val specs: NamedDomainObjectContainer<JustworksSpecConfiguration>
  ```

## Function Design

**Size:**
- Functions typically 5-50 lines
- Large functions refactored into helper functions
- Example: `SpecParser.transformToModel()` ~100 lines → multiple private helpers like `extractSchemas()`, `extractEndpoints()`

**Parameters:**
- 1-2 parameters: listed inline
- 3+ parameters: split across multiple lines (enforced by ktlint)
- Trailing comma after last parameter (ktlint rule): `fun ClientGenerator(apiPackage: String, modelPackage: String,)`
- Named parameters used at call sites for clarity: `endpoint(operationId = "listPets", tags = listOf("Pets"))`

**Return Values:**
- Explicit return types for public functions
- Type inference used for local variables
- Sealed interfaces (`ParseResult`) for disjoint return types
- Early returns for validation/guards
- Example from `TypeMapping.toTypeName()`: returns `TypeName` with exhaustive when expression

## Module Design

**Exports:**
- Top-level functions exported as extension functions for discoverability
  - `String.toCamelCase()` in `NameUtils.kt` module
  - `String.toKotlinIdentifier()` in `NameUtils.kt` module
- Objects exported for grouping related functions: `object TypeMapping`, `object SpecValidator`

**Barrel Files:**
- No barrel/index files in use
- Imports are direct to specific files

**Module Naming:**
- `core` module: parsing, generation, models
- `plugin` module: Gradle plugin integration
- Packages organized by domain: `.parser`, `.gen`, `.model`

## Kotlin-Specific Conventions

**Sealed Types:**
- Used for algebraic data types: `sealed interface TypeRef { data class Primitive(...), data class Array(...), ... }`
- Used for result types: `sealed interface ParseResult { data class Success(...), data class Failure(...) }`
- Provides exhaustiveness checking in when expressions

**Data Classes:**
- All model objects are data classes: `data class ApiSpec(...)`
- Provides auto-generated `equals()`, `hashCode()`, `copy()`, `toString()`

**Object Declaration:**
- Singleton objects for stateless utilities: `object TypeMapping`, `object NameUtils`, `object SpecValidator`
- Contains static-like functions/extensions

**Extension Functions:**
- Heavily used for string transformations: `String.toCamelCase()`, `String.toPascalCase()`, `String.toEnumConstantName()`, `String.toKotlinIdentifier()`
- Improves readability at call sites: `spec.name.toPascalCase()` vs. `NameUtils.toPascalCase(spec.name)`

**Null Safety:**
- Nullable types marked with `?`: `val summary: String?`
- `.orEmpty()` on optional collections: `openApi.components?.schemas.orEmpty()`
- `.orElse()` on Gradle properties: `spec.apiPackage.orElse(spec.packageName.map { "$it.api" })`
- `?:` operator for defaults: `val url = javaClass.getResource("/$name") ?: fail("Test resource not found: $name")`

---

*Convention analysis: 2026-03-11*
