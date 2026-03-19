# External Integrations

**Analysis Date:** 2026-03-19

## APIs & External Services

**OpenAPI Specification Parsing:**
- Swagger Parser v3 (io.swagger.parser.v3:swagger-parser:2.1.39) - Parses OpenAPI 3.0 spec files
  - SDK/Client: Swagger Parser (`io.swagger.parser.OpenAPIParser`, `io.swagger.v3.oas.models.*`)
  - Auth: None (file-based parsing)
  - Used in: `core/src/main/kotlin/com/avsystem/justworks/core/parser/SpecParser.kt`

**Code Generation:**
- KotlinPoet 2.2.0 (com.squareup:kotlinpoet) - Generates Kotlin source files
  - SDK/Client: KotlinPoet AST builders (`FileSpec`, `ClassSpec`, `FunSpec`, etc.)
  - Auth: None (local code generation)
  - Used in: `core/src/main/kotlin/com/avsystem/justworks/core/gen/` (all generator modules)

## Data Storage

**Databases:**
- Not applicable - Tool is a code generator, not a data application

**File Storage:**
- Local filesystem only - Reads OpenAPI spec files from disk, writes generated Kotlin source files to disk
- Test resources in: `core/src/test/resources/` (YAML and JSON spec files)

**Caching:**
- Gradle build cache - Configured via `org.gradle.caching=true` in `gradle.properties`
- No application-level caching

## Authentication & Identity

**Auth Provider:**
- Not applicable - Tool has no authentication mechanism

## Monitoring & Observability

**Error Tracking:**
- Not integrated - Uses structured error handling via Arrow functional programming library

**Logs:**
- Standard output only - Uses `println` or similar; no logging framework configured
- CI logging: GitHub Actions workflow at `.github/workflows/ci.yml` logs build output

## CI/CD & Deployment

**Hosting:**
- GitHub repository: `https://github.com/AVSystem/justworks.git`

**CI Pipeline:**
- GitHub Actions (`.github/workflows/ci.yml`)
  - Triggers: Push to `master` branch, pull requests to `master`
  - Steps:
    - Checkout code (actions/checkout@v4)
    - Setup Java 21 with Temurin distribution (actions/setup-java@v4)
    - Setup Gradle (gradle/actions/setup-gradle@v4)
    - Run KtLint checks: `./gradlew ktlintCheck`
    - Run tests with coverage: `./gradlew core:koverXmlReport`
    - Upload coverage to Codecov (codecov/codecov-action@v5) on pull requests

**Publishing:**
- Maven Central via `maven-publish` Gradle plugin in `core/build.gradle.kts`
- Publication configured as `<MavenPublication>("maven")` with Java components
- Maven metadata group: `com.avsystem`

## Environment Configuration

**Required env vars:**
- `CODECOV_TOKEN` - GitHub Actions secret for Codecov coverage uploads (optional, only for PR coverage reporting)

**Secrets location:**
- GitHub Actions secrets for `CODECOV_TOKEN` (referenced in `.github/workflows/ci.yml`)

## Webhooks & Callbacks

**Incoming:**
- Not applicable

**Outgoing:**
- Codecov webhook callbacks - Coverage reports sent to Codecov on pull requests via `codecov/codecov-action@v5`

---

*Integration audit: 2026-03-19*
