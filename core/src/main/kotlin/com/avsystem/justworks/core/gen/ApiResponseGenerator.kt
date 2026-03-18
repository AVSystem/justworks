package com.avsystem.justworks.core.gen

import com.squareup.kotlinpoet.FileSpec
import com.squareup.kotlinpoet.FunSpec
import com.squareup.kotlinpoet.INT
import com.squareup.kotlinpoet.KModifier
import com.squareup.kotlinpoet.PropertySpec
import com.squareup.kotlinpoet.STRING
import com.squareup.kotlinpoet.TypeSpec
import com.squareup.kotlinpoet.TypeVariableName

/**
 * Generates [FileSpec]s containing:
 * - `HttpErrorType` enum class with Client, Server, Network values
 * - `HttpError` data class with code, message, type fields
 * - `HttpSuccess<T>` data class wrapping successful responses
 */
object ApiResponseGenerator {
    private const val BODY = "body"
    private const val CODE = "code"
    private const val MESSAGE = "message"
    private const val TYPE = "type"

    private val codeProperty = PropertySpec
        .builder(CODE, INT)
        .initializer(CODE)
        .build()

    fun generateHttpError(): FileSpec {
        val enumType = TypeSpec
            .enumBuilder(HTTP_ERROR_TYPE)
            .addEnumConstant("Client")
            .addEnumConstant("Server")
            .addEnumConstant("Network")
            .build()

        val primaryConstructor = FunSpec
            .constructorBuilder()
            .addParameter(CODE, INT)
            .addParameter(MESSAGE, STRING)
            .addParameter(TYPE, HTTP_ERROR_TYPE)
            .build()

        val dataClassType = TypeSpec
            .classBuilder(HTTP_ERROR)
            .addModifiers(KModifier.DATA)
            .primaryConstructor(primaryConstructor)
            .addProperty(PropertySpec.builder(MESSAGE, STRING).initializer(MESSAGE).build())
            .addProperty(PropertySpec.builder(TYPE, HTTP_ERROR_TYPE).initializer(TYPE).build())
            .addProperty(codeProperty)
            .build()

        return FileSpec
            .builder(HTTP_ERROR)
            .addType(enumType)
            .addType(dataClassType)
            .build()
    }

    fun generateHttpSuccess(): FileSpec {
        val t = TypeVariableName("T")

        val primaryConstructor = FunSpec
            .constructorBuilder()
            .addParameter(CODE, INT)
            .addParameter(BODY, t)
            .build()

        val successType = TypeSpec
            .classBuilder(HTTP_SUCCESS)
            .addModifiers(KModifier.DATA)
            .addTypeVariable(t)
            .primaryConstructor(primaryConstructor)
            .addProperty(PropertySpec.builder(BODY, t).initializer(BODY).build())
            .addProperty(codeProperty)
            .build()

        return FileSpec
            .builder(HTTP_SUCCESS)
            .addType(successType)
            .build()
    }
}
