package com.avsystem.justworks.core.gen

import com.avsystem.justworks.core.model.ApiSpec
import com.avsystem.justworks.core.model.EnumModel
import com.avsystem.justworks.core.model.SchemaModel
import com.avsystem.justworks.core.model.TypeRef

// Hoists anonymous TypeRef.InlineType nodes into named models, producing the
// structural-key -> name maps consumed by resolveInlineTypes.

private fun ApiSpec.topLevelTypeRefs(): List<TypeRef> {
    val endpointRefs = endpoints.flatMap { endpoint ->
        val requestRef = endpoint.requestBody?.schema
        val responseRefs = endpoint.responses.values.map { it.schema }
        responseRefs + requestRef
    }
    val schemaPropertyRefs = schemas.flatMap { schema -> schema.properties.map { it.type } }
    return (endpointRefs + schemaPropertyRefs).filterNotNull()
}

private val descendants = DeepRecursiveFunction<TypeRef, List<TypeRef>> { type ->
    listOf(type) + when (type) {
        is TypeRef.Inline -> type.properties.flatMap { callRecursive(it.type) }
        is TypeRef.Array -> callRecursive(type.items)
        is TypeRef.Map -> callRecursive(type.valueType)
        is TypeRef.Primitive, is TypeRef.Reference, is TypeRef.InlineEnum, TypeRef.Unknown -> emptyList()
    }
}

private inline fun <reified T : TypeRef.InlineType> ApiSpec.inlineRefs(): List<T> =
    topLevelTypeRefs().flatMap { descendants(it) }.filterIsInstance<T>()

context(nameRegistry: NameRegistry)
private fun <T : TypeRef.InlineType, K, M> Iterable<T>.toNamedModels(
    keyOf: (T) -> K,
    modelOf: (T, String) -> M,
): Pair<List<M>, Map<K, String>> {
    val nameMap = mutableMapOf<K, String>()
    val models = asSequence()
        .sortedBy { it.contextHint }
        .distinctBy(keyOf)
        .map { ref ->
            val generatedName = nameRegistry.register(ref.contextHint.toInlinedName())
            nameMap[keyOf(ref)] = generatedName
            modelOf(ref, generatedName)
        }.toList()
    return models to nameMap
}

context(_: NameRegistry)
internal fun ApiSpec.collectInlineSchemas(): Pair<List<SchemaModel>, Map<InlineSchemaKey, String>> =
    inlineRefs<TypeRef.Inline>().toNamedModels(
        keyOf = { InlineSchemaKey.from(it.properties, it.requiredProperties) },
        modelOf = { ref, name ->
            SchemaModel(
                name = name,
                description = null,
                properties = ref.properties,
                requiredProperties = ref.requiredProperties,
                allOf = null,
                oneOf = null,
                anyOf = null,
                discriminator = null,
            )
        },
    )

context(_: NameRegistry)
internal fun ApiSpec.collectInlineEnums(): Pair<List<EnumModel>, Map<InlineEnumKey, String>> =
    inlineRefs<TypeRef.InlineEnum>().toNamedModels(
        keyOf = InlineEnumKey::from,
        modelOf = { ref, name ->
            EnumModel(
                name = name,
                description = null,
                type = ref.backingType,
                values = ref.values.map { EnumModel.Value(it) },
            )
        },
    )
