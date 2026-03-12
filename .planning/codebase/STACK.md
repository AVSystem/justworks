# Technology Stack

**Analysis Date:** 2026-03-11

## Languages

**Primary:**
- Kotlin 2.3.0 - Core language for all code generation logic, parser, plugin, and tests

**Secondary:**
- Java 21 - JVM toolchain target for compilation
- Gradle Kotlin DSL (*.kts) - Build configuration scripts

## Runtime

**Environment:**
- Java Virtual Machine (JVM) - Gradle-managed via Foojay toolchain resolver
- Java 21 toolchain - Explicitly configured in `build.gradle.kts` via `jvmToolchain(21)`

**Package Manager:**
- Gradle 8.13 - Build system and dependency management
- Lockfile: Present - Gradle wrapper configured with specific distribution URL

## Frameworks

**Core:**
- Swagger Parser v3 (2.1.39) - OpenAPI 3.0 specification parsing
  - Location: `io.swagger.parser.v3` - Used in `core/src/main/kotlin/com/avsystem/justworks/core/parser/SpecParser.kt`
- KotlinPoet 2.2.0 - Code generation for Kotlin files
  - Used across `core/src/main/kotlin/com/avsystem/justworks/core/gen/` generators

**Plugin System:**
- Gradle Plugin DSL (`java-gradle-plugin`) - Enables creation of Gradle plugins
  - Plugin class: `com.avsystem.justworks.gradle.JustworksPlugin`
  - Plugin ID: `com.avsystem.justworks`

**Testing:**
- Kotlin Test (`kotlin("test")`) - Testing framework via stdlib
- JUnit Platform - Test runner for JVM tests
  - Configured in build tasks: `useJUnitPlatform()`
- Gradle TestKit - Functional testing of Gradle plugins
  - Source set: `functionalTest` in `plugin/build.gradle.kts`

**Build/Dev:**
- Ktlint 12.1.2 - Kotlin code linting and formatting
  - Applied globally via `org.jlleitschuh.gradle.ktlint` plugin
  - Configuration in `.editorconfig` with max line length 120
- Kover 0.9.1 - Code coverage measurement (`org.jetbrains.kotlinx.kover`)
- Foojay Resolver Convention 1.0.0 - JDK toolchain discovery

## Key Dependencies

**Critical:**
- Arrow Core 2.2.1.1 - Functional programming utilities for error handling with `Raise<E>` context
  - Usage: `com.avsystem.justworks.core.gen.Names` references `arrow.core.raise` and `arrow.core.raise.context`
  - Alternative to exceptions for controlled error propagation

**Infrastructure:**
- kotlinx-datetime 0.7.1 - Date/time handling
  - Classes: `LocalDate` for date serialization in generated models
  - Location: `kotlinx.datetime` package
- kotlinx.serialization (implicit via Ktor) - JSON serialization framework for generated data classes
  - Annotations: `@Serializable`, `@SerialName`, `@ExperimentalSerializationApi`
  - Modules: `SerializersModule` for polymorphic serialization

**HTTP Client (Target Runtime):**
- Ktor HTTP Client (not a direct dependency, generated code assumes presence)
  - Packages: `io.ktor.client`, `io.ktor.client.plugins.contentnegotiation`, `io.ktor.serialization.kotlinx.json`
  - Generated code uses: `HttpClient`, `ContentNegotiation`, HTTP verb methods (`get`, `post`, `put`, `delete`, `patch`)

## Configuration

**Environment:**
- Gradle properties file: `/Users/bkozak/IdeaProjects/ApiKt/gradle.properties`
  - `kotlin.code.style=official` - Enforces official Kotlin conventions
  - `org.gradle.caching=true` - Build cache enabled
  - `org.gradle.parallel=true` - Parallel task execution
  - `org.gradle.configuration-cache=true` - Configuration cache enabled
  - `org.gradle.jvmargs=-Xmx2048m -Dfile.encoding=UTF-8 -XX:+UseParallelGC` - JVM memory/encoding

**Build:**
- Build script: `build.gradle.kts` (root) - Multi-module build configuration
  - Modules: `core` (parser + generators), `plugin` (Gradle integration)
- Settings script: `settings.gradle.kts` - Defines project structure and toolchain plugin
- Editor configuration: `.editorconfig` - Ktlint code style rules
  - `ktlint_code_style = ktlint_official`
  - `max_line_length = 120`
  - Class/function signatures: multiline when parameters >= 3
  - Trailing commas: enabled at call site, disabled at declaration

## Platform Requirements

**Development:**
- Java 21 toolchain (auto-managed by Gradle Foojay resolver)
- Gradle 8.13 (bundled via gradlew)
- Kotlin 2.3.0 compiler
- IDE support for Gradle Kotlin DSL (.kts)

**Production:**
- Gradle 7.0+ (for plugin consumers)
- JVM toolchain 21+ (for generated code consumers)
- Ktor HTTP Client (peer dependency for generated code users)
- kotlinx.serialization (peer dependency for generated code users)

**Distribution:**
- Maven publishing configured in `core/build.gradle.kts`
  - Publication: Maven via `maven-publish` plugin
  - Package group: `com.avsystem`
  - Package version: `0.0.1`
- Gradle Plugin Portal publication configured in `plugin/build.gradle.kts`
  - Plugin ID: `com.avsystem.justworks`
  - Implementation class: `com.avsystem.justworks.gradle.JustworksPlugin`

---

*Stack analysis: 2026-03-11*
