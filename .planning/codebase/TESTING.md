# Testing Patterns

**Analysis Date:** 2026-03-11

## Test Framework

**Runner:**
- Kotlin Test (kotlin.test) with JUnit Platform integration
- Version: Part of Kotlin stdlib (no explicit version in build.gradle.kts)
- Config: `useJUnitPlatform()` in `tasks.test` block (`.../core/build.gradle.kts`)

**Assertion Library:**
- `kotlin.test.*` assertions: `assertEquals()`, `assertTrue()`, `assertFalse()`, `assertNotNull()`, `assertIs()`, `fail()`
- No external assertion library (AssertJ, Hamcrest, etc.)

**Run Commands:**
```bash
./gradlew test                 # Run all tests
./gradlew test --watch        # Watch mode (not explicitly configured)
./gradlew test --info         # Verbose output
./gradlew koverHtmlReport     # Coverage report (Kover v0.9.1 configured)
```

## Test File Organization

**Location:**
- Co-located with source: `src/test/kotlin/` mirrors `src/main/kotlin/` structure
- Example: Test for `src/main/kotlin/com/avsystem/justworks/core/gen/TypeMapping.kt` is at `src/test/kotlin/com/avsystem/justworks/core/gen/TypeMappingTest.kt`
- Gradle tasks: `src/functionalTest/kotlin/` for integration tests (e.g., `JustworksPluginFunctionalTest.kt`)

**Naming:**
- Pattern: `{ClassUnderTest}Test.kt`
- Examples: `TypeMappingTest.kt`, `NameUtilsTest.kt`, `ModelGeneratorTest.kt`, `ClientGeneratorTest.kt`, `SpecParserTest.kt`, `SpecValidatorTest.kt`, `SpecParserPolymorphicTest.kt`, `ModelGeneratorPolymorphicTest.kt`
- Specialized suffixes for variant tests: `PolymorphicTest.kt` (e.g., `SpecParserPolymorphicTest.kt` tests polymorphic schema handling)

**Structure:**
```
core/
├── src/
│   ├── main/kotlin/com/avsystem/justworks/core/
│   │   ├── gen/
│   │   ├── parser/
│   │   ├── model/
│   │   └── Generator.kt
│   └── test/kotlin/com/avsystem/justworks/core/
│       ├── gen/
│       ├── parser/
│       └── [Test files mirror main structure]
plugin/
├── src/
│   ├── main/kotlin/com/avsystem/justworks/gradle/
│   └── functionalTest/kotlin/com/avsystem/justworks/gradle/
│       └── JustworksPluginFunctionalTest.kt
```

## Test Structure

**Suite Organization:**
```kotlin
class TypeMappingTest {
    private val pkg = "com.example.model"

    // -- Primitive types --

    @Test
    fun `maps STRING to kotlin String`() {
        val result = TypeMapping.toTypeName(TypeRef.Primitive(PrimitiveType.STRING), pkg)
        assertEquals("kotlin.String", result.toString())
    }

    @Test
    fun `maps INT to kotlin Int`() {
        // ...
    }

    // -- Array --

    @Test
    fun `maps Array of String to List of String`() {
        // ...
    }
}
```

**Patterns:**

1. **Setup Pattern:**
   - Private fields for shared test data: `private val pkg = "com.example.model"`
   - Helper factory functions for creating test objects:
     ```kotlin
     private fun spec(schemas: List<SchemaModel> = emptyList(), enums: List<EnumModel> = emptyList()) = ApiSpec(
         title = "Test",
         version = "1.0",
         endpoints = emptyList(),
         schemas = schemas,
         enums = enums,
     )
     ```
   - Inline object construction for simple test data:
     ```kotlin
     private val petSchema = SchemaModel(
         name = "Pet",
         description = "A pet in the store",
         properties = listOf(...),
         requiredProperties = setOf("id", "name"),
         // ...
     )
     ```

2. **Test Method Pattern:**
   - Single responsibility per test
   - Arrange-Act-Assert (AAA) implicit:
     ```kotlin
     @Test
     fun `required property is non-nullable in constructor`() {
         // Arrange
         val files = generator.generate(spec(schemas = listOf(petSchema)))

         // Act & Assert
         val typeSpec = files.first().members.filterIsInstance<TypeSpec>().first()
         val constructor = assertNotNull(typeSpec.primaryConstructor, "Expected primary constructor")
         val idParam = constructor.parameters.first { it.name == "id" }
         assertTrue(!idParam.type.isNullable, "Required property 'id' should be non-nullable")
     }
     ```

3. **Assertion Pattern:**
   - Use `kotlin.test` assertions exclusively
   - `assertEquals()` for value equality: `assertEquals("kotlin.String", result.toString())`
   - `assertNotNull()` with optional message: `assertNotNull(typeSpec, "Expected primary constructor")`
   - `assertIs<T>()` for type assertions: `assertIs<ParseResult.Success>(result).spec`
   - `assertTrue()/assertFalse()` with messages: `assertTrue("Pet" in schemaNames, "Pet schema missing")`
   - `fail()` for explicit failure: `fail("Pet schema not found")`

4. **Resource Loading Pattern:**
   ```kotlin
   private fun loadResource(name: String): File {
       val url = javaClass.getResource("/$name")
           ?: fail("Test resource not found: $name")
       return File(url.toURI())
   }
   ```
   - Resources stored in `src/test/resources/` (e.g., `petstore.yaml`)

5. **Test Grouping:**
   - Comments group related tests with `// -- Category --` headers
   - Examples: `// -- Primitive types --`, `// -- Array --`, `// -- Reference --`
   - Helps organize large test classes by domain

## Mocking

**Framework:** No explicit mocking library detected (no Mockk, Mockito, etc. in dependencies)

**Patterns:**
- **Fake objects:** Create real instances with test data instead of mocks
  ```kotlin
  private fun endpoint(
      path: String = "/pets",
      method: HttpMethod = HttpMethod.GET,
      operationId: String = "listPets",
      tags: List<String> = listOf("Pets"),
      parameters: List<Parameter> = emptyList(),
      requestBody: RequestBody? = null,
      responses: Map<String, Response> = mapOf("200" to Response("200", "OK", TypeRef.Reference("Pet"))),
  ) = Endpoint(path, method, operationId, null, tags, parameters, requestBody, responses)
  ```
  - Calling test provides custom values, defaults fill rest
  - No mocking framework needed for data object creation

- **Builder pattern:** Use helper builders for complex objects
  - Tests construct real ApiSpec, Endpoint, Response objects with defaults
  - Allows fine-grained control over test inputs

**What to Mock:**
- Nothing currently — all tests use real objects and pure functions
- External file I/O used with real files (test resources)

**What NOT to Mock:**
- Never mock the code under test (generators, parsers)
- Never mock models/data classes (construct real instances)
- Never mock Kotlin standard library or external APIs

## Fixtures and Factories

**Test Data:**
- Factory functions (not fixtures) create reusable test objects:
  ```kotlin
  // TypeMappingTest.kt
  private val pkg = "com.example.model"

  // ClientGeneratorTest.kt
  private fun spec(endpoints: List<Endpoint>) = ApiSpec(...)
  private fun endpoint(...) = Endpoint(...)
  private fun clientClass(endpoints: List<Endpoint>): TypeSpec { ... }
  ```
- Inline construction for one-off objects
- Parameterized factories with sensible defaults for flexibility

**Location:**
- Defined as private methods/fields in test class
- No separate fixture files or factories directory
- Test data inline or as small helper functions

**Example from ModelGeneratorTest.kt:**
```kotlin
class ModelGeneratorTest {
    private val modelPackage = "com.example.model"
    private val generator = ModelGenerator(modelPackage)

    private fun spec(schemas: List<SchemaModel> = emptyList(), enums: List<EnumModel> = emptyList()) =
        ApiSpec(title = "Test", version = "1.0", endpoints = emptyList(), schemas = schemas, enums = enums)

    private val petSchema = SchemaModel(
        name = "Pet",
        description = "A pet in the store",
        properties = listOf(...),
        requiredProperties = setOf("id", "name"),
        isEnum = false,
        allOf = null,
        oneOf = null,
        anyOf = null,
        discriminator = null,
    )
}
```

## Coverage

**Requirements:**
- No explicit coverage target enforced
- Kover plugin (v0.9.1) configured for coverage reporting: can generate HTML reports
- Coverage is measured but not enforced as gate in build

**View Coverage:**
```bash
./gradlew koverHtmlReport
# Opens: build/reports/kover/html/index.html
```

## Test Types

**Unit Tests:**
- **Scope:** Test a single class/function in isolation
- **Approach:** Direct instantiation of code under test with test data
- **Location:** `src/test/kotlin/.../*Test.kt`
- **Examples:**
  - `TypeMappingTest`: Tests `TypeMapping.toTypeName()` with various `TypeRef` inputs
  - `NameUtilsTest`: Tests string transformation functions (`toCamelCase()`, `toEnumConstantName()`, etc.)
  - `ModelGeneratorTest`: Tests model code generation with constructed `ApiSpec` objects
  - `ClientGeneratorTest`: Tests client code generation logic

**Integration Tests:**
- **Scope:** Test interaction between parser, validators, and generators
- **Approach:** Use real YAML/JSON OpenAPI specs as input
- **Location:** `src/test/kotlin/.../SpecParser*Test.kt`
- **Examples:**
  - `SpecParserTest`: Parses real `petstore.yaml`, validates endpoints, schemas, enums
  - `SpecValidatorTest`: Tests OpenAPI validation against Swagger Parser

**Functional/Plugin Tests:**
- **Location:** `plugin/src/functionalTest/kotlin/.../JustworksPluginFunctionalTest.kt`
- **Approach:** Test full Gradle plugin lifecycle (registration, task creation, execution)
- **Scope:** Verify plugin integration with Gradle build system

**E2E Tests:**
- Not used currently
- Functional tests cover end-to-end plugin behavior

## Common Patterns

**Async Testing:**
- No async/coroutine testing found
- Core generation logic is synchronous

**Error Testing:**
```kotlin
@Test
fun `parse invalid spec produces Failure`() {
    val result = parser.parse(loadResource("invalid.yaml"))
    assertIs<ParseResult.Failure>(result)
    assertTrue(result.errors.isNotEmpty())
}

@Test
fun `OpenAPI with null info produces errors`() {
    val openApi = OpenAPI().apply { info = null; paths = Paths() }
    val errors = SpecValidator.validate(openApi)
    assertTrue(errors.isNotEmpty(), "Missing info should produce errors")
    assertTrue(errors.any { it.contains("info", ignoreCase = true) }, "Error should mention 'info': $errors")
}
```

**Type Assertion:**
```kotlin
// Pattern 1: assertIs<T>() with immediate property access
val spec = assertIs<ParseResult.Success>(result).spec

// Pattern 2: filterIsInstance<T>() for collections
val typeSpec = files.first().members.filterIsInstance<TypeSpec>().first()
```

**Null Safety in Tests:**
```kotlin
// Pattern: assertNotNull() with message
val constructor = assertNotNull(typeSpec.primaryConstructor, "Expected primary constructor")
val idParam = constructor.parameters.first { it.name == "id" }

// Pattern: fail() for explicit missing resource
val url = javaClass.getResource("/$name") ?: fail("Test resource not found: $name")
```

**Collection Testing:**
```kotlin
// Pattern: size assertion then element assertion
assertEquals(3, spec.endpoints.size, "Expected 3 endpoints")

// Pattern: Set membership
val schemaNames = spec.schemas.map { it.name }.toSet()
assertTrue("Pet" in schemaNames, "Pet schema missing")

// Pattern: find with assertion
val petStatus = spec.enums.find { it.name == "PetStatus" }
assertNotNull(petStatus, "PetStatus enum missing")
```

## Test Organization Summary

- **File naming:** `{ClassUnderTest}Test.kt` or `{Feature}PolymorphicTest.kt`
- **Setup:** Private fields + helper factory functions
- **Assertions:** `kotlin.test.*` exclusively
- **Mocking:** None — use real objects with sensible defaults
- **Fixtures:** Inline factories, no separate fixture directory
- **Coverage:** Measured with Kover, not enforced
- **Test types:** Unit (main), Integration (parser + spec), Functional (plugin)
- **Comments:** Group tests with `// -- Category --` headers for clarity

---

*Testing analysis: 2026-03-11*
