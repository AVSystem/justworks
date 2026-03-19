# Architecture

**Analysis Date:** 2026-03-19

## Pattern Overview

**Overall:** Three-stage pipeline code generator with sealed model intermediary.

**Key Characteristics:**
- **Input:** OpenAPI 3.0 / Swagger 2.0 specification files (YAML or JSON)
- **Intermediate:** Language-agnostic `ApiSpec` domain model
- **Output:** Ktor HTTP client code + kotlinx.serialization models via KotlinPoet
- **Error handling:** Arrow Either/Raise for error accumulation and type-safe failures
- **Code generation:** Direct AST construction with KotlinPoet; no string templates

## Layers

**Parser Layer:**
- **Purpose:** Convert Swagger/OpenAPI specs to intermediate `ApiSpec` model
- **Location:** `core/src/main/kotlin/com/avsystem/justworks/core/parser/`
- **Contains:** Spec parsing, validation, schema extraction
- **Depends on:** Swagger Parser v3, io.swagger models
- **Used by:** Generator layer

**Model Layer:**
- **Purpose:** Define intermediate domain model + type reference system
- **Location:** `core/src/main/kotlin/com/avsystem/justworks/core/model/`
- **Contains:** `ApiSpec`, `Endpoint`, `SchemaModel`, `EnumModel`, `TypeRef` sealed tree
- **Depends on:** Kotlin stdlib only
- **Used by:** Parser layer (produces), Generator layer (consumes)

**Generator Layer:**
- **Purpose:** Transform `ApiSpec` into Kotlin source code via KotlinPoet
- **Location:** `core/src/main/kotlin/com/avsystem/justworks/core/gen/`
- **Contains:** Multiple generator classes, type mapping, code utilities
- **Depends on:** Model layer, KotlinPoet, kotlinx.serialization, Ktor types
- **Used by:** External tools/plugins (not self-contained)

**No orchestrator layer exists** — callers must invoke:
1. `SpecParser.parse(specFile)` → `ParseResult`
2. `ClientGenerator.generate(apiSpec)` → `List<FileSpec>`
3. `ModelGenerator.generate(apiSpec)` → `List<FileSpec>`
4. Optionally: `ApiClientBaseGenerator.generate()`, `ApiResponseGenerator`

## Data Flow

**Parsing Flow:**

1. `SpecParser.parse(specFile: File)` reads YAML/JSON
2. Swagger Parser v3 converts to `OpenAPI` model; resolves refs fully
3. `SpecValidator.validate(openApi)` collects errors/warnings without short-circuiting
4. Extract endpoints: map paths + operations → `Endpoint` list
5. Extract schemas: enumerate `components.schemas` → `SchemaModel` or `EnumModel`
6. Handle allOf composition, oneOf/anyOf polymorphism, inline schema nesting
7. Produce `ParseResult.Success(ApiSpec)` or `ParseResult.Failure(errors, warnings)`

**Code Generation Flow:**

1. `ClientGenerator.generate(spec)` per endpoint tag:
   - Groups endpoints by `tags.firstOrNull()`
   - Generates one client class per tag group
   - Each endpoint becomes a suspend function with context receiver `Raise<HttpError>`

2. `ModelGenerator.generate(spec)` processes schemas:
   - Builds sealed hierarchy info for oneOf/anyOf polymorphism
   - Generates data classes from schemas + inline schemas collected from endpoints
   - For polymorphism without discriminator: creates `JsonContentPolymorphicSerializer`
   - For oneOf wrapper pattern: unwraps single-property variants
   - Generates enum classes with @SerialName mapping
   - Optionally: `SerializersModuleGenerator` registers polymorphic subtypes

3. `ApiClientBaseGenerator.generate()` one-shot:
   - Produces utilities: `encodeParam<T>()`, response mapping extensions
   - Generates `ApiClientBase` abstract class with Ktor HttpClient setup

4. `ApiResponseGenerator.generateHttpError()`, `generateHttpSuccess()`:
   - Produces error/success wrapper types used in client functions

**Output:** Each generator returns `List<FileSpec>` (KotlinPoet file ASTs). Caller is responsible for writing to disk.

**State Management:**
- **Parser:** Uses Arrow context receivers for scoped state (component schema identity, schema registry). Stateless between calls.
- **Generator:** Uses Arrow context receivers for hierarchy info. Stateless; dependencies passed through function contexts.
- **No global state:** Thread-safe; can parse/generate multiple specs concurrently.

## Key Abstractions

**ApiSpec:**
- Purpose: Immutable, language-neutral representation of API structure
- Bridges Swagger Parser raw model and generators
- No knowledge of Kotlin or code generation
- File: `core/src/main/kotlin/com/avsystem/justworks/core/model/ApiSpec.kt`

**TypeRef (Sealed Tree):**
- Purpose: Represent all possible types in OpenAPI specs with Kotlin context
- Variants: `Primitive`, `Array`, `Reference`, `Map`, `Inline`, `Unknown`
- `Inline` captures anonymous object schemas with context hint (e.g., "CreatePetRequest")
- Enables uniform type mapping: `TypeMapping.toTypeName(typeRef, modelPackage)` → `TypeName`
- File: `core/src/main/kotlin/com/avsystem/justworks/core/model/TypeRef.kt`

**ParseResult (Sealed Sum):**
- Purpose: Encode both success and failure paths; carry warnings in both
- `Success(apiSpec, warnings)` — spec is valid, proceed to generation
- `Failure(errors, warnings)` — spec has fatal issues, abort generation
- File: `core/src/main/kotlin/com/avsystem/justworks/core/parser/SpecParser.kt`

**HierarchyInfo:**
- Purpose: Cache polymorphic schema relationships during model generation
- Precomputed in `ModelGenerator.buildHierarchyInfo(schemas)` once, passed via context
- Maps: sealed interfaces to variant names, variants to parent + @SerialName
- Tracks anyOf-without-discriminator for custom serializer generation
- File: `core/src/main/kotlin/com/avsystem/justworks/core/gen/ModelGenerator.kt` (lines 51-89)

**InlineSchemaDeduplicator:**
- Purpose: Prevent duplicate data classes for structurally identical inline schemas
- Maintains a counter: if same shape appears with different context hints, reuse generated name
- File: `core/src/main/kotlin/com/avsystem/justworks/core/gen/InlineSchemaDeduplicator.kt`

## Entry Points

**SpecParser.parse(specFile: File) → ParseResult:**
- Location: `core/src/main/kotlin/com/avsystem/justworks/core/parser/SpecParser.kt:68`
- Triggers: Manual invocation by tools/plugins
- Responsibilities:
  - Read YAML/JSON from disk
  - Call Swagger Parser; resolve refs fully
  - Validate; extract endpoints/schemas
  - Return success or failures with diagnostics

**ClientGenerator.generate(spec: ApiSpec, hasPolymorphicTypes: Boolean) → List<FileSpec>:**
- Location: `core/src/main/kotlin/com/avsystem/justworks/core/gen/ClientGenerator.kt:35`
- Triggers: After `SpecParser.parse()` succeeds
- Responsibilities:
  - Group endpoints by tag
  - Generate suspend functions per endpoint
  - Build HttpClient initialization (with/without SerializersModule)
  - Return file specs ready for KotlinPoet.writeTo()

**ModelGenerator.generate(spec: ApiSpec) → List<FileSpec>:**
- Location: `core/src/main/kotlin/com/avsystem/justworks/core/gen/ModelGenerator.kt:34`
- Triggers: After `SpecParser.parse()` succeeds
- Responsibilities:
  - Extract all schemas (components + inline collected from endpoints)
  - Deduplicate inline schema shapes
  - Generate data/sealed/enum classes
  - Handle polymorphic serialization
  - Return file specs for models + SerializersModule (if needed)

## Error Handling

**Strategy:** Arrow Either + Raise pattern; error accumulation where possible.

**Patterns:**

1. **SpecParser:** Uses `either { }` block with `Raise<ParseResult.Failure>` context.
   - Validations use `ensure()` and `ensureNotNull()` for short-circuit errors
   - `SpecValidator.validate()` uses `accumulate { }` to gather all issues (non-fatal warnings + fatal errors) without stopping at first error
   - `ParseResult.Failure` carries full error list + warnings

2. **Generators:** No explicit error handling — assume valid `ApiSpec`.
   - KotlinPoet exceptions propagate (type mismatch, illegal names, etc.)
   - Exception: `ModelGenerator.formatDefaultValue()` explicitly throws on unsupported types
   - No error accumulation; first exception wins

3. **Custom Serializers:** `ModelGenerator.buildSelectDeserializerBody()` for anyOf without discriminator:
   - Inserts `TODO()` for variants with no unique discriminating fields
   - Falls back to `SerializationException` if no variant matches

## Cross-Cutting Concerns

**Logging:** None. No logging framework integrated. Errors flow via `ParseResult` or exceptions.

**Validation:**
- **Parser phase:** `SpecValidator` checks for required fields (info section), unsupported constructs (callbacks, links)
- **Generator phase:** Implicit during code generation; KotlinPoet catches invalid identifiers, illegal modifiers

**Authentication:**
- **Generated client:** `ApiClientBase.applyAuth()` injects Bearer token into Authorization header
- **Token supplier:** Passed as lambda `() -> String` to client constructor (caller provides implementation)

**Naming Conventions:**
- **PascalCase:** Schema names, class names, enum variants (via `String.toPascalCase()`)
- **camelCase:** Function names, property names, parameters (via `String.toCamelCase()`)
- **UPPER_SNAKE_CASE:** Enum constant names (via `String.toEnumConstantName()`)
- **Nested inline:** Dots replaced with underscores (e.g., "Pet.Address" → "Pet_Address")

**Polymorphism Handling:**
- **With discriminator:** `@JsonClassDiscriminator("discriminatorField")` on sealed interface
- **oneOf unwrapping:** Detects and unwraps wrapper pattern (single-property objects), creates synthetic discriminator
- **anyOf without discriminator:** Custom `JsonContentPolymorphicSerializer` with field-presence heuristics

---

*Architecture analysis: 2026-03-19*
