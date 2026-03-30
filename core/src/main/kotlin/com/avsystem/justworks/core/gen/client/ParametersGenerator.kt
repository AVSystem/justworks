package com.avsystem.justworks.core.gen.client

import com.avsystem.justworks.core.gen.BODY
import com.avsystem.justworks.core.gen.CHANNEL_PROVIDER
import com.avsystem.justworks.core.gen.CONTENT_TYPE_CLASS
import com.avsystem.justworks.core.gen.ModelPackage
import com.avsystem.justworks.core.gen.isBinaryUpload
import com.avsystem.justworks.core.gen.properties
import com.avsystem.justworks.core.gen.requiredProperties
import com.avsystem.justworks.core.gen.toCamelCase
import com.avsystem.justworks.core.gen.toTypeName
import com.avsystem.justworks.core.model.ContentType
import com.avsystem.justworks.core.model.RequestBody
import com.avsystem.justworks.core.model.TypeRef
import com.squareup.kotlinpoet.ParameterSpec
import com.squareup.kotlinpoet.STRING

internal object ParametersGenerator {
    context(_: ModelPackage)
    fun buildMultipartParameters(requestBody: RequestBody): List<ParameterSpec> =
        requestBody.schema.properties.flatMap { prop ->
            val name = prop.name.toCamelCase()
            if (prop.type.isBinaryUpload()) {
                listOf(
                    ParameterSpec(name, CHANNEL_PROVIDER),
                    ParameterSpec("${name}Name", STRING),
                    ParameterSpec("${name}ContentType", CONTENT_TYPE_CLASS),
                )
            } else {
                listOf(
                    ParameterSpec(name, prop.type.toTypeName()),
                )
            }
        }

    context(_: ModelPackage)
    fun buildFormParameters(requestBody: RequestBody): List<ParameterSpec> = requestBody.schema.properties.map { prop ->
        val isRequired = requestBody.required && prop.name in requestBody.schema.requiredProperties
        buildNullableParameter(prop.type, prop.name, isRequired)
    }

    context(_: ModelPackage)
    fun buildNullableParameter(
        typeRef: TypeRef,
        name: String,
        required: Boolean,
    ): ParameterSpec {
        val builder = ParameterSpec.builder(name.toCamelCase(), typeRef.toTypeName().copy(nullable = !required))
        if (!required) builder.defaultValue("null")
        return builder.build()
    }

    context(_: ModelPackage)
    fun buildBodyParams(requestBody: RequestBody) = when (requestBody.contentType) {
        ContentType.MULTIPART_FORM_DATA -> buildMultipartParameters(requestBody)
        ContentType.FORM_URL_ENCODED -> buildFormParameters(requestBody)
        ContentType.JSON_CONTENT_TYPE -> listOf(buildNullableParameter(requestBody.schema, BODY, requestBody.required))
    }
}
