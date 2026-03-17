package com.avsystem.justworks.core.gen

import com.squareup.kotlinpoet.FileSpec
import com.squareup.kotlinpoet.FunSpec
import com.squareup.kotlinpoet.INT
import com.squareup.kotlinpoet.KModifier
import com.squareup.kotlinpoet.PropertySpec
import com.squareup.kotlinpoet.STRING
import com.squareup.kotlinpoet.TypeName
import com.squareup.kotlinpoet.TypeSpec
import com.squareup.kotlinpoet.TypeVariableName
import java.io.File

/**
 * Generates [FileSpec]s containing:
 * - `HttpErrorType` enum class with Client, Server, Network values
 * - `HttpError` data class with code, message, type fields
 * - `HttpSuccess<T>` data class wrapping successful responses
 */
object ApiResponseGenerator {
    fun primaryConstructor(bodyType: TypeName = STRING) = FunSpec
        .constructorBuilder()
        .addParameter("statusCode", INT)
        .addParameter("body", bodyType)
        .build()

    val statusCodeProperty =
        PropertySpec
            .builder("statusCode", INT)
            .initializer("statusCode")
            .build()

    fun bodyProperty(bodyType: TypeName = STRING) = PropertySpec
        .builder("body", bodyType)
        .initializer("body")
        .build()

    /**
     * Generates files and writes them to [outputDir].
     * Returns the number of files written.
     */
    fun generateTo(outputDir: File): Int {
        val files = listOf(generateHttpError(), generateHttpSuccess())
        for (fileSpec in files) {
            fileSpec.writeTo(outputDir)
        }
        return files.size
    }

    fun generateHttpError(): FileSpec {
        val enumType =
            TypeSpec
                .enumBuilder(HTTP_ERROR_TYPE)
                .addEnumConstant("Client")
                .addEnumConstant("Server")
                .addEnumConstant("Network")
                .build()

        val dataClassType =
            TypeSpec
                .classBuilder(HTTP_ERROR)
                .addModifiers(KModifier.DATA)
                .primaryConstructor(
                    FunSpec
                        .constructorBuilder()
                        .addParameter("code", INT)
                        .addParameter("message", STRING)
                        .addParameter("type", HTTP_ERROR_TYPE)
                        .build(),
                ).addProperty(
                    PropertySpec
                        .builder("code", INT)
                        .initializer("code")
                        .build(),
                ).addProperty(
                    PropertySpec
                        .builder("message", STRING)
                        .initializer("message")
                        .build(),
                ).addProperty(
                    PropertySpec
                        .builder("type", HTTP_ERROR_TYPE)
                        .initializer("type")
                        .build(),
                ).build()

        return FileSpec
            .builder(HTTP_ERROR)
            .addType(enumType)
            .addType(dataClassType)
            .build()
    }

    fun generateHttpSuccess(): FileSpec {
        val t = TypeVariableName("T")

        val successType =
            TypeSpec
                .classBuilder(HTTP_SUCCESS)
                .addModifiers(KModifier.DATA)
                .addTypeVariable(t)
                .primaryConstructor(primaryConstructor(t))
                .addProperty(bodyProperty(t))
                .addProperty(statusCodeProperty)
                .build()

        return FileSpec
            .builder(HTTP_SUCCESS)
            .addType(successType)
            .build()
    }
}
