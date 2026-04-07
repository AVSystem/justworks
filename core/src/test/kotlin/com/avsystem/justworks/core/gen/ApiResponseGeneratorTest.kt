package com.avsystem.justworks.core.gen

import com.avsystem.justworks.core.gen.shared.ApiResponseGenerator
import com.squareup.kotlinpoet.KModifier
import com.squareup.kotlinpoet.TypeSpec
import com.squareup.kotlinpoet.TypeVariableName
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class ApiResponseGeneratorTest {
    private fun httpErrorClass(): TypeSpec {
        val files = listOf(ApiResponseGenerator.generateHttpError(), ApiResponseGenerator.generateHttpSuccess())
        val httpErrorFile = files.first { it.name == "HttpError" }
        return httpErrorFile.members.filterIsInstance<TypeSpec>().first { it.name == "HttpError" }
    }

    private fun httpErrorTypeEnum(): TypeSpec {
        val files = listOf(ApiResponseGenerator.generateHttpError(), ApiResponseGenerator.generateHttpSuccess())
        val httpErrorFile = files.first { it.name == "HttpError" }
        return httpErrorFile.members.filterIsInstance<TypeSpec>().first { it.name == "HttpErrorType" }
    }

    private fun successClass(): TypeSpec {
        val files = listOf(ApiResponseGenerator.generateHttpError(), ApiResponseGenerator.generateHttpSuccess())
        val successFile = files.first { it.name == "HttpSuccess" }
        return successFile.members.filterIsInstance<TypeSpec>().first()
    }

    @Test
    fun `generates data class HttpError extending RuntimeException`() {
        val typeSpec = httpErrorClass()
        assertEquals("HttpError", typeSpec.name)
        assertTrue(KModifier.DATA in typeSpec.modifiers, "Expected DATA modifier")
        assertEquals("kotlin.RuntimeException", typeSpec.superclass.toString(), "Expected RuntimeException superclass")
        assertTrue(
            typeSpec.superclassConstructorParameters.isNotEmpty(),
            "Expected superclass constructor parameter for message",
        )
        assertTrue(
            typeSpec.superclassConstructorParameters.first().toString().contains("message"),
            "Expected message passed to RuntimeException constructor",
        )
    }

    @Test
    fun `HttpError data class has code message and type fields`() {
        val typeSpec = httpErrorClass()
        val constructor = assertNotNull(typeSpec.primaryConstructor)
        assertEquals(3, constructor.parameters.size)
        val codeParam = constructor.parameters.first { it.name == "code" }
        assertEquals("kotlin.Int", codeParam.type.toString())
        val messageParam = constructor.parameters.first { it.name == "message" }
        assertEquals("kotlin.String", messageParam.type.toString())
        val typeParam = constructor.parameters.first { it.name == "type" }
        assertEquals("com.avsystem.justworks.HttpErrorType", typeParam.type.toString())
    }

    @Test
    fun `generates HttpErrorType enum with four values`() {
        val typeSpec = httpErrorTypeEnum()
        assertEquals("HttpErrorType", typeSpec.name)
        val constantNames = typeSpec.enumConstants.keys.sorted()
        assertEquals(listOf("Client", "Network", "Redirect", "Server"), constantNames)
    }

    @Test
    fun `Success is a data class with body and statusCode`() {
        val success = successClass()
        assertEquals("HttpSuccess", success.name)
        assertTrue(KModifier.DATA in success.modifiers, "Expected DATA modifier on Success")
        val constructor = assertNotNull(success.primaryConstructor)
        val paramNames = constructor.parameters.map { it.name }
        assertTrue("body" in paramNames, "Expected 'body' parameter")
        assertTrue("code" in paramNames, "Expected 'code' parameter")
        val bodyParam = constructor.parameters.first { it.name == "body" }
        assertTrue(bodyParam.type is TypeVariableName, "body should be type variable T")
    }

    @Test
    fun `generates two files`() {
        val files = listOf(ApiResponseGenerator.generateHttpError(), ApiResponseGenerator.generateHttpSuccess())
        assertEquals(2, files.size)
        val fileNames = files.map { it.name }.sorted()
        assertEquals(listOf("HttpError", "HttpSuccess"), fileNames)
    }
}
