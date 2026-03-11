# Codebase Concerns

**Analysis Date:** 2026-03-11

## Tech Debt

**Primitive-only Schema Type Inference:**
- Issue: `ModelGenerator.kt:161` contains a TODO noting that primitive-only schemas default to String type alias without inferring actual primitive type from OpenAPI spec
- Files: `core/src/main/kotlin/com/avsystem/justworks/core/gen/ModelGenerator.kt:159-164`
- Impact: If a primitive-only schema (e.g., `type: number`) is used, it will generate `typealias Id = String` instead of `typealias Id = Double`. Client code will have incorrect types.
- Fix approach: Extend `SchemaModel` to track the `primitiveType` field for schemas with no properties. Update `SpecParser.extractSchemaModel()` to capture primitive type, then use it in `ModelGenerator.generateTypeAlias()`.

**Nested Inline Class Generation:**
- Issue: `ModelGenerator.kt:662` TODO notes that nested inline classes (e.g., "Pet.Address") are generated as top-level classes with sanitized names instead of true nested classes
- Files: `core/src/main/kotlin/com/avsystem/justworks/core/gen/ModelGenerator.kt:664-670`
- Impact: Generated code loses namespace structure. Client imports become verbose if many nested schemas exist. Name collisions possible if sanitization occurs.
- Fix approach: Use `TypeSpec.Builder.addType()` to create actual nested classes, or adopt a package-based namespace approach (e.g., `com.example.model.pet.Address`).

**Unreliable Field-Presence Heuristic for anyOf without Discriminator:**
- Issue: `ModelGenerator.kt:260` generates `TODO()` placeholders for anyOf variants without unique discriminating fields
- Files: `core/src/main/kotlin/com/avsystem/justworks/core/gen/ModelGenerator.kt:312-357`, test case at `core/src/test/kotlin/com/avsystem/justworks/core/gen/ModelGeneratorPolymorphicTest.kt:523`
- Impact: Generated client code contains `TODO()` that will crash at runtime if the variant's deserialization path is hit. Manual intervention required in every generated file.
- Fix approach: Require explicit discriminator in OpenAPI spec for anyOf schemas. Alternatively, implement fallback to field-frequency heuristics or error early at generation time.

## Known Bugs

**Default Value Validation Limited to Generation Time:**
- Symptoms: Invalid ISO-8601 date/time defaults in OpenAPI specs throw `IllegalArgumentException` during generation. This is caught, but only as a last resort—no early validation.
- Files: `core/src/main/kotlin/com/avsystem/justworks/core/gen/ModelGenerator.kt:530-553`
- Trigger: Include a property with `default: "invalid-date"` where format is `date` or `date-time`
- Workaround: Pre-validate OpenAPI spec externally; add stricter validation in `SpecValidator.validate()`

**Array Items Default Fallback:**
- Symptoms: If an array schema lacks `items` definition, parser defaults to `TypeRef.Primitive(PrimitiveType.STRING)` silently
- Files: `core/src/main/kotlin/com/avsystem/justworks/core/parser/SpecParser.kt:254-258`
- Trigger: Array schema with missing/null `items` property
- Workaround: SpecValidator should flag malformed array schemas. Alternatively, throw during parse instead of silent default.

**oneOf Wrapper Detection Fragility:**
- Symptoms: `detectAndUnwrapOneOfWrappers()` relies on exact pattern matching (one object property per variant). Non-matching specs fall back silently to standard extraction.
- Files: `core/src/main/kotlin/com/avsystem/justworks/core/parser/SpecParser.kt:305-371`
- Trigger: Schemas with one property that isn't a reference but resembles the pattern
- Workaround: None; detection is all-or-nothing. Unclear feedback if pattern detection fails.

## Security Considerations

**No Validation of Generated Class Names Against Reserved Keywords:**
- Risk: OpenAPI spec property names or schema names could collide with Kotlin keywords (e.g., `fun`, `class`, `throw`). Generated code will not compile.
- Files: `core/src/main/kotlin/com/avsystem/justworks/core/gen/NameUtils.kt:22-34` has keyword list but only used for enum constant names
- Current mitigation: `toKotlinIdentifier()` escapes reserved names as backtick identifiers, but this is partial
- Recommendations: Extend keyword escaping to all generated identifiers (schema names, property names). Update `SpecValidator` to warn about problematic names.

**Missing Input Validation for File Paths:**
- Risk: Plugin accepts file paths without validation. Malicious gradle configurations could write to arbitrary locations.
- Files: `plugin/src/main/kotlin/com/avsystem/justworks/gradle/JustworksPlugin.kt:92-93` validates presence but not path safety
- Current mitigation: Gradle task framework constrains output to build directory by default
- Recommendations: Validate that `specFile` and output directories are within expected paths. Add explicit check in `JustworksPlugin.apply()`.

**Unvalidated HTTP Client Configuration:**
- Risk: Generated HTTP clients use default Ktor configuration with `expectSuccess = false`. Token provider is a plain string—no encryption or secure storage guidance.
- Files: `core/src/main/kotlin/com/avsystem/justworks/core/gen/ClientGenerator.kt:122-149`
- Current mitigation: Generated code is not a security boundary; users must configure client creation securely
- Recommendations: Add documentation in generated client code warning about token handling. Optionally generate configuration hooks.

## Performance Bottlenecks

**ModelGenerator State Cleared Per Spec:**
- Problem: `ModelGenerator.generate()` clears mutable state (`sealedHierarchies`, `variantParents`, `anyOfWithoutDiscriminator`) on every call. If generating multiple specs in parallel, map rebuilding occurs redundantly.
- Files: `core/src/main/kotlin/com/avsystem/justworks/core/gen/ModelGenerator.kt:43-47`
- Cause: Mutable state design; generator not stateless
- Improvement path: Extract state into immutable parameters or use per-spec generator instances. Profile to confirm this is a real bottleneck in multi-spec scenarios.

**Identity Map Rebuilding for Every Parse:**
- Problem: `SpecParser.componentSchemaIdentity` is rebuilt during every `transformToModel()` call via `IdentityHashMap` population.
- Files: `core/src/main/kotlin/com/avsystem/justworks/core/parser/SpecParser.kt:73-77`
- Cause: No caching of identity mappings across parse calls
- Improvement path: Cache identity maps if same spec is parsed multiple times. Unlikely to be a real issue unless parse is called in a loop.

## Fragile Areas

**Polymorphic Serialization Strategy Selection:**
- Files: `core/src/main/kotlin/com/avsystem/justworks/core/gen/ModelGenerator.kt:205-310`, `core/src/test/kotlin/com/avsystem/justworks/core/gen/ModelGeneratorPolymorphicTest.kt`
- Why fragile: Complex conditional logic decides between three strategies—oneOf with discriminator (sealed + @JsonClassDiscriminator), anyOf with discriminator (same), anyOf without discriminator (custom JsonContentPolymorphicSerializer). Variants without unique fields force TODO() generation.
- Safe modification: Add comprehensive test cases for edge cases (overlapping properties, all fields shared, single variant, etc.). Validate discriminator fields exist on all variants.
- Test coverage: 8+ test cases exist covering basic patterns; coverage of error/edge cases is good but fragile areas are not yet tested for all combinations.

**Inline Schema Deduplication and Naming:**
- Files: `core/src/main/kotlin/com/avsystem/justworks/core/gen/InlineSchemaDeduplicator.kt`, `core/src/main/kotlin/com/avsystem/justworks/core/gen/ModelGenerator.kt:74-105`
- Why fragile: Collision detection relies on name prefix matching and set membership. Nested inline schemas use dot notation; name sanitization removes dots. This can create unintended collisions.
- Safe modification: Add logging to trace deduplication decisions. Test with specs containing many similarly-structured inline schemas. Consider deterministic naming scheme (e.g., hash-based).
- Test coverage: `InlineSchemaDedupTest.kt` exists and covers basic deduplication; no tests for collision edge cases.

**OpenAPI Spec Parsing with resolveFully=true:**
- Files: `core/src/main/kotlin/com/avsystem/justworks/core/parser/SpecParser.kt:36-41`
- Why fragile: Parser uses Swagger Parser library with `resolveFully=true`, which resolves all `$ref` pointers inline. This can cause issues with circular schemas or deeply nested references. Identity map is used to detect resolved refs, but this is implicit and hard to debug.
- Safe modification: Test with circular schemas (Pet with owner: Pet). Add debug logging to trace identity resolution. Document the implications of resolveFully=true.
- Test coverage: `SpecParserTest.kt` and `SpecParserPolymorphicTest.kt` exist but don't explicitly test circular references.

## Scaling Limits

**No Streaming for Large Specs:**
- Current capacity: Parser loads entire OpenAPI spec into memory. Generated files are created one per schema/endpoint.
- Limit: Specs with hundreds of endpoints or thousands of inline schemas will create proportional memory usage and file count.
- Scaling path: Implement streaming generation (output files as soon as schema is processed). Consider file aggregation options (merge small models into single file).

**Sealed Hierarchies Registry is In-Memory:**
- Current capacity: `ModelGenerator.sealedHierarchies` maps parent -> list of variants. For large specs, this grows linearly with schema count.
- Limit: No hard limit; grows with spec size.
- Scaling path: For multi-spec generation, use separate generator instances per spec to isolate state. Profile memory usage with large specs.

## Dependencies at Risk

**Swagger Parser v3 (2.1.39):**
- Risk: Library is actively maintained but integration with newer OpenAPI spec versions should be monitored. `resolveFully=true` behavior may change.
- Impact: Changes to ref resolution could silently break identity map detection
- Migration plan: Monitor Swagger Parser releases. Add version pinning in build docs. Test against minor version updates before upgrading.

**KotlinPoet (2.2.0):**
- Risk: Code generation library could change API in minor versions. Current usage is low-level (direct FileSpec, TypeSpec construction).
- Impact: Minor API changes may require generator rewrites
- Migration plan: Keep close to latest versions. Use deprecation warnings as early warning of API changes.

## Test Coverage Gaps

**Missing Error Path Testing:**
- What's not tested: Invalid OpenAPI specs (malformed refs, circular allOf, mixed oneOf+anyOf with conflicting discriminators)
- Files: Parser error handling in `core/src/main/kotlin/com/avsystem/justworks/core/parser/SpecParser.kt:59-65`, validation in `core/src/main/kotlin/com/avsystem/justworks/core/parser/SpecValidator.kt`
- Risk: Parser may throw uncaught exceptions or produce silently invalid output on edge-case specs
- Priority: High

**Missing Multi-Spec Gradle Plugin Tests:**
- What's not tested: Gradle plugin behavior when multiple specs are registered; task ordering and dependencies; output directory isolation
- Files: `plugin/src/functionalTest/kotlin/com/avsystem/justworks/gradle/JustworksPluginFunctionalTest.kt` exists but is minimal
- Risk: Plugin may not scale to realistic multi-spec projects
- Priority: Medium

**Missing HTTP Client Generation Integration Tests:**
- What's not tested: Generated client code compilation and execution against real Ktor setup
- Files: `core/src/test/kotlin/com/avsystem/justworks/core/gen/ClientGeneratorTest.kt` tests code structure, not compilation
- Risk: Generated code may not compile or may have runtime errors
- Priority: Medium

**Missing Serialization Round-Trip Tests:**
- What's not tested: Generated data classes with kotlinx.serialization actually serialize/deserialize correctly
- Files: Model generator has no serialization tests; ModelGeneratorTest covers structure only
- Risk: Generated models may have serialization annotation issues not caught until runtime
- Priority: Medium

**Enum Edge Cases Untested:**
- What's not tested: Enums with numeric values, special characters in names, duplicate values
- Files: `core/src/main/kotlin/com/avsystem/justworks/core/gen/ModelGenerator.kt:595-625`, test file `core/src/test/kotlin/com/avsystem/justworks/core/gen/ModelGeneratorTest.kt`
- Risk: Generated enum constants may fail compilation or have naming collisions
- Priority: Low

---

*Concerns audit: 2026-03-11*
