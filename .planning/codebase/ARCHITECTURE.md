# Architecture

**Analysis Date:** 2026-03-11

## Pattern Overview

**Overall:** Three-layer code generation pipeline with Gradle plugin integration

**Key Characteristics:**
- **Separation of concerns:** Parsing (OpenAPI → model), generation (model → Kotlin code), plugin integration (Gradle build hooks)
- **Pluggable generators:** Independent `ModelGenerator`, `ClientGenerator`, `SerializersModuleGenerator` can generate incrementally
- **Type-driven design:** Type system models (`TypeRef` sealed interface) abstract over OpenAPI schemas and enable reusable code generation
- **Multi-spec support:** Single Gradle project can configure multiple OpenAPI specs with independent tasks and outputs

## Layers

**Parser Layer:**
- Purpose: Convert OpenAPI 3.0 YAML/JSON to a validated, normalized internal model (`ApiSpec`)
- Location: `core/src/main/kotlin/com/avsystem/justworks/core/parser/`
- Contains: `SpecParser` (orchestrator), `SpecValidator` (validation rules), `ParseResult` (sealed result type)
- Depends on: Swagger Parser v3 (OpenAPIParser), core model types
- Used by: `JustworksGenerateTask` in the Gradle plugin layer

**Model Layer:**
- Purpose: Define the domain model that represents an OpenAPI spec in memory and the type system for code generation
- Location: `core/src/main/kotlin/com/avsystem/justworks/core/model/`
- Contains: `ApiSpec`, `Endpoint`, `Parameter`, `RequestBody`, `Response`, `SchemaModel`, `EnumModel`, `TypeRef` (sealed type system), `PropertyModel`, `Discriminator`
- Depends on: Nothing (pure data structures)
- Used by: Parser layer (populates), all generators (consume)

**Generation Layer:**
- Purpose: Transform the model to KotlinPoet code specifications and write files
- Location: `core/src/main/kotlin/com/avsystem/justworks/core/gen/`
- Contains: `ModelGenerator` (data classes/sealed interfaces), `ClientGenerator` (API client suspend functions), `SerializersModuleGenerator` (kotlinx.serialization setup), `TypeMapping` (type conversion), `NameUtils` (naming conventions), `InlineSchemaDeduplicator` (deduplication logic)
- Depends on: KotlinPoet 2.2.0, core model, naming utilities
- Used by: `JustworksGenerateTask` in the Gradle plugin layer

**Gradle Plugin Layer:**
- Purpose: Integrate code generation into Gradle build lifecycle, manage multi-spec configuration and task wiring
- Location: `plugin/src/main/kotlin/com/avsystem/justworks/gradle/`
- Contains: `JustworksPlugin` (entry point, task registration, source set wiring), `JustworksGenerateTask` (orchestrates parser → generators → file write), `JustworksExtension` (DSL), `JustworksSpecConfiguration` (per-spec config), `JustworksSharedTypesTask` (generates common response types)
- Depends on: Core layer, Gradle API, Java plugin extension
- Used by: User's `build.gradle.kts` via `justworks { }` DSL

## Data Flow

**Code Generation Flow:**

1. User runs `./gradlew justworksGenerate<SpecName>` or `compileKotlin` (which depends on `justworksGenerateAll`)
2. `JustworksPlugin.apply()` registers:
   - `justworksSharedTypes` task (generates `HttpError`, `Success` response types)
   - `justworksGenerate<SpecName>` task for each configured spec
   - `justworksGenerateAll` aggregate task
   - Wires output directories into main sourceSet
   - Hooks `compileKotlin` to depend on `justworksGenerateAll`
3. `JustworksGenerateTask.generate()` executes for each spec:
   - Reads OpenAPI spec file from disk
   - Calls `SpecParser.parse(specFile)` → `ParseResult.Success` or `ParseResult.Failure`
   - On success, passes `ApiSpec` to `ModelGenerator.generate(spec)`
   - `ModelGenerator` returns `List<FileSpec>` for models, enums, inline deduplicated types
   - Checks for polymorphic types (sealed hierarchies) in model generator state
   - Passes same `ApiSpec` to `ClientGenerator.generate(spec, hasPolymorphicTypes)`
   - `ClientGenerator` returns `List<FileSpec>` grouped by API tag
   - Both generators call `.writeTo(outputDir)` to persist files
   - Task logs "Generated X model files, Y client files"
4. Generated files are in `build/generated/justworks/<specName>/` and `build/generated/justworks/shared/`
5. Gradle source sets include these directories, files are compiled with main code

**Type Transformation Flow:**

1. OpenAPI spec → Swagger Parser (resolves $refs, resolves schemas)
2. `SpecParser.transformToModel()` walks paths and components:
   - Extracts `Endpoint` from each path item and operation
   - Extracts `SchemaModel` and `EnumModel` from components/schemas
   - Converts each OpenAPI schema to a `TypeRef`: Primitive, Array, Map, Reference, or Inline
3. `TypeMapping.toTypeName(typeRef, modelPackage)` maps TypeRef to KotlinPoet TypeName:
   - Primitive → Kotlin built-in types (String, Int, Long, Boolean, etc.)
   - Reference → ClassName in modelPackage
   - Array → List<T>
   - Map → Map<String, V>
   - Inline → Generated class name (deduplicated)

**State Management:**

- **Parser state:** `SpecParser` maintains `componentSchemaIdentity: IdentityHashMap<Schema<*>, String>` to track which resolved Schema objects correspond to component schema names (used to detect inline vs. referenced schemas after full resolution)
- **Model generator state:** `ModelGenerator` maintains `sealedHierarchies`, `variantParents`, `anyOfWithoutDiscriminator` across a single `generate()` call to coordinate polymorphic type handling
- **Deduplicator state:** `InlineSchemaDeduplicator` tracks `componentSchemas` to prevent duplicate generation of structurally identical inline types

## Key Abstractions

**TypeRef (Sealed Interface):**
- Purpose: Represents all possible type references in the domain model, abstracting OpenAPI schema types
- Examples: `TypeRef.Primitive(STRING)`, `TypeRef.Array(TypeRef.Reference("Pet"))`, `TypeRef.Inline(...)`
- Pattern: Sealed interface enables exhaustive pattern matching in code generation

**ParseResult (Sealed Interface):**
- Purpose: Represents the outcome of parsing an OpenAPI spec (success with warnings or failure with errors)
- Examples: `ParseResult.Success(spec, warnings)`, `ParseResult.Failure(errors)`
- Pattern: Enforces explicit handling of both success and error cases

**ApiSpec (Data Class):**
- Purpose: Normalized, validated OpenAPI spec representation
- Contains: `title`, `version`, `endpoints: List<Endpoint>`, `schemas: List<SchemaModel>`, `enums: List<EnumModel>`
- Pattern: Immutable data holder, passed to all generators

**Generator Classes:**
- `ModelGenerator`: Transforms `ApiSpec` → `List<FileSpec>` for data models and enums
- `ClientGenerator`: Transforms `ApiSpec` → `List<FileSpec>` for API clients (grouped by tag)
- `SerializersModuleGenerator`: Generates `kotlinx.serialization` module configuration for polymorphic types

## Entry Points

**Gradle Plugin Entry Point:**
- Location: `plugin/src/main/kotlin/com/avsystem/justworks/gradle/JustworksPlugin.kt`
- Triggers: User applies plugin in `build.gradle.kts`: `id("com.avsystem.justworks")`
- Responsibilities:
  - Registers `JustworksExtension` DSL (`justworks { specs { ... } }`)
  - Dynamically creates `JustworksGenerateTask` for each configured spec
  - Registers `JustworksSharedTypesTask` (one-time, shared output)
  - Wires generated sources into main source set (lazy, after Kotlin plugin applied)
  - Makes `compileKotlin` depend on code generation

**Per-Spec Generation Entry Point:**
- Location: `plugin/src/main/kotlin/com/avsystem/justworks/gradle/JustworksGenerateTask.kt`
- Triggers: `./gradlew justworksGenerate<Name>` or `compileKotlin`
- Responsibilities:
  - Parses OpenAPI spec file
  - Runs model and client generators
  - Writes output files to `build/generated/justworks/<specName>/`
  - Reports generation results to logger

**Core Parser Entry Point:**
- Location: `core/src/main/kotlin/com/avsystem/justworks/core/parser/SpecParser.kt`
- Triggers: Called by `JustworksGenerateTask.generate()`
- Responsibilities:
  - Loads and parses OpenAPI file via Swagger Parser v3
  - Validates spec structure via `SpecValidator`
  - Transforms OpenAPI structure to `ApiSpec`
  - Returns `ParseResult` (sealed)

## Error Handling

**Strategy:** Layered error propagation with type safety

**Patterns:**
- **Parser validation:** `SpecValidator.validate(openApi)` returns `List<String>` errors; if non-empty, parsing fails
- **Parse errors:** `SpecParser.parse()` returns `ParseResult.Failure(errors)` with list of all issues
- **Task failures:** `JustworksGenerateTask` catches `ParseResult.Failure` and throws `GradleException` (stops build with readable message)
- **Schema validation:** `SpecParser` catches `IllegalArgumentException` from schema extraction and converts to `ParseResult.Failure`
- **Configuration validation:** `JustworksPlugin.afterEvaluate()` validates required properties (`specFile`, `packageName`) and throws `GradleException` with spec name context

## Cross-Cutting Concerns

**Logging:** Gradle `logger.lifecycle()` in `JustworksGenerateTask` reports generation counts and outcome; Parser layer silent unless errors

**Validation:**
- Spec-level: `SpecValidator` checks OpenAPI structure
- Task-level: Gradle validates required inputs (specFile, packageName)
- Runtime: Schema extraction raises `IllegalArgumentException` for invalid structures

**Naming Conventions:**
- `NameUtils` centralizes transformation: `toCamelCase()`, `toPascalCase()`, `toEnumConstantName()`, `toKotlinIdentifier()`, `operationNameFromPath()`
- API client class names: `<Tag>Api` (PascalCase tag)
- Enum constant names: `UPPER_SNAKE_CASE` with digit prefix handling
- Operation names: `<Method><Path>` (e.g., `GetPetsById`)
- Model property names: camelCase, escaped if Kotlin keyword

---

*Architecture analysis: 2026-03-11*
