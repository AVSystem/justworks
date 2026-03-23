package com.avsystem.justworks.core.gen

import com.squareup.kotlinpoet.KModifier
import com.squareup.kotlinpoet.TypeAliasSpec
import com.squareup.kotlinpoet.TypeSpec
import com.squareup.kotlinpoet.TypeVariableName
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class ApiResponseGeneratorTest {
    private val files = ApiResponseGenerator.generate()
    private val httpErrorFile = files.first { it.name == "HttpError" }

    private fun httpErrorClass(): TypeSpec =
        httpErrorFile.members.filterIsInstance<TypeSpec>().first { it.name == "HttpError" }

    private fun httpResultAlias(): TypeAliasSpec =
        httpErrorFile.members.filterIsInstance<TypeAliasSpec>().first { it.name == "HttpResult" }

    private fun successClass(): TypeSpec {
        val successFile = files.first { it.name == "HttpSuccess" }
        return successFile.members.filterIsInstance<TypeSpec>().first()
    }

    @Test
    fun `HttpError is a sealed class`() {
        val typeSpec = httpErrorClass()
        assertEquals("HttpError", typeSpec.name)
        assertTrue(KModifier.SEALED in typeSpec.modifiers, "Expected SEALED modifier")
        assertTrue(KModifier.DATA !in typeSpec.modifiers, "Should NOT have DATA modifier")
    }

    @Test
    fun `HttpError has type variable B with out variance`() {
        val typeSpec = httpErrorClass()
        assertEquals(1, typeSpec.typeVariables.size)
        val typeVar = typeSpec.typeVariables.first()
        assertEquals("B", typeVar.name)
        assertTrue(typeVar.variance == KModifier.OUT, "Expected OUT variance on B")
    }

    @Test
    fun `HttpError has abstract code and body properties`() {
        val typeSpec = httpErrorClass()
        val codeProp = typeSpec.propertySpecs.first { it.name == "code" }
        assertTrue(KModifier.ABSTRACT in codeProp.modifiers, "code should be abstract")
        assertEquals("kotlin.Int", codeProp.type.toString())

        val bodyProp = typeSpec.propertySpecs.first { it.name == "body" }
        assertTrue(KModifier.ABSTRACT in bodyProp.modifiers, "body should be abstract")
        assertEquals("B?", bodyProp.type.toString())
    }

    @Test
    fun `HttpError has all predefined subtypes`() {
        val typeSpec = httpErrorClass()
        val subtypeNames = typeSpec.typeSpecs.mapNotNull { it.name }.sorted()
        val expected = listOf(
            "BadGateway",
            "BadRequest",
            "Conflict",
            "Forbidden",
            "Gone",
            "InternalServerError",
            "MethodNotAllowed",
            "Network",
            "NotFound",
            "Other",
            "ServiceUnavailable",
            "TooManyRequests",
            "Unauthorized",
            "UnprocessableEntity",
        )
        assertEquals(expected, subtypeNames)
        assertEquals(14, subtypeNames.size)
    }

    @Test
    fun `predefined subtypes are data classes`() {
        val typeSpec = httpErrorClass()
        val badRequest = typeSpec.typeSpecs.first { it.name == "BadRequest" }
        assertTrue(KModifier.DATA in badRequest.modifiers, "BadRequest should be DATA")

        val other = typeSpec.typeSpecs.first { it.name == "Other" }
        assertTrue(KModifier.DATA in other.modifiers, "Other should be DATA")

        val network = typeSpec.typeSpecs.first { it.name == "Network" }
        assertTrue(KModifier.DATA in network.modifiers, "Network should be DATA")
    }

    @Test
    fun `BadRequest subtype has body parameter and code 400`() {
        val typeSpec = httpErrorClass()
        val badRequest = typeSpec.typeSpecs.first { it.name == "BadRequest" }

        val constructor = assertNotNull(badRequest.primaryConstructor)
        assertEquals(1, constructor.parameters.size)
        assertEquals("body", constructor.parameters.first().name)

        val codeProp = badRequest.propertySpecs.first { it.name == "code" }
        assertTrue(KModifier.OVERRIDE in codeProp.modifiers)
        assertNotNull(codeProp.getter, "code should have a getter")
        assertTrue(codeProp.getter.toString().contains("400"), "code getter should return 400")
    }

    @Test
    fun `Other subtype has both code and body in constructor`() {
        val typeSpec = httpErrorClass()
        val other = typeSpec.typeSpecs.first { it.name == "Other" }

        val constructor = assertNotNull(other.primaryConstructor)
        assertEquals(2, constructor.parameters.size)
        val paramNames = constructor.parameters.map { it.name }
        assertTrue("code" in paramNames, "Other should have code param")
        assertTrue("body" in paramNames, "Other should have body param")
    }

    @Test
    fun `Network subtype has cause parameter and no type variable`() {
        val typeSpec = httpErrorClass()
        val network = typeSpec.typeSpecs.first { it.name == "Network" }

        assertTrue(network.typeVariables.isEmpty(), "Network should have no type variables")

        val constructor = assertNotNull(network.primaryConstructor)
        assertEquals(1, constructor.parameters.size)
        val causeParam = constructor.parameters.first()
        assertEquals("cause", causeParam.name)
        assertTrue(causeParam.type.toString().contains("Throwable"), "cause should be Throwable?")
        assertTrue(causeParam.type.isNullable, "cause should be nullable")

        val codeProp = network.propertySpecs.first { it.name == "code" }
        assertTrue(KModifier.OVERRIDE in codeProp.modifiers)
        assertNotNull(codeProp.getter, "code should have a getter")
        assertTrue(codeProp.getter.toString().contains("0"), "code getter should return 0")

        val bodyProp = network.propertySpecs.first { it.name == "body" }
        assertTrue(KModifier.OVERRIDE in bodyProp.modifiers)
        assertNotNull(bodyProp.getter, "body should have a getter")
        assertTrue(bodyProp.getter.toString().contains("null"), "body getter should return null")
    }

    @Test
    fun `HttpResult typealias is generated`() {
        val alias = httpResultAlias()
        assertEquals("HttpResult", alias.name)
        assertEquals(2, alias.typeVariables.size)
        assertEquals("E", alias.typeVariables[0].name)
        assertEquals("T", alias.typeVariables[1].name)
        assertTrue(alias.type.toString().contains("Either"), "Should reference Either")
        assertTrue(alias.type.toString().contains("HttpError"), "Should reference HttpError")
        assertTrue(alias.type.toString().contains("HttpSuccess"), "Should reference HttpSuccess")
    }

    @Test
    fun `HttpErrorType enum is not generated`() {
        val allTypes = httpErrorFile.members.filterIsInstance<TypeSpec>()
        assertTrue(allTypes.none { it.name == "HttpErrorType" }, "HttpErrorType should not exist")
    }

    @Test
    fun `HttpSuccess is unchanged`() {
        val success = successClass()
        assertEquals("HttpSuccess", success.name)
        assertTrue(KModifier.DATA in success.modifiers, "Expected DATA modifier on HttpSuccess")

        assertEquals(1, success.typeVariables.size)
        assertEquals("T", success.typeVariables.first().name)

        val constructor = assertNotNull(success.primaryConstructor)
        val paramNames = constructor.parameters.map { it.name }
        assertTrue("body" in paramNames, "Expected 'body' parameter")
        assertTrue("code" in paramNames, "Expected 'code' parameter")

        val bodyParam = constructor.parameters.first { it.name == "body" }
        assertTrue(bodyParam.type is TypeVariableName, "body should be type variable T")
    }

    @Test
    fun `generates two files`() {
        assertEquals(2, files.size)
        val fileNames = files.map { it.name }.sorted()
        assertEquals(listOf("HttpError", "HttpSuccess"), fileNames)
    }
}
