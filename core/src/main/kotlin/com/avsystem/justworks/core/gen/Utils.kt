package com.avsystem.justworks.core.gen

import com.avsystem.justworks.core.model.PropertyModel
import com.avsystem.justworks.core.model.TypeRef
import com.squareup.kotlinpoet.CodeBlock

/**
 * If [required], emits [block] directly. Otherwise wraps it in `if (name != null) { ... }`.
 */
internal inline fun CodeBlock.Builder.optionalGuard(
    required: Boolean,
    name: String,
    block: CodeBlock.Builder.() -> Unit,
) {
    if (!required) beginControlFlow("if (%L != null)", name)
    block()
    if (!required) endControlFlow()
}

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
