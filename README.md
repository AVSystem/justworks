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

Generated sources are automatically wired into Kotlin source sets, so `compileKotlin` depends on code generation -- no extra configuration needed.

### Configuration options

| Property       | Required | Default                  | Description                          |
|----------------|----------|--------------------------|--------------------------------------|
| `specFile`     | Yes      | --                       | Path to the OpenAPI spec (.yaml/.json) |
| `packageName`  | Yes      | --                       | Base package for generated code      |
| `apiPackage`   | No       | `$packageName.api`       | Package for API client classes       |
| `modelPackage` | No       | `$packageName.model`     | Package for model/data classes       |

## Generated Client Usage

After running code generation, the plugin produces type-safe Kotlin client classes.
Here is how to use them.

### Dependencies

Add the required runtime dependencies to your consuming project:

```kotlin
dependencies {
    implementation("io.ktor:ktor-client-core:3.1.1")
    implementation("io.ktor:ktor-client-cio:3.1.1")       // or another engine (OkHttp, Apache, etc.)
    implementation("io.ktor:ktor-client-content-negotiation:3.1.1")
    implementation("io.ktor:ktor-serialization-kotlinx-json:3.1.1")
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.8.1")
    implementation("io.arrow-kt:arrow-core:2.1.2")
}
```

### Creating the Client

Each generated client extends `ApiClientBase` and creates its own pre-configured `HttpClient` internally.
You only need to provide the base URL and authentication credentials.

Class names are derived from OpenAPI tags as `<Tag>Api` (e.g., a `pets` tag produces `PetsApi`). Untagged endpoints go to `DefaultApi`.

```kotlin
val client = PetsApi(
    baseUrl = "https://api.example.com",
    token = { "your-bearer-token" },
)
```

The `token` parameter is a `() -> String` lambda called on every request and sent as a `Bearer` token in the `Authorization` header. This lets you supply a provider that refreshes automatically:

```kotlin
val client = PetsApi(
    baseUrl = "https://api.example.com",
    token = { tokenStore.getAccessToken() },
)
```

The client implements `Closeable` -- call `client.close()` when done to release HTTP resources.

### Making Requests

Every endpoint becomes a `suspend` function on the client. Functions use Arrow's [Raise](https://arrow-kt.io/docs/typed-errors/) for structured error handling -- they require a `context(Raise<HttpError>)` and return `HttpSuccess<T>` on success:

```kotlin
// Inside a Raise<HttpError> context (e.g., within either { ... })
val result: HttpSuccess<List<Pet>> = client.listPets(limit = 10)
println(result.body) // the deserialized response body
println(result.code) // the HTTP status code
```

Path, query, and header parameters map to function arguments. Optional parameters default to `null`:

```kotlin
val result = client.findPets(status = "available", limit = 20)
```

### Error Handling

Generated endpoints use [Arrow's Raise](https://arrow-kt.io/docs/typed-errors/) -- errors are raised, not returned as `Either`. Use Arrow's `either { ... }` block to obtain an `Either<HttpError, HttpSuccess<T>>`:

```kotlin
val result: Either<HttpError, HttpSuccess<Pet>> = either {
    client.getPet(petId = 123)
}

result.fold(
    ifLeft = { error ->
        when (error.type) {
            HttpErrorType.Client -> println("Client error ${error.code}: ${error.message}")
            HttpErrorType.Server -> println("Server error ${error.code}: ${error.message}")
            HttpErrorType.Redirect -> println("Redirect ${error.code}")
            HttpErrorType.Network -> println("Connection failed: ${error.message}")
        }
    },
    ifRight = { success ->
        println("Found: ${success.body.name}")
    }
)
```

`HttpError` is a data class with the following fields:

| Field     | Type            | Description                              |
|-----------|-----------------|------------------------------------------|
| `code`    | `Int`           | HTTP status code (or `0` for network errors) |
| `message` | `String`        | Response body text or exception message  |
| `type`    | `HttpErrorType` | Category of the error                    |

`HttpErrorType` categorizes errors:

| `HttpErrorType` value | Covered statuses / scenario        |
|-----------------------|-------------------------------------|
| `Client`              | HTTP 4xx client errors             |
| `Server`              | HTTP 5xx server errors             |
| `Redirect`            | HTTP 3xx redirect responses        |
| `Network`             | I/O failures, timeouts, DNS issues |

Network errors (connection timeouts, DNS failures) are caught and reported as `HttpError(code = 0, ..., type = HttpErrorType.Network)` instead of propagating exceptions.

### Serialization Setup

Generated models use `@Serializable` from kotlinx.serialization. The client sets up JSON content negotiation internally via the `createHttpClient()` method.

If your spec uses polymorphic types (`oneOf` / `anyOf` with discriminators), the generator produces a `SerializersModule` that is automatically registered with the internal JSON instance. No manual serialization configuration is needed.

The internal `createHttpClient()` uses default `Json` settings (it does not set `ignoreUnknownKeys` or `isLenient`). If you need a `Json` instance for external use (e.g., parsing API responses outside the client), you may want to configure these yourself:

```kotlin
val json = Json {
    ignoreUnknownKeys = true    // recommended: specs may evolve
    isLenient = true            // optional: tolerant parsing
}
```

For polymorphic types, register the generated `SerializersModule`:

```kotlin
val json = Json {
    ignoreUnknownKeys = true
    serializersModule = com.example.petstore.model.generatedSerializersModule
}
```

## Publishing

Releases are published to [Maven Central](https://central.sonatype.com/) automatically when a version tag (`v*`) is pushed. The CD pipeline runs CI checks first, then publishes signed artifacts via the [vanniktech maven-publish](https://github.com/vanniktech/gradle-maven-publish-plugin) plugin.

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
