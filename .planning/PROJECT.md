# Justworks

## What This Is

Kotlin OpenAPI 3.0 client code generator distributed as a Gradle plugin. Parses OpenAPI specs and generates type-safe Ktor HTTP client code with kotlinx.serialization support. Aimed at Kotlin backend/fullstack developers who want generated API clients from OpenAPI specs without leaving the Gradle ecosystem.

## Core Value

Developers apply one Gradle plugin, point it at an OpenAPI spec, and get production-ready, type-safe Kotlin API clients generated at build time.

## Requirements

### Validated

- ✓ Gradle build with core + plugin multi-module layout — existing
- ✓ CI pipeline with tests and coverage (GitHub Actions) — existing
- ✓ ktlint integration with .editorconfig — existing

### Active

- [ ] OpenAPI 3.0 parser with validation (SpecParser, SpecValidator)
- [ ] Intermediate model (ApiSpec, TypeRef, SchemaModel, EnumModel)
- [ ] Model code generator (data classes, enums, sealed hierarchies)
- [ ] Client code generator (per-tag Ktor suspend functions)
- [ ] Response type generator (Success, HttpError)
- [ ] Serializers module generator (polymorphic types)
- [ ] Gradle plugin with multi-spec DSL support
- [ ] anyOf/oneOf polymorphic type support
- [ ] Inline schema detection and deduplication
- [ ] Default parameter values and constructor ordering
- [ ] Date/time type mapping (kotlin.time.Instant, kotlinx.datetime.LocalDate)
- [ ] CD pipeline for Maven Central Portal publishing
- [ ] All draft code merged to master via clean, reviewed PRs

### Out of Scope

- Gradle Plugin Portal publishing — Maven Central only for now
- OpenAPI 3.1 support — 3.0 first
- Code generation for non-Ktor HTTP clients — Ktor only
- Runtime library — generated code is standalone

## Context

The project has a fully functional draft on the `draft` branch with all features implemented across phases 01-07 plus quick tasks. The code works but was developed incrementally and needs cleanup before merging to master.

**Current PR stack (sequential, all open):**

| # | Branch | Content |
|---|--------|---------|
| #2 | feat/model-parser | Parser + validator + data model |
| #4 | feat/generators | Code generators (model, client, response, serializers) |
| #5 | feat/plugin | Gradle plugin with multi-spec support |
| #7 | feat/parser-enhancements | anyOf, inline schemas, defaults, type improvements |
| #6 | feat/cd-maven-central | CD pipeline for Maven Central Portal |

**Merged PRs:** #1 (Gradle setup), #3 (ktlint)

The goal is to get all open PRs reviewed, cleaned up, and merged to master.

## Constraints

- **Tech stack**: Kotlin 2.3.0, Java 21, KotlinPoet 2.2.0, Swagger Parser 2.1.39
- **Target branch**: master (clean orphan branch)
- **Source branch**: draft (full development history)
- **PR strategy**: Sequential stacking — each PR builds on previous
- **Cleanup level**: Moderate — ktlint + refactoring where needed, not full rewrite

## Key Decisions

| Decision | Rationale | Outcome |
|----------|-----------|---------|
| Sequential PR stacking | Allows incremental review, each PR is self-contained | — Pending |
| Maven Central Portal (not OSSRH) | Modern approach, simpler API | — Pending |
| Moderate cleanup | Balance between shipping speed and code quality | — Pending |
| kotlin.time.Instant over kotlinx.datetime.Instant | Kotlin 2.3 stdlib has native Instant | — Pending |

---
*Last updated: 2026-03-12 after initialization*
