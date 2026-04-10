package com.avsystem.justworks.core.gen

import com.avsystem.justworks.core.gen.client.ClientGenerator
import com.avsystem.justworks.core.model.ApiKeyLocation
import com.avsystem.justworks.core.model.ApiSpec
import com.avsystem.justworks.core.model.ContentType
import com.avsystem.justworks.core.model.Endpoint
import com.avsystem.justworks.core.model.HttpMethod
import com.avsystem.justworks.core.model.Parameter
import com.avsystem.justworks.core.model.ParameterLocation
import com.avsystem.justworks.core.model.PrimitiveType
import com.avsystem.justworks.core.model.PropertyModel
import com.avsystem.justworks.core.model.RequestBody
import com.avsystem.justworks.core.model.Response
import com.avsystem.justworks.core.model.SecurityScheme
import com.avsystem.justworks.core.model.TypeRef
import com.squareup.kotlinpoet.ExperimentalKotlinPoetApi
import com.squareup.kotlinpoet.FileSpec
import com.squareup.kotlinpoet.KModifier
import com.squareup.kotlinpoet.ParameterizedTypeName
import com.squareup.kotlinpoet.TypeSpec
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class ClientGeneratorTest {
    private val apiPackage = "com.example.api"
    private val modelPackage = "com.example.model"

    private fun generate(spec: ApiSpec, hasPolymorphicTypes: Boolean = false): List<FileSpec> = context(
        Hierarchy(ModelPackage(modelPackage)).apply {
            addSchemas(spec.schemas)
        },
        ApiPackage(apiPackage),
        NameRegistry(),
    ) {
        ClientGenerator.generate(spec, hasPolymorphicTypes)
    }

    private fun spec(vararg endpoints: Endpoint) = spec(endpoints.toList())

    private fun spec(endpoints: List<Endpoint>, securitySchemes: List<SecurityScheme> = emptyList()) = ApiSpec(
        title = "Test",
        version = "1.0",
        endpoints = endpoints.toList(),
        schemas = emptyList(),
        enums = emptyList(),
        securitySchemes = securitySchemes,
    )

    private fun endpoint(
        path: String = "/pets",
        method: HttpMethod = HttpMethod.GET,
        operationId: String = "listPets",
        summary: String? = null,
        description: String? = null,
        tags: List<String> = listOf("Pets"),
        parameters: List<Parameter> = emptyList(),
        requestBody: RequestBody? = null,
        responses: Map<String, Response> =
            mapOf(
                "200" to Response("200", "OK", TypeRef.Reference("Pet")),
            ),
    ) = Endpoint(
        path = path,
        method = method,
        operationId = operationId,
        summary = summary,
        description = description,
        tags = tags,
        parameters = parameters,
        requestBody = requestBody,
        responses = responses,
    )

    private fun clientClass(vararg endpoints: Endpoint): TypeSpec = clientClass(endpoints.toList())

    private fun clientClass(endpoints: List<Endpoint>, securitySchemes: List<SecurityScheme> = emptyList()): TypeSpec {
        val files = generate(spec(endpoints, securitySchemes))
        return files
            .first()
            .members
            .filterIsInstance<TypeSpec>()
            .first()
    }

    // -- CLNT-01: One client class per tag --

    @Test
    fun `generates one client class per tag`() {
        val endpoints = arrayOf(
            endpoint(operationId = "listPets", tags = listOf("Pets")),
            endpoint(path = "/store", operationId = "getInventory", tags = listOf("Store")),
        )
        val files = generate(spec(*endpoints))
        assertEquals(2, files.size)
        val classNames =
            files
                .map {
                    it.members
                        .filterIsInstance<TypeSpec>()
                        .first()
                        .name!!
                }.sorted()
        assertEquals(listOf("PetsApi", "StoreApi"), classNames)
    }

    // -- CLNT-02: Endpoint functions are suspend --

    @Test
    fun `endpoint functions are suspend`() {
        val cls = clientClass(endpoint())
        val funSpec = cls.funSpecs.first { it.name == "listPets" }
        assertTrue(KModifier.SUSPEND in funSpec.modifiers, "Expected SUSPEND modifier")
    }

    // -- CLNT-03: All HTTP methods --

    @Test
    fun `supports all HTTP methods`() {
        val methods = listOf(
            HttpMethod.GET to "getPet",
            HttpMethod.POST to "createPet",
            HttpMethod.PUT to "updatePet",
            HttpMethod.DELETE to "deletePet",
            HttpMethod.PATCH to "patchPet",
        )
        val endpoints = methods.map { (method, opId) -> endpoint(method = method, operationId = opId) }.toTypedArray()
        val cls = clientClass(*endpoints)
        val funBodies = cls.funSpecs.associate { it.name to it.body.toString() }
        assertTrue(
            funBodies["getPet"]!!.contains("request.get(") || funBodies["getPet"]!!.contains("request.`get`("),
            "GET method expected",
        )
        assertTrue(
            funBodies["createPet"]!!.contains("request.post(") || funBodies["createPet"]!!.contains("request.`post`("),
            "POST method expected",
        )
        assertTrue(
            funBodies["updatePet"]!!.contains("request.put(") || funBodies["updatePet"]!!.contains("request.`put`("),
            "PUT method expected",
        )
        assertTrue(
            funBodies["deletePet"]!!.contains("request.delete(") ||
                funBodies["deletePet"]!!.contains("request.`delete`("),
            "DELETE method expected",
        )
        assertTrue(
            funBodies["patchPet"]!!.contains("request.patch(") || funBodies["patchPet"]!!.contains("request.`patch`("),
            "PATCH method expected",
        )
    }

    // -- CLNT-04: Path parameters become function parameters --

    @Test
    fun `path parameters become function parameters`() {
        val ep =
            endpoint(
                path = "/pets/{petId}",
                operationId = "getPet",
                parameters = listOf(
                    Parameter("petId", ParameterLocation.PATH, true, TypeRef.Primitive(PrimitiveType.LONG), null),
                ),
            )
        val cls = clientClass(ep)
        val funSpec = cls.funSpecs.first { it.name == "getPet" }
        val param = funSpec.parameters.first { it.name == "petId" }
        assertEquals("kotlin.Long", param.type.toString())
    }

    // -- CLNT-05: Query parameters become function parameters --

    @Test
    fun `query parameters become function parameters`() {
        val ep =
            endpoint(
                operationId = "listPets",
                parameters =
                    listOf(
                        Parameter("limit", ParameterLocation.QUERY, true, TypeRef.Primitive(PrimitiveType.INT), null),
                    ),
            )
        val cls = clientClass(ep)
        val funSpec = cls.funSpecs.first { it.name == "listPets" }
        val param = funSpec.parameters.first { it.name == "limit" }
        assertEquals("kotlin.Int", param.type.toString())
    }

    // -- CLNT-06: Optional query parameters default to null --

    @Test
    fun `optional query parameters default to null`() {
        val ep = endpoint(
            operationId = "listPets",
            parameters =
                listOf(
                    Parameter("limit", ParameterLocation.QUERY, false, TypeRef.Primitive(PrimitiveType.INT), null),
                ),
        )
        val cls = clientClass(ep)
        val funSpec = cls.funSpecs.first { it.name == "listPets" }
        val param = funSpec.parameters.first { it.name == "limit" }
        assertTrue(param.type.isNullable, "Optional query param should be nullable")
        assertEquals("null", param.defaultValue.toString())
    }

    // -- CLNT-07: Request body becomes function parameter --

    @Test
    fun `request body becomes function parameter`() {
        val ep = endpoint(
            method = HttpMethod.POST,
            operationId = "createPet",
            requestBody = RequestBody(true, ContentType.JSON_CONTENT_TYPE, TypeRef.Reference("Pet")),
        )
        val cls = clientClass(ep)
        val funSpec = cls.funSpecs.first { it.name == "createPet" }
        val bodyParam = funSpec.parameters.first { it.name == "body" }
        assertEquals("com.example.model.Pet", bodyParam.type.toString())
    }

    // -- CLNT-08: Return type is HttpResult parameterized --

    @Test
    fun `return type is HttpResult parameterized`() {
        val cls = clientClass(endpoint())
        val funSpec = cls.funSpecs.first { it.name == "listPets" }
        val returnType = funSpec.returnType
        assertNotNull(returnType)
        assertTrue(returnType is ParameterizedTypeName, "Expected ParameterizedTypeName")
        assertEquals("com.avsystem.justworks.HttpResult", returnType.rawType.toString())
        assertEquals(
            "kotlinx.serialization.json.JsonElement",
            returnType.typeArguments[0].toString(),
            "Expected JsonElement as error type",
        )
        assertEquals(
            "com.example.model.Pet",
            returnType.typeArguments[1].toString(),
            "Expected Pet as success body type",
        )
    }

    // -- ERR-01: No Raise context on endpoint functions --

    @OptIn(ExperimentalKotlinPoetApi::class)
    @Test
    fun `endpoint functions have no context parameters`() {
        val cls = clientClass(endpoint())
        val funSpec = cls.funSpecs.first { it.name == "listPets" }
        assertTrue(funSpec.contextParameters.isEmpty(), "Expected no context parameters")
    }

    // -- CLNT-09: Header parameters become function parameters --

    @Test
    fun `header parameters become function parameters`() {
        val ep = endpoint(
            operationId = "listPets",
            parameters =
                listOf(
                    Parameter(
                        "X-Request-Id",
                        ParameterLocation.HEADER,
                        true,
                        TypeRef.Primitive(PrimitiveType.STRING),
                        null,
                    ),
                ),
        )
        val cls = clientClass(ep)
        val funSpec = cls.funSpecs.first { it.name == "listPets" }
        val param = funSpec.parameters.first { it.name == "xRequestId" }
        assertEquals("kotlin.String", param.type.toString())
    }

    @Test
    fun `header parameters are emitted inside headers block`() {
        val ep = endpoint(
            operationId = "listPets",
            parameters =
                listOf(
                    Parameter(
                        "X-Request-Id",
                        ParameterLocation.HEADER,
                        true,
                        TypeRef.Primitive(PrimitiveType.STRING),
                        null,
                    ),
                ),
        )
        val cls = clientClass(ep)
        val funSpec = cls.funSpecs.first { it.name == "listPets" }
        val body = funSpec.body.toString()
        assertTrue(body.contains("headers"), "Expected headers block in generated body")
        assertTrue(body.contains("append(\"X-Request-Id\""), "Expected header append inside headers block")
    }

    // -- CLNT-10: Client constructor has baseUrl parameter --

    @Test
    fun `client constructor has baseUrl parameter`() {
        val cls = clientClass(endpoint())
        val constructor = assertNotNull(cls.primaryConstructor)
        val baseUrl = constructor.parameters.first { it.name == "baseUrl" }
        assertEquals("kotlin.String", baseUrl.type.toString())
    }

    // -- No security: constructor has only baseUrl --

    @Test
    fun `no security schemes generates constructor with only baseUrl`() {
        val cls = clientClass(endpoint())
        val constructor = assertNotNull(cls.primaryConstructor)
        val paramNames = constructor.parameters.map { it.name }
        assertEquals(listOf("baseUrl"), paramNames)
    }

    // -- Pitfall 3: Untagged endpoints go to DefaultClient --

    @Test
    fun `untagged endpoints go to DefaultApi`() {
        val ep = endpoint(operationId = "healthCheck", tags = emptyList())
        val files = generate(spec(ep))
        val className =
            files
                .first()
                .members
                .filterIsInstance<TypeSpec>()
                .first()
                .name
        assertEquals("DefaultApi", className)
    }

    // -- Pitfall 5: Void response uses Unit type parameter --

    @Test
    fun `void response uses HttpResult with Unit type parameter`() {
        val ep = endpoint(
            method = HttpMethod.DELETE,
            operationId = "deletePet",
            responses = mapOf("204" to Response("204", "No content", null)),
        )
        val cls = clientClass(ep)
        val funSpec = cls.funSpecs.first { it.name == "deletePet" }
        val returnType = funSpec.returnType as ParameterizedTypeName
        assertEquals("com.avsystem.justworks.HttpResult", returnType.rawType.toString())
        assertEquals(
            "kotlinx.serialization.json.JsonElement",
            returnType.typeArguments[0].toString(),
            "Expected JsonElement as error type",
        )
        assertEquals(
            "kotlin.Unit",
            returnType.typeArguments[1].toString(),
            "Expected Unit as success body type",
        )
    }

    // -- CONT-03: Response code handling --

    @Test
    fun `201 Created with schema returns typed response`() {
        val ep = endpoint(
            method = HttpMethod.POST,
            operationId = "createPet",
            responses = mapOf(
                "201" to Response("201", "Created", TypeRef.Reference("Pet")),
            ),
        )
        val cls = clientClass(ep)
        val funSpec = cls.funSpecs.first { it.name == "createPet" }
        val returnType = funSpec.returnType as ParameterizedTypeName
        assertEquals("com.avsystem.justworks.HttpResult", returnType.rawType.toString())
        assertEquals("com.example.model.Pet", returnType.typeArguments[1].toString())
    }

    @Test
    fun `mixed 200 and 204 responses uses 200 schema type`() {
        val ep = endpoint(
            method = HttpMethod.DELETE,
            operationId = "removePet",
            responses = mapOf(
                "200" to Response("200", "OK", TypeRef.Reference("Pet")),
                "204" to Response("204", "No content", null),
            ),
        )
        val cls = clientClass(ep)
        val funSpec = cls.funSpecs.first { it.name == "removePet" }
        val returnType = funSpec.returnType as ParameterizedTypeName
        assertEquals("com.example.model.Pet", returnType.typeArguments[1].toString())
    }

    // -- Client class extends ApiClientBase --

    @Test
    fun `client class extends ApiClientBase`() {
        val cls = clientClass(endpoint())
        assertEquals("com.avsystem.justworks.ApiClientBase", cls.superclass.toString())
    }

    // -- SER-01: Polymorphic spec wires SerializersModule --

    @Test
    fun `polymorphic spec wires serializersModule in createHttpClient call`() {
        val files = generate(spec(endpoint()), hasPolymorphicTypes = true)
        val clientProperty = files
            .first()
            .members
            .filterIsInstance<TypeSpec>()
            .first()
            .propertySpecs
            .first { it.name == "client" }
        val clientInitializer = clientProperty.initializer.toString()
        assertTrue(
            clientInitializer.contains("generatedSerializersModule"),
            "Expected generatedSerializersModule reference",
        )
        assertTrue(clientInitializer.contains("createHttpClient"), "Expected createHttpClient call")
    }

    // -- CONT-01: Multipart form-data code generation --

    @Test
    fun `multipart endpoint generates submitFormWithBinaryData call`() {
        val ep = endpoint(
            method = HttpMethod.POST,
            operationId = "uploadFile",
            requestBody = RequestBody(
                required = true,
                contentType = ContentType.MULTIPART_FORM_DATA,
                schema = TypeRef.Inline(
                    properties = listOf(
                        PropertyModel("file", TypeRef.Primitive(PrimitiveType.BYTE_ARRAY), null, false),
                        PropertyModel("description", TypeRef.Primitive(PrimitiveType.STRING), null, false),
                    ),
                    requiredProperties = setOf("file", "description"),
                    contextHint = "request",
                ),
            ),
        )
        val cls = clientClass(ep)
        val funSpec = cls.funSpecs.first { it.name == "uploadFile" }
        val body = funSpec.body.toString()
        assertTrue(body.contains("submitFormWithBinaryData"), "Expected submitFormWithBinaryData call")
        assertTrue(body.contains("formData"), "Expected formData builder")
    }

    @Test
    fun `multipart endpoint has ChannelProvider param for binary field`() {
        val ep = endpoint(
            method = HttpMethod.POST,
            operationId = "uploadFile",
            requestBody = RequestBody(
                required = true,
                contentType = ContentType.MULTIPART_FORM_DATA,
                schema = TypeRef.Inline(
                    properties = listOf(
                        PropertyModel("file", TypeRef.Primitive(PrimitiveType.BYTE_ARRAY), null, false),
                    ),
                    requiredProperties = setOf("file"),
                    contextHint = "request",
                ),
            ),
        )
        val cls = clientClass(ep)
        val funSpec = cls.funSpecs.first { it.name == "uploadFile" }
        val paramTypes = funSpec.parameters.associate { it.name to it.type.toString() }
        assertEquals("io.ktor.client.request.forms.ChannelProvider", paramTypes["file"])
        assertEquals("kotlin.String", paramTypes["fileName"])
        assertEquals("io.ktor.http.ContentType", paramTypes["fileContentType"])
    }

    @Test
    fun `multipart text fields use simple append`() {
        val ep = endpoint(
            method = HttpMethod.POST,
            operationId = "uploadFile",
            requestBody = RequestBody(
                required = true,
                contentType = ContentType.MULTIPART_FORM_DATA,
                schema = TypeRef.Inline(
                    properties = listOf(
                        PropertyModel("file", TypeRef.Primitive(PrimitiveType.BYTE_ARRAY), null, false),
                        PropertyModel("description", TypeRef.Primitive(PrimitiveType.STRING), null, false),
                    ),
                    requiredProperties = setOf("file", "description"),
                    contextHint = "request",
                ),
            ),
        )
        val cls = clientClass(ep)
        val funSpec = cls.funSpecs.first { it.name == "uploadFile" }
        val body = funSpec.body.toString()
        assertTrue(body.contains("append(\"description\", description)"), "Expected simple append for text field")
    }

    @Test
    fun `multipart binary fields include ContentDisposition header`() {
        val ep = endpoint(
            method = HttpMethod.POST,
            operationId = "uploadFile",
            requestBody = RequestBody(
                required = true,
                contentType = ContentType.MULTIPART_FORM_DATA,
                schema = TypeRef.Inline(
                    properties = listOf(
                        PropertyModel("file", TypeRef.Primitive(PrimitiveType.BYTE_ARRAY), null, false),
                    ),
                    requiredProperties = setOf("file"),
                    contextHint = "request",
                ),
            ),
        )
        val cls = clientClass(ep)
        val funSpec = cls.funSpecs.first { it.name == "uploadFile" }
        val body = funSpec.body.toString()
        assertTrue(body.contains("ContentDisposition"), "Expected ContentDisposition in headers")
        assertTrue(body.contains("filename"), "Expected filename in ContentDisposition")
    }

    @Test
    fun `existing JSON requestBody still generates setBody pattern`() {
        val ep = endpoint(
            method = HttpMethod.POST,
            operationId = "createPet",
            requestBody = RequestBody(true, ContentType.JSON_CONTENT_TYPE, TypeRef.Reference("Pet")),
        )
        val cls = clientClass(ep)
        val funSpec = cls.funSpecs.first { it.name == "createPet" }
        val body = funSpec.body.toString()
        assertTrue(body.contains("setBody"), "Expected setBody for JSON content type")
        assertFalse(body.contains("submitForm"), "Should NOT contain submitForm for JSON")
    }

    // -- No body: endpoint without requestBody should not emit setBody or contentType --

    @Test
    fun `endpoint without requestBody does not generate body null check`() {
        val ep = endpoint(
            method = HttpMethod.GET,
            operationId = "listPets",
            requestBody = null,
        )
        val cls = clientClass(ep)
        val funSpec = cls.funSpecs.first { it.name == "listPets" }
        val body = funSpec.body.toString()
        assertFalse(body.contains("setBody"), "Should NOT contain setBody when no requestBody")
        assertFalse(body.contains("contentType"), "Should NOT set contentType when no requestBody")
        assertFalse(body.contains("if (body"), "Should NOT check body != null when no requestBody")
    }

    // -- URL interpolation: baseUrl must be interpolated, not literal --

    @Test
    fun `generated URL interpolates baseUrl property`() {
        val ep = endpoint(
            path = "/pets/{petId}",
            operationId = "getPet",
            parameters = listOf(
                Parameter("petId", ParameterLocation.PATH, true, TypeRef.Primitive(PrimitiveType.LONG), null),
            ),
        )
        val cls = clientClass(ep)
        val funSpec = cls.funSpecs.first { it.name == "getPet" }
        val body = funSpec.body.toString()
        // Must contain ${baseUrl} as interpolation, not ${'$'}{baseUrl} (escaped/literal)
        assertTrue(body.contains("\${baseUrl}"), "Expected \${baseUrl} interpolation in URL")
        assertFalse(body.contains("\${'$'}{baseUrl}"), "baseUrl must not be escaped as literal text")
    }

    // -- CONT-02: Form-urlencoded code generation --

    @Test
    fun `form-urlencoded endpoint generates submitForm call`() {
        val ep = endpoint(
            method = HttpMethod.POST,
            operationId = "createUser",
            requestBody = RequestBody(
                required = true,
                contentType = ContentType.FORM_URL_ENCODED,
                schema = TypeRef.Inline(
                    properties = listOf(
                        PropertyModel("username", TypeRef.Primitive(PrimitiveType.STRING), null, false),
                        PropertyModel("age", TypeRef.Primitive(PrimitiveType.INT), null, false),
                    ),
                    requiredProperties = setOf("username", "age"),
                    contextHint = "request",
                ),
            ),
        )
        val cls = clientClass(ep)
        val funSpec = cls.funSpecs.first { it.name == "createUser" }
        val body = funSpec.body.toString()
        assertTrue(body.contains("submitForm"), "Expected submitForm call")
        assertTrue(body.contains("parameters"), "Expected parameters builder")

        val paramTypes = funSpec.parameters.associate { it.name to it.type.toString() }
        assertEquals("kotlin.String", paramTypes["username"])
        assertEquals("kotlin.Int", paramTypes["age"])
    }

    @Test
    fun `form-urlencoded non-string params use toString`() {
        val ep = endpoint(
            method = HttpMethod.POST,
            operationId = "createUser",
            requestBody = RequestBody(
                required = true,
                contentType = ContentType.FORM_URL_ENCODED,
                schema = TypeRef.Inline(
                    properties = listOf(
                        PropertyModel("username", TypeRef.Primitive(PrimitiveType.STRING), null, false),
                        PropertyModel("age", TypeRef.Primitive(PrimitiveType.INT), null, false),
                    ),
                    requiredProperties = setOf("username", "age"),
                    contextHint = "request",
                ),
            ),
        )
        val cls = clientClass(ep)
        val funSpec = cls.funSpecs.first { it.name == "createUser" }
        val body = funSpec.body.toString()
        assertTrue(body.contains("age.toString()"), "Expected toString() for non-string param")
        assertFalse(body.contains("username.toString()"), "String param should NOT use toString()")
    }

    @Test
    fun `form-urlencoded optional field generates nullable param with guard`() {
        val ep = endpoint(
            method = HttpMethod.POST,
            operationId = "createUser",
            requestBody = RequestBody(
                required = true,
                contentType = ContentType.FORM_URL_ENCODED,
                schema = TypeRef.Inline(
                    properties = listOf(
                        PropertyModel("username", TypeRef.Primitive(PrimitiveType.STRING), null, false),
                        PropertyModel("nickname", TypeRef.Primitive(PrimitiveType.STRING), null, false),
                    ),
                    requiredProperties = setOf("username"),
                    contextHint = "request",
                ),
            ),
        )
        val cls = clientClass(ep)
        val funSpec = cls.funSpecs.first { it.name == "createUser" }
        val nicknameParam = funSpec.parameters.first { it.name == "nickname" }
        assertTrue(nicknameParam.type.isNullable, "Optional form field should be nullable")
        assertEquals("null", nicknameParam.defaultValue.toString())

        val body = funSpec.body.toString()
        assertTrue(body.contains("if (nickname != null)"), "Expected null guard for optional field")
    }

    @Test
    fun `non-polymorphic spec has createHttpClient without serializersModule`() {
        val files = generate(spec(endpoint()), hasPolymorphicTypes = false)
        val clientProperty = files
            .first()
            .members
            .filterIsInstance<TypeSpec>()
            .first()
            .propertySpecs
            .first { it.name == "client" }
        val clientInitializer = clientProperty.initializer.toString()
        assertTrue(clientInitializer.contains("createHttpClient()"), "Expected plain createHttpClient()")
        assertTrue(!clientInitializer.contains("serializersModule"), "Expected no serializersModule")
    }

    // -- Generated code uses shared helpers --

    @Test
    fun `generated code calls applyAuth`() {
        val cls = clientClass(endpoint())
        val funSpec = cls.funSpecs.first { it.name == "listPets" }
        val body = funSpec.body.toString()
        assertTrue(body.contains("applyAuth()"), "Expected applyAuth() call")
    }

    @Test
    fun `generated code calls safeCall`() {
        val cls = clientClass(endpoint())
        val funSpec = cls.funSpecs.first { it.name == "listPets" }
        val body = funSpec.body.toString()
        assertTrue(body.contains("safeCall"), "Expected safeCall call")
    }

    @Test
    fun `generated code calls toResult for typed response`() {
        val cls = clientClass(endpoint())
        val funSpec = cls.funSpecs.first { it.name == "listPets" }
        val body = funSpec.body.toString()
        assertTrue(body.contains("toResult"), "Expected toResult call")
    }

    @Test
    fun `generated code calls toEmptyResult for void response`() {
        val ep =
            endpoint(
                method = HttpMethod.DELETE,
                operationId = "deletePet",
                responses = mapOf("204" to Response("204", "No content", null)),
            )
        val cls = clientClass(ep)
        val funSpec = cls.funSpecs.first { it.name == "deletePet" }
        val body = funSpec.body.toString()
        assertTrue(body.contains("toEmptyResult"), "Expected toEmptyResult call")
    }

    // -- SECU: Security-aware constructor generation --

    @Test
    fun `ApiKey HEADER scheme generates constructor with baseUrl and apiKey param`() {
        val cls = clientClass(
            listOf(endpoint()),
            listOf(SecurityScheme.ApiKey("ApiKeyHeader", "X-API-Key", ApiKeyLocation.HEADER)),
        )
        val constructor = assertNotNull(cls.primaryConstructor)
        val paramNames = constructor.parameters.map { it.name }
        assertTrue("baseUrl" in paramNames, "Expected baseUrl param")
        assertTrue("apiKeyHeaderTest" in paramNames, "Expected apiKeyHeaderTest param")
    }

    @Test
    fun `Basic scheme generates constructor with baseUrl, username, and password`() {
        val cls = clientClass(
            listOf(endpoint()),
            listOf(SecurityScheme.Basic("BasicAuth")),
        )
        val constructor = assertNotNull(cls.primaryConstructor)
        val paramNames = constructor.parameters.map { it.name }
        assertTrue("baseUrl" in paramNames, "Expected baseUrl param")
        assertTrue("basicAuthTestUsername" in paramNames, "Expected basicAuthTestUsername param")
        assertTrue("basicAuthTestPassword" in paramNames, "Expected basicAuthTestPassword param")
    }

    @Test
    fun `multiple schemes generate all constructor params and pass all to super`() {
        val cls = clientClass(
            listOf(endpoint()),
            listOf(
                SecurityScheme.Bearer("BearerAuth"),
                SecurityScheme.ApiKey("ApiKeyHeader", "X-API-Key", ApiKeyLocation.HEADER),
            ),
        )
        val constructor = assertNotNull(cls.primaryConstructor)
        val paramNames = constructor.parameters.map { it.name }
        assertTrue("baseUrl" in paramNames, "Expected baseUrl param")
        assertTrue("bearerAuthTestToken" in paramNames, "Expected bearerAuthTestToken param")
        assertTrue("apiKeyHeaderTest" in paramNames, "Expected apiKeyHeaderTest param")

        // Verify only baseUrl is passed to super
        val superParams = cls.superclassConstructorParameters.map { it.toString().trim() }
        assertEquals(1, superParams.size, "Expected only baseUrl passed to super")
        assertEquals("baseUrl", superParams[0])
    }

    @Test
    fun `explicit empty securitySchemes generates constructor with only baseUrl`() {
        // Explicit empty securitySchemes = spec has security: [] (no auth required)
        val spec = ApiSpec(
            title = "Test",
            version = "1.0",
            endpoints = listOf(endpoint()),
            schemas = emptyList(),
            enums = emptyList(),
            securitySchemes = emptyList(),
        )
        val files = generate(spec)
        val cls = files
            .first()
            .members
            .filterIsInstance<TypeSpec>()
            .first()
        val constructor = assertNotNull(cls.primaryConstructor)
        val paramNames = constructor.parameters.map { it.name }
        assertEquals(
            listOf("baseUrl"),
            paramNames,
            "Expected only baseUrl param when security is explicitly empty",
        )
    }

    // -- ERR-01: Error type resolution from OpenAPI error response schemas --

    @Test
    fun `single error response schema generates typed error in HttpResult`() {
        val ep = endpoint(
            responses = mapOf(
                "200" to Response("200", "OK", TypeRef.Reference("Pet")),
                "400" to Response("400", "Bad request", TypeRef.Reference("ValidationError")),
            ),
        )
        val cls = clientClass(ep)
        val funSpec = cls.funSpecs.first { it.name == "listPets" }
        val returnType = funSpec.returnType as ParameterizedTypeName
        assertEquals(
            "com.example.model.ValidationError",
            returnType.typeArguments[0].toString(),
            "Expected typed error for single error schema",
        )
    }

    @Test
    fun `multiple error responses with same schema generates typed error`() {
        val ep = endpoint(
            responses = mapOf(
                "200" to Response("200", "OK", TypeRef.Reference("Pet")),
                "400" to Response("400", "Bad request", TypeRef.Reference("ValidationError")),
                "422" to Response("422", "Unprocessable", TypeRef.Reference("ValidationError")),
            ),
        )
        val cls = clientClass(ep)
        val funSpec = cls.funSpecs.first { it.name == "listPets" }
        val returnType = funSpec.returnType as ParameterizedTypeName
        assertEquals(
            "com.example.model.ValidationError",
            returnType.typeArguments[0].toString(),
            "Expected typed error when all error schemas are the same",
        )
    }

    @Test
    fun `multiple error responses with different schemas falls back to JsonElement`() {
        val ep = endpoint(
            responses = mapOf(
                "200" to Response("200", "OK", TypeRef.Reference("Pet")),
                "400" to Response("400", "Bad request", TypeRef.Reference("ValidationError")),
                "404" to Response("404", "Not found", TypeRef.Reference("NotFoundError")),
            ),
        )
        val cls = clientClass(ep)
        val funSpec = cls.funSpecs.first { it.name == "listPets" }
        val returnType = funSpec.returnType as ParameterizedTypeName
        assertEquals(
            "kotlinx.serialization.json.JsonElement",
            returnType.typeArguments[0].toString(),
            "Expected JsonElement fallback for different error schemas",
        )
    }

    @Test
    fun `error response with null schema falls back to JsonElement`() {
        val ep = endpoint(
            responses = mapOf(
                "200" to Response("200", "OK", TypeRef.Reference("Pet")),
                "401" to Response("401", "Unauthorized", null),
            ),
        )
        val cls = clientClass(ep)
        val funSpec = cls.funSpecs.first { it.name == "listPets" }
        val returnType = funSpec.returnType as ParameterizedTypeName
        assertEquals(
            "kotlinx.serialization.json.JsonElement",
            returnType.typeArguments[0].toString(),
            "Expected JsonElement fallback for null error schema",
        )
    }

    @Test
    fun `single Bearer scheme uses plain token param (no prefix)`() {
        val cls = clientClass(
            listOf(endpoint()),
            listOf(SecurityScheme.Bearer("BearerAuth")),
        )
        val constructor = assertNotNull(cls.primaryConstructor)
        val paramNames = constructor.parameters.map { it.name }
        assertEquals(listOf("baseUrl", "token"), paramNames, "Single Bearer should use plain token param")
    }

    @Test
    fun `single Bearer scheme overrides applyAuth with Bearer token`() {
        val cls = clientClass(
            listOf(endpoint()),
            listOf(SecurityScheme.Bearer("BearerAuth")),
        )
        val applyAuth = cls.funSpecs.first { it.name == "applyAuth" }
        val body = applyAuth.body.toString()
        assertTrue(body.contains("Authorization"), "Expected Authorization header")
        assertTrue(body.contains("Bearer"), "Expected Bearer prefix")
        assertTrue(body.contains("token()"), "Expected token() invocation")
    }

    // -- SECU: applyAuth body assertions --

    @Test
    fun `Basic scheme applyAuth contains Authorization header with Base64 encoding`() {
        val cls = clientClass(
            listOf(endpoint()),
            listOf(SecurityScheme.Basic("BasicAuth")),
        )
        val applyAuth = cls.funSpecs.first { it.name == "applyAuth" }
        val body = applyAuth.body.toString()
        assertTrue(body.contains("Authorization"), "Expected Authorization header")
        assertTrue(body.contains("Basic"), "Expected Basic prefix")
        assertTrue(body.contains("Base64"), "Expected Base64 encoding")
        assertTrue(body.contains("basicAuthTestUsername()"), "Expected username invocation")
        assertTrue(body.contains("basicAuthTestPassword()"), "Expected password invocation")
    }

    @Test
    fun `ApiKey HEADER scheme applyAuth appends header with spec parameter name`() {
        val cls = clientClass(
            listOf(endpoint()),
            listOf(SecurityScheme.ApiKey("ApiKeyHeader", "X-API-Key", ApiKeyLocation.HEADER)),
        )
        val applyAuth = cls.funSpecs.first { it.name == "applyAuth" }
        val body = applyAuth.body.toString()
        assertTrue(body.contains("X-API-Key"), "Expected X-API-Key header name")
        assertTrue(body.contains("apiKeyHeaderTest()"), "Expected apiKeyHeaderTest() invocation")
    }

    @Test
    fun `ApiKey QUERY scheme applyAuth appends query parameter`() {
        val cls = clientClass(
            listOf(endpoint()),
            listOf(SecurityScheme.ApiKey("ApiKeyQuery", "api_key", ApiKeyLocation.QUERY)),
        )
        val applyAuth = cls.funSpecs.first { it.name == "applyAuth" }
        val body = applyAuth.body.toString()
        assertTrue(body.contains("parameters.append"), "Expected query parameters.append call")
        assertTrue(body.contains("api_key"), "Expected api_key parameter name")
        assertTrue(body.contains("apiKeyQueryTest()"), "Expected apiKeyQueryTest() invocation")
    }

    // -- DOCS-03: Endpoint KDoc generation --

    @Test
    fun `endpoint with summary generates KDoc`() {
        val ep = endpoint(summary = "List all pets")
        val cls = clientClass(ep)
        val funSpec = cls.funSpecs.first { it.name == "listPets" }
        assertTrue(
            funSpec.kdoc.toString().contains("List all pets"),
            "Expected KDoc with summary, got: ${funSpec.kdoc}",
        )
    }

    @Test
    fun `endpoint with summary and description generates KDoc with both`() {
        val ep = endpoint(summary = "List pets", description = "Returns a paginated list of pets")
        val cls = clientClass(ep)
        val funSpec = cls.funSpecs.first { it.name == "listPets" }
        val kdoc = funSpec.kdoc.toString()
        assertTrue(kdoc.contains("List pets"), "Expected summary in KDoc, got: $kdoc")
        assertTrue(kdoc.contains("Returns a paginated list of pets"), "Expected description in KDoc, got: $kdoc")
    }

    @Test
    fun `endpoint with parameter descriptions generates param KDoc`() {
        val ep = endpoint(
            parameters = listOf(
                Parameter(
                    "limit",
                    ParameterLocation.QUERY,
                    false,
                    TypeRef.Primitive(PrimitiveType.INT),
                    "Max items to return",
                ),
            ),
        )
        val cls = clientClass(ep)
        val funSpec = cls.funSpecs.first { it.name == "listPets" }
        val kdoc = funSpec.kdoc.toString()
        assertTrue(kdoc.contains("@param"), "Expected @param in KDoc, got: $kdoc")
        assertTrue(kdoc.contains("Max items to return"), "Expected param description in KDoc, got: $kdoc")
    }

    @Test
    fun `endpoint with non-Unit return generates return KDoc`() {
        val ep = endpoint(
            responses = mapOf("200" to Response("200", "OK", TypeRef.Reference("Pet"))),
        )
        val cls = clientClass(ep)
        val funSpec = cls.funSpecs.first { it.name == "listPets" }
        val kdoc = funSpec.kdoc.toString()
        assertTrue(kdoc.contains("@return"), "Expected @return in KDoc, got: $kdoc")
    }

    @Test
    fun `endpoint without descriptions generates no KDoc`() {
        val ep = endpoint(
            summary = null,
            description = null,
            parameters = emptyList(),
            responses = mapOf("204" to Response("204", "No content", null)),
        )
        val cls = clientClass(ep)
        val funSpec = cls.funSpecs.first { it.name == "listPets" }
        assertTrue(
            funSpec.kdoc.toString().isEmpty(),
            "Expected no KDoc when no descriptions, got: ${funSpec.kdoc}",
        )
    }
}
