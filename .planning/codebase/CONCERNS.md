# Codebase Concerns

**Analysis Date:** 2026-03-19

## Tech Debt

**ktlint gradle plugin version mismatch:**
- Issue: Comment at `core/build.gradle.kts:13` indicates workaround for unresolved issue in ktlint-gradle plugin v12.1.2
- Files: `core/build.gradle.kts`
- Impact: Version pinning (1.8.0) may become stale; blocks upgrade path for linting infrastructure
- Fix approach: Monitor https://github.com/JLLeitschuh/ktlint-gradle/issues/912 and remove workaround when resolved

**Missing cookie parameter support:**
- Issue: ParameterLocation enum at `core/src/main/kotlin/com/avsystem/justworks/core/model/ApiSpec.kt:49` explicitly documents "// todo: add cookie"
- Files: `core/src/main/kotlin/com/avsystem/justworks/core/model/ApiSpec.kt`
- Impact: HTTP cookie parameters in OpenAPI specs cannot be parsed; endpoints using cookie-based auth will be incomplete
- Fix approach: Add `COOKIE` variant to `ParameterLocation` enum and update parser to handle cookie location in `SwaggerParameter.toParameter()` at `core/src/main/kotlin/com/avsystem/justworks/core/parser/SpecParser.kt:182`

## Known Bugs

**Incomplete anyOf discriminator handling - generates TODO() at runtime:**
- Symptoms: Generated deserialization code contains `TODO()` statement that throws at runtime when variants lack unique discriminating fields
- Files: `core/src/main/kotlin/com/avsystem/justworks/core/gen/ModelGenerator.kt:271` (in `buildSelectDeserializerBody`)
- Trigger: anyOf schema without explicit discriminator where not all variants have a unique field that distinguishes them
- Current behavior: Generates comment "// No unique discriminating fields found for variant '$variantName'" and emits:
  ```kotlin
  else -> TODO("Cannot discriminate variants [...] of anyOf 'parentName' - manual selectDeserializer required")
  ```
- Workaround: Add explicit discriminator to anyOf schema in OpenAPI spec
- Root cause: The field-presence heuristic at lines 254-267 only detects variants with unique fields; when multiple variants share all fields, no discriminator can be auto-generated
- Fix approach: Either (a) require explicit discriminator in these cases and fail at parse-time with clear error, or (b) implement more sophisticated detection strategy (e.g., field value patterns, nested object structure), or (c) generate boilerplate code for user to implement custom discriminator logic

**Default value parsing fails silently on invalid ISO-8601 dates:**
- Symptoms: If OpenAPI schema contains invalid ISO-8601 date or date-time default, `ModelGenerator.formatDefaultValue()` throws `IllegalArgumentException` during code generation
- Files: `core/src/main/kotlin/com/avsystem/justworks/core/gen/ModelGenerator.kt:363-381`
- Trigger: Property with `PrimitiveType.DATE` or `PrimitiveType.DATE_TIME` and invalid default value (e.g., "not-a-date")
- Current behavior: Code generation fails with exception; error message is detailed but appears during build
- Workaround: Ensure OpenAPI spec contains only valid RFC-3339 dates/times for default values
- Root cause: Uses Arrow `catch()` to validate parsing at generation time; exception is raised but may not be caught gracefully by build system
- Fix approach: Validate defaults during spec parsing stage in `SpecParser` rather than during code generation to fail faster with context; alternatively, wrap in Result type and report validation errors consistently

## Security Considerations

**Deserialization of arbitrary JSON to typed models:**
- Risk: Generated client accepts JSON responses from server and deserializes directly to Kotlin data classes; if server is compromised, malicious JSON could cause issues
- Files: `core/src/main/kotlin/com/avsystem/justworks/core/gen/ApiClientBaseGenerator.kt` (deserialization context), `core/src/main/kotlin/com/avsystem/justworks/core/gen/ClientGenerator.kt` (JSON reading)
- Current mitigation: kotlinx.serialization uses type-safe mapping; Arrow's error handling prevents stack overflows; no unbounded collections parsed
- Recommendations: Document that generated clients assume trusted servers; consider adding strict deserialization policies (e.g., max nesting depth, max string length) if consuming untrusted APIs; validate response schema matches expectations

**Default serializer module registration for polymorphic types:**
- Risk: Custom `generatedSerializersModule` is optional; if not provided to HttpClient, polymorphic deserialization silently falls back to default behavior or fails
- Files: `core/src/main/kotlin/com/avsystem/justworks/core/gen/ClientGenerator.kt:47-52` (conditional module registration), `core/src/main/kotlin/com/avsystem/justworks/core/gen/SerializersModuleGenerator.kt`
- Current mitigation: Code checks `hasPolymorphicTypes` flag to conditionally pass module
- Recommendations: Make SerializersModule registration mandatory when polymorphic types exist; fail at generation time if polymorphic schema exists but module would not be registered; add clear documentation/runtime checks

## Performance Bottlenecks

**Inline schema deduplication uses full set operations on every schema generation:**
- Problem: `InlineSchemaDeduplicator.getOrGenerateName()` at `core/src/main/kotlin/com/avsystem/justworks/core/gen/InlineSchemaDeduplicator.kt:37-49` constructs full set of existing names and generates candidates sequentially until finding unoccupied name
- Files: `core/src/main/kotlin/com/avsystem/justworks/core/gen/InlineSchemaDeduplicator.kt`
- Current behavior: O(n) set construction per inline schema; linear search through candidates
- Cause: No caching of candidate availability; naive implementation sufficient for typical specs but quadratic in worst case
- Scaling issue: Specs with 1000+ inline schemas could generate excessive allocations
- Improvement path: Cache non-colliding candidate names; use AtomicInteger to track numeric suffix instead of generating infinite sequence; or pre-allocate all names upfront in pass 1

**collectInlineTypeRefs uses ArrayDeque with repeated filtering and iteration:**
- Problem: `ModelGenerator.collectInlineTypeRefs()` at line 435 processes all TypeRef nodes with manual queue management; repeated `.filterNotNull()` and `.map()` calls
- Files: `core/src/main/kotlin/com/avsystem/justworks/core/gen/ModelGenerator.kt:435-457`
- Current behavior: O(n) time with linear space for queue and visited set
- Cause: Iterative approach is correct but could be streamlined; filterNotNull creates intermediate list
- Impact: Not critical for typical specs; becomes visible only with deeply nested 100+ level schema hierarchies
- Improvement path: Eliminate filterNotNull by using nullable queue; use visitor pattern instead of manual queue

**ModelGenerator context parameter passing creates new scope for every generator call:**
- Problem: Context parameters in Kotlin (via `context(...)` syntax) create implicit parameter passing; used extensively in ModelGenerator, could add overhead if compiler inlines poorly
- Files: `core/src/main/kotlin/com/avsystem/justworks/core/gen/ModelGenerator.kt` (lines 34, 91, 119, 143, 289, 459)
- Current behavior: Context parameters allow clean parameter threading without boilerplate
- Cause: Kotlin compiler optimization not transparent to developer; context parameters are syntactic sugar
- Impact: Negligible for code generation (CPU-bound on I/O, not parameter passing)
- Recommendation: Monitor performance with large specs; profile before optimizing; current approach prioritizes clarity

## Fragile Areas

**Polymorphic schema handling without explicit discriminator:**
- Files: `core/src/main/kotlin/com/avsystem/justworks/core/parser/SpecParser.kt:281-310` (detectAndUnwrapOneOfWrappers), `core/src/main/kotlin/com/avsystem/justworks/core/gen/ModelGenerator.kt:192-284` (generatePolymorphicSerializer)
- Why fragile: Multi-step heuristic for unwrapping oneOf wrappers and detecting unique variant fields makes assumptions about schema structure; if assumptions shift slightly, generated serializer fails at runtime
- Safe modification: Add tests for edge cases (variants with overlapping fields, variants with null values, empty variants); validate unwrapping logic in parser tests before modifying; ensure generated TODO() code is caught in integration tests
- Test coverage: Parser tests at `core/src/test/kotlin/com/avsystem/justworks/core/parser/SpecParserPolymorphicTest.kt` cover happy path but not error paths; ModelGenerator polymorphic tests at `core/src/test/kotlin/com/avsystem/justworks/core/gen/ModelGeneratorPolymorphicTest.kt` test output structure but not deserialization correctness

**Complex type resolution in toTypeRef() function:**
- Files: `core/src/main/kotlin/com/avsystem/justworks/core/parser/SpecParser.kt:313-334`
- Why fragile: Single function handles 6 different type cases (string, integer, number, boolean, array, object) plus `Unknown` fallback; mutations in format mappings or new schema constructs can silently create TypeRef.Unknown instead of proper type
- Safe modification: Avoid changing type mapping constants without tests; ensure each new format is tested with both valid and invalid inputs; check for TypeRef.Unknown usage in generated code with integration tests
- Test coverage: TypeMappingTest covers primitive mappings; SpecParserTest covers common cases but edge case formats (e.g., "byte", "date-time") may not be fully exercised

**Property ordering in ModelGenerator data class generation:**
- Files: `core/src/main/kotlin/com/avsystem/justworks/core/gen/ModelGenerator.kt:297-303`
- Why fragile: Constructor parameter order affects serialization behavior; sorting by required/optional/default status is deliberate but easy to break if modifier logic changes
- Safe modification: Maintain test that verifies constructor parameter order matches specification (required before optional); test serialization/deserialization round-trip for mixed required/optional/default properties
- Test coverage: ModelGeneratorTest verifies that properties exist and have correct nullability but does not verify constructor parameter order explicitly

**AllOf property merging logic:**
- Files: `core/src/main/kotlin/com/avsystem/justworks/core/parser/SpecParser.kt:245-263`
- Why fragile: Merging properties from multiple allOf schemas with deduplication by name; if two parent schemas define different types for same property name, first wins (no conflict detection)
- Safe modification: Explicitly validate that merged properties do not have type conflicts; add test for allOf with conflicting property types
- Test coverage: SpecParserTest covers basic allOf; polymorphic parser test does not test property conflicts

## Scaling Limits

**Inline schema name generation with linear search:**
- Current capacity: Typical specs with <500 inline schemas; name collision resolution unbounded
- Limit: At 5000+ inline schemas, name candidate generation becomes O(n²) in worst case (every schema could require multiple collision attempts)
- Scaling path: Pre-allocate numeric namespace (InlineName, InlineName1, InlineName2...); cache occupied names; or hash-based naming scheme

**API endpoint grouping by tags:**
- Current capacity: Specs with <200 endpoints per tag; generates one client class per tag
- Limit: Single client class with 200+ suspend functions becomes hard to navigate and compile; method lookup becomes slow
- Scaling path: Split large tag groups into multiple files (e.g., PetsApiV1, PetsApiV2); or support client class name customization

**ArrayDeque-based type traversal in collectInlineTypeRefs:**
- Current capacity: Specs with <500 nested type references; depth-first traversal with visited set
- Limit: Stack overflow risk if schema nesting exceeds ~1000 levels (unlikely in practice); visited set memory grows linearly with unique inline types
- Scaling path: No action needed for reasonable specs; if hits limit, convert to iterative graph exploration with explicit stack

## Dependencies at Risk

**Swagger Parser v3 (io.swagger.parser.v3:swagger-parser:2.1.39):**
- Risk: Library maintained by SmartBear; last release March 2024; uses Jackson 2.17+ with known security advisories
- Files: `core/build.gradle.kts:20`, `core/src/main/kotlin/com/avsystem/justworks/core/parser/SpecParser.kt`
- Impact: If parser has bugs, cannot generate correct models; if parser library has CVE, affects all generated clients
- Current mitigation: Usage is limited to parse-time (build phase); generated code does not depend on Swagger Parser
- Migration plan: Monitor Swagger Parser releases; if maintenance stalls, consider switching to io.swagger.parser.v2 (deprecated) or maintaining custom OpenAPI v3.0 parser wrapper

**KotlinPoet v2.2.0 (com.squareup:kotlinpoet:2.2.0):**
- Risk: Active maintenance but rapid release cycle; API changes between major versions
- Files: `core/build.gradle.kts:21`, all generator files use KotlinPoet extensively
- Impact: Major version upgrade requires rewriting all code generation logic
- Current mitigation: Heavy coverage of generated code by tests catches misuse quickly; code uses only stable KotlinPoet APIs
- Migration plan: Monitor KotlinPoet releases; pin version to 2.x; create compatibility layer if major version needed

**Arrow Core (io.arrow-kt:arrow-core:2.2.1.1):**
- Risk: Functional programming library with evolving API; context parameters are experimental (uses `@OptIn(ExperimentalRaiseAccumulateApi)`)
- Files: `core/build.gradle.kts:23`, `core/src/main/kotlin/com/avsystem/justworks/core/parser/SpecParser.kt`, `core/src/main/kotlin/com/avsystem/justworks/core/gen/ModelGenerator.kt`
- Impact: If Arrow context parameters stabilize differently, code may break; Experimental API could be removed
- Current mitigation: Limited to parsing and validation logic; could be replaced with custom error handling if needed
- Migration plan: Evaluate moving away from Arrow context parameters to custom validation DSL; or upgrade to stable Arrow APIs when available

## Missing Critical Features

**No support for OpenAPI 3.1 features:**
- Problem: Parser targets OpenAPI 3.0; missing JSON Schema Draft 2020-12 support (3.1 requirement)
- Blocks: Using `$dynamicRef`, newer constraint keywords, full JSON Schema compatibility
- Impact: Specs written for 3.1 may fail or lose fidelity when processed

**No support for content-type negotiation:**
- Problem: Parser assumes `application/json` for all request/response bodies
- Files: `core/src/main/kotlin/com/avsystem/justworks/core/parser/SpecParser.kt:151,163` (hard-coded JSON_CONTENT_TYPE)
- Blocks: APIs using other content types (XML, form-encoded, multipart) cannot be generated
- Impact: Generated clients only work with JSON APIs

**No inheritance/extension schema support:**
- Problem: allOf is recognized but not schema extension pattern (properties from parent class)
- Blocks: Clean generation of base classes in inheritance hierarchies
- Impact: Generated code requires manual composition instead of inheritance

**No validation annotation generation:**
- Problem: Generated data classes have no input validation annotations (e.g., `@Min`, `@Max`, `@NotBlank`)
- Files: `core/src/main/kotlin/com/avsystem/justworks/core/gen/ModelGenerator.kt:306-345` (data class generation)
- Blocks: Consumer must manually validate after deserialization
- Impact: Type safety only; no runtime constraint enforcement

## Test Coverage Gaps

**anyOf without discriminator - serialization not tested:**
- What's not tested: Round-trip serialization/deserialization of anyOf without explicit discriminator
- Files: `core/src/main/kotlin/com/avsystem/justworks/core/gen/ModelGenerator.kt` (generatePolymorphicSerializer at line 192)
- Risk: Generated `JsonContentPolymorphicSerializer` could produce invalid JSON or fail deserialization silently
- Priority: **High** - This is the fragile anyOf area; actual serialization must be tested with real JSON

**Complex allOf merging scenarios:**
- What's not tested: allOf with 3+ levels of nesting, allOf with property name conflicts, allOf with circular references
- Files: `core/src/main/kotlin/com/avsystem/justworks/core/parser/SpecParser.kt:245-263`
- Risk: Edge cases could produce incorrect property lists or infinite loops
- Priority: **Medium** - Less common but possible in real specs

**Unknown TypeRef handling end-to-end:**
- What's not tested: Code generation with TypeRef.Unknown in endpoint parameters, request/response bodies
- Files: `core/src/main/kotlin/com/avsystem/justworks/core/parser/SpecParser.kt:315,325,329,333` (Unknown fallbacks)
- Risk: Generated code uses `Any` for Unknown types; runtime casting failures not caught
- Priority: **Medium** - Should fail at generation time or at compile time; silently using Any is problematic

**ClientGenerator with optional query/header parameters:**
- What's not tested: Client function generation with null query/header params; guard logic in generated code
- Files: `core/src/main/kotlin/com/avsystem/justworks/core/gen/ClientGenerator.kt:211-219` (optionalGuard)
- Risk: Generated `if (param != null)` blocks could be emitted incorrectly or not at all
- Priority: **Medium** - Affects correctness of generated requests

**Default value formatting for edge cases:**
- What's not tested: Default values for enums, default values for dates at epoch/edge times, empty array/map defaults
- Files: `core/src/main/kotlin/com/avsystem/justworks/core/gen/ModelGenerator.kt:351-395`
- Risk: Edge case defaults produce invalid Kotlin code or wrong runtime values
- Priority: **Low** - Less common but possible in real-world specs

---

*Concerns audit: 2026-03-19*
