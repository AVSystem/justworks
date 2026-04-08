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

### Not Supported

Callbacks, links, webhooks, XML content types, and OpenAPI vendor extensions (`x-*`) are not processed. The plugin logs
warnings for callbacks and links found in a spec.

## Generated Code Structure

The plugin produces two categories of output: **shared types** (generated once) and **per-spec types** (generated per
registered spec).

### Output Layout

```
build/generated/justworks/
├── shared/kotlin/
│   └── com/avsystem/justworks/
│       ├── ApiClientBase.kt          # Abstract base class + helper extensions
│       ├── HttpResult.kt             # HttpResult<E, T> sealed interface
│       ├── HttpError.kt              # HttpError<B> sealed class hierarchy
│       └── HttpSuccess.kt            # HttpSuccess<T> data class
│
└── specName/
    └── com/example/
        ├── model/
        │   ├── Pet.kt                # @Serializable data class
        │   ├── PetStatus.kt          # @Serializable enum class
        │   ├── Shape.kt              # sealed interface + nested variants (oneOf/anyOf)
        │   ├── UuidSerializer.kt     # (if spec uses UUID fields)
        │   └── SerializersModule.kt  # (if spec has polymorphic types)
        └── api/
            └── PetsApi.kt            # Client class per OpenAPI tag
```

### Model Package

- **Data classes** -- one per named schema. Properties annotated with `@SerialName`, sorted required-first.
- **Enums** -- constants in `UPPER_SNAKE_CASE` with `@SerialName` for the wire value.
- **Sealed interfaces** -- for `oneOf`/`anyOf` schemas. Discriminated variants are nested inside the sealed interface file.
- **SerializersModule** -- top-level `val generatedSerializersModule` registering all polymorphic hierarchies. Only
  generated when needed.

### API Package

One client class per OpenAPI tag (e.g. `pets` tag -> `PetsApi`). Untagged endpoints go to `DefaultApi`.

Each endpoint becomes a `suspend` function that returns `HttpResult<E, T>` -- a sealed interface implemented by
`HttpError<E>` (for failures) and `HttpSuccess<T>` (for successes). No Arrow or other external runtime dependencies are required.

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
You only need to provide the base URL and authentication credentials.

Class names are derived from OpenAPI tags as `<Tag>Api` (e.g., a `pets` tag produces `PetsApi`). Untagged endpoints go
to `DefaultApi`.

```kotlin
val client = PetsApi(
    baseUrl = "https://api.example.com",
    token = { "your-bearer-token" },
)
```

The `token` parameter is a `() -> String` lambda called on every request and sent as a `Bearer` token in the
`Authorization` header. This lets you supply a provider that refreshes automatically:

```kotlin
val client = PetsApi(
    baseUrl = "https://api.example.com",
    token = { tokenStore.getAccessToken() },
)
```

The client implements `Closeable` -- call `client.close()` when done to release HTTP resources.

### Making Requests

Every endpoint becomes a `suspend` function on the client that returns `HttpResult<E, T>`:

```kotlin
val result: HttpResult<JsonElement, List<Pet>> = client.listPets(limit = 10)

when (result) {
    is HttpSuccess -> {
        println(result.body) // the deserialized response body
        println(result.code) // the HTTP status code
    }
    is HttpError -> {
        println("Error ${result.code}: ${result.body}")
    }
}
```

Path, query, and header parameters map to function arguments. Optional parameters default to `null`:

```kotlin
val result = client.findPets(status = "available", limit = 20)
```

### Error Handling

`HttpResult<E, T>` is a sealed interface with two branches:

- `HttpSuccess<T>` -- successful response (2xx) with a deserialized body
- `HttpError<E>` -- sealed class hierarchy for all error cases

`HttpError<E>` provides typed subtypes for common HTTP error codes:

| Subtype                        | HTTP status | Description           |
|--------------------------------|-------------|-----------------------|
| `HttpError.BadRequest`         | 400         | Bad request           |
| `HttpError.Unauthorized`       | 401         | Unauthorized          |
| `HttpError.Forbidden`          | 403         | Forbidden             |
| `HttpError.NotFound`           | 404         | Not found             |
| `HttpError.MethodNotAllowed`   | 405         | Method not allowed    |
| `HttpError.Conflict`           | 409         | Conflict              |
| `HttpError.Gone`               | 410         | Gone                  |
| `HttpError.UnprocessableEntity`| 422         | Unprocessable entity  |
| `HttpError.TooManyRequests`    | 429         | Too many requests     |
| `HttpError.InternalServerError`| 500         | Internal server error |
| `HttpError.BadGateway`         | 502         | Bad gateway           |
| `HttpError.ServiceUnavailable` | 503         | Service unavailable   |
| `HttpError.Other`              | *any other* | Catchall with code    |
| `HttpError.Network`            | --          | I/O or timeout        |

Each error subtype carries a nullable `body: E?` with the deserialized error response (or `null` if deserialization
failed), plus an `code: Int` property.

```kotlin
when (result) {
    is HttpSuccess -> println("Pet: ${result.body.name}")
    is HttpError.NotFound -> println("Pet not found")
    is HttpError.Unauthorized -> println("Please log in")
    is HttpError.Network -> println("Connection failed: ${result.cause}")
    is HttpError -> println("HTTP ${result.code}: ${result.body}")
}
```

Network errors (connection timeouts, DNS failures) are caught and reported as `HttpError.Network` instead of
propagating exceptions.

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
