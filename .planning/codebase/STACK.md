# Technology Stack

**Analysis Date:** 2026-03-19

## Languages

**Primary:**
- Kotlin 2.3.0 - Core language for parser, generators, and Gradle plugin

**Secondary:**
- Java 21 - JVM toolchain and interoperability

## Runtime

**Environment:**
- JVM (Java Virtual Machine) via Gradle wrapper
- Gradle 8.13 (via `gradle/wrapper/gradle-wrapper.properties`)

**Package Manager:**
- Gradle - Build automation and dependency management
- Lockfile: Not detected (Gradle uses `.gradle/` cache)

## Frameworks

**Core:**
- KotlinPoet 2.2.0 - Code generation for Kotlin source files
- Swagger Parser v3 (swagger-parser 2.1.39) - OpenAPI 3.0 specification parsing

**Testing:**
- kotlin.test (included with Kotlin stdlib) - Test framework with assertions
- JUnit Platform - Test runner (configured via `useJUnitPlatform()` in `core/build.gradle.kts`)

**Build/Dev:**
- Gradle Kotlin DSL - Build scripts in `.gradle.kts` format
- JetBrains KotlinX Kover 0.9.1 - Code coverage reporting (configured in `core/build.gradle.kts`)
- KtLint Gradle Plugin 12.1.2 - Kotlin linting and formatting

## Key Dependencies

**Critical:**
- `io.swagger.parser.v3:swagger-parser:2.1.39` - Parses OpenAPI 3.0 specs; used in `SpecParser.kt` to load and validate API specifications
- `com.squareup:kotlinpoet:2.2.0` - Generates Kotlin source code via AST; used in `ClientGenerator.kt`, `ModelGenerator.kt`, `ApiClientBaseGenerator.kt`, `SerializersModuleGenerator.kt`
- `org.jetbrains.kotlinx:kotlinx-datetime:0.7.1` - Date/time types; imported in `ModelGenerator.kt` for LocalDate and Instant types

**Infrastructure:**
- `io.arrow-kt:arrow-core:2.2.1.1` - Functional programming library (Either, raise DSL); used throughout for error handling in `SpecParser.kt`, `ModelGenerator.kt` for structured error flows

## Configuration

**Environment:**
- Settings in `gradle.properties`:
  - `kotlin.code.style=official` - Enforces official Kotlin style
  - `org.gradle.caching=true` - Build caching enabled
  - `org.gradle.parallel=true` - Parallel task execution
  - `org.gradle.configuration-cache=true` - Configuration cache optimization
  - `org.gradle.jvmargs=-Xmx2048m -Dfile.encoding=UTF-8 -XX:+UseParallelGC` - JVM memory limit 2GB, UTF-8 encoding, parallel GC

**Build:**
- `build.gradle.kts` (root) - Plugin declarations and allprojects configuration
- `settings.gradle.kts` - Root project name "justworks", module includes, Gradle toolchain resolver
- `core/build.gradle.kts` - Core module dependencies, Maven publishing, Kover coverage, KtLint version override

## Platform Requirements

**Development:**
- Java 21 toolchain (via `jvmToolchain(21)` in `core/build.gradle.kts`)
- Gradle 8.13 or wrapper-compatible version

**Production:**
- JVM runtime with Java 21 compatibility
- Distribution: Published as Gradle plugin to Maven Central via `maven-publish` plugin
- Package: `com.avsystem:justworks` (group) version 0.0.1

---

*Stack analysis: 2026-03-19*
