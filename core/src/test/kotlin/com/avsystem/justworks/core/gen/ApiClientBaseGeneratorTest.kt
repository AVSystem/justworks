package com.avsystem.justworks.core.gen

import com.avsystem.justworks.core.model.ApiKeyLocation
import com.avsystem.justworks.core.model.SecurityScheme
import com.squareup.kotlinpoet.ExperimentalKotlinPoetApi
import com.squareup.kotlinpoet.FunSpec
import com.squareup.kotlinpoet.KModifier
import com.squareup.kotlinpoet.ParameterizedTypeName
import com.squareup.kotlinpoet.TypeSpec
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

@OptIn(ExperimentalKotlinPoetApi::class)
class ApiClientBaseGeneratorTest {
    private val file = ApiClientBaseGenerator.generate()

    private val classSpec: TypeSpec
        get() = file.members.filterIsInstance<TypeSpec>().first { it.name == "ApiClientBase" }

    private fun topLevelFun(name: String): FunSpec = file.members.filterIsInstance<FunSpec>().first { it.name == name }

    private fun classFor(schemes: List<SecurityScheme>): TypeSpec {
        val f = ApiClientBaseGenerator.generate(schemes)
        return f.members.filterIsInstance<TypeSpec>().first { it.name == "ApiClientBase" }
    }

    private fun applyAuthBody(schemes: List<SecurityScheme>): String {
        val cls = classFor(schemes)
        return cls.funSpecs
            .first { it.name == "applyAuth" }
            .body
            .toString()
    }

    private fun constructorParamNames(schemes: List<SecurityScheme>): List<String> {
        val cls = classFor(schemes)
        return cls.primaryConstructor!!.parameters.map { it.name }
    }

    // -- ApiClientBase class (no-arg backward compat) --

    @Test
    fun `ApiClientBase is abstract`() {
        assertTrue(KModifier.ABSTRACT in classSpec.modifiers)
    }

    @Test
    fun `ApiClientBase implements Closeable`() {
        val superinterfaces = classSpec.superinterfaces.keys.map { it.toString() }
        assertTrue("java.io.Closeable" in superinterfaces)
    }

    @Test
    fun `ApiClientBase has constructor with baseUrl and token provider`() {
        val constructor = assertNotNull(classSpec.primaryConstructor)
        val paramNames = constructor.parameters.map { it.name }
        assertTrue("baseUrl" in paramNames)
        assertTrue("token" in paramNames)
        val tokenParam = constructor.parameters.first { it.name == "token" }
        assertEquals("() -> kotlin.String", tokenParam.type.toString(), "token should be a () -> String lambda")
    }

    @Test
    fun `ApiClientBase has abstract client property`() {
        val clientProp = classSpec.propertySpecs.first { it.name == "client" }
        assertTrue(KModifier.ABSTRACT in clientProp.modifiers)
        assertTrue(KModifier.PROTECTED in clientProp.modifiers)
        assertEquals("io.ktor.client.HttpClient", clientProp.type.toString())
    }

    @Test
    fun `ApiClientBase has close function`() {
        val closeFun = classSpec.funSpecs.first { it.name == "close" }
        assertTrue(KModifier.OVERRIDE in closeFun.modifiers)
        assertTrue(closeFun.body.toString().contains("client.close()"))
    }

    @Test
    fun `ApiClientBase has applyAuth function`() {
        val applyAuth = classSpec.funSpecs.first { it.name == "applyAuth" }
        assertTrue(KModifier.PROTECTED in applyAuth.modifiers)
        assertNotNull(applyAuth.receiverType, "Expected HttpRequestBuilder receiver")
        val body = applyAuth.body.toString()
        assertTrue(body.contains("Authorization"), "Expected Authorization header")
        assertTrue(body.contains("Bearer"), "Expected Bearer prefix")
        assertTrue(body.contains("token()"), "Expected token() invocation")
    }

    @Test
    fun `ApiClientBase has safeCall function with no context parameters`() {
        val safeCall = classSpec.funSpecs.first { it.name == "safeCall" }
        assertTrue(KModifier.PROTECTED in safeCall.modifiers)
        assertTrue(KModifier.SUSPEND in safeCall.modifiers)
        assertTrue(KModifier.INLINE in safeCall.modifiers)
        assertTrue(safeCall.contextParameters.isEmpty(), "Expected no context parameters")
        assertEquals(2, safeCall.typeVariables.size, "Expected E and T type variables")
        assertTrue(safeCall.typeVariables.all { it.isReified }, "Expected reified type variables")
        val body = safeCall.body.toString()
        assertTrue(body.contains("IOException"), "Expected IOException catch")
        assertTrue(body.contains("HttpRequestTimeoutException"), "Expected HttpRequestTimeoutException catch")
        assertTrue(body.contains("Either.Left"), "Expected Either.Left in body")
        assertTrue(body.contains("HttpError.Network"), "Expected HttpError.Network in body")
    }

    @Test
    fun `ApiClientBase has createHttpClient function`() {
        val create = classSpec.funSpecs.first { it.name == "createHttpClient" }
        assertTrue(KModifier.PROTECTED in create.modifiers)
        val param = create.parameters.first { it.name == "serializersModule" }
        assertTrue(param.type.isNullable, "serializersModule should be nullable")
        assertEquals("null", param.defaultValue.toString())
        val body = create.body.toString()
        assertTrue(body.contains("ContentNegotiation"), "Expected ContentNegotiation install")
        assertTrue(body.contains("expectSuccess"), "Expected expectSuccess = false")
    }

    // -- Top-level functions --

    @Test
    fun `encodeParam is inline with reified type parameter`() {
        val fn = topLevelFun("encodeParam")
        assertTrue(KModifier.INLINE in fn.modifiers)
        val typeVar = fn.typeVariables.first()
        assertTrue(typeVar.isReified, "Expected reified type variable")
    }

    @Test
    fun `toResult is suspend inline with reified E and T, no context parameter`() {
        val fn = topLevelFun("toResult")
        assertTrue(KModifier.SUSPEND in fn.modifiers)
        assertTrue(KModifier.INLINE in fn.modifiers)
        assertEquals(2, fn.typeVariables.size, "Expected E and T type variables")
        assertTrue(fn.typeVariables.all { it.isReified }, "Expected reified type variables")
        assertNotNull(fn.receiverType, "Expected HttpResponse receiver")
        assertTrue(fn.contextParameters.isEmpty(), "Expected no context parameters")
        val returnType = fn.returnType as ParameterizedTypeName
        assertEquals("com.avsystem.justworks.HttpResult", returnType.rawType.toString())
    }

    @Test
    fun `toEmptyResult returns HttpResult E Unit with no context parameter`() {
        val fn = topLevelFun("toEmptyResult")
        assertTrue(KModifier.SUSPEND in fn.modifiers)
        assertTrue(KModifier.INLINE in fn.modifiers)
        assertEquals(1, fn.typeVariables.size, "Expected E type variable")
        assertTrue(fn.typeVariables.first().isReified, "Expected reified type variable")
        assertNotNull(fn.receiverType, "Expected HttpResponse receiver")
        assertTrue(fn.contextParameters.isEmpty(), "Expected no context parameters")
        val returnType = fn.returnType as ParameterizedTypeName
        assertEquals("com.avsystem.justworks.HttpResult", returnType.rawType.toString())
    }

    @Test
    fun `mapToResult branches on specific status codes`() {
        val fn = topLevelFun("mapToResult")
        val body = fn.body.toString()
        assertTrue(body.contains("400 ->"), "Expected 400 branch")
        assertTrue(body.contains("401 ->"), "Expected 401 branch")
        assertTrue(body.contains("403 ->"), "Expected 403 branch")
        assertTrue(body.contains("404 ->"), "Expected 404 branch")
        assertTrue(body.contains("405 ->"), "Expected 405 branch")
        assertTrue(body.contains("409 ->"), "Expected 409 branch")
        assertTrue(body.contains("410 ->"), "Expected 410 branch")
        assertTrue(body.contains("422 ->"), "Expected 422 branch")
        assertTrue(body.contains("429 ->"), "Expected 429 branch")
        assertTrue(body.contains("500 ->"), "Expected 500 branch")
        assertTrue(body.contains("502 ->"), "Expected 502 branch")
        assertTrue(body.contains("503 ->"), "Expected 503 branch")
        assertTrue(body.contains("HttpError.BadRequest"), "Expected HttpError.BadRequest")
        assertTrue(body.contains("HttpError.NotFound"), "Expected HttpError.NotFound")
        assertTrue(body.contains("HttpError.InternalServerError"), "Expected HttpError.InternalServerError")
        assertTrue(body.contains("HttpError.Other"), "Expected HttpError.Other catchall")
        assertTrue(body.contains("Either.Right"), "Expected Either.Right for success")
        assertTrue(body.contains("Either.Left"), "Expected Either.Left for errors")
    }

    @Test
    fun `deserializeErrorBody helper function exists`() {
        val fn = topLevelFun("deserializeErrorBody")
        assertTrue(KModifier.INTERNAL in fn.modifiers)
        assertTrue(KModifier.INLINE in fn.modifiers)
        assertTrue(KModifier.SUSPEND in fn.modifiers)
        assertEquals(1, fn.typeVariables.size, "Expected E type variable")
        assertTrue(fn.typeVariables.first().isReified, "Expected reified type variable")
        assertNotNull(fn.receiverType, "Expected HttpResponse receiver")
        val body = fn.body.toString()
        assertTrue(body.contains("body"), "Expected body() call")
        assertTrue(body.contains("catch"), "Expected catch block for fallback")
    }

    @Test
    fun `generates single file named ApiClientBase`() {
        assertEquals("ApiClientBase", file.name)
    }

    // -- Security scheme: single Bearer (backward compat) --

    @Test
    fun `single Bearer scheme uses token param name for backward compat`() {
        val params = constructorParamNames(listOf(SecurityScheme.Bearer("BearerAuth")))
        assertTrue("baseUrl" in params, "Expected baseUrl param")
        assertTrue("token" in params, "Expected token param (backward compat)")
    }

    @Test
    fun `single Bearer scheme generates Bearer auth in applyAuth`() {
        val body = applyAuthBody(listOf(SecurityScheme.Bearer("BearerAuth")))
        assertTrue(body.contains("Authorization"), "Expected Authorization header")
        assertTrue(body.contains("Bearer"), "Expected Bearer prefix")
        assertTrue(body.contains("token()"), "Expected token() invocation")
    }

    // -- Security scheme: ApiKey in header --

    @Test
    fun `ApiKey HEADER scheme generates constructor param with Key suffix`() {
        val params = constructorParamNames(
            listOf(SecurityScheme.ApiKey("ApiKeyHeader", "X-API-Key", ApiKeyLocation.HEADER)),
        )
        assertTrue("baseUrl" in params, "Expected baseUrl param")
        assertTrue("apiKeyHeaderKey" in params, "Expected apiKeyHeaderKey param")
    }

    @Test
    fun `ApiKey HEADER scheme generates header append in applyAuth`() {
        val body = applyAuthBody(
            listOf(SecurityScheme.ApiKey("ApiKeyHeader", "X-API-Key", ApiKeyLocation.HEADER)),
        )
        assertTrue(body.contains("headers"), "Expected headers block")
        assertTrue(body.contains("X-API-Key"), "Expected X-API-Key header name")
        assertTrue(body.contains("apiKeyHeaderKey()"), "Expected apiKeyHeaderKey() invocation")
    }

    // -- Security scheme: ApiKey in query --

    @Test
    fun `ApiKey QUERY scheme generates constructor param with Key suffix`() {
        val params = constructorParamNames(
            listOf(SecurityScheme.ApiKey("ApiKeyQuery", "api_key", ApiKeyLocation.QUERY)),
        )
        assertTrue("baseUrl" in params, "Expected baseUrl param")
        assertTrue("apiKeyQueryKey" in params, "Expected apiKeyQueryKey param")
    }

    @Test
    fun `ApiKey QUERY scheme generates query parameter in applyAuth`() {
        val body = applyAuthBody(
            listOf(SecurityScheme.ApiKey("ApiKeyQuery", "api_key", ApiKeyLocation.QUERY)),
        )
        assertTrue(body.contains("url"), "Expected url block")
        assertTrue(body.contains("parameters.append"), "Expected parameters.append")
        assertTrue(body.contains("api_key"), "Expected api_key query param name")
        assertTrue(body.contains("apiKeyQueryKey()"), "Expected apiKeyQueryKey() invocation")
    }

    // -- Security scheme: HTTP Basic --

    @Test
    fun `Basic scheme generates username and password constructor params`() {
        val params = constructorParamNames(listOf(SecurityScheme.Basic("BasicAuth")))
        assertTrue("baseUrl" in params, "Expected baseUrl param")
        assertTrue("basicAuthUsername" in params, "Expected basicAuthUsername param")
        assertTrue("basicAuthPassword" in params, "Expected basicAuthPassword param")
    }

    @Test
    fun `Basic scheme generates Base64 Authorization header in applyAuth`() {
        val body = applyAuthBody(listOf(SecurityScheme.Basic("BasicAuth")))
        assertTrue(body.contains("Authorization"), "Expected Authorization header")
        assertTrue(body.contains("Basic"), "Expected Basic prefix")
        assertTrue(body.contains("Base64"), "Expected Base64 encoding")
        assertTrue(body.contains("basicAuthUsername()"), "Expected basicAuthUsername() invocation")
        assertTrue(body.contains("basicAuthPassword()"), "Expected basicAuthPassword() invocation")
    }

    // -- Multiple schemes --

    @Test
    fun `multiple schemes generate all constructor params`() {
        val params = constructorParamNames(
            listOf(
                SecurityScheme.Bearer("BearerAuth"),
                SecurityScheme.ApiKey("ApiKeyHeader", "X-API-Key", ApiKeyLocation.HEADER),
                SecurityScheme.Basic("BasicAuth"),
            ),
        )
        assertTrue("baseUrl" in params, "Expected baseUrl param")
        assertTrue("bearerAuthToken" in params, "Expected bearerAuthToken param (multi-scheme uses full name)")
        assertTrue("apiKeyHeaderKey" in params, "Expected apiKeyHeaderKey param")
        assertTrue("basicAuthUsername" in params, "Expected basicAuthUsername param")
        assertTrue("basicAuthPassword" in params, "Expected basicAuthPassword param")
    }

    @Test
    fun `multiple schemes generate all auth types in applyAuth`() {
        val body = applyAuthBody(
            listOf(
                SecurityScheme.Bearer("BearerAuth"),
                SecurityScheme.ApiKey("ApiKeyHeader", "X-API-Key", ApiKeyLocation.HEADER),
                SecurityScheme.ApiKey("ApiKeyQuery", "api_key", ApiKeyLocation.QUERY),
                SecurityScheme.Basic("BasicAuth"),
            ),
        )
        assertTrue(body.contains("Bearer"), "Expected Bearer in applyAuth")
        assertTrue(body.contains("X-API-Key"), "Expected X-API-Key in applyAuth")
        assertTrue(body.contains("api_key"), "Expected api_key query param in applyAuth")
        assertTrue(body.contains("Basic"), "Expected Basic in applyAuth")
        assertTrue(body.contains("Base64"), "Expected Base64 in applyAuth")
    }

    // -- Empty schemes (spec with no security) --

    @Test
    fun `empty schemes list generates only baseUrl constructor param`() {
        val params = constructorParamNames(emptyList())
        assertEquals(listOf("baseUrl"), params, "Expected only baseUrl param when no security schemes")
    }

    @Test
    fun `empty schemes list generates empty applyAuth body`() {
        val body = applyAuthBody(emptyList())
        assertTrue(!body.contains("headers"), "Expected no headers block for empty schemes")
        assertTrue(!body.contains("url"), "Expected no url block for empty schemes")
    }
}
