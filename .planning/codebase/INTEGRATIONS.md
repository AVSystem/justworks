# External Integrations

**Analysis Date:** 2026-03-11

## APIs & External Services

**OpenAPI/Swagger Specification Parsing:**
- Swagger Parser v3 (io.swagger.parser.v3) - Parses OpenAPI 3.0 specifications
  - SDK/Client: `io.swagger.parser.v3:swagger-parser:2.1.39`
  - Usage location: `core/src/main/kotlin/com/avsystem/justworks/core/parser/SpecParser.kt`
  - Integration method: `OpenAPIParser().readLocation(specFile.absolutePath, null, parseOptions)`
  - Configuration: Parse options with `isResolve=true`, `isResolveFully=true`, `isResolveCombinators=false`

## Code Generation Targets

**Ktor HTTP Client Framework:**
- Not a direct dependency (users of generated code must provide)
- Generated code assumes availability of:
  - `io.ktor.client.HttpClient`
  - `io.ktor.client.plugins.contentnegotiation.ContentNegotiation`
  - `io.ktor.serialization.kotlinx.json.json`
  - HTTP verb functions: `get`, `post`, `put`, `delete`, `patch` from `io.ktor.client.request`
- Integration point: Generated client classes use Ktor suspend functions for HTTP operations

**kotlinx.serialization:**
- Not a direct dependency (users of generated code must provide)
- Generated code assumes availability of:
  - `kotlinx.serialization.Serializable` annotation
  - `kotlinx.serialization.SerialName` annotation
  - `kotlinx.serialization.modules.SerializersModule` for polymorphic serialization
  - `kotlinx.serialization.json.Json` for JSON encoding/decoding
  - `kotlinx.serialization.json.JsonContentPolymorphicSerializer` for polymorphic handling
- Integration point: Generated data classes and serialization modules

## Data Storage

**Databases:**
- Not used - This is a code generation tool, not a runtime application

**File Storage:**
- Local filesystem only
  - Input: OpenAPI spec files (provided by users)
  - Output: Generated Kotlin source files written to build directories
  - Output paths: `build/generated/justworks/shared/kotlin/` (shared types), `build/generated/justworks/{specName}/` (spec-specific)

**Caching:**
- Gradle build cache - Configured via `org.gradle.caching=true` in `gradle.properties`

## Authentication & Identity

**Auth Provider:**
- Custom approach via generated code patterns
- Generated client classes support:
  - Header-based authentication (via HTTP headers in request configuration)
  - Query parameters (supported by parameter mapping system)
- No built-in OAuth/API key integration; users configure auth headers manually

## Monitoring & Observability

**Error Tracking:**
- None - This is a build-time tool

**Logs:**
- Gradle logger via `project.logger.warn()` in `plugin/src/main/kotlin/com/avsystem/justworks/gradle/JustworksPlugin.kt`
- Example: "justworks: no specs configured in justworks.specs { } block"
- Validation errors and warnings from parser propagated to Gradle build output

**Build Output:**
- Generated code written to filesystem
- Test results via JUnit Platform
- Code coverage reports via Kover

## CI/CD & Deployment

**Hosting:**
- Not applicable - Distributed as Gradle plugin via Maven Central / Gradle Plugin Portal
- Users integrate via `plugins { id("com.avsystem.justworks") }` in their build scripts

**CI Pipeline:**
- GitHub Actions configured (`.github/` directory present)
- Build validation via Gradle: `./gradlew build`
- Tests include:
  - Unit tests: `test` task
  - Functional tests: `functionalTest` task (tests Gradle plugin behavior via TestKit)

**Publishing:**
- Maven publishing configuration in `core/build.gradle.kts`
- Gradle Plugin publishing configuration in `plugin/build.gradle.kts`
- Version: `0.0.1`
- Group: `com.avsystem`
- Plugin ID: `com.avsystem.justworks`

## Environment Configuration

**Required for Plugin Users (Not the Plugin Itself):**
- OpenAPI specification file path (configured via DSL)
- Target package names (configured via DSL)
- Gradle 7.0+
- JVM 21+
- Ktor HTTP Client (peer dependency)
- kotlinx.serialization (peer dependency)

**Plugin Build Configuration:**
- No external API credentials required
- No environment variables needed for plugin development/building
- All configuration is declarative in Gradle DSL

## Webhooks & Callbacks

**Incoming:**
- None

**Outgoing:**
- None

## Generated Code Runtime Dependencies

The plugin generates code that assumes these are available in consumer projects:

**Required:**
- `io.ktor:ktor-client-core:*` (for HttpClient)
- `io.ktor:ktor-client-serialization:*` (for JSON content negotiation)
- `org.jetbrains.kotlinx:kotlinx-serialization-json:*` (for serialization)
- `org.jetbrains.kotlinx:kotlinx-datetime:*` (for date/time support)
- `io.arrow-kt:arrow-core:*` (for error handling via Raise context)

**Generated Entry Points:**
- Shared types file: Generated to `build/generated/justworks/shared/kotlin/SharedTypes.kt`
  - Contains: `HttpError`, `HttpErrorType`, `HttpSuccess` types
- Spec-specific files: Generated to `build/generated/justworks/{specName}/`
  - Contains: Client classes (per API tag), data models, enums, serializers module

---

*Integration audit: 2026-03-11*
