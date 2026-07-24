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

    private fun topLevelFunOverloads(name: String): List<FunSpec> =
        file.members.filterIsInstance<FunSpec>().filter { it.name == name }

    // Every simple (non-enum) overload takes a single non-generic parameter of one of these types —
    // all of them JSON-primitive-safe. Notably absent: any collection/array/map/object type, and
    // also Uuid/Instant/LocalDate — those are JSON-primitive-safe too, but rendering them here would
    // force every generated client to opt into ExperimentalUuidApi / depend on kotlinx-datetime even
    // when the spec never uses them. BodyGeneratorTest / ClientGeneratorTest cover how those three
    // are actually encoded (a direct .toString() call at the call site).
    private val expectedSimpleParamTypes = setOf(
        "kotlin.String",
        "kotlin.Number",
        "kotlin.Boolean",
    )

    private fun FunSpec.singleParamType(): String = parameters.single().type.toString()

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
    fun `ApiClientBase has empty applyAuth function`() {
        val applyAuth = classSpec.funSpecs.first { it.name == "applyAuth" }
        assertTrue(KModifier.PROTECTED in applyAuth.modifiers)
        assertTrue(KModifier.OPEN in applyAuth.modifiers)
        assertNotNull(applyAuth.receiverType, "Expected HttpRequestBuilder receiver")
        assertTrue(applyAuth.body.toString().isBlank(), "Base applyAuth should be a no-op")
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

    // -- encodeParam / encodePathParam: overload set, not a generic passthrough --
    //
    // These are no longer single generic <reified T> functions. A generic passthrough would accept
    // ANY type, including objects/lists/maps that don't serialize to a JsonPrimitive, and only fail
    // at runtime when Json.encodeToJsonElement(...).jsonPrimitive throws. Restricting to an explicit
    // overload set (one per JSON-primitive-safe type, plus a reified Enum<T> overload) means a value
    // that can't be a JSON primitive fails to *compile* in the generated client instead.

    @Test
    fun `encodeParam has exactly one overload per JSON-primitive-safe type plus one enum overload`() {
        val overloads = topLevelFunOverloads("encodeParam")
        assertEquals(expectedSimpleParamTypes.size + 1, overloads.size, "Unexpected number of encodeParam overloads")
    }

    @Test
    fun `encodeParam simple overloads take exactly the expected non-generic types`() {
        val simpleOverloads = topLevelFunOverloads("encodeParam").filter { it.typeVariables.isEmpty() }
        val actualParamTypes = simpleOverloads.map { it.singleParamType() }.toSet()
        assertEquals(expectedSimpleParamTypes, actualParamTypes)
        assertTrue(simpleOverloads.none { KModifier.INLINE in it.modifiers }, "Simple overloads need no inline/reified")
    }

    @Test
    fun `encodeParam has no overload accepting a collection, map, or array type`() {
        val paramTypes = topLevelFunOverloads("encodeParam").map { it.singleParamType() }
        assertTrue(
            paramTypes.none { it.startsWith("kotlin.collections.") },
            "Found a collection-typed overload: $paramTypes",
        )
    }

    @Test
    fun `encodeParam string overload is the identity function, not a JSON round-trip`() {
        val stringOverload = topLevelFunOverloads("encodeParam").first { it.singleParamType() == "kotlin.String" }
        assertEquals("return value\n", stringOverload.body.toString())
    }

    @Test
    fun `encodeParam enum overload is inline reified and bounded by Enum, extracting raw JSON primitive content`() {
        val enumOverload = topLevelFunOverloads("encodeParam").first { it.typeVariables.isNotEmpty() }
        assertTrue(KModifier.INLINE in enumOverload.modifiers)
        val typeVar = enumOverload.typeVariables.single()
        assertTrue(typeVar.isReified, "Expected reified type variable")
        assertTrue(typeVar.bounds.any { it.toString().contains("Enum") }, "Expected an Enum<T> bound")

        val body = enumOverload.body.toString()
        assertTrue(body.contains("encodeToJsonElement"), "Expected encodeToJsonElement call")
        assertTrue(body.contains("jsonPrimitive"), "Expected jsonPrimitive extraction")
        assertTrue(body.contains(".content"), "Expected raw .content, not a URL-encoded value")
        assertTrue(!body.contains("encodeURLPathPart"), "encodeParam must not URL-encode")
    }

    @Test
    fun `encodePathParam has exactly one overload per JSON-primitive-safe type plus one enum overload`() {
        val overloads = topLevelFunOverloads("encodePathParam")
        assertEquals(
            expectedSimpleParamTypes.size + 1,
            overloads.size,
            "Unexpected number of encodePathParam overloads",
        )
    }

    @Test
    fun `encodePathParam simple overloads delegate to the matching encodeParam overload and URL-encode`() {
        val simpleOverloads = topLevelFunOverloads("encodePathParam").filter { it.typeVariables.isEmpty() }
        assertEquals(expectedSimpleParamTypes, simpleOverloads.map { it.singleParamType() }.toSet())
        for (overload in simpleOverloads) {
            assertTrue(KModifier.INLINE !in overload.modifiers, "Simple overloads need no inline/reified")
            val body = overload.body.toString()
            assertTrue(body.contains("encodeParam(value)"), "Expected delegation to encodeParam: $body")
            assertTrue(body.contains("encodeURLPathPart"), "Expected encodeURLPathPart to escape the segment: $body")
        }
    }

    @Test
    fun `encodePathParam enum overload is inline reified, bounded by Enum, and URL-encodes`() {
        val enumOverload = topLevelFunOverloads("encodePathParam").first { it.typeVariables.isNotEmpty() }
        assertTrue(KModifier.INLINE in enumOverload.modifiers)
        val typeVar = enumOverload.typeVariables.single()
        assertTrue(typeVar.isReified, "Expected reified type variable")
        assertTrue(typeVar.bounds.any { it.toString().contains("Enum") }, "Expected an Enum<T> bound")

        val body = enumOverload.body.toString()
        assertTrue(body.contains("encodeParam(value)"), "Expected delegation to encodeParam")
        assertTrue(body.contains("encodeURLPathPart"), "Expected encodeURLPathPart to escape the segment")
    }

    @Test
    fun `ApiClientBase file never references Uuid, Instant, or LocalDate, so it needs no extra deps`() {
        // Regression guard: this file is generated once, independent of any spec. Referencing
        // kotlinx.datetime.LocalDate (or requiring an ExperimentalUuidApi opt-in) here would force
        // EVERY consumer to add kotlinx-datetime / opt in, even for a spec with no date/uuid fields.
        assertTrue(file.annotations.none { it.typeName.toString() == "kotlin.OptIn" }, "Expected no file-level @OptIn")
        val rendered = file.toString()
        assertTrue("Uuid" !in rendered, "ApiClientBase.kt must not reference Uuid")
        assertTrue("kotlinx.datetime" !in rendered, "ApiClientBase.kt must not depend on kotlinx-datetime")
        assertTrue("kotlin.time.Instant" !in rendered, "ApiClientBase.kt must not reference Instant")
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
