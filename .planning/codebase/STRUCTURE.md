# Codebase Structure

**Analysis Date:** 2026-03-19

## Directory Layout

```
justworks/
├── core/                           # Main library module (all generation logic)
│   ├── src/main/kotlin/
│   │   └── com/avsystem/justworks/core/
│   │       ├── parser/             # OpenAPI parsing & validation
│   │       ├── model/              # Intermediate domain model
│   │       └── gen/                # Code generators
│   ├── src/test/kotlin/
│   │   └── com/avsystem/justworks/core/
│   │       ├── gen/                # Generator unit tests
│   │       └── parser/             # Parser unit tests
│   ├── src/test/resources/         # Test OpenAPI/Swagger specs
│   └── build.gradle.kts            # Module build config
├── plugin/                         # Gradle plugin module (stub)
├── build.gradle.kts                # Root build config (versions, repos, ktlint)
├── settings.gradle.kts             # Gradle settings
└── gradle.properties               # Gradle properties
```

## Directory Purposes

**core/src/main/kotlin/com/avsystem/justworks/core/parser/:**
- Purpose: Parse OpenAPI 3.0 / Swagger 2.0 specs into `ApiSpec` intermediate model
- Contains: `SpecParser.kt` (main entry), `SpecValidator.kt` (validation rules)
- Key files:
  - `SpecParser.kt` — 403 lines, stateless parser using Swagger Parser v3
  - `SpecValidator.kt` — Arrow accumulate pattern for non-short-circuiting validation

**core/src/main/kotlin/com/avsystem/justworks/core/model/:**
- Purpose: Language-agnostic intermediate representations
- Contains: Immutable data classes, sealed trees, no generation logic
- Key files:
  - `ApiSpec.kt` — 109 lines, root model class + all nested data structures
  - `TypeRef.kt` — 21 lines, sealed tree for type references (Primitive, Array, Reference, Map, Inline, Unknown)

**core/src/main/kotlin/com/avsystem/justworks/core/gen/:**
- Purpose: Transform `ApiSpec` to Kotlin source code using KotlinPoet
- Contains: 8 code generator classes, type mapping, utilities
- Key files:
  - `ModelGenerator.kt` — 480 lines, data/sealed/enum class generation + polymorphic handling
  - `ClientGenerator.kt` — 220 lines, HTTP client stub generation per endpoint tag
  - `ApiClientBaseGenerator.kt` — 212 lines, shared base class + utilities (encodeParam, mapToResult)
  - `Names.kt` — 98 lines, centralized KotlinPoet ClassName/MemberName definitions
  - `TypeMapping.kt` — 58 lines, TypeRef → KotlinPoet TypeName conversion
  - `ApiResponseGenerator.kt` — 78 lines, HttpError/HttpSuccess wrapper types
  - `SerializersModuleGenerator.kt` — 55 lines, polymorphic serializer registration
  - `InlineSchemaDeduplicator.kt` — 50 lines, deduplication by structural equality
  - `NameUtils.kt` — 73 lines, case conversion utilities (camelCase, PascalCase, ENUM_CONSTANT_NAME)

**core/src/test/kotlin/com/avsystem/justworks/core/:**
- Purpose: Unit tests for parsers and generators
- Tests follow same directory structure as main
- Key test files:
  - `gen/ClientGeneratorTest.kt` — Parametric tests for endpoint function generation
  - `gen/ModelGeneratorTest.kt`, `ModelGeneratorPolymorphicTest.kt` — Data class + sealed interface generation
  - `parser/SpecParserTest.kt`, `SpecParserPolymorphicTest.kt` — Parsing + polymorphism detection

**core/src/test/resources/:**
- Purpose: Example OpenAPI/Swagger specification files for testing
- Contains: 8 YAML/JSON test fixtures
  - `petstore.yaml` — Full OAS 3.0 spec with endpoints, schemas, polymorphism
  - `petstore-v2.json` — Swagger 2.0 spec (tests backward compatibility)
  - `polymorphic-spec.yaml` — Tests oneOf with discriminator
  - `anyof-spec.yaml`, `anyof-valid-spec.yaml` — Tests anyOf patterns
  - `refs-spec.yaml` — Tests $ref resolution
  - `mixed-combinator-spec.yaml`, `invalid-spec.yaml` — Error conditions

## Key File Locations

**Entry Points:**
- `core/src/main/kotlin/com/avsystem/justworks/core/parser/SpecParser.kt` (line 68): `fun parse(specFile: File): ParseResult`
- `core/src/main/kotlin/com/avsystem/justworks/core/gen/ClientGenerator.kt` (line 35): `fun generate(spec: ApiSpec, hasPolymorphicTypes: Boolean): List<FileSpec>`
- `core/src/main/kotlin/com/avsystem/justworks/core/gen/ModelGenerator.kt` (line 34): `fun generate(spec: ApiSpec): List<FileSpec>`

**Configuration:**
- `core/build.gradle.kts` — Dependencies (Swagger Parser, KotlinPoet, Arrow, kotlinx.datetime), publishing config
- `build.gradle.kts` — Root project config (Kotlin 2.3.0, Java 21 toolchain, ktlint plugin)
- `settings.gradle.kts` — Module includes (core only; plugin is stub)

**Core Logic:**
- `core/src/main/kotlin/com/avsystem/justworks/core/model/ApiSpec.kt` — Spec hierarchy definition
- `core/src/main/kotlin/com/avsystem/justworks/core/parser/SpecParser.kt` — Parsing logic
- `core/src/main/kotlin/com/avsystem/justworks/core/gen/ModelGenerator.kt` — Model generation
- `core/src/main/kotlin/com/avsystem/justworks/core/gen/ClientGenerator.kt` — Client generation

**Testing:**
- `core/src/test/kotlin/com/avsystem/justworks/core/gen/ClientGeneratorTest.kt` — Client generator tests
- `core/src/test/kotlin/com/avsystem/justworks/core/gen/ModelGeneratorTest.kt` — Model generator tests
- `core/src/test/kotlin/com/avsystem/justworks/core/parser/SpecParserTest.kt` — Parser tests
- `core/src/test/resources/` — Test fixture specs (YAML/JSON)

## Naming Conventions

**Files:**
- `*.kt` for Kotlin sources
- `*Test.kt` for unit test classes (co-located with source structure)
- `*Generator.kt` for code generation classes
- `*Validator.kt` for validation classes
- `*.yaml` / `*.json` for test OpenAPI specs

**Directories:**
- `com/avsystem/justworks/core/{parser,model,gen}/` — Strict package-to-directory mapping

**Classes:**
- Generators: `XxxGenerator` (e.g., `ClientGenerator`, `ModelGenerator`)
- Data models: `XxxModel` (e.g., `SchemaModel`, `EnumModel`)
- Parser: `SpecParser` (singleton object)
- Validators: `SpecValidator` (singleton object)
- Utilities: `Names`, `NameUtils`, `TypeMapping` (singleton objects/no constructors)

**Functions:**
- Top-level case converters: `String.toCamelCase()`, `String.toPascalCase()`, `String.toEnumConstantName()` in `NameUtils.kt`
- `generate()` method on all generator classes (overloaded by parameter type)
- `parse()` on `SpecParser`
- `validate()` on `SpecValidator`

**Variables/Properties:**
- camelCase: function params, local variables, property names
- UPPER_SNAKE_CASE: constants in companion objects / top-level object declarations

## Where to Add New Code

**New Feature (e.g., support for request/response headers validation):**
- **Spec extraction logic:** Add to `SpecParser.kt` in the appropriate extraction function (e.g., `extractEndpoints`, `extractSchemaModel`)
- **Model representation:** Add new field to `ApiSpec.kt` or related model classes
- **Code generation:** Add method to relevant `*Generator` (likely `ClientGenerator` or `ApiClientBaseGenerator`)
- **Tests:** Add cases to `SpecParserTest.kt` (for parsing) and corresponding `*GeneratorTest.kt` (for code generation)

**New Generator (e.g., OpenAPI docs generator from ApiSpec):**
- Create `OpenApiGenerator.kt` in `core/src/main/kotlin/com/avsystem/justworks/core/gen/`
- Implement `fun generate(spec: ApiSpec): List<FileSpec>` or appropriate return type
- Import/define necessary `ClassName`/`MemberName` constants in `Names.kt`
- Add unit tests in `core/src/test/kotlin/com/avsystem/justworks/core/gen/OpenApiGeneratorTest.kt`

**New Utility Function (e.g., type name sanitization):**
- If it's a general string transformation: add to `NameUtils.kt` as extension function on `String`
- If it's type-specific: add to `TypeMapping.kt`
- Document behavior with examples in KDoc

**New Validation Rule:**
- Add to `SpecValidator.validate()` in `core/src/main/kotlin/com/avsystem/justworks/core/parser/SpecValidator.kt`
- Use `ensureOrAccumulate()` for warnings, `ensureOrAccumulate()` for errors
- Add test case to `SpecParserTest.kt` with fixture in `core/src/test/resources/`

**Breaking Change to Model:**
- Edit `ApiSpec.kt` or related `*Model` classes
- Update `SpecParser` extraction logic
- Update all tests that construct mock models
- Update all generators that consume the changed model

## Special Directories

**plugin/:**
- Purpose: Gradle plugin integration (stub; not yet functional)
- Generated: Yes (build artifacts)
- Committed: Yes (directory exists but empty)

**.gradle/:**
- Purpose: Gradle build cache and metadata
- Generated: Yes
- Committed: No (.gitignore)

**build/:**
- Purpose: Compiled classes, jars, test reports
- Generated: Yes
- Committed: No (.gitignore)

**.idea/:**
- Purpose: IntelliJ IDEA project metadata
- Generated: Yes (by IDE)
- Committed: No (.gitignore)

**gradle/wrapper/:**
- Purpose: Gradle wrapper distribution
- Generated: No (committed)
- Committed: Yes

---

*Structure analysis: 2026-03-19*
