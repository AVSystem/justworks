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
You only need to provide the base URL and authentication credentials:

```kotlin
val client = PetstoreApi(
    baseUrl = "https://api.example.com",
    token = { "your-bearer-token" },
)
```

Auth parameters are lambdas (`() -> String`), so you can supply a token provider that refreshes automatically:

```kotlin
val client = PetstoreApi(
    baseUrl = "https://api.example.com",
    token = { tokenStore.getAccessToken() },
)
```

The client implements `Closeable` -- call `client.close()` when done to release HTTP resources.

### Authentication

The generated constructor signature depends on the security schemes defined in your OpenAPI spec:

**Bearer Token** (single scheme):

```kotlin
val client = PetstoreApi(
    baseUrl = "https://api.example.com",
    token = { "your-bearer-token" },
)
```

**API Key** (sent as header or query parameter based on the spec):

```kotlin
val client = PetstoreApi(
    baseUrl = "https://api.example.com",
    myApiKey = { "your-api-key" },
)
```

**HTTP Basic**:

```kotlin
val client = PetstoreApi(
    baseUrl = "https://api.example.com",
    myAuthUsername = { "user" },
    myAuthPassword = { "pass" },
)
```

**No Authentication** (spec has no security schemes):

```kotlin
val client = PetstoreApi(
    baseUrl = "https://api.example.com",
)
```

When the spec defines multiple security schemes, the constructor includes a parameter for each one.

### Making Requests

Every endpoint becomes a `suspend` function on the client. The return type is `HttpResult<E, T>`, where `E` is the error body type and `T` is the success body type:

```kotlin
val result: HttpResult<JsonElement, List<Pet>> = client.listPets(limit = 10)
```

Path, query, and header parameters map to function arguments. Optional parameters default to `null`:

```kotlin
val result = client.findPets(status = "available", limit = 20)
```

### Error Handling

`HttpResult<E, T>` is a typealias for `Either<HttpError<E>, HttpSuccess<T>>` (using [Arrow](https://arrow-kt.io/)).
Every API call returns a result instead of throwing exceptions:

```kotlin
when (val result = client.getPet(petId = 123)) {
    is Either.Right -> {
        val pet = result.value.body
        println("Found: ${pet.name}")
    }
    is Either.Left -> when (val error = result.value) {
        is HttpError.NotFound -> println("Pet not found")
        is HttpError.Unauthorized -> println("Auth required")
        is HttpError.Network -> println("Connection failed: ${error.cause}")
        else -> println("Error ${error.statusCode}: ${error.body}")
    }
}
```

`HttpError` covers specific HTTP status codes as sealed subtypes:

| Subtype                | Status |
|------------------------|--------|
| `BadRequest`           | 400    |
| `Unauthorized`         | 401    |
| `Forbidden`            | 403    |
| `NotFound`             | 404    |
| `MethodNotAllowed`     | 405    |
| `Conflict`             | 409    |
| `Gone`                 | 410    |
| `UnprocessableEntity`  | 422    |
| `TooManyRequests`      | 429    |
| `InternalServerError`  | 500    |
| `BadGateway`           | 502    |
| `ServiceUnavailable`   | 503    |
| `Network`              | --     |
| `Other`                | any    |

Network errors (connection timeouts, DNS failures) are caught and wrapped in `HttpError.Network` instead of propagating exceptions.

### Serialization Setup

Generated models use `@Serializable` from kotlinx.serialization. The client sets up JSON content negotiation internally via the `createHttpClient()` method.

If your spec uses polymorphic types (`oneOf` / `anyOf` with discriminators), the generator produces a `SerializersModule` that is automatically registered with the internal JSON instance. No manual serialization configuration is needed.

If you need to customize the JSON configuration for external use (e.g., parsing API responses outside the client), use the same settings:

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

### Multi-Spec Configuration

When your project consumes multiple APIs, register each spec separately.
The plugin generates independent client classes per spec:

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
            apiPackage = "com.example.payments.client"
            modelPackage = "com.example.payments.dto"
        }
    }
}
```

Each spec gets its own Gradle task (`justworksGenerate<Name>`) and output directory. The generated clients are independent and can be used side by side.

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
