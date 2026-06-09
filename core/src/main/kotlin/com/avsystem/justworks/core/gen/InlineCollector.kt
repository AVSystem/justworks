package com.avsystem.justworks.core.gen

import com.avsystem.justworks.core.model.ApiSpec
import com.avsystem.justworks.core.model.EnumModel
import com.avsystem.justworks.core.model.SchemaModel
import com.avsystem.justworks.core.model.TypeRef

// Hoists anonymous TypeRef.InlineType nodes into named models, producing the
// structural-key -> name maps consumed by resolveInlineTypes.

private fun ApiSpec.topLevelTypeRefs(): Sequence<TypeRef> {
    val endpointRefs = endpoints.asSequence().flatMap { endpoint ->
        endpoint.responses.values.map { it.schema } + endpoint.requestBody?.schema
    }
    val schemaPropertyRefs = schemas.asSequence().flatMap { schema -> schema.properties.map { it.type } }
    return (endpointRefs + schemaPropertyRefs).filterNotNull()
}

/**
 * Drops the discriminator property from every polymorphic variant schema.
 *
 * In a sealed (oneOf/anyOf + discriminator) hierarchy the discriminator is emitted via
 * `@SerialName`/`@JsonClassDiscriminator` on the subtype, never as a field — the variant's own
 * (typically single-value) `type` property is dead weight. Removing it here keeps inline-enum
 * collection, resolution, and generation consistent, so no orphan `*_Type` enum is hoisted.
 *
 * The @SerialName value comes from the parent's `discriminator.mapping`
 * (see [com.avsystem.justworks.core.gen.resolveSerialName]), so dropping the field is lossless.
 */
internal fun ApiSpec.stripDiscriminatorProperties(): ApiSpec {
    val discriminatorProps = discriminatorPropertyByVariant()
    if (discriminatorProps.isEmpty()) return this
    return copy(
        schemas = schemas.map { schema ->
            val discriminatorProp = discriminatorProps[schema.name] ?: return@map schema
            schema.copy(properties = schema.properties.filterNot { it.name == discriminatorProp })
        },
    )
}

/**
 * Maps each polymorphic variant schema name to the discriminator property it carries.
 */
private fun ApiSpec.discriminatorPropertyByVariant(): Map<String, String> = schemas
    .asSequence()
    .mapNotNull { parent -> parent.discriminator?.propertyName?.let { it to parent } }
    .flatMap { (propertyName, parent) ->
        (parent.oneOf.orEmpty() + parent.anyOf.orEmpty())
            .asSequence()
            .filterIsInstance<TypeRef.Reference>()
            .map { it.schemaName to propertyName }
    }.toMap()

private val descendants = DeepRecursiveFunction<TypeRef, List<TypeRef>> { type ->
    listOf(type) + when (type) {
        is TypeRef.Inline -> type.properties.flatMap { callRecursive(it.type) }
        is TypeRef.Array -> callRecursive(type.items)
        is TypeRef.Map -> callRecursive(type.valueType)
        is TypeRef.Primitive, is TypeRef.Reference, is TypeRef.InlineEnum, TypeRef.Unknown -> emptyList()
    }
}

private inline fun <reified T : TypeRef.InlineType> ApiSpec.inlineRefs(): Sequence<T> =
    topLevelTypeRefs().flatMap { descendants(it) }.filterIsInstance<T>()

context(nameRegistry: NameRegistry)
private fun <T : TypeRef.InlineType, K, M> Sequence<T>.toNamedModels(
    keyOf: (T) -> K,
    modelOf: (T, String) -> M,
): Pair<List<M>, Map<K, String>> {
    val nameMap = mutableMapOf<K, String>()
    val models = sortedBy { it.contextHint }
        .distinctBy(keyOf)
        .map { ref ->
            val generatedName = nameRegistry.register(ref.contextHint.toInlinedName())
            nameMap[keyOf(ref)] = generatedName
            modelOf(ref, generatedName)
        }.toList()
    return models to nameMap
}

context(_: NameRegistry)
internal fun collectInlineSchemas(spec: ApiSpec): Pair<List<SchemaModel>, Map<InlineSchemaKey, String>> =
    spec.inlineRefs<TypeRef.Inline>().toNamedModels(
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
internal fun collectInlineEnums(spec: ApiSpec): Pair<List<EnumModel>, Map<InlineEnumKey, String>> =
    spec.inlineRefs<TypeRef.InlineEnum>().toNamedModels(
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
