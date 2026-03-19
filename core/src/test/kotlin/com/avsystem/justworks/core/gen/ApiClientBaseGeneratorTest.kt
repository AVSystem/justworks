package com.avsystem.justworks.core.gen

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
    fun `ApiClientBase has safeCall function`() {
        val safeCall = classSpec.funSpecs.first { it.name == "safeCall" }
        assertTrue(KModifier.PROTECTED in safeCall.modifiers)
        assertTrue(KModifier.SUSPEND in safeCall.modifiers)
        assertTrue(safeCall.contextParameters.isNotEmpty(), "Expected context parameter")
        val body = safeCall.body.toString()
        assertTrue(body.contains("IOException"), "Expected IOException catch")
        assertTrue(body.contains("HttpRequestTimeoutException"), "Expected HttpRequestTimeoutException catch")
        assertTrue(body.contains("Network error"), "Expected Network error message")
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
    fun `toResult is suspend inline with reified T and context parameter`() {
        val fn = topLevelFun("toResult")
        assertTrue(KModifier.SUSPEND in fn.modifiers)
        assertTrue(KModifier.INLINE in fn.modifiers)
        val typeVar = fn.typeVariables.first()
        assertTrue(typeVar.isReified, "Expected reified type variable")
        assertNotNull(fn.receiverType, "Expected HttpResponse receiver")
        assertTrue(fn.contextParameters.isNotEmpty(), "Expected context parameter")
        val contextType = fn.contextParameters.first().type
        assertTrue(contextType is ParameterizedTypeName)
        assertEquals("arrow.core.raise.Raise", contextType.rawType.toString())
    }

    @Test
    fun `toEmptyResult is suspend with context parameter and returns HttpSuccess Unit`() {
        val fn = topLevelFun("toEmptyResult")
        assertTrue(KModifier.SUSPEND in fn.modifiers)
        assertNotNull(fn.receiverType, "Expected HttpResponse receiver")
        assertTrue(fn.contextParameters.isNotEmpty(), "Expected context parameter")
        val returnType = fn.returnType as ParameterizedTypeName
        assertEquals("com.avsystem.justworks.HttpSuccess", returnType.rawType.toString())
        assertEquals("kotlin.Unit", returnType.typeArguments.first().toString())
    }

    @Test
    fun `generates single file named ApiClientBase`() {
        assertEquals("ApiClientBase", file.name)
    }
}
