package com.avsystem.justworks.core.gen

import com.avsystem.justworks.core.model.PropertyModel
import com.avsystem.justworks.core.model.TypeRef
import com.squareup.kotlinpoet.CodeBlock

internal val TypeRef.properties: List<PropertyModel>
    get() = when (this) {
        is TypeRef.Inline -> properties
        is TypeRef.Array, is TypeRef.Map, is TypeRef.Primitive, is TypeRef.Reference, TypeRef.Unknown -> emptyList()
    }

internal val TypeRef.requiredProperties: Set<String>
    get() = when (this) {
        is TypeRef.Inline -> requiredProperties
        is TypeRef.Array, is TypeRef.Map, is TypeRef.Primitive, is TypeRef.Reference, TypeRef.Unknown -> emptySet()
    }
