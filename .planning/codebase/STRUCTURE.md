# Codebase Structure

**Analysis Date:** 2026-03-11

## Directory Layout

```
justworks/                                # Root project (multi-module)
├── build.gradle.kts                      # Root build config
├── settings.gradle.kts                   # Module includes (core, plugin)
├── gradle/                               # Gradle wrapper
├── buildSrc/                             # Build logic (shared across modules)
├── .editorconfig                         # Editor/IDE formatting rules
├── .github/                              # GitHub Actions workflows
│   └── workflows/
├── core/                                 # Core code generation library
│   ├── build.gradle.kts                  # Core module build config
│   ├── src/main/kotlin/com/avsystem/justworks/core/
│   │   ├── Generator.kt                  # Placeholder entry point (Phase 1)
│   │   ├── model/                        # Domain model (immutable data structures)
│   │   │   ├── ApiSpec.kt                # Main spec + nested types (Endpoint, Parameter, Response, etc.)
│   │   │   └── TypeRef.kt                # Sealed type system (Primitive, Array, Map, Reference, Inline)
│   │   ├── parser/                       # OpenAPI parsing → ApiSpec
│   │   │   ├── SpecParser.kt             # Main parser (Swagger Parser v3 orchestrator)
│   │   │   ├── SpecValidator.kt          # Validation rules
│   │   │   └── ParseResult.kt            # Sealed result type (Success | Failure)
│   │   └── gen/                          # Code generators (model, client, serializers)
│   │       ├── ModelGenerator.kt         # Data class/sealed interface generation
│   │       ├── ClientGenerator.kt        # API client class generation (by tag)
│   │       ├── SerializersModuleGenerator.kt  # kotlinx.serialization module
│   │       ├── ApiResponseGenerator.kt   # Response wrapper types
│   │       ├── InlineSchemaDeduplicator.kt    # Deduplication of structurally identical types
│   │       ├── TypeMapping.kt            # TypeRef → KotlinPoet TypeName conversion
│   │       ├── NameUtils.kt              # Naming transformations (camelCase, PascalCase, UPPER_SNAKE_CASE)
│   │       └── Names.kt                  # Generated name value objects
│   └── src/test/kotlin/com/avsystem/justworks/core/
│       ├── gen/                          # Generator unit tests
│       │   ├── ModelGeneratorTest.kt
│       │   ├── ClientGeneratorTest.kt
│       │   ├── ApiResponseGeneratorTest.kt
│       │   ├── SerializersModuleGeneratorTest.kt
│       │   ├── TypeMappingTest.kt
│       │   ├── NameUtilsTest.kt
│       │   ├── ModelGeneratorPolymorphicTest.kt
│       │   └── InlineSchemaDedupTest.kt
│       └── parser/                       # Parser unit tests
│           ├── SpecParserTest.kt
│           ├── SpecValidatorTest.kt
│           ├── SpecParserPolymorphicTest.kt
│           └── resources/                # Test OpenAPI spec fixtures (YAML files)
│
└── plugin/                               # Gradle plugin module
    ├── build.gradle.kts                  # Plugin module build config
    ├── src/main/kotlin/com/avsystem/justworks/gradle/
    │   ├── JustworksPlugin.kt            # Plugin entry point (applies DSL, registers tasks)
    │   ├── JustworksGenerateTask.kt      # Task: orchestrates parsing and generation
    │   ├── JustworksSharedTypesTask.kt   # Task: generates common response types
    │   ├── JustworksExtension.kt         # DSL: justworks { specs { ... } }
    │   └── JustworksSpecConfiguration.kt # Per-spec config object
    └── src/functionalTest/kotlin/com/avsystem/justworks/gradle/
        └── (functional test directory for GradleTestKit tests)
```

## Directory Purposes

**core/ (Code Generation Library):**
- Purpose: Self-contained library that parses OpenAPI specs and generates Kotlin code
- Contains: Model definitions, parser, code generators, naming utilities
- Key files: `SpecParser.kt`, `ModelGenerator.kt`, `ClientGenerator.kt`, `ApiSpec.kt`
- Independence: Zero dependencies on Gradle; pure Kotlin library

**core/src/main/kotlin/com/avsystem/justworks/core/model/:**
- Purpose: Domain model representing the normalized OpenAPI spec in memory
- Contains: Data classes for `ApiSpec`, `Endpoint`, `Parameter`, `RequestBody`, `Response`, `SchemaModel`, `PropertyModel`, `EnumModel`, `Discriminator`, and the sealed `TypeRef` type system
- Key files: `ApiSpec.kt`, `TypeRef.kt`

**core/src/main/kotlin/com/avsystem/justworks/core/parser/:**
- Purpose: Parse and validate OpenAPI 3.0 YAML/JSON files
- Contains: `SpecParser` (Swagger Parser v3 wrapper + transformation), `SpecValidator` (validation rules), `ParseResult` (result type)
- Key files: `SpecParser.kt`, `ParseResult.kt`

**core/src/main/kotlin/com/avsystem/justworks/core/gen/:**
- Purpose: Generate Kotlin source code from the normalized model
- Contains: `ModelGenerator`, `ClientGenerator`, `SerializersModuleGenerator`, `TypeMapping`, `NameUtils`, deduplication logic
- Key files: `ModelGenerator.kt`, `ClientGenerator.kt`, `TypeMapping.kt`, `NameUtils.kt`

**plugin/ (Gradle Plugin):**
- Purpose: Gradle integration; provides DSL and task wiring
- Contains: Plugin class, task definitions, DSL extension, spec configuration
- Key files: `JustworksPlugin.kt`, `JustworksGenerateTask.kt`, `JustworksExtension.kt`

**plugin/src/main/kotlin/com/avsystem/justworks/gradle/:**
- Purpose: Gradle plugin implementation and tasks
- Contains: `JustworksPlugin` (entry point), `JustworksGenerateTask` (per-spec generation), `JustworksSharedTypesTask` (shared types), DSL classes
- Key files: `JustworksPlugin.kt`, `JustworksGenerateTask.kt`

## Key File Locations

**Entry Points:**
- `plugin/src/main/kotlin/com/avsystem/justworks/gradle/JustworksPlugin.kt`: Gradle plugin entry point (invoked when user applies plugin)
- `core/src/main/kotlin/com/avsystem/justworks/core/parser/SpecParser.kt`: Parsing entry point (invoked by `JustworksGenerateTask`)

**Configuration:**
- `plugin/src/main/kotlin/com/avsystem/justworks/gradle/JustworksExtension.kt`: DSL extension (`justworks { specs { ... } }`)
- `plugin/src/main/kotlin/com/avsystem/justworks/gradle/JustworksSpecConfiguration.kt`: Per-spec config (specFile, packageName, etc.)
- `build.gradle.kts` (root): Root Gradle config (plugin versions, repositories, allprojects settings)
- `core/build.gradle.kts`: Core module dependencies (Swagger Parser, KotlinPoet, kotlinx-datetime)
- `plugin/build.gradle.kts`: Plugin module dependencies (core, gradleTestKit)

**Core Logic:**
- `core/src/main/kotlin/com/avsystem/justworks/core/parser/SpecParser.kt`: Parses OpenAPI spec file, normalizes to `ApiSpec`
- `core/src/main/kotlin/com/avsystem/justworks/core/gen/ModelGenerator.kt`: Generates data classes, sealed interfaces, enums
- `core/src/main/kotlin/com/avsystem/justworks/core/gen/ClientGenerator.kt`: Generates API client suspend functions (grouped by tag)
- `core/src/main/kotlin/com/avsystem/justworks/core/gen/TypeMapping.kt`: Converts `TypeRef` to KotlinPoet `TypeName`
- `core/src/main/kotlin/com/avsystem/justworks/core/gen/NameUtils.kt`: Naming transformations (camelCase, PascalCase, UPPER_SNAKE_CASE, etc.)
- `core/src/main/kotlin/com/avsystem/justworks/core/model/ApiSpec.kt`: Main data model definition
- `core/src/main/kotlin/com/avsystem/justworks/core/model/TypeRef.kt`: Type system definition

**Testing:**
- `core/src/test/kotlin/com/avsystem/justworks/core/parser/SpecParserTest.kt`: Parser tests
- `core/src/test/kotlin/com/avsystem/justworks/core/gen/ModelGeneratorTest.kt`: Model generator tests
- `core/src/test/kotlin/com/avsystem/justworks/core/gen/ClientGeneratorTest.kt`: Client generator tests
- `core/src/test/resources/`: OpenAPI spec fixtures (petstore.yaml, etc.)
- `plugin/src/functionalTest/`: Gradle plugin functional tests (GradleTestKit)

## Naming Conventions

**Files:**
- Kotlin source files: PascalCase matching main class/interface name (e.g., `ModelGenerator.kt` contains `class ModelGenerator`)
- Test files: Same name as source + `Test` suffix (e.g., `ModelGeneratorTest.kt`)
- Test fixtures: lowercase with hyphens or underscores (e.g., `petstore.yaml`)

**Directories:**
- Package directories: lowercase with dots separating segments (e.g., `com/avsystem/justworks/core/gen/`)
- Functional grouping: `parser/`, `gen/`, `model/` group related classes by responsibility

**Classes:**
- Generators: `<Domain>Generator` (e.g., `ModelGenerator`, `ClientGenerator`)
- Tasks: `Justworks<Action>Task` (e.g., `JustworksGenerateTask`, `JustworksSharedTypesTask`)
- Models: Named after domain concepts (e.g., `ApiSpec`, `Endpoint`, `Parameter`)
- Sealed interfaces: Descriptive names (e.g., `TypeRef`, `ParseResult`)

**Functions:**
- Naming transformations: `to<Format>` (e.g., `toCamelCase()`, `toPascalCase()`, `toEnumConstantName()`)
- Conversion functions: `<Source>To<Target>` or as extension (e.g., `TypeMapping.toTypeName()`)
- Utility functions: Action verbs (e.g., `operationNameFromPath()`)

**Variables & Properties:**
- camelCase (e.g., `specFile`, `packageName`, `modelPackage`)
- Mutable collections: Explicit (`mutableListOf`, `mutableMapOf`, `mutableSetOf`)
- Properties: Short, clear names reflecting their content (e.g., `sealedHierarchies`, `variantParents`)

## Where to Add New Code

**New Feature (e.g., Add Support for New OpenAPI Construct):**
- Model representation: Add field or new data class in `core/src/main/kotlin/com/avsystem/justworks/core/model/ApiSpec.kt` or extend `TypeRef`
- Parsing logic: Add extraction in `core/src/main/kotlin/com/avsystem/justworks/core/parser/SpecParser.kt` (in `transformToModel()`)
- Generation logic: Add handling in `core/src/main/kotlin/com/avsystem/justworks/core/gen/ModelGenerator.kt` or `ClientGenerator.kt`
- Validation: Add check in `core/src/main/kotlin/com/avsystem/justworks/core/parser/SpecValidator.kt`
- Test fixture: Add OpenAPI YAML to `core/src/test/resources/` and test in `core/src/test/kotlin/com/avsystem/justworks/core/parser/SpecParserTest.kt`

**New Generator (e.g., OpenAPI Server Stub Generator):**
- Create new class in `core/src/main/kotlin/com/avsystem/justworks/core/gen/NewGenerator.kt` implementing the generation logic
- Wire into `JustworksGenerateTask.generate()` (call new generator and write outputs)
- Ensure generator depends only on `ApiSpec` model (not parser internals)
- Add tests in `core/src/test/kotlin/com/avsystem/justworks/core/gen/NewGeneratorTest.kt`

**New Gradle Task:**
- Create class extending `DefaultTask` in `plugin/src/main/kotlin/com/avsystem/justworks/gradle/JustworksNewTask.kt`
- Register in `JustworksPlugin.apply()` using `project.tasks.register()`
- Wire dependencies and outputs appropriately
- Integrate with source sets if generating code
- Test via `plugin/src/functionalTest/` using `GradleTestKit`

**Utilities/Helpers:**
- Shared naming logic: Add functions to `core/src/main/kotlin/com/avsystem/justworks/core/gen/NameUtils.kt`
- Type conversion: Add mappings to `core/src/main/kotlin/com/avsystem/justworks/core/gen/TypeMapping.kt`
- Validation rules: Add checks in `core/src/main/kotlin/com/avsystem/justworks/core/parser/SpecValidator.kt`

## Special Directories

**build/ (Generated by Gradle):**
- Purpose: Build outputs and intermediates
- Generated: Yes
- Committed: No (in .gitignore)
- Contains: Compiled bytecode, JAR artifacts, generated Kotlin code for testing

**build/generated/justworks/ (Plugin-Generated Code):**
- Purpose: Output directory for generated Kotlin client code
- Generated: Yes (by `JustworksGenerateTask`)
- Committed: No (in .gitignore)
- Structure:
  - `build/generated/justworks/shared/kotlin/`: Shared types (HttpError, Success) from `JustworksSharedTypesTask`
  - `build/generated/justworks/<specName>/`: Per-spec generated models and clients

**.gradle/ (Gradle Cache):**
- Purpose: Gradle daemon cache, dependency artifacts, build cache
- Generated: Yes
- Committed: No (in .gitignore)

**buildSrc/ (Build Logic):**
- Purpose: Shared Gradle build logic and conventions
- Generated: No (source)
- Committed: Yes
- Contains: Version constants, custom plugins, common configurations

---

*Structure analysis: 2026-03-11*
