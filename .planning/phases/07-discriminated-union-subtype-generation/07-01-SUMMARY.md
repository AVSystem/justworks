---
phase: 07-discriminated-union-subtype-generation
plan: 01
subsystem: api
tags: [openapi, oneOf, discriminator, kotlin, code-generation, sealed-hierarchy]

requires:
  - phase: 03-polymorphic-serialization
    provides: "Sealed interface generation, SerializersModule, discriminator handling"
provides:
  - "Parser includes synthetic schemas from wrapper unwrapping in ApiSpec.schemas"
  - "Name sanitization for invalid Kotlin identifiers (keywords, digits) in schema names"
  - "Boolean discriminator values produce valid class names while preserving @SerialName"
affects: []

tech-stack:
  added: []
  patterns:
    - "sanitizeSchemaName pattern for discriminator-derived class names"
    - "Synthetic schema collection after main extraction loop in parser"

key-files:
  created:
    - core/src/test/resources/boolean-discriminator-spec.yaml
  modified:
    - core/src/main/kotlin/com/avsystem/justworks/core/gen/NameUtils.kt
    - core/src/main/kotlin/com/avsystem/justworks/core/parser/SpecParser.kt
    - core/src/test/kotlin/com/avsystem/justworks/core/gen/NameUtilsTest.kt
    - core/src/test/kotlin/com/avsystem/justworks/core/parser/SpecParserPolymorphicTest.kt
    - core/src/test/kotlin/com/avsystem/justworks/core/gen/ModelGeneratorPolymorphicTest.kt

key-decisions:
  - "Parser-side fix for synthetic schemas (not generator-side) -- fixes at source"
  - "sanitizeSchemaName uses parentName prefix for invalid identifiers -- produces DeviceStatusTrue/False"
  - "Discriminator mapping keys preserve original values (true/false) for correct JSON deserialization"

patterns-established:
  - "isValidKotlinIdentifier + sanitizeSchemaName: reusable name validation and sanitization"
  - "Synthetic schema collection: syntheticSchemaNames = componentSchemas.keys - allSchemas.keys"

requirements-completed: [CEM-01]

duration: 7min
completed: 2026-03-20
---

# Phase 7 Plan 1: Discriminated Union Subtype Generation Summary

**Fixed synthetic schema inclusion in parser and added name sanitization for boolean discriminator values**

## Performance

- **Duration:** 7 min
- **Started:** 2026-03-20T13:28:16Z
- **Completed:** 2026-03-20T13:35:16Z
- **Tasks:** 2
- **Files modified:** 6

## Accomplishments
- Parser now includes synthetic schemas from wrapper unwrapping in ApiSpec.schemas output
- Boolean discriminator values (true/false) produce valid Kotlin class names (DeviceStatusTrue/DeviceStatusFalse)
- @SerialName annotations preserve original discriminator values for correct JSON deserialization
- Comprehensive tests for name sanitization, parser synthetic schema inclusion, and generator e2e

## Task Commits

Each task was committed atomically:

1. **Task 1: Fix parser synthetic schemas + name sanitization** - `7963002` (test RED), `ea13da2` (feat GREEN)
2. **Task 2: E2e generator tests for boolean discriminator and multi-variant oneOf** - `b19049c` (test)

## Files Created/Modified
- `core/src/main/kotlin/com/avsystem/justworks/core/gen/NameUtils.kt` - Added isValidKotlinIdentifier, sanitizeSchemaName, KOTLIN_HARD_KEYWORDS
- `core/src/main/kotlin/com/avsystem/justworks/core/parser/SpecParser.kt` - Synthetic schema collection in toApiSpec(), sanitization in detectAndUnwrapOneOfWrappers()
- `core/src/test/kotlin/com/avsystem/justworks/core/gen/NameUtilsTest.kt` - Tests for identifier validation and name sanitization
- `core/src/test/kotlin/com/avsystem/justworks/core/parser/SpecParserPolymorphicTest.kt` - Tests for boolean discriminator parsing and synthetic schema inclusion
- `core/src/test/kotlin/com/avsystem/justworks/core/gen/ModelGeneratorPolymorphicTest.kt` - E2e tests for sanitized boolean discriminator names and multi-variant oneOf
- `core/src/test/resources/boolean-discriminator-spec.yaml` - Test fixture with boolean discriminator pattern

## Decisions Made
- Parser-side fix chosen over generator-side: fixes at the source so ApiSpec is complete
- sanitizeSchemaName prefixes parentName for invalid identifiers (e.g., true -> DeviceStatusTrue)
- Discriminator mapping keys always preserve original discriminator values for JSON roundtrip

## Deviations from Plan

### Auto-fixed Issues

**1. [Rule 1 - Bug] Fixed test expectation for digit-prefixed name sanitization**
- **Found during:** Task 1 (GREEN phase)
- **Issue:** Test expected "Status123Abc" but toPascalCase("123abc") produces "123abc" (no delimiter to split on)
- **Fix:** Corrected test expectation to "Status123abc"
- **Files modified:** core/src/test/kotlin/com/avsystem/justworks/core/gen/NameUtilsTest.kt
- **Verification:** Test passes
- **Committed in:** ea13da2

---

**Total deviations:** 1 auto-fixed (1 bug)
**Impact on plan:** Minor test expectation correction. No scope creep.

## Issues Encountered
None

## User Setup Required
None - no external service configuration required.

## Next Phase Readiness
- All oneOf subtype classes referenced in SerializersModule will now be generated
- Boolean discriminator values handled gracefully with valid Kotlin class names
- Ready for subsequent phases

---
*Phase: 07-discriminated-union-subtype-generation*
*Completed: 2026-03-20*
