# Justworks

## What This Is

Kotlin OpenAPI 3.0 client code generator dystrybuowany jako plugin Gradle. Parsuje specyfikacje OpenAPI i generuje kod klienta HTTP oparty na Ktor z serializacją kotlinx.serialization. Przeznaczony dla zespołów Kotlin korzystających z Ktor.

## Core Value

Generator musi poprawnie obsługiwać typowe wzorce OpenAPI 3.0 — schematy, parametry, request/response — produkując kompilujący się, idiomatyczny kod Kotlin.

## Requirements

### Validated

- ✓ Parsowanie specyfikacji OpenAPI 3.0 / Swagger 2.0 (YAML/JSON) — existing
- ✓ Generowanie klas modeli (data class) z komponentów schemas — existing
- ✓ Generowanie klientów HTTP jako suspend funkcji z Ktor — existing
- ✓ Obsługa polimorfizmu oneOf/anyOf z discriminatorem — existing
- ✓ Obsługa enum z @SerialName mapping — existing
- ✓ Deduplikacja inline schemas — existing
- ✓ Generowanie ApiClientBase z konfiguracją HttpClient — existing
- ✓ Obsługa Bearer token authentication — existing
- ✓ Dystrybucja jako Gradle plugin — existing

### Active

- [ ] Naprawienie istniejących bugów zidentyfikowanych w code review
- [ ] Pełniejsza obsługa schematów (allOf composition, nullable, default values)
- [ ] Obsługa parametrów query/path/header/cookie z style/explode
- [ ] Obsługa multipart/form-data i multiple content types
- [ ] Obsługa security schemes (OAuth2, API key, HTTP basic)
- [ ] Obsługa callbacks, links, webhooks (jeśli typowe w realnych API)

### Out of Scope

- Generowanie kodu serwera — projekt skupia się na kliencie
- Wsparcie dla frameworków HTTP innych niż Ktor — Ktor jest jedynym targetem
- OpenAPI 3.1 — skupiamy się na 3.0
- GUI / interfejs graficzny — plugin Gradle wystarczy

## Context

- Architektura trzystopniowa: Parser → Model (ApiSpec) → Generator (KotlinPoet)
- Error handling oparty na Arrow Either/Raise z akumulacją błędów
- TypeRef sealed tree reprezentuje system typów OpenAPI
- Polimorfizm: discriminator → @JsonClassDiscriminator, anyOf bez discriminatora → JsonContentPolymorphicSerializer
- Generator nie ma własnego error handling — zakłada poprawny ApiSpec
- Codebase map dostępny w .planning/codebase/

## Constraints

- **Tech stack**: Kotlin 2.3.0, Java 21, KotlinPoet 2.2.0, Swagger Parser 2.1.39
- **Output format**: Ktor HTTP client + kotlinx.serialization — nie zmieniamy
- **Zakres OAS**: OpenAPI 3.0 spec, typowe przypadki z realnych API
- **Podejście**: Bug fixy i nowe funkcje równolegle w każdej fazie

## Key Decisions

| Decision | Rationale | Outcome |
|----------|-----------|---------|
| Typowe przypadki, nie pełna spec | Pragmatyczne podejście — pokrycie 90% realnych API zamiast 100% spec | — Pending |
| Bugi i nowe funkcje równolegle | Stabilność i rozwój idą w parze | — Pending |
| Ktor jako jedyny target | Fokus na jednym frameworku zapewnia jakość | ✓ Good |

---
*Last updated: 2026-03-19 after initialization*
