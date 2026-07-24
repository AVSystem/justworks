# Vendored public OpenAPI specs (for integration tests)

- `swagger-petstore.json` — Swagger Petstore (OpenAPI 3.0.4), https://petstore3.swagger.io/api/v3/openapi.json
- `petstore-expanded.yaml` — OAI example (uses allOf), https://github.com/OAI/OpenAPI-Specification (tag 3.0.4, examples/v3.0/petstore-expanded.yaml). The upstream `NewPet.tag` type typo (`sthciring`) was corrected to `string`.
- `uspto.yaml` — OAI example, real USPTO Data Set API, https://github.com/OAI/OpenAPI-Specification (examples/v3.0/uspto.yaml)

Vendored (not fetched at build time) for deterministic, offline tests.
