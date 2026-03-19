# Testing Patterns

**Analysis Date:** 2026-03-19

## Test Framework

**Runner:**
- Kotlin Test (built-in) with JUnit Platform support
- Version: Bundled with Kotlin 2.3.0
- Config: `core/build.gradle.kts` line 28: `tasks.test { useJUnitPlatform() }`

**Assertion Library:**
- Kotlin Test assertions: `kotlin.test.assertEquals`, `kotlin.test.assertNotNull`, `kotlin.test.assertTrue`, `kotlin.test.fail`, `kotlin.test.assertIs`

**Run Commands:**
```bash
./gradlew test                    # Run all tests
./gradlew test --continuous       # Watch mode
./gradlew koverHtmlReport         # Coverage report (using Kover 0.9.1)
```

## Test File Organization

**Location:**
- Co-located with source files in parallel directory structure
- Source: `core/src/main/kotlin/...`
- Tests: `core/src/test/kotlin/...` (mirrored package structure)
- Test resources: `core/src/test/resources/` (YAML/JSON OpenAPI specs)

**Naming:**
- Test class: `{SourceClass}Test.kt` (e.g., `NameUtilsTest.kt` for `NameUtils.kt`, `ClientGeneratorTest.kt` for `ClientGenerator.kt`)
- 13 test files total with 180+ test methods

**Structure:**
```
core/src/test/kotlin/com/avsystem/justworks/core/
├── gen/
│   ├── ApiClientBaseGeneratorTest.kt
│   ├── ApiResponseGeneratorTest.kt
│   ├── ClientGeneratorTest.kt
│   ├── InlineSchemaDedupTest.kt
│   ├── ModelGeneratorTest.kt
│   ├── ModelGeneratorPolymorphicTest.kt
│   ├── NameUtilsTest.kt
│   ├── SerializersModuleGeneratorTest.kt
│   └── TypeMappingTest.kt
└── parser/
    ├── SpecParserPolymorphicTest.kt
    ├── SpecParserTest.kt
    ├── SpecParserTestBase.kt
    └── SpecValidatorTest.kt

core/src/test/resources/
├── anyof-spec.yaml
├── anyof-valid-spec.yaml
├── invalid-spec.yaml
├── mixed-combinator-spec.yaml
├── petstore-v2.json
├── petstore.yaml
├── polymorphic-spec.yaml
└── refs-spec.yaml
```

Test resources are OpenAPI specification files (YAML and JSON) used to test parsing and code generation.

## Test Structure

**Suite Organization:**

Test classes typically have a companion object or factory methods for test data setup.

Example pattern from `ClientGeneratorTest.kt` (lines 21-63):

```kotlin
class ClientGeneratorTest {
    private val apiPackage = "com.example.api"
    private val modelPackage = "com.example.model"
    private val generator = ClientGenerator(apiPackage, modelPackage)

    private fun spec(endpoints: List<Endpoint>) = ApiSpec(
        title = "Test",
        version = "1.0",
        endpoints = endpoints,
        schemas = emptyList(),
        enums = emptyList(),
    )

    private fun endpoint(
        path: String = "/pets",
        method: HttpMethod = HttpMethod.GET,
        operationId: String = "listPets",
        // ... more defaults ...
    ) = Endpoint(path = path, method = method, /* ... */)

    private fun clientClass(endpoints: List<Endpoint>): TypeSpec {
        val files = generator.generate(spec(endpoints))
        return files.first().members.filterIsInstance<TypeSpec>().first()
    }
```

**Patterns:**

1. **Arrange-Act-Assert (AAA):** Each test method clearly separates setup, execution, and verification
2. **Factory methods:** Private builder methods with sensible defaults allow concise test cases
3. **Data class construction:** Uses named parameters for clarity
4. **Comments as test section markers:** `// -- CATEGORY-01: Description --` above related test groups

Example from `NameUtilsTest.kt` (lines 7-12):

```kotlin
// -- toCamelCase --

@Test
fun `toCamelCase converts snake_case`() {
    assertEquals("snakeCase", "snake_case".toCamelCase())
}
```

## Mocking

**Framework:** Not detected

**What to Mock:**
- File system access: Use `File(url.toURI())` from test resources instead of mocking
- External APIs: Use OpenAPI spec files as fixtures instead of mocking HTTP calls
- No mock framework (Mockito, etc.) detected in dependencies

**What NOT to Mock:**
- Data models (use real data classes with test factories)
- Generators (unit tests instantiate real generators)
- Parsers (use real parser with fixture specs)

Example: `SpecParserTestBase.kt` (lines 7-18) loads real spec files and parses them:

```kotlin
abstract class SpecParserTestBase {
    protected fun loadResource(name: String): File {
        val url =
            javaClass.getResource("/$name")
                ?: fail("Test resource not found: $name")
        return File(url.toURI())
    }

    protected fun parseSpec(file: File): ApiSpec = when (val result = SpecParser.parse(file)) {
        is ParseResult.Success -> result.apiSpec
        is ParseResult.Failure -> fail("Expected success but got errors: ${result.errors}")
    }
}
```

## Fixtures and Factories

**Test Data:**

Factory functions provide sensible defaults for common test objects. All data is constructed in-memory.

Example from `ModelGeneratorTest.kt` (lines 28-43):

```kotlin
private val petSchema =
    SchemaModel(
        name = "Pet",
        description = "A pet in the store",
        properties =
            listOf(
                PropertyModel("id", TypeRef.Primitive(PrimitiveType.LONG), null, false),
                PropertyModel("name", TypeRef.Primitive(PrimitiveType.STRING), null, false),
                PropertyModel("tag", TypeRef.Primitive(PrimitiveType.STRING), null, true),
            ),
        requiredProperties = setOf("id", "name"),
        allOf = null,
        oneOf = null,
        anyOf = null,
        discriminator = null,
    )
```

**Location:**
- Test resources: `core/src/test/resources/` (YAML/JSON OpenAPI specs: `petstore.yaml`, `polymorphic-spec.yaml`, etc.)
- Inline factories: Private methods in test classes (e.g., `endpoint()`, `schema()`, `spec()`)
- Base class fixtures: `SpecParserTestBase.kt` provides common loading/parsing helpers

## Coverage

**Requirements:** Not enforced in build; Kover integration present but no minimum coverage threshold configured

**View Coverage:**
```bash
./gradlew koverHtmlReport        # Generates HTML report
# Report location: core/build/reports/kover/html/index.html
```

Kover configured in `core/build.gradle.kts` line 6:
```kotlin
id("org.jetbrains.kotlinx.kover") version "0.9.1"
```

## Test Types

**Unit Tests:**
- Scope: Individual functions and classes in isolation
- Approach: Test each function with multiple input variations
- Example: `NameUtilsTest.kt` (190 lines, tests `toCamelCase()`, `toPascalCase()`, `toEnumConstantName()`, `operationNameFromPath()` with 18 distinct cases)
- Tests typically 1-3 lines (pure assertion)

**Integration Tests:**
- Scope: Parser + validator + model transformation pipeline
- Approach: Load real OpenAPI specs, parse end-to-end, verify output model structure
- Examples: `SpecParserTest.kt` loads `petstore.yaml` and verifies endpoints, schemas, enums are extracted correctly
- Tests typically 5-15 lines (setup assertion checking)

**End-to-End Tests:**
- Scope: Full code generation pipeline (parser → model generation)
- Approach: Verify generated Kotlin code structure via KotlinPoet AST inspection
- Examples: `ClientGeneratorTest.kt` generates client code and inspects `TypeSpec` for modifiers, function names, parameters
- Tests typically 5-20 lines

**E2E Tests:**
- Not found; no compilation of generated code or runtime verification

## Common Patterns

**Assertion Patterns:**

Simple equality assertions dominate:
```kotlin
assertEquals("snakeCase", "snake_case".toCamelCase())
assertEquals(3, petstore.endpoints.size, "Expected 3 endpoints")
```

Presence checks with descriptive messages:
```kotlin
assertTrue("Pet" in schemaNames, "Pet schema missing")
assertTrue(KModifier.DATA in typeSpec.modifiers, "Expected DATA modifier")
```

Type-safe assertions for sealed interfaces:
```kotlin
assertIs<TypeRef.Primitive>(idType)
assertEquals(PrimitiveType.LONG, idType.type)
```

Null and not-null checks:
```kotlin
assertNotNull(petStatus, "PetStatus enum missing")
assertNotNull(constructor, "Expected primary constructor")
```

**Test Naming Convention:**

Backtick-quoted test names in clear English:
```kotlin
@Test
fun `toCamelCase converts snake_case`() { ... }

@Test
fun `generates one client class per tag`() { ... }

@Test
fun `endpoint functions are suspend`() { ... }
```

**Pattern Matching in Tests:**

`when` expressions for result handling (same as production code):
```kotlin
when (val result = SpecParser.parse(file)) {
    is ParseResult.Success -> result.apiSpec
    is ParseResult.Failure -> fail("Expected success but got errors: ${result.errors}")
}
```

**Test Class Lifecycle:**

Using `@BeforeTest` (Kotlin Test) for one-time setup:
```kotlin
@BeforeTest
fun setUp() {
    if (!::petstore.isInitialized) {
        petstore = parseSpec(loadResource("petstore.yaml"))
    }
}
```

`@TestInstance(TestInstance.Lifecycle.PER_CLASS)` annotation used for per-class lifecycle (example: `SpecParserTest.kt` line 19)

**Error Testing:**

Verify failure paths with explicit error checks:
```kotlin
@Test
fun `OpenAPI with null info produces errors`() {
    val issues = SpecValidator.validate(openApi)
    assertTrue(issues.isNotEmpty(), "Missing info should produce issues")
    assertTrue(
        issues.any { it.message.contains("info", ignoreCase = true) },
        "Error should mention 'info': $issues",
    )
}
```

## Coverage Gaps

**Areas with Limited Testing:**
- Error recovery paths in generators (focus on happy path)
- Complex nested schema transformations (basic coverage only)
- Edge cases in type mapping with non-standard OpenAPI constructs

---

*Testing analysis: 2026-03-19*
