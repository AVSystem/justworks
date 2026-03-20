# Roadmap: Justworks

## Overview

Justworks generates Kotlin/Ktor client code from OpenAPI 3.0 specs. The foundation works but has compilation-breaking bugs and missing type/format support. This roadmap fixes critical bugs first, then expands type correctness, schema semantics, content type handling, and security schemes -- each phase producing a small, independently shippable branch.

## Phases

**Phase Numbering:**
- Integer phases (1, 2, 3): Planned milestone work
- Decimal phases (2.1, 2.2): Urgent insertions (marked with INSERTED)

Decimal phases appear between their surrounding integers in numeric order.

- [x] **Phase 1: Critical Bug Fixes** - Fix compilation-breaking bugs in enum generation, Instant import, and ApiClientBase (completed 2026-03-19)
- [ ] **Phase 2: Type Mapping and Reserved Words** - Correct format-to-type mappings and escape reserved Kotlin keywords
- [ ] **Phase 3: Schema Semantics and Documentation** - Nullable handling, default values, @Deprecated, KDoc propagation
- [ ] **Phase 4: Content Types** - Multipart/form-data, form-urlencoded, and response code distinction
- [ ] **Phase 5: Security Schemes** - API key and HTTP basic authentication support
- [x] **Phase 6: TypeAlias for Non-String Inline Schemas** - Extend typealias generation to all primitive types, arrays, and $ref wrappers (completed 2026-03-20)

- [x] **Phase 7: Discriminated Union Subtype Generation** - Generate missing oneOf/discriminator subtypes referenced in SerializersModule (completed 2026-03-20)
- [ ] **Phase 8: Freeform/Any Type Handling** - Replace unserializable `Any`/`Any?` with `@Contextual` annotation or `JsonElement`
- [ ] **Phase 9: Gradle Plugin Publication** - Publish plugin to Maven Central / Gradle Plugin Portal for production use

## Phase Details

### Phase 1: Critical Bug Fixes
**Goal**: Generator produces compilable code for real-world specs (no crashes from enum, Instant, or missing ApiClientBase)
**Depends on**: Nothing (first phase)
**Requirements**: SCHM-07, SCHM-08, SCHM-09
**Success Criteria** (what must be TRUE):
  1. Running the generator against ump-api.json produces code that compiles without enum companion object errors
  2. Generated code importing Instant uses `kotlinx.datetime.Instant`, not `kotlin.time.Instant`
  3. ApiClientBase class is generated in the output when the spec contains endpoints
**Plans**: 3 plans

Plans:
- [ ] 01-01-PLAN.md — Fix enum duplicate companion object bug and verify Instant mapping
- [ ] 01-02-PLAN.md — Recover plugin source from draft branch for ApiClientBase generation
- [ ] 01-03-PLAN.md — Integration test with ump-api.json validating all fixes end-to-end

### Phase 2: Type Mapping and Reserved Words
**Goal**: Generator correctly maps all common string formats and escapes Kotlin reserved words in property names
**Depends on**: Phase 1
**Requirements**: SCHM-03, SCHM-04, SCHM-05, SCHM-06
**Success Criteria** (what must be TRUE):
  1. Schema property with `format: uuid` generates a `kotlin.uuid.Uuid` typed property
  2. Schema property with `format: uri` or `format: url` generates a `String` typed property
  3. Schema property with `format: binary` generates a `ByteArray` typed property
  4. Schema property named `class`, `object`, `size`, `entries`, or `values` generates compilable code with backtick escaping and correct `@SerialName`
**Plans**: 2 plans

Plans:
- [ ] 02-01-PLAN.md — Extend format maps, add UUID type support with UuidSerializer generation
- [ ] 02-02-PLAN.md — Test-driven reserved word escaping and integration test validation

### Phase 3: Schema Semantics and Documentation
**Goal**: Generator handles nullable properties correctly, emits default values, and propagates spec documentation into generated code
**Depends on**: Phase 2
**Requirements**: SCHM-01, SCHM-02, DOCS-01, DOCS-02
**Success Criteria** (what must be TRUE):
  1. Schema property with `nullable: true` generates a nullable Kotlin type (`Type?`) regardless of whether it appears in the `required` list
  2. Schema properties with `default` values generate data class parameters with Kotlin default values for all primitive types
  3. Operations and schemas marked `deprecated: true` in the spec produce `@Deprecated` annotations in generated code
  4. Schema/property/operation descriptions from the spec appear as KDoc comments on generated classes, properties, and functions
**Plans**: TBD

Plans:
- [ ] 03-01: TBD

### Phase 4: Content Types
**Goal**: Generator supports file uploads via multipart, form-urlencoded submissions, and distinguishes response status codes
**Depends on**: Phase 3
**Requirements**: CONT-01, CONT-02, CONT-03
**Success Criteria** (what must be TRUE):
  1. Operation with `multipart/form-data` request body generates code using Ktor's multipart API (`submitFormWithBinaryData` or `formData {}`)
  2. Operation with `application/x-www-form-urlencoded` request body generates code using Ktor's form submission
  3. Operations returning 204 No Content generate functions that return `Unit` (not attempting to deserialize a response body)
**Plans**: TBD

Plans:
- [ ] 04-01: TBD

### Phase 5: Security Schemes
**Goal**: Generator supports API key and HTTP basic authentication beyond the existing Bearer token support
**Depends on**: Phase 1 (uses ApiClientBase generated in Phase 1)
**Requirements**: SECU-01, SECU-02
**Success Criteria** (what must be TRUE):
  1. Spec with `securitySchemes` containing `apiKey` (in: header or query) generates client code that attaches the API key to requests
  2. Spec with `securitySchemes` containing `http/basic` generates client code that sends Basic auth credentials
  3. Existing Bearer token authentication continues to work unchanged
**Plans**: TBD

Plans:
- [ ] 05-01: TBD

### Phase 6: TypeAlias for Non-String Inline Schemas
**Goal:** Generator produces correct typealiases for all primitive types, arrays, and single-$ref wrappers (not just String)
**Requirements**: TYPEALIAS-01
**Depends on:** Phase 1 (independent of phases 2-5)
**Success Criteria** (what must be TRUE):
  1. Primitive-only schema with `type: integer` generates `typealias Foo = Int` (not `typealias Foo = String`)
  2. Primitive-only schema with `type: string, format: uuid` generates `typealias Foo = Uuid`
  3. Primitive-only schema with `type: array` generates `typealias Foo = List<ItemType>`
  4. Single-$ref wrapper schema generates `typealias Foo = ReferencedType`
  5. Existing String typealiases continue to work unchanged
**Plans**: 1 plan

Plans:
- [ ] 06-01-PLAN.md — Add underlyingType to SchemaModel and resolve dynamic typealias targets

### Phase 7: Discriminated Union Subtype Generation
**Goal**: Generator produces all subtype classes for oneOf/discriminator schemas, not just the sealed interface and SerializersModule
**Depends on**: Phase 1
**Requirements**: CEM-01
**Source**: cem-mobile-app compilation errors — `SerializersModule.kt` references `DataModelSource`, `SettingValueSource` (subtypes of `ResourceSource`) and `ExtenderDevice`, `EthernetDevice`, `WanDevice`, `USBDevice`, `WiFiDevice`, `OtherDevice` (subtypes of `NetworkMeshDevice`) but these classes are never generated
**Additional issue**: Boolean discriminator values generate degenerate class names — `DeviceStatus` with `online` discriminator produces `true.kt`/`false.kt` with backtick class names `` `true` `` and `` `false` ``
**Success Criteria** (what must be TRUE):
  1. All oneOf subtype classes referenced in SerializersModule are generated as concrete data classes implementing the sealed interface
  2. Discriminator values that are not valid Kotlin identifiers (e.g., `true`, `false`) are handled gracefully
  3. Generated polymorphic hierarchy compiles and serializes/deserializes correctly
**Plans**: 1 plan

Plans:
- [ ] 07-01-PLAN.md — Fix parser to include synthetic schemas, add name sanitization, and test polymorphic generation end-to-end

### Phase 8: Freeform/Any Type Handling
**Goal**: Generator handles schemas with no concrete type (freeform objects, `additionalProperties: true`) without producing unserializable `Any` types
**Depends on**: Phase 1
**Requirements**: CEM-02
**Source**: cem-mobile-app compilation errors — `TaskReportDTO.response: Any?`, `WorkflowResultDTO.response: Any`, `DeviceInfo.healthChecks: List<Any>` all fail with "Serializer has not been found for type 'Any'"
**Success Criteria** (what must be TRUE):
  1. Freeform object properties generate `@Contextual` annotated types or use `kotlinx.serialization.json.JsonElement` instead of bare `Any`
  2. Generated code compiles without "Serializer has not been found for type 'Any'" errors
  3. Nullable freeform properties (`Any?`) are handled correctly
**Plans**: TBD

Plans:
- [ ] 08-01: TBD

### Phase 9: Gradle Plugin Publication
**Goal**: Plugin is published and consumable from external projects via standard Gradle dependency resolution
**Depends on**: All other phases (publish when stable)
**Requirements**: CEM-03
**Source**: cem-mobile-app cannot use plugin because it's not published — forced to commit 130+ generated files to VCS (commit `2eb4f1bc`)
**Success Criteria** (what must be TRUE):
  1. Plugin is published to a Maven repository (Maven Central or AVSystem internal)
  2. External project can apply plugin via `plugins { id("com.avsystem.justworks") version "X.Y.Z" }`
  3. Plugin resolves all transitive dependencies correctly
**Plans**: TBD

Plans:
- [ ] 09-01: TBD

## Progress

**Execution Order:**
Phases execute in numeric order: 1 -> 2 -> 3 -> 4 -> 5 -> 6

| Phase | Plans Complete | Status | Completed |
|-------|----------------|--------|-----------|
| 1. Critical Bug Fixes | 3/3 | Complete   | 2026-03-19 |
| 2. Type Mapping and Reserved Words | 1/2 | In Progress|  |
| 3. Schema Semantics and Documentation | 0/? | Not started | - |
| 4. Content Types | 0/? | Not started | - |
| 5. Security Schemes | 0/? | Not started | - |
| 6. TypeAlias for Non-String Inline Schemas | 1/1 | Complete    | 2026-03-20 |
| 7. Discriminated Union Subtype Generation | 1/1 | Complete   | 2026-03-20 |
| 8. Freeform/Any Type Handling | 0/? | Not started | - |
| 9. Gradle Plugin Publication | 0/? | Not started | - |
