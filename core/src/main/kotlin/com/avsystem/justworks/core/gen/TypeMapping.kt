package com.avsystem.justworks.core.gen

import com.avsystem.justworks.core.model.PrimitiveType
import com.avsystem.justworks.core.model.TypeRef
import com.squareup.kotlinpoet.ANY
import com.squareup.kotlinpoet.BOOLEAN
import com.squareup.kotlinpoet.BYTE_ARRAY
import com.squareup.kotlinpoet.ClassName
import com.squareup.kotlinpoet.DOUBLE
import com.squareup.kotlinpoet.FLOAT
import com.squareup.kotlinpoet.INT
import com.squareup.kotlinpoet.LIST
import com.squareup.kotlinpoet.LONG
import com.squareup.kotlinpoet.MAP
import com.squareup.kotlinpoet.ParameterizedTypeName.Companion.parameterizedBy
import com.squareup.kotlinpoet.STRING
import com.squareup.kotlinpoet.TypeName

/**
 * Maps [TypeRef] sealed variants to KotlinPoet [TypeName] instances.
 */
object TypeMapping {
    fun toTypeName(typeRef: TypeRef, modelPackage: String): TypeName = when (typeRef) {
        is TypeRef.Primitive -> {
            when (typeRef.type) {
                PrimitiveType.STRING -> STRING
                PrimitiveType.INT -> INT
                PrimitiveType.LONG -> LONG
                PrimitiveType.DOUBLE -> DOUBLE
                PrimitiveType.FLOAT -> FLOAT
                PrimitiveType.BOOLEAN -> BOOLEAN
                PrimitiveType.BYTE_ARRAY -> BYTE_ARRAY
                PrimitiveType.DATE_TIME -> INSTANT
                PrimitiveType.DATE -> LOCAL_DATE
            }
        }

        is TypeRef.Array -> {
            LIST.parameterizedBy(toTypeName(typeRef.items, modelPackage))
        }

        is TypeRef.Map -> {
            MAP.parameterizedBy(STRING, toTypeName(typeRef.valueType, modelPackage))
        }

        is TypeRef.Reference -> {
            ClassName(modelPackage, typeRef.schemaName)
        }

        is TypeRef.Inline -> {
            ClassName(modelPackage, typeRef.contextHint.toInlinedName())
        }

        is TypeRef.Unknown -> {
            ANY
        }
    }
}
