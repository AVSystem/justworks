# justworks

A Gradle plugin that generates type-safe Kotlin [Ktor](https://ktor.io/) client code from OpenAPI 3.0 specifications.

## Installation

Add the plugin to your `build.gradle.kts`:

```kotlin
plugins {
    id("com.avsystem.justworks") version "<version>"
}
```

The plugin and core library are published to Maven Central:

- `com.avsystem.justworks:plugin` -- Gradle plugin
- `com.avsystem.justworks:core` -- OpenAPI parser and code generator

## Usage

Configure one or more OpenAPI specs in the `justworks` extension:

```kotlin
justworks {
    specs {
        register("petstore") {
            specFile = file("api/petstore.yaml")
            packageName = "com.example.petstore"
        }
        register("payments") {
            specFile = file("api/payments.yaml")
            packageName = "com.example.payments"
            // Optional: override default sub-packages
            apiPackage = "com.example.payments.client"
            modelPackage = "com.example.payments.dto"
        }
    }
}
```

Each spec gets its own Gradle task (`justworksGenerate<Name>`) and output directory. Run all generators at once with:

```bash
./gradlew justworksGenerateAll
```

Generated sources are automatically wired into Kotlin source sets, so `compileKotlin` depends on code generation -- no
extra configuration needed.

### Configuration options

| Property       | Required | Default              | Description                            |
|----------------|----------|----------------------|----------------------------------------|
| `specFile`     | Yes      | --                   | Path to the OpenAPI spec (.yaml/.json) |
| `packageName`  | Yes      | --                   | Base package for generated code        |
| `apiPackage`   | No       | `$packageName.api`   | Package for API client classes         |
| `modelPackage` | No       | `$packageName.model` | Package for model/data classes         |

## Supported OpenAPI Features

### Schema Types

| OpenAPI type           | Format      | Kotlin type      |
|------------------------|-------------|------------------|
| `string`               | *(default)* | `String`         |
| `string`               | `date`      | `LocalDate`      |
| `string`               | `date-time` | `Instant`        |
| `string`               | `uuid`      | `Uuid`           |
| `string`               | `byte`      | `ByteArray`      |
| `string`               | `binary`    | `ByteArray`      |
| `integer`              | `int32`     | `Int`            |
| `integer`              | `int64`     | `Long`           |
| `number`               | `float`     | `Float`          |
| `number`               | `double`    | `Double`         |
| `boolean`              | --          | `Boolean`        |
| `array`                | --          | `List<T>`        |
| `object`               | --          | data class       |
| `additionalProperties` | --          | `Map<String, T>` |

Other string formats (`email`, `uri`, `hostname`, etc.) are kept as `String`.

### Composition & Polymorphism

| Feature                       | Support | Generated Kotlin                                                                   |
|-------------------------------|---------|------------------------------------------------------------------------------------|
| `allOf`                       | Full    | Merged data class (properties from all schemas)                                    |
| `oneOf` with discriminator    | Full    | `sealed interface` + variant data classes with `@JsonClassDiscriminator`           |
| `anyOf` with discriminator    | Full    | Same as `oneOf`                                                                    |
| `anyOf` without discriminator | Partial | `sealed interface` + `JsonContentPolymorphicSerializer` (field-presence heuristic) |
| Discriminator mapping         | Full    | `@SerialName` on variants                                                          |

A `SerializersModule` is auto-generated when discriminated polymorphic types are present.

### Enums, Nullability, Defaults

- **Enums** -- generated as `enum class` with `@SerialName` per constant. String and integer backing types supported.
- **Required properties** -- non-nullable constructor parameters.
- **Optional properties** -- nullable with `= null` default.
- **Default values** -- supported for primitives, dates, and enum references.
- **Inline schemas** -- auto-named from context (e.g. `CreatePetRequest`) and deduplicated by structure.

### Parameters & Request Bodies

| Feature                         | Status            |
|---------------------------------|-------------------|
| Path parameters                 | Supported         |
| Query parameters                | Supported         |
| Header parameters               | Supported         |
| Cookie parameters               | Not yet supported |
| `application/json` request body | Supported         |
| Form data / multipart           | Not supported     |

### Security Schemes

The plugin reads security schemes defined in the OpenAPI spec and generates authentication handling automatically.
Only schemes referenced in the top-level `security` requirement are included.

| Scheme type | Location | Generated constructor parameter(s)                             |
|-------------|----------|----------------------------------------------------------------|
| HTTP Bearer | Header   | `token: () -> String` (or `{name}Token` if multiple)           |
| HTTP Basic  | Header   | `{name}Username: () -> String`, `{name}Password: () -> String` |
| API Key     | Header   | `{name}Key: () -> String`                                      |
| API Key     | Query    | `{name}Key: () -> String`                                      |

All auth parameters are `() -> String` lambdas, called on every request. This lets you supply providers that refresh
credentials automatically.

The generated `ApiClientBase` contains an `applyAuth()` method that applies all credentials to each request:

- Bearer tokens are sent as `Authorization: Bearer {token}` headers
- Basic auth is sent as `Authorization: Basic {base64(username:password)}` headers
- Header API keys are appended to request headers using the parameter name from the spec
- Query API keys are appended to URL query parameters

### Not Supported

Callbacks, links, webhooks, XML content types, OpenAPI vendor extensions (`x-*`), OAuth 2.0, OpenID Connect, and
cookie-based API keys are not processed. The plugin logs warnings for callbacks and links found in a spec.

## Generated Code Structure

The plugin produces two categories of output: **shared types** (generated once) and **per-spec types** (generated per
registered spec).

### Output Layout

```
build/generated/justworks/
├── shared/kotlin/
│   └── com/avsystem/justworks/
│       ├── ApiClientBase.kt          # Abstract base class + auth handling + helper extensions
│       ├── HttpError.kt              # HttpErrorType enum + HttpError data class
│       └── HttpSuccess.kt            # HttpSuccess<T> data class
│
└── specName/
    └── com/example/
        ├── model/
        │   ├── Pet.kt                # @Serializable data class
        │   ├── PetStatus.kt          # @Serializable enum class
        │   ├── Shape.kt              # sealed interface (oneOf/anyOf)
        │   ├── Circle.kt             # variant data class : Shape
        │   ├── UuidSerializer.kt     # (if spec uses UUID fields)
        │   └── SerializersModule.kt  # (if spec has polymorphic types)
        └── api/
            └── PetsApi.kt            # Client class per OpenAPI tag
```

### Model Package

- **Data classes** -- one per named schema. Properties annotated with `@SerialName`, sorted required-first.
- **Enums** -- constants in `UPPER_SNAKE_CASE` with `@SerialName` for the wire value.
- **Sealed interfaces** -- for `oneOf`/`anyOf` schemas. Variants are separate data classes implementing the interface.
- **SerializersModule** -- top-level `val generatedSerializersModule` registering all polymorphic hierarchies. Only
  generated when needed.

### API Package

One client class per OpenAPI tag (e.g. `pets` tag -> `PetsApi`). Untagged endpoints go to `DefaultApi`.

Each endpoint becomes a `suspend` function with `context(Raise<HttpError>)` that returns `HttpSuccess<T>`.

### Gradle Tasks

| Task                      | Description                                           |
|---------------------------|-------------------------------------------------------|
| `justworksSharedTypes`    | Generates shared types (once per build)               |
| `justworksGenerate<Name>` | Generates code for one spec (depends on shared types) |
| `justworksGenerateAll`    | Aggregate -- triggers all spec tasks                  |

`compileKotlin` depends on `justworksGenerateAll`, so generation runs automatically.

## Customization

### Package Overrides

Override the default `api` / `model` sub-packages per spec:

```kotlin
justworks {
    specs {
        register("petstore") {
            specFile = file("api/petstore.yaml")
            packageName = "com.example.petstore"
            apiPackage = "com.example.petstore.client"   // default: packageName.api
            modelPackage = "com.example.petstore.dto"     // default: packageName.model
        }
    }
}
```

### Ktor Engine

The generated `HttpClient` is created without an explicit engine, so Ktor uses whichever engine is on the classpath.
Switch engines by changing your dependency:

```kotlin
dependencies {
    // Pick one:
    implementation("io.ktor:ktor-client-cio:3.1.1")     // CIO (default, pure Kotlin)
    implementation("io.ktor:ktor-client-okhttp:3.1.1")   // OkHttp
    implementation("io.ktor:ktor-client-apache:3.1.1")   // Apache
}
```

### JSON Configuration

The internal `Json` instance uses default settings (`ignoreUnknownKeys = false`, `isLenient = false`). If you need a
custom `Json` for use outside the client, configure it yourself and include the generated `SerializersModule` when your
spec has polymorphic types:

```kotlin
val json = Json {
    ignoreUnknownKeys = true
    serializersModule = com.example.petstore.model.generatedSerializersModule
}
```

## Generated Client Usage

After running code generation, the plugin produces type-safe Kotlin client classes.
Here is how to use them.

### Dependencies

Add the required runtime dependencies:

```kotlin
dependencies {
    implementation("io.ktor:ktor-client-core:3.1.1")
    implementation("io.ktor:ktor-client-cio:3.1.1")       // or another engine (OkHttp, Apache, etc.)
    implementation("io.ktor:ktor-client-content-negotiation:3.1.1")
    implementation("io.ktor:ktor-serialization-kotlinx-json:3.1.1")
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.8.1")
}
```

### Creating the Client

Each generated client extends `ApiClientBase` and creates its own pre-configured `HttpClient` internally.
You only need to provide the base URL and authentication credentials (if the spec defines security schemes).

Class names are derived from OpenAPI tags as `<Tag>Api` (e.g., a `pets` tag produces `PetsApi`). Untagged endpoints go
to `DefaultApi`.

**Single Bearer token** (most common case):

```kotlin
val client = PetsApi(
    baseUrl = "https://api.example.com",
    token = { "your-bearer-token" },
)
```

The `token` parameter is a `() -> String` lambda called on every request. This lets you supply a provider that refreshes
automatically:

```kotlin
val client = PetsApi(
    baseUrl = "https://api.example.com",
    token = { tokenStore.getAccessToken() },
)
```

**Multiple security schemes** -- constructor parameters are derived from the scheme names defined in the spec:

```kotlin
val client = PetsApi(
    baseUrl = "https://api.example.com",
    bearerToken = { tokenStore.getAccessToken() },
    internalApiKey = { secrets.getApiKey() },
)
```

**Basic auth**:

```kotlin
val client = PetsApi(
    baseUrl = "https://api.example.com",
    basicUsername = { "user" },
    basicPassword = { "pass" },
)
```

See [Security Schemes](#security-schemes) for the full mapping of scheme types to constructor parameters.

The client implements `Closeable` -- call `client.close()` when done to release HTTP resources.

### Making Requests

Every endpoint becomes a `suspend` function on the client that returns `HttpSuccess<T>` on success and throws
`HttpError` on failure:

```kotlin
val result: HttpSuccess<List<Pet>> = client.listPets(limit = 10)
println(result.body) // the deserialized response body
println(result.code) // the HTTP status code
```

Path, query, and header parameters map to function arguments. Optional parameters default to `null`:

```kotlin
val result = client.findPets(status = "available", limit = 20)
```

### Error Handling

Generated endpoints throw `HttpError` (a `RuntimeException` subclass) for non-2xx responses and network failures.
Use standard `try`/`catch` to handle errors:

```kotlin
try {
    val success = client.getPet(petId = 123)
    println("Found: ${success.body.name}")
} catch (e: HttpError) {
    when (e.type) {
        HttpErrorType.Client -> println("Client error ${e.code}: ${e.message}")
        HttpErrorType.Server -> println("Server error ${e.code}: ${e.message}")
        HttpErrorType.Redirect -> println("Redirect ${e.code}")
        HttpErrorType.Network -> println("Connection failed: ${e.message}")
    }
}
```

`HttpError` is a data class with the following fields:

| Field     | Type            | Description                                  |
|-----------|-----------------|----------------------------------------------|
| `code`    | `Int`           | HTTP status code (or `0` for network errors) |
| `message` | `String`        | Response body text or exception message      |
| `type`    | `HttpErrorType` | Category of the error                        |

`HttpErrorType` categorizes errors:

| `HttpErrorType` value | Covered statuses / scenario        |
|-----------------------|------------------------------------|
| `Client`              | HTTP 4xx client errors             |
| `Server`              | HTTP 5xx server errors             |
| `Redirect`            | HTTP 3xx redirect responses        |
| `Network`             | I/O failures, timeouts, DNS issues |

Network errors (connection timeouts, DNS failures) are caught and reported as
`HttpError(code = 0, ..., type = HttpErrorType.Network)` instead of propagating raw exceptions.

## Publishing

Releases are published to [Maven Central](https://central.sonatype.com/) automatically when a version tag (`v*`) is
pushed. The CD pipeline runs CI checks first, then publishes signed artifacts via
the [vanniktech maven-publish](https://github.com/vanniktech/gradle-maven-publish-plugin) plugin.

To trigger a release:

```bash
git tag v1.0.0
git push origin v1.0.0
```

The version is derived from the tag (stripping the `v` prefix). Without a tag, the version defaults to `0.0.1-SNAPSHOT`.

## Development

Requires **JDK 21+**.

```bash
# Run tests and linting
./gradlew check

# Run only unit tests
./gradlew test

# Run functional tests (plugin module)
./gradlew plugin:functionalTest

# Lint check
./gradlew ktlintCheck
```

## License

Apache License 2.0
