package com.avsystem.justworks.core.gen

import com.avsystem.justworks.core.model.ApiSpec
import com.avsystem.justworks.core.model.Endpoint
import com.avsystem.justworks.core.model.HttpMethod
import com.avsystem.justworks.core.model.Parameter
import com.avsystem.justworks.core.model.ParameterLocation
import com.avsystem.justworks.core.model.PrimitiveType
import com.avsystem.justworks.core.model.RequestBody
import com.avsystem.justworks.core.model.Response
import com.avsystem.justworks.core.model.TypeRef
import com.squareup.kotlinpoet.ExperimentalKotlinPoetApi
import com.squareup.kotlinpoet.KModifier
import com.squareup.kotlinpoet.ParameterizedTypeName
import com.squareup.kotlinpoet.TypeSpec
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class ClientGeneratorTest {
    private val apiPackage = "com.example.api"
    private val modelPackage = "com.example.model"
    private val generator = ClientGenerator(apiPackage, modelPackage)

    private fun spec(endpoints: List<Endpoint>) = ApiSpec(
        title = "Test",
        version = "1.0",
        endpoints = endpoints,
        schemas = emptyList(),
        enums = emptyList(),
    )

    private fun endpoint(
        path: String = "/pets",
        method: HttpMethod = HttpMethod.GET,
        operationId: String = "listPets",
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
        summary = null,
        tags = tags,
        parameters = parameters,
        requestBody = requestBody,
        responses = responses,
    )

    private fun clientClass(endpoints: List<Endpoint>): TypeSpec {
        val files = generator.generate(spec(endpoints))
        return files
            .first()
            .members
            .filterIsInstance<TypeSpec>()
            .first()
    }

    // -- CLNT-01: One client class per tag --

    @Test
    fun `generates one client class per tag`() {
        val endpoints =
            listOf(
                endpoint(operationId = "listPets", tags = listOf("Pets")),
                endpoint(path = "/store", operationId = "getInventory", tags = listOf("Store")),
            )
        val files = generator.generate(spec(endpoints))
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
        val cls = clientClass(listOf(endpoint()))
        val funSpec = cls.funSpecs.first { it.name == "listPets" }
        assertTrue(KModifier.SUSPEND in funSpec.modifiers, "Expected SUSPEND modifier")
    }

    // -- CLNT-03: All HTTP methods --

    @Test
    fun `supports all HTTP methods`() {
        val methods =
            listOf(
                HttpMethod.GET to "getPet",
                HttpMethod.POST to "createPet",
                HttpMethod.PUT to "updatePet",
                HttpMethod.DELETE to "deletePet",
                HttpMethod.PATCH to "patchPet",
            )
        val endpoints =
            methods.map { (method, opId) ->
                endpoint(method = method, operationId = opId)
            }
        val cls = clientClass(endpoints)
        val funBodies =
            cls.funSpecs.filter { it.name != "close" }.associate {
                it.name to it.body.toString()
            }
        // MemberName body.toString() renders fully-qualified names (e.g., io.ktor.client.request.`get`)
        // Check that each body references the correct Ktor request method path
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
                parameters =
                    listOf(
                        Parameter("petId", ParameterLocation.PATH, true, TypeRef.Primitive(PrimitiveType.LONG), null),
                    ),
            )
        val cls = clientClass(listOf(ep))
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
        val cls = clientClass(listOf(ep))
        val funSpec = cls.funSpecs.first { it.name == "listPets" }
        val param = funSpec.parameters.first { it.name == "limit" }
        assertEquals("kotlin.Int", param.type.toString())
    }

    // -- CLNT-06: Optional query parameters default to null --

    @Test
    fun `optional query parameters default to null`() {
        val ep =
            endpoint(
                operationId = "listPets",
                parameters =
                    listOf(
                        Parameter("limit", ParameterLocation.QUERY, false, TypeRef.Primitive(PrimitiveType.INT), null),
                    ),
            )
        val cls = clientClass(listOf(ep))
        val funSpec = cls.funSpecs.first { it.name == "listPets" }
        val param = funSpec.parameters.first { it.name == "limit" }
        assertTrue(param.type.isNullable, "Optional query param should be nullable")
        assertEquals("null", param.defaultValue.toString())
    }

    // -- CLNT-07: Request body becomes function parameter --

    @Test
    fun `request body becomes function parameter`() {
        val ep =
            endpoint(
                method = HttpMethod.POST,
                operationId = "createPet",
                requestBody = RequestBody(true, "application/json", TypeRef.Reference("Pet")),
            )
        val cls = clientClass(listOf(ep))
        val funSpec = cls.funSpecs.first { it.name == "createPet" }
        val bodyParam = funSpec.parameters.first { it.name == "body" }
        assertEquals("com.example.model.Pet", bodyParam.type.toString())
    }

    // -- CLNT-08: Return type is Success parameterized --

    @Test
    fun `return type is Success parameterized`() {
        val cls = clientClass(listOf(endpoint()))
        val funSpec = cls.funSpecs.first { it.name == "listPets" }
        val returnType = funSpec.returnType
        assertNotNull(returnType)
        assertTrue(returnType is ParameterizedTypeName, "Expected ParameterizedTypeName")
        assertEquals("com.avsystem.justworks.HttpSuccess", returnType.rawType.toString())
        assertEquals("com.example.model.Pet", returnType.typeArguments.first().toString())
    }

    // -- Context receiver: Raise<HttpError> --

    @OptIn(ExperimentalKotlinPoetApi::class)
    @Test
    fun `endpoint functions have Raise HttpError context receiver`() {
        val cls = clientClass(listOf(endpoint()))
        val funSpec = cls.funSpecs.first { it.name == "listPets" }
        val contextReceivers = funSpec.contextReceiverTypes
        assertTrue(contextReceivers.isNotEmpty(), "Expected context receiver")
        val contextType = contextReceivers.first()
        assertTrue(contextType is ParameterizedTypeName, "Expected parameterized Raise type")
        assertEquals("arrow.core.raise.Raise", contextType.rawType.toString())
        assertEquals("com.avsystem.justworks.HttpError", contextType.typeArguments.first().toString())
    }

    // -- CLNT-09: Header parameters become function parameters --

    @Test
    fun `header parameters become function parameters`() {
        val ep =
            endpoint(
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
        val cls = clientClass(listOf(ep))
        val funSpec = cls.funSpecs.first { it.name == "listPets" }
        val param = funSpec.parameters.first { it.name == "xRequestId" }
        assertEquals("kotlin.String", param.type.toString())
    }

    // -- CLNT-10: Client constructor has baseUrl parameter --

    @Test
    fun `client constructor has baseUrl parameter`() {
        val cls = clientClass(listOf(endpoint()))
        val constructor = assertNotNull(cls.primaryConstructor)
        val baseUrl = constructor.parameters.first { it.name == "baseUrl" }
        assertEquals("kotlin.String", baseUrl.type.toString())
    }

    // -- AUTH-01: Client constructor has tokenProvider parameter --

    @Test
    fun `client constructor has tokenProvider parameter`() {
        val cls = clientClass(listOf(endpoint()))
        val constructor = assertNotNull(cls.primaryConstructor)
        val tokenProvider = constructor.parameters.first { it.name == "tokenProvider" }
        assertTrue(tokenProvider.type.toString().contains("String"), "tokenProvider should return String")
    }

    // -- Pitfall 3: Untagged endpoints go to DefaultClient --

    @Test
    fun `untagged endpoints go to DefaultApi`() {
        val ep = endpoint(operationId = "healthCheck", tags = emptyList())
        val files = generator.generate(spec(listOf(ep)))
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
    fun `void response uses Unit type parameter`() {
        val ep =
            endpoint(
                method = HttpMethod.DELETE,
                operationId = "deletePet",
                responses = mapOf("204" to Response("204", "No content", null)),
            )
        val cls = clientClass(listOf(ep))
        val funSpec = cls.funSpecs.first { it.name == "deletePet" }
        val returnType = funSpec.returnType as ParameterizedTypeName
        assertEquals("com.avsystem.justworks.HttpSuccess", returnType.rawType.toString())
        assertEquals("kotlin.Unit", returnType.typeArguments.first().toString())
    }

    // -- Client class implements Closeable --

    @Test
    fun `client class implements Closeable`() {
        val cls = clientClass(listOf(endpoint()))
        val superinterfaces = cls.superinterfaces.keys.map { it.toString() }
        assertTrue("java.io.Closeable" in superinterfaces, "Expected Closeable superinterface")
    }

    // -- SER-01: Polymorphic spec wires SerializersModule --

    @Test
    fun `polymorphic spec wires serializersModule in json block`() {
        val files = ClientGenerator(
            apiPackage,
            modelPackage,
        ).generate(spec(listOf(endpoint())), hasPolymorphicTypes = true)
        val clientProperty = files
            .first()
            .members
            .filterIsInstance<TypeSpec>()
            .first()
            .propertySpecs
            .first { it.name == "client" }
        val clientInitializer = clientProperty.initializer.toString()
        assertTrue(clientInitializer.contains("serializersModule"), "Expected serializersModule in client initializer")
        assertTrue(
            clientInitializer.contains("generatedSerializersModule"),
            "Expected generatedSerializersModule reference",
        )
    }

    @Test
    fun `non-polymorphic spec has plain json() with no serializersModule`() {
        val files = ClientGenerator(
            apiPackage,
            modelPackage,
        ).generate(spec(listOf(endpoint())), hasPolymorphicTypes = false)
        val clientProperty = files
            .first()
            .members
            .filterIsInstance<TypeSpec>()
            .first()
            .propertySpecs
            .first { it.name == "client" }
        val clientInitializer = clientProperty.initializer.toString()
        assertTrue(
            !clientInitializer.contains("serializersModule"),
            "Expected no serializersModule for non-polymorphic spec",
        )
    }

    // -- AUTH-02, AUTH-03: Generated code contains bearer auth header --

    @Test
    fun `generated code contains bearer auth header`() {
        val cls = clientClass(listOf(endpoint()))
        val funSpec = cls.funSpecs.first { it.name == "listPets" }
        val body = funSpec.body.toString()
        assertTrue(body.contains("Authorization"), "Expected Authorization header")
        assertTrue(body.contains("Bearer"), "Expected Bearer prefix")
        assertTrue(body.contains("tokenProvider"), "Expected tokenProvider call")
    }

    // -- ERRH-05: Generated code contains try-catch with raise --

    @Test
    fun `generated code contains try-catch with raise NetworkError`() {
        val cls = clientClass(listOf(endpoint()))
        val funSpec = cls.funSpecs.first { it.name == "listPets" }
        val body = funSpec.body.toString()
        assertTrue(body.contains("catch"), "Expected catch block")
        assertTrue(body.contains("HttpError("), "Expected HttpError constructor call in catch")
        assertTrue(
            body.contains("HttpErrorType.Network") || body.contains(".Network"),
            "Expected HttpErrorType.Network in catch",
        )
        assertTrue(body.contains("raise"), "Expected raise call")
    }

    // -- ERRH-02, ERRH-03, ERRH-04: Generated code branches on status code with raise --

    @Test
    fun `generated code branches on status code with raise`() {
        val cls = clientClass(listOf(endpoint()))
        val funSpec = cls.funSpecs.first { it.name == "listPets" }
        val body = funSpec.body.toString()
        assertTrue(body.contains("200..299"), "Expected 2xx range check")
        assertTrue(body.contains("400..499"), "Expected 4xx range check")
        assertTrue(body.contains("Success"), "Expected Success for 2xx")
        assertTrue(body.contains("HttpError("), "Expected HttpError constructor call")
        assertTrue(
            body.contains("HttpErrorType.Client") || body.contains(".Client"),
            "Expected HttpErrorType.Client for 4xx",
        )
        assertTrue(
            body.contains("HttpErrorType.Server") || body.contains(".Server"),
            "Expected HttpErrorType.Server for 5xx",
        )
        assertTrue(body.contains("raise"), "Expected raise call")
    }
}
