package com.avsystem.justworks.core.gen

import com.avsystem.justworks.core.gen.shared.ApiClientBaseGenerator
import com.squareup.kotlinpoet.ExperimentalKotlinPoetApi
import com.squareup.kotlinpoet.FunSpec
import com.squareup.kotlinpoet.KModifier
import com.squareup.kotlinpoet.ParameterizedTypeName
import com.squareup.kotlinpoet.TypeSpec
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class ApiClientBaseGeneratorTest {
    private val file = ApiClientBaseGenerator.generate()

    private val classSpec: TypeSpec
        get() = file.members.filterIsInstance<TypeSpec>().first { it.name == "ApiClientBase" }

    private fun topLevelFun(name: String): FunSpec = file.members.filterIsInstance<FunSpec>().first { it.name == name }

    // -- ApiClientBase class --

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
    fun `ApiClientBase has constructor with only baseUrl`() {
        val constructor = assertNotNull(classSpec.primaryConstructor)
        val paramNames = constructor.parameters.map { it.name }
        assertEquals(listOf("baseUrl"), paramNames)
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
    }

    @OptIn(ExperimentalKotlinPoetApi::class)
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

    @OptIn(ExperimentalKotlinPoetApi::class)
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

    @OptIn(ExperimentalKotlinPoetApi::class)
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
        assertTrue(body.contains("in 200..299"), "Expected 2xx success range")
        assertTrue(body.contains("HttpSuccess"), "Expected HttpSuccess for success")
        assertTrue(body.contains("in 300..399"), "Expected 3xx redirect range")
        assertTrue(body.contains("HttpError.Redirect"), "Expected HttpError.Redirect")
        assertTrue(body.contains("400 ->"), "Expected 400 branch")
        assertTrue(body.contains("401 ->"), "Expected 401 branch")
        assertTrue(body.contains("403 ->"), "Expected 403 branch")
        assertTrue(body.contains("404 ->"), "Expected 404 branch")
        assertTrue(body.contains("405 ->"), "Expected 405 branch")
        assertTrue(body.contains("408 ->"), "Expected 408 branch")
        assertTrue(body.contains("409 ->"), "Expected 409 branch")
        assertTrue(body.contains("410 ->"), "Expected 410 branch")
        assertTrue(body.contains("413 ->"), "Expected 413 branch")
        assertTrue(body.contains("415 ->"), "Expected 415 branch")
        assertTrue(body.contains("422 ->"), "Expected 422 branch")
        assertTrue(body.contains("429 ->"), "Expected 429 branch")
        assertTrue(body.contains("500 ->"), "Expected 500 branch")
        assertTrue(body.contains("502 ->"), "Expected 502 branch")
        assertTrue(body.contains("503 ->"), "Expected 503 branch")
        assertTrue(body.contains("504 ->"), "Expected 504 branch")
        assertTrue(body.contains("HttpError.BadRequest"), "Expected HttpError.BadRequest")
        assertTrue(body.contains("HttpError.NotFound"), "Expected HttpError.NotFound")
        assertTrue(body.contains("HttpError.InternalServerError"), "Expected HttpError.InternalServerError")
        assertTrue(body.contains("HttpError.Other"), "Expected HttpError.Other catchall")
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
}
